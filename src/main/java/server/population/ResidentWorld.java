package server.population;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A coarse population model. Identities and long regional schedules exist without loading maps,
 * creating actors, simulating battles, or awarding items/experience. Materialized actors hold a
 * claim until the caller releases them; the clock cannot move a visible actor behind its back.
 */
public final class ResidentWorld {
    public enum Activity { IDLE, REST, TRAIN, ROAM }

    public record Presence(ResidentProfiles.Profile profile, int mapId, int channel,
                           Activity activity, long nextChangeMillis, boolean claimed) { }

    private record Region(List<ResidentCatalog.MapSpec> towns, List<ResidentCatalog.MapSpec> fields, long weight) { }

    private static final long TICK_MILLIS = 30_000;
    private static final int MAX_IDENTITIES = 10_000;
    private static final int HENESYS = 100000000;
    private static final int TRAINING_GROUND = 100010000;
    private static final int PIG_FIELD = 100020000;

    private final int channelCount;
    private final List<Region> regions;
    private final long regionWeight;
    private final Map<Integer, Presence> residents = new LinkedHashMap<>();
    private final Map<Long, LinkedHashMap<Integer, Presence>> available = new HashMap<>();
    private final int[] activityCounts = new int[Activity.values().length];
    private long tickBucket;
    private int claimedCount;

    public ResidentWorld(List<ResidentProfiles.Profile> profiles, List<ResidentCatalog.MapSpec> maps,
                         int channelCount, long nowMillis) {
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(maps, "maps");
        if (profiles.size() > MAX_IDENTITIES || channelCount < 1 || maps.isEmpty()) {
            throw new IllegalArgumentException("A world needs up to 10000 identities, at least one channel and one map");
        }
        this.channelCount = channelCount;
        this.regions = regions(maps);
        this.regionWeight = regions.stream().mapToLong(Region::weight).sum();
        tickBucket = Math.floorDiv(nowMillis, TICK_MILLIS);
        var knownMaps = new HashMap<Integer, ResidentCatalog.MapSpec>();
        maps.forEach(map -> knownMaps.put(map.id(), map));
        for (ResidentProfiles.Profile profile : profiles.stream().sorted(Comparator.comparingInt(ResidentProfiles.Profile::id)).toList()) {
            if (profile.id() < 1 || residents.containsKey(profile.id())) {
                throw new IllegalArgumentException("Resident identity IDs must be positive and unique: " + profile.id());
            }
            if (profile.id() <= 28 && !knownMaps.containsKey(originalHome(profile.id()))) {
                throw new IllegalArgumentException("Missing original resident home: " + originalHome(profile.id()));
            }
            Presence presence = scheduled(profile, nowMillis);
            residents.put(profile.id(), presence);
            addAvailable(presence);
            activityCounts[presence.activity().ordinal()]++;
        }
    }

    /** At most one linear scan per 30-second clock bucket; long downtime needs no replay loop. */
    public synchronized void tick(long nowMillis) {
        long bucket = Math.floorDiv(nowMillis, TICK_MILLIS);
        if (bucket == tickBucket) {
            return;
        }
        boolean forward = bucket > tickBucket;
        tickBucket = bucket;
        for (var entry : residents.entrySet()) {
            Presence old = entry.getValue();
            if (old.claimed() || old.profile().id() <= 28) {
                continue;
            }
            if (forward && old.nextChangeMillis() > nowMillis) {
                continue;
            }
            Presence next = scheduled(old.profile(), nowMillis);
            if (old.equals(next)) {
                continue;
            }
            removeAvailable(old);
            entry.setValue(next);
            addAvailable(next);
            updateActivityCount(old, next);
        }
    }

    /** Immutable candidate snapshot for one actual channel/map, excluding all active claims. */
    public synchronized List<Presence> candidates(int mapId, int channel, long nowMillis) {
        tick(nowMillis);
        Map<Integer, Presence> bucket = available.get(key(mapId, channel));
        return bucket == null ? List.of() : List.copyOf(bucket.values());
    }

    /** Claim only an identity currently scheduled here; repeated or stale claims cannot duplicate it. */
    public synchronized boolean claim(int id, int mapId, int channel) {
        Presence old = residents.get(id);
        if (old == null || old.claimed() || old.mapId() != mapId || old.channel() != channel) {
            return false;
        }
        removeAvailable(old);
        residents.put(id, new Presence(old.profile(), mapId, channel, old.activity(), Long.MAX_VALUE, true));
        claimedCount++;
        return true;
    }

    /** Track a real active actor's portal crossing, including public maps outside the spawn whitelist. */
    public synchronized boolean moveClaim(int id, int mapId, int channel) {
        Presence old = residents.get(id);
        if (old == null || !old.claimed() || mapId <= 0 || channel < 1 || channel > channelCount) {
            return false;
        }
        residents.put(id, new Presence(old.profile(), mapId, channel, old.activity(), Long.MAX_VALUE, true));
        return true;
    }

    /** The actor has been removed; resume its clock schedule directly at the current time. */
    public synchronized void release(int id, long nowMillis) {
        Presence old = residents.get(id);
        if (old == null || !old.claimed()) {
            return;
        }
        Presence next = scheduled(old.profile(), nowMillis);
        residents.put(id, next);
        addAvailable(next);
        updateActivityCount(old, next);
        claimedCount--;
    }

    /** Returns null for an unknown identity. It does not advance the model's clock. */
    public synchronized Presence state(int id) {
        return residents.get(id);
    }

    public synchronized int total() {
        return residents.size();
    }

    public synchronized int claimedCount() {
        return claimedCount;
    }

    /** Coarse planned activities; claimed actors' live behavior remains the caller's responsibility. */
    public synchronized Map<Activity, Integer> activityCounts() {
        Map<Activity, Integer> result = new EnumMap<>(Activity.class);
        for (Activity activity : Activity.values()) {
            result.put(activity, activityCounts[activity.ordinal()]);
        }
        return Map.copyOf(result);
    }

    private Presence scheduled(ResidentProfiles.Profile profile, long now) {
        int id = profile.id();
        if (id <= 28) {
            return new Presence(profile, originalHome(id), 1, id <= 20 ? Activity.IDLE : Activity.TRAIN,
                    Long.MAX_VALUE, false);
        }
        long identity = mix(id * 0x9E3779B97F4A7C15L);
        int channel = 1 + index(mix(identity ^ 0x43A11EL), channelCount);
        Region region = region(identity);
        List<ResidentCatalog.MapSpec> homes = region.towns().isEmpty() ? region.fields() : region.towns();
        ResidentCatalog.MapSpec home = homes.get(index(mix(identity ^ 0x110AEL), homes.size()));
        long duration = 180_000 + index(mix(identity ^ 0xC10C4L), 240_001); // 3–7 minutes per phase
        long offset = index(mix(identity ^ 0x0FF5E7L), (int) duration);
        long within = Math.floorMod(now, duration) + offset;
        long slot = Math.floorDiv(now, duration) + within / duration + index(mix(identity ^ 0xFA5EL), 6);
        int phase = (int) Math.floorMod(slot, 6L);
        long cycle = Math.floorDiv(slot, 6L);
        List<ResidentCatalog.MapSpec> fields = region.fields().isEmpty() ? homes : region.fields();
        ResidentCatalog.MapSpec field = fields.get(index(mix(identity ^ cycle * 0xD1B54A32D192ED03L), fields.size()));
        ResidentCatalog.MapSpec location = phase == 2 || phase == 3 ? field : home;
        Activity activity = switch (phase) {
            case 0 -> Activity.IDLE;
            case 1, 5 -> Activity.ROAM;
            case 2, 3 -> location.town() ? Activity.IDLE : Activity.TRAIN;
            default -> Activity.REST;
        };
        long remaining = duration - within % duration;
        long nextChange = now > Long.MAX_VALUE - remaining ? Long.MAX_VALUE : now + remaining;
        return new Presence(profile, location.id(), channel, activity, nextChange, false);
    }

    private static List<Region> regions(List<ResidentCatalog.MapSpec> maps) {
        var grouped = new TreeMap<String, List<ResidentCatalog.MapSpec>>();
        var seen = new java.util.HashSet<Integer>();
        for (ResidentCatalog.MapSpec map : maps) {
            if (map.id() < 0 || map.region() == null || map.region().isBlank() || map.capacity() < 1 || !seen.add(map.id())) {
                throw new IllegalArgumentException("Map IDs must be unique and have a region: " + map);
            }
            grouped.computeIfAbsent(map.region(), ignored -> new ArrayList<>()).add(map);
        }
        List<Region> result = new ArrayList<>();
        for (List<ResidentCatalog.MapSpec> group : grouped.values()) {
            group.sort(Comparator.comparingInt(ResidentCatalog.MapSpec::id));
            result.add(new Region(group.stream().filter(ResidentCatalog.MapSpec::town).toList(),
                    group.stream().filter(map -> !map.town()).toList(),
                    group.stream().mapToLong(map -> (long) map.capacity() * (map.town() ? 3 : 1)).sum()));
        }
        return List.copyOf(result);
    }

    private Region region(long identity) {
        long ticket = Math.floorMod(identity, regionWeight);
        for (Region region : regions) {
            if (ticket < region.weight()) {
                return region;
            }
            ticket -= region.weight();
        }
        throw new AssertionError("Region weights must cover every selection");
    }

    private void addAvailable(Presence presence) {
        available.computeIfAbsent(key(presence.mapId(), presence.channel()), ignored -> new LinkedHashMap<>())
                .put(presence.profile().id(), presence);
    }

    private void removeAvailable(Presence presence) {
        long key = key(presence.mapId(), presence.channel());
        Map<Integer, Presence> bucket = available.get(key);
        if (bucket != null) {
            bucket.remove(presence.profile().id());
            if (bucket.isEmpty()) {
                available.remove(key);
            }
        }
    }

    private void updateActivityCount(Presence old, Presence next) {
        if (old.activity() != next.activity()) {
            activityCounts[old.activity().ordinal()]--;
            activityCounts[next.activity().ordinal()]++;
        }
    }

    private static int originalHome(int id) {
        return id <= 20 ? HENESYS : id <= 26 ? TRAINING_GROUND : PIG_FIELD;
    }

    private static long key(int mapId, int channel) {
        return (long) channel << 32 | (mapId & 0xFFFFFFFFL);
    }

    private static int index(long hash, int size) {
        return (int) Math.floorMod(hash, (long) size);
    }

    private static long mix(long value) {
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }
}
