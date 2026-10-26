package server.population;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import server.maps.Portal;
import server.population.navigation.CompanionNavigation;
import tools.PacketCreator;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Town actors have no login session and never count as players or control monsters. */
public final class TownPopulation {
    public record PartyReservation(ResidentProfiles.Profile profile, Point spawn, int worldId) {
        public PartyReservation { spawn = new Point(spawn); }
        @Override public Point spawn() { return new Point(spawn); }
    }
    private final Set<String> partyReservations = ConcurrentHashMap.newKeySet();

    /** Promote existing identities, retaining their world claim while they are real party actors. */
    public static List<PartyReservation> reservePartyMembers(Character host, int count) {
        TownPopulation population = Holder.INSTANCE;
        synchronized (population) {
            long now = System.currentTimeMillis();
            MapleMap map = host.getMap();
            population.prepareScene(map, now);
            ResidentWorld world = population.world(map, now);
            int channel = host.getClient().getChannel();
            var result = new ArrayList<PartyReservation>();
            Scene scene = population.scenes.get(map);
            if (scene != null) for (TownResident resident : List.copyOf(scene.residents)) {
                var profile = resident.getProfile();
                if (result.size() == count) break;
                if (!partyEligible(profile) || population.journeys.values().stream().anyMatch(j -> j.resident == resident)) continue;
                Point position = resident.getPosition();
                population.retire(scene, resident, now);
                var state = world.state(profile.id());
                if (world.claim(profile.id(), state.mapId(), state.channel())) {
                    world.moveClaim(profile.id(), map.getId(), channel);
                    population.partyReservations.add(map.getWorld() + ":" + profile.id());
                    result.add(new PartyReservation(profile, position, map.getWorld()));
                }
            }
            // Background residents may arrive from the local region; never duplicate an active identity.
            for (var profile : ResidentCatalog.all()) {
                if (result.size() == count || population.routines.size() + population.partyReservations.size() >= MAX_ACTIVE) break;
                if (!partyEligible(profile)) continue;
                var state = world.state(profile.id());
                if (state.claimed() || state.channel() != channel || state.mapId() / 1_000_000 != 103) continue;
                if (!world.claim(profile.id(), state.mapId(), channel)) continue;
                world.moveClaim(profile.id(), map.getId(), channel);
                Point spawn = map.getPortal(0).getPosition();
                population.partyReservations.add(map.getWorld() + ":" + profile.id());
                result.add(new PartyReservation(profile, spawn, map.getWorld()));
            }
            if (result.size() != count) {
                releasePartyMembers(result);
                return List.of();
            }
            return List.copyOf(result);
        }
    }

    private static boolean partyEligible(ResidentProfiles.Profile p) {
        return p.level() >= 21 && p.level() <= 30 && p.job() >= 100 && p.job() < 500 && p.job() % 100 == 0;
    }

    public static void releasePartyMembers(List<PartyReservation> reservations) {
        TownPopulation population = Holder.INSTANCE;
        synchronized (population) {
            for (var reservation : reservations) {
                if (population.partyReservations.remove(reservation.worldId() + ":" + reservation.profile().id())) {
                    ResidentWorld world = population.worlds.get(reservation.worldId());
                    if (world != null) world.release(reservation.profile().id(), System.currentTimeMillis());
                }
            }
        }
    }
    public static final int HENESYS = 100000000;
    private static final Logger log = LoggerFactory.getLogger(TownPopulation.class);
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("cosmic.residents.enabled", "true"));
    private static final int ACTOR_ID_BASE = 1_800_000_000;
    private static final double TALK_RANGE_SQUARED = 650 * 650;
    static final int MAX_ACTIVE = Math.max(48, Math.min(1024, Integer.getInteger("cosmic.residents.activeLimit", 256)));
    private static final long EMPTY_SCENE_GRACE = 20_000;
    // Homes remain stable across visits and restarts. Main-street seeds skip raised benches.
    private static final int[][] HOMES = {
            {2820, 330}, {3000, 330}, {3200, 330}, {2490, 330}, {2610, 330},
            {2730, 330}, {2910, 330}, {3090, 330}, {3290, 330}, {3350, 330},
            {150, 260}, {300, 260}, {470, 260}, {610, 260}, {740, 260},
            {4800, 440}, {4990, 440}, {5250, 440}, {3550, 110}, {3890, 110}
    };

    private final Map<MapleMap, Scene> scenes = new ConcurrentHashMap<>();
    private final Map<Integer, CompanionTravel> journeys = new ConcurrentHashMap<>();
    private final Map<TownResident, ResidentRoutine> routines = new ConcurrentHashMap<>();
    private final Map<Integer, ResidentWorld> worlds = new ConcurrentHashMap<>();
    private final Set<Integer> introduced = ConcurrentHashMap.newKeySet();
    private final ResidentSocial social = new ResidentSocial();
    private final ScheduledExecutorService simulation = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "town-residents");
        thread.setDaemon(true);
        return thread;
    });
    private final ScheduledExecutorService persistence = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "resident-memories");
        thread.setDaemon(true);
        return thread;
    });
    private final ResidentMemoryStore memories;
    private final ResidentDialogue dialogue;
    private final boolean persistent;

    private static final class Holder {
        private static final TownPopulation INSTANCE = new TownPopulation();
    }

    private record LoadedMemory(ResidentMemoryStore store, boolean persistent) { }

    private static LoadedMemory loadMemories() {
        ResidentMemoryStore store;
        boolean loaded = true;
        try {
            store = new ResidentMemoryStore(Path.of(System.getProperty("cosmic.residents.memory", ".local/population/memories.properties")));
        } catch (IOException e) {
            log.error("Resident memory could not be opened; preserving the file and using session-only memories", e);
            store = ResidentMemoryStore.inMemory();
            loaded = false;
        }
        return new LoadedMemory(store, loaded);
    }

    private TownPopulation() {
        this(loadMemories(), true);
    }

    TownPopulation(ResidentMemoryStore store) {
        this(new LoadedMemory(store, false), false);
    }

    private TownPopulation(LoadedMemory loaded, boolean scheduled) {
        memories = loaded.store();
        persistent = loaded.persistent();
        dialogue = new ResidentDialogue(memories);
        if (scheduled) {
            simulation.scheduleWithFixedDelay(this::tick, 50, 50, TimeUnit.MILLISECONDS);
            persistence.scheduleWithFixedDelay(this::saveMemories, 5, 5, TimeUnit.SECONDS);
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "save-resident-memories"));
        }
    }

    public static void prepare(MapleMap map) {
        if (ENABLED && isPopulated(map.getId()) && map.getEventInstance() == null) {
            try {
                TownPopulation population = Holder.INSTANCE;
                population.prepareScene(map, System.currentTimeMillis());
            } catch (RuntimeException failure) {
                // Town atmosphere is optional: a bad spawn must not abort the
                // real player's map entry or leave half a roster to duplicate.
                log.error("Unable to prepare residents for map {}; normal map entry will continue", map.getId(), failure);
            }
        }
    }

    public static void onEnter(Character player) {
        if (player.isResidentBot()) return;
        if (!ENABLED) {
            return;
        }
        TownPopulation population = Holder.INSTANCE;
        Scene scene = population.scenes.get(player.getMap());
        if (scene == null) {
            return;
        }
        population.submit(() -> {
            long visit = ++scene.visitSequence;
            scene.visits.put(player.getId(), visit);
            population.schedule(() -> {
                if (!scene.isCurrentVisit(player, visit)) {
                    return;
                }
                if (population.introduced.add(player.getId())) {
                    player.dropMessage(5, "This area has simulated adventurers. Say a name to chat, or type @residents.");
                }
                long now = System.currentTimeMillis();
                if (player.isHidden() || scene.map.isMuted() || now - scene.lastSpeech < 3000) {
                    return;
                }
                TownResident greeter = scene.nearest(player.getPosition(), true);
                ResidentMemoryStore.Memory memory = greeter == null ? null
                        : population.memories.get(player.getId(), greeter.getProfile().id(), player.getName());
                if (memory != null && memory.hasMet() && now - memory.lastSeen() > 180_000
                        && scene.random.nextInt(3) == 0 && nearby(greeter, player)
                        && population.availableForChat(scene, greeter, now)
                        && scene.reserveSpeech(greeter.getId(), now, 3000)) {
                    population.routines.get(greeter).conversation(now + 3000);
                    greeter.faceToward(player.getPosition());
                    greeter.say(population.dialogue.greeting(greeter.getProfile(), player.getId(), player.getName()));
                    scene.lastSpeech = now;
                }
            }, 2500, TimeUnit.MILLISECONDS);
        });
    }

    private static boolean isPopulated(int mapId) {
        return ResidentCatalog.maps().stream().anyMatch(m -> m.id() == mapId);
    }

    private ResidentWorld world(MapleMap map, long now) {
        return worlds.computeIfAbsent(map.getWorld(), id -> new ResidentWorld(ResidentCatalog.all(),
                ResidentCatalog.maps(), Math.max(1, map.getWorldServer().getChannelsSize()), now));
    }

    synchronized void prepareScene(MapleMap map, long now) {
        if (map.getEventInstance() != null || !isPopulated(map.getId())) return;
        evictEmptyScenes(now, true);
        Scene scene = scenes.get(map);
        if (scene != null) {
            scene.lastOccupied = now;
            return; // Refill on the simulation thread so existing observers also receive new spawns.
        }
        scene = new Scene(map);
        scenes.put(map, scene);
        scene.lastOccupied = now;
        materialize(scene, now, false, scene.limit);
    }

    private void materialize(Scene scene, long now, boolean broadcast, int batch) {
        if (!isPopulated(scene.map.getId()) || scene.residents.size() >= scene.limit || routines.size() + partyReservations.size() >= MAX_ACTIVE) return;
        ResidentWorld world = world(scene.map, now);
        int channel = scene.map.getChannelServer().getId();
        List<Point> anchors = scene.map.getAllPlayers().stream().sorted(Comparator.comparingInt(Character::getId))
                .map(Character::getPosition).toList();
        if (anchors.isEmpty()) anchors = scene.map.getPortals().stream().filter(p -> p.getType() == 0)
                .sorted(Comparator.comparingInt(Portal::getId)).map(Portal::getPosition).toList();
        int added = 0;
        List<ResidentWorld.Presence> candidates = world.candidates(scene.map.getId(), channel, now).stream()
                .sorted(Comparator.comparingInt(p -> p.profile().id())).toList();
        for (ResidentWorld.Presence presence : candidates) {
            if (scene.residents.size() >= scene.limit || routines.size() + partyReservations.size() >= MAX_ACTIVE || added >= batch) break;
            var profile = presence.profile();
            if (!world.claim(profile.id(), scene.map.getId(), channel)) continue;
            TownResident resident = null;
            try {
                Point spawn;
                if (profile.id() <= 20) spawn = new Point(HOMES[profile.id() - 1][0], HOMES[profile.id() - 1][1]);
                else if (profile.id() <= 28) spawn = ResidentRoster.forMap(scene.map.getId()).stream()
                        .filter(p -> p.profile().id() == profile.id()).findFirst().orElseThrow().position();
                else spawn = ResidentSpawns.choose(CompanionNavigation.forMap(scene.map), anchors, profile.id());
                resident = new TownResident(ACTOR_ID_BASE + profile.id(), profile, scene.map, spawn);
                scene.map.addMapObject(resident);
                ResidentRoutine routine = new ResidentRoutine(resident, this::transferResident);
                if (profile.id() > 28) routine.resumeBackground(presence.activity(), now);
                routines.put(resident, routine);
                scene.residents.add(resident);
                if (broadcast) scene.map.broadcastMessage(ResidentPackets.spawn(resident));
                added++;
            } catch (RuntimeException failure) {
                if (resident != null) {
                    resident.retire();
                    scene.residents.remove(resident);
                    ResidentRoutine routine = routines.remove(resident);
                    if (routine != null) routine.close();
                    if (scene.map.getMapObject(resident.getObjectId()) == resident) scene.map.removeMapObject(resident.getObjectId());
                }
                world.release(profile.id(), now);
                log.warn("Unable to materialize resident {} in map {}", profile.name(), scene.map.getId(), failure);
                break; // Invalid geometry should not repeat thousands of times on one map entry.
            }
        }
    }

    private void retire(Scene scene, TownResident resident, long now) {
        synchronized (resident) {
            resident.retire();
            ResidentRoutine routine = routines.remove(resident);
            if (routine != null) routine.close();
            scene.map.broadcastMessage(ResidentPackets.remove(resident.getId()));
            scene.map.removeMapObject(resident.getObjectId());
            scene.residents.remove(resident);
            scene.pendingReplies.remove(resident.getId());
            scene.speechUntil.remove(resident.getId());
            scene.lastPartner.values().removeIf(id -> id == resident.getId());
            world(scene.map, now).release(resident.getProfile().id(), now);
        }
    }

    private void evictEmptyScenes(long now, boolean reclaim) {
        Set<TownResident> travelling = new java.util.HashSet<>();
        journeys.values().forEach(j -> travelling.add(j.resident));
        for (Scene scene : List.copyOf(scenes.values())) {
            if (!scene.map.getAllPlayers().isEmpty()) continue;
            if (scene.residents.stream().anyMatch(r -> travelling.contains(r)
                    || routines.containsKey(r) && routines.get(r).travelling())) continue;
            if (now - scene.lastOccupied < EMPTY_SCENE_GRACE && !(reclaim && routines.size() >= MAX_ACTIVE)) continue;
            for (TownResident resident : List.copyOf(scene.residents)) retire(scene, resident, now);
            scenes.remove(scene.map, scene);
        }
    }

    public static void onLeave(Character player) {
        if (player.isResidentBot()) return;
        if (!ENABLED) {
            return;
        }
        Scene scene = Holder.INSTANCE.scenes.get(player.getMap());
        if (scene != null) {
            Holder.INSTANCE.submit(() -> {
                scene.visits.remove(player.getId());
                scene.lastReply.remove(player.getId());
                scene.lastPartner.remove(player.getId());
                scene.observedLevels.remove(player.getId());
                scene.pendingReplies.values().removeIf(p -> p.player() == player);
                scene.latestMovement.remove(player.getId());

            });
        }
    }

    public static void onPortalCrossed(Character player, MapleMap source, MapleMap destination, Portal portal, Point arrival) {
        if (!ENABLED) {
            return;
        }
        Point landing = new Point(arrival);
        TownPopulation population = Holder.INSTANCE;
        population.submit(() -> {
            CompanionTravel journey = population.journeys.get(player.getId());
            if (journey == null) {
                return;
            }
            if (!journey.cross(source, destination, portal, landing)) {
                journey.returnHome();
                population.journeys.remove(player.getId(), journey);
                player.dropMessage(5, journey.resident.getName() + " headed back to Henesys. You can meet again there.");
            }
        });
    }

    public static void companionStatus(Character player, boolean dismiss) {
        if (!ENABLED) {
            return;
        }
        TownPopulation population = Holder.INSTANCE;
        population.submit(() -> {
            CompanionTravel journey = population.journeys.get(player.getId());
            if (journey == null) {
                player.dropMessage(5, "Meet MapleMia, PipArcher or Juniper in Henesys and ask them to follow you.");
                return;
            }
            if (dismiss) {
                journey.returnHome();
                population.journeys.remove(player.getId(), journey);
                player.dropMessage(5, journey.resident.getName() + " headed home. See you in Henesys!");
            } else {
                String location = journey.resident.getMap().getMapName();
                player.dropMessage(5, journey.resident.getName() + (journey.isFollowing() ? " is following you" : " is waiting")
                        + " in " + location + ". Use @companion dismiss to send them home.");
            }
        });
    }

    private synchronized void transferResident(TownResident resident, MapleMap destination, Point arrival) {
        // Project the destination before removing anything from the source map.
        CompanionNavigation navigation = CompanionNavigation.forMap(destination);
        Point safe = navigation.groundBelow(new Point(arrival.x, arrival.y - 8), 600)
                .or(() -> navigation.project(arrival, 160)).orElseThrow(() -> new IllegalArgumentException("No landing near portal " + arrival)).position();
        Scene next = scenes.computeIfAbsent(destination, Scene::new);
        if (next.residents.size() >= next.limit) {
            TownResident replace = next.residents.stream().filter(r -> r != resident && !r.getProfile().companion())
                    .filter(r -> journeys.values().stream().noneMatch(j -> j.resident == r))
                    .max(Comparator.comparingInt(r -> r.getProfile().id())).orElse(null);
            if (replace == null) throw new IllegalStateException("No resident capacity at destination");
            retire(next, replace, System.currentTimeMillis());
        }
        synchronized (resident) {
            MapleMap source = resident.getMap();
            Scene previous = scenes.get(source);
            Point oldPosition = resident.getPosition();
            Point rollbackPosition = CompanionNavigation.forMap(source).groundBelow(oldPosition, 1200)
                    .or(() -> CompanionNavigation.forMap(source).project(oldPosition, 200)).orElseThrow(
                            () -> new IllegalArgumentException("No return landing near " + oldPosition)).position();
            source.broadcastMessage(ResidentPackets.remove(resident.getId()));
            source.removeMapObject(resident.getObjectId());
            if (previous != null) {
                previous.residents.remove(resident);
                previous.speechUntil.remove(resident.getId());
                previous.lastPartner.values().removeIf(id -> id == resident.getId());
            }
            try {
                resident.relocateTo(destination, safe);
                destination.addMapObject(resident);
                next.residents.add(resident);
                for (Character observer : destination.getAllPlayers()) {
                    next.visits.computeIfAbsent(observer.getId(), id -> ++next.visitSequence);
                }
                destination.broadcastMessage(ResidentPackets.spawn(resident));
                world(destination, System.currentTimeMillis()).moveClaim(resident.getProfile().id(), destination.getId(),
                        destination.getChannelServer().getId());
                log.info("Resident {} traveled from {} to {}", resident.getName(), source.getId(), destination.getId());
            } catch (RuntimeException failure) {
                if (destination.getMapObject(resident.getObjectId()) == resident) {
                    destination.removeMapObject(resident.getObjectId());
                }
                next.residents.remove(resident);
                resident.relocateTo(source, rollbackPosition);
                source.addMapObject(resident);
                if (previous != null) {
                    previous.residents.add(resident);
                }
                source.broadcastMessage(ResidentPackets.spawn(resident));
                throw failure;
            }
            if (previous != null && previous.residents.isEmpty() && !isPopulated(source.getId())) {
                scenes.remove(source, previous);
            }
        }
    }

    public static void showResidents(Character player) {
        if (!ENABLED) {
            player.dropMessage(5, "Town residents are disabled on this server.");
            return;
        }
        TownPopulation population = Holder.INSTANCE;
        ResidentWorld world = population.world(player.getMap(), System.currentTimeMillis());
        var performance = ResidentPopulationMetrics.snapshot();
        player.dropMessage(5, "World population: " + world.total() + " simulated identities; " + world.claimedCount()
                + " fully active, " + (world.total() - world.claimedCount()) + " on background schedules.");
        player.dropMessage(5, "Server active budget: " + (population.routines.size() + population.partyReservations.size()) + "/" + MAX_ACTIVE
                + ". Resident tick p95: " + String.format(Locale.ROOT, "%.2f", performance.p95Millis()) + " ms.");
        Scene scene = population.scenes.get(player.getMap());
        if (scene == null || scene.residents.isEmpty()) {
            player.dropMessage(5, "Meet companions in Henesys, then ask one to follow you into the fields.");
            return;
        }
        player.dropMessage(5, "Simulated adventurers here: " + scene.residents.size() + "/" + scene.limit
                + ". Companions: MapleMia, PipArcher, Juniper in Henesys CH1.");
        String names = scene.residents.stream().filter(r -> nearby(r, player)).map(TownResident::getName)
                .reduce((a, b) -> a + ", " + b).orElse("Walk toward the market to meet someone.");
        player.dropMessage(5, "Nearby: " + names);
        player.dropMessage(5, "Say 'MapleMia, hello' or @talk MapleMia hello. Try 'follow me', 'stay', or 'remember me'.");
        player.dropMessage(5, "Hunters train and collect their own drops; companions assist with monsters you've attacked.");
        if (!Holder.INSTANCE.persistent) {
            player.dropMessage(5, "Memories are available for this session; the server could not open the memory file.");
        }
    }

    public static void onChat(Character player, String text) {
        if (server.population.party.KpqPartyService.onChat(player, text, false)) return;
        if (!ENABLED || text.isBlank()) {
            return;
        }
        Scene scene = Holder.INSTANCE.scenes.get(player.getMap());
        if (scene == null) {
            return;
        }
        Holder.INSTANCE.submit(() -> {
            TownResident resident = addressedResident(scene.residents, text);
            if (resident != null) {
                Holder.INSTANCE.respond(scene, player, resident, text, false);
                return;
            }
            // Only continue an existing conversation briefly; ordinary map chat is not a bot summons.
            Integer partner = scene.lastPartner.get(player.getId());
            long since = System.currentTimeMillis() - scene.lastReply.getOrDefault(player.getId(), 0L);
            if (partner != null && since < 30_000 && ResidentDialogue.continuesConversation(text)) {
                Holder.INSTANCE.respond(scene, player, scene.byId(partner), text, false);
            } else if (partner == null && ResidentChatInput.parse(text).intent() == ResidentChatInput.Intent.GREETING
                    && System.currentTimeMillis() - scene.lastSpeech > 15_000) {
                TownResident neighbor = scene.residents.stream()
                        .filter(r -> Holder.INSTANCE.availableForChat(scene, r, System.currentTimeMillis()))
                        .filter(r -> r.getPosition().distanceSq(player.getPosition()) < 250 * 250)
                        .min(Comparator.comparingDouble(r -> r.getPosition().distanceSq(player.getPosition()))).orElse(null);
                if (neighbor != null) Holder.INSTANCE.respond(scene, player, neighbor, text, false);
            }
        });
    }

    public static void talk(Character player, String name, String text, boolean echo) {
        if (server.population.party.KpqPartyService.onChat(player, text, false)) return;
        if (!ENABLED) {
            player.dropMessage(5, "Town residents are disabled on this server.");
            return;
        }
        Scene scene = Holder.INSTANCE.scenes.get(player.getMap());
        if (scene == null) {
            return;
        }
        TownResident resident = scene.residents.stream().filter(r -> r.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
        if (resident == null) {
            player.dropMessage(5, "That resident isn't here. Use @residents to see who's nearby.");
            return;
        }
        Holder.INSTANCE.submit(() -> Holder.INSTANCE.respond(scene, player, resident, text, echo));
    }

    /** Consume a normal remote-character click when its network ID belongs to a resident. */
    public static boolean interact(Character player, int networkId) {
        if (!ENABLED) {
            return false;
        }
        TownPopulation population = Holder.INSTANCE;
        Scene scene = population.scenes.get(player.getMap());
        if (scene == null) {
            return false;
        }
        TownResident resident = scene.residents.stream().filter(r -> r.getId() == networkId).findFirst().orElse(null);
        if (resident == null) {
            return false;
        }
        population.submit(() -> population.respond(scene, player, resident, "hello", false));
        return true;
    }

    static TownResident addressedResident(List<TownResident> residents, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        TownResident first = null;
        int firstPosition = Integer.MAX_VALUE;
        for (TownResident resident : residents) {
            List<String> names = switch (resident.getName()) {
                case "MapleMia" -> List.of("maplemia", "mia");
                case "PipArcher" -> List.of("piparcher", "pip");
                default -> List.of(resident.getName().toLowerCase(Locale.ROOT));
            };
            for (String name : names) {
            int start = lower.indexOf(name);
            while (start >= 0 && start < firstPosition) {
                if ((start == 0 || !java.lang.Character.isLetterOrDigit(lower.charAt(start - 1)))
                        && (start + name.length() == lower.length() || !java.lang.Character.isLetterOrDigit(lower.charAt(start + name.length())))) {
                    String prefix = lower.substring(0, start).strip();
                    String suffix = lower.substring(start + name.length()).strip();
                    boolean aboutThem = suffix.matches("^(?:said|told|thinks|says|was|has|is)\\b.*");
                    if (!aboutThem && (prefix.isEmpty() || prefix.matches("(?:hi|hey|hello|yo|sup)[,!? ]*")
                            || suffix.matches("[!?., ]*"))) {
                        first = resident;
                        firstPosition = start;
                        break;
                    }
                }
                start = lower.indexOf(name, start + name.length());
            }
            }
        }
        return first;
    }

    private void respond(Scene scene, Character player, TownResident resident, String message, boolean echo) {
        if (resident == null || resident.getMap() != scene.map || player.getMap() != scene.map
                || !player.isLoggedin() || scene.map.isMuted() || !nearby(resident, player)) return;
        PendingReply pending = scene.pendingReplies.get(resident.getId());
        if (pending != null && pending.player() != player) {
            if (echo) player.dropMessage(5, resident.getName() + " is chatting. Give them a moment.");
            return;
        }
        String text = message.strip();
        if (text.isEmpty()) text = "hey";
        text = text.substring(0, Math.min(120, text.length()));
        if (echo) {
            if (player.isHidden()) player.dropMessage(5, "You to " + resident.getName() + ": " + text);
            else scene.map.broadcastMessage(PacketCreator.getChatText(player.getId(), resident.getName() + ", " + text, false, 0));
        }
        long now = System.currentTimeMillis();
        scene.lastReply.put(player.getId(), now);
        scene.lastPartner.put(player.getId(), resident.getId());
        ResidentDialogue.Intent requested = dialogue.intentFor(resident.getProfile(), player.getId(), text);
        boolean movement = requested == ResidentDialogue.Intent.FOLLOW || requested == ResidentDialogue.Intent.STOP_FOLLOWING;
        long revision = movement ? ++scene.movementSequence : 0;
        if (movement) scene.latestMovement.put(player.getId(), revision);
        if (pending != null) {
            pending.turn().append(text);
            if (movement) pending.turn().movementRevision(revision);
            return;
        }
        PendingReply queued = new PendingReply(player, resident, scene.visits.get(player.getId()), new ResidentChatTurn(resident.getName(), text));
        queued.turn().movementRevision(revision);
        scene.pendingReplies.put(resident.getId(), queued);
        long delay = Math.max(0, scene.speechUntil.getOrDefault(resident.getId(), 0L) - now)
                + ResidentSocial.replyDelay(resident.getProfile(), text, scene.sequence++);
        scene.speechUntil.put(resident.getId(), now + delay + 1800);
        scene.lastSpeech = now;
        ResidentRoutine routine = routines.get(resident);
        if (routine != null) routine.conversation(now + delay + 1800);
        resident.faceToward(player.getPosition());
        schedule(() -> {
            if (scene.pendingReplies.remove(resident.getId(), queued) && scene.isCurrentVisit(player, queued.visit())) {
                String latest = queued.turn().message();
                ResidentDialogue.Intent command = dialogue.intentFor(resident.getProfile(), player.getId(), latest);
                if ((command == ResidentDialogue.Intent.FOLLOW || command == ResidentDialogue.Intent.STOP_FOLLOWING)
                        && queued.turn().movementRevision() != scene.latestMovement.getOrDefault(player.getId(), 0L)) return;
                replyNow(scene, player, resident, latest);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    private record PendingReply(Character player, TownResident resident, Long visit, ResidentChatTurn turn) { }

    private void replyNow(Scene scene, Character player, TownResident resident, String message) {
        if (resident == null || resident.getMap() != scene.map || !scene.residents.contains(resident)
                || player.getMap() != scene.map || !player.isLoggedin() || scene.map.isMuted()) {
            return;
        }
        if (!nearby(resident, player)) {
            return;
        }
        long now = System.currentTimeMillis();
        String text = message.strip();
        if (text.isEmpty()) {
            text = "hello";
        }
        text = text.substring(0, Math.min(120, text.length()));
        scene.lastReply.put(player.getId(), now);
        scene.lastPartner.put(player.getId(), resident.getId());
        scene.lastSpeech = now; // also reserve the pause before the spoken reply
        ResidentDialogue.Intent intent = dialogue.intentFor(resident.getProfile(), player.getId(), text);
        ResidentRoutine routine = routines.get(resident);
        CompanionTravel currentJourney = journeys.get(player.getId());
        boolean walkingTogether = currentJourney != null && currentJourney.resident == resident && currentJourney.isFollowing();
        String contextualReply = dialogue.reply(resident.getProfile(), player.getId(), player.getName(), text,
                routine == null ? ResidentDialogue.Context.town() : routine.context(walkingTogether));
        String reply;
        if (intent == ResidentDialogue.Intent.FOLLOW) {
            CompanionTravel owner = journeys.values().stream().filter(j -> j.resident == resident).findFirst().orElse(null);
            if (!resident.getProfile().companion()) {
                reply = contextualReply;
            } else if (owner != null && owner.leader != player) {
                reply = "running with someone rn, sry";
            } else {
                CompanionTravel previous = journeys.get(player.getId());
                if (previous != null && previous.resident != resident) {
                    previous.returnHome();
                    journeys.remove(player.getId());
                }
                CompanionTravel journey = journeys.computeIfAbsent(player.getId(), id -> new CompanionTravel(player, resident, this::transferResident));
                if (routine != null) {
                    routine.suspend();
                }
                journey.follow();
                reply = contextualReply;
            }
        } else if (intent == ResidentDialogue.Intent.STOP_FOLLOWING) {
            CompanionTravel journey = journeys.get(player.getId());
            if (journey != null && journey.resident == resident) {
                journey.stay();
                reply = contextualReply;
            } else {
                reply = "kk, chilling here";
            }
        } else if (intent == ResidentDialogue.Intent.MAP_SPACE) {
            boolean reserved = journeys.values().stream().anyMatch(j -> j.resident == resident);
            if (reserved) reply = "just here with someone. u can train";
            else {
                if (routine != null && !scene.map.isTown()) routine.makeRoom(now + 120_000);
                reply = contextualReply;
            }
        } else {
            reply = contextualReply;
        }
        if (intent == ResidentDialogue.Intent.FAREWELL) {
            scene.lastPartner.remove(player.getId());
        }
        resident.faceToward(player.getPosition());
        String answer = reply;
        scene.speechUntil.put(resident.getId(), now + 1800);
        if (routine != null) {
            routine.conversation(now + 1800);
        }
        if (intent == ResidentDialogue.Intent.TALK || intent == ResidentDialogue.Intent.HELP
                || server.population.knowledge.GameKnowledge.isQuestion(text)) {
            Long visit = scene.visits.get(player.getId());
            String observed = "Map: " + scene.map.getMapName() + ". Activity: "
                    + (routine == null ? "standing nearby" : routine.context(walkingTogether).activity())
                    + ". You are a server-run resident. Only discuss facts from the supplied game knowledge. "
                    + "You have not joined a party or traded items through this conversation.";
            server.population.intelligence.ResidentIntelligence.getInstance().reply(resident.getProfile(),
                    server.population.knowledge.PlayerContext.snapshot(player), text, observed, answer)
                    .thenAccept(response -> {
                        var lines = server.population.intelligence.ResidentIntelligence.lines(response.text());
                        for (int i = 0; i < lines.size(); i++) {
                            String line = lines.get(i);
                            schedule(() -> {
                                if (scene.isCurrentVisit(player, visit) && scene.residents.contains(resident)
                                        && player.getMap() == scene.map && resident.getMap() == scene.map
                                        && nearby(resident, player) && !scene.map.isMuted()
                                        && scene.lastReply.getOrDefault(player.getId(), 0L) == now) {
                                    resident.say(line);
                                    scene.lastSpeech = System.currentTimeMillis();
                                }
                            }, i * 1200L, TimeUnit.MILLISECONDS);
                        }
                    });
        } else resident.say(answer);
        scene.lastSpeech = now;
    }

    private void tick() {
        long start = System.nanoTime();
        try {
            tick(System.currentTimeMillis());
        } catch (RuntimeException failure) {
            log.error("Resident population tick failed; retrying next tick", failure);
        } finally {
            ResidentPopulationMetrics.recordTick(System.nanoTime() - start);
        }
    }

    synchronized void tick(long now) {
        worlds.values().forEach(world -> world.tick(now));
        evictEmptyScenes(now, false);
        for (CompanionTravel journey : journeys.values()) {
            try {
                ResidentRoutine routine = routines.get(journey.resident);
                if (routine != null) {
                    routine.suspend();
                }
                if (!journey.tick(now)) {
                    journeys.remove(journey.leader.getId(), journey);
                } else if (routine != null) {
                    routine.assist(now, journey.leader, journey.canAssist());
                }
            } catch (RuntimeException failure) {
                log.error("Companion trip failed for {}", journey.resident.getName(), failure);
                try {
                    journey.returnHome();
                    journeys.remove(journey.leader.getId(), journey);
                } catch (RuntimeException returnFailure) {
                    log.error("Could not return companion home; will retry", returnFailure);
                }
            }
        }
        Map<TownResident, CompanionTravel> owned = new HashMap<>();
        journeys.values().forEach(j -> owned.put(j.resident, j));
        for (Scene scene : scenes.values()) {
            try {
                List<Character> players = scene.map.getAllPlayers();
                if (players.isEmpty()) {
                    scene.visits.clear();
                    scene.lastReply.clear();
                    scene.lastPartner.clear();
                    scene.observedLevels.clear();
                    scene.pendingReplies.clear();
                    scene.latestMovement.clear();
                } else {
                    scene.lastOccupied = now;
                    if (now >= scene.nextPopulation) {
                        materialize(scene, now, true, 4);
                        scene.nextPopulation = now + 2_000;
                    }
                }
                for (TownResident resident : scene.residents) {
                    if (resident.getMap() != scene.map) {
                        continue;
                    }
                    ResidentRoutine routine = routines.get(resident);
                    if (routine != null) {
                        routine.maintain(now);
                    }
                    // A traveling companion must still reach the exit after its
                    // leader has left; ordinary residents sleep in empty maps.
                    if (!players.isEmpty() || owned.containsKey(resident) && owned.get(resident).isFollowing()
                            || routine != null && routine.travelling()) {
                        if (routine != null && !owned.containsKey(resident)) {
                            routine.tick(now);
                        }
                        resident.tick(now);
                    }
                }
                observeLevels(scene, players, now);
                ambient(scene, players, now);
            } catch (Exception e) {
                log.error("Unable to update residents in map {}", scene.map.getId(), e);
            }
        }
    }

    private static boolean nearby(TownResident resident, Character player) {
        Point a = resident.getPosition();
        Point b = player.getPosition();
        return a.distanceSq(b) <= TALK_RANGE_SQUARED && Math.abs(a.y - b.y) < 170;
    }

    private boolean availableForChat(Scene scene, TownResident resident, long now) {
        if (resident.getMap() != scene.map || resident.getFootholdId() == 0 || resident.isFollowing()
                || now < scene.speechUntil.getOrDefault(resident.getId(), 0L)
                || journeys.values().stream().anyMatch(j -> j.resident == resident)) {
            return false;
        }
        ResidentRoutine routine = routines.get(resident);
        if (routine == null) {
            return false;
        }
        return switch (routine.context(false).activity()) {
            case IDLE, RESTING, SHOPPING -> true;
            default -> false;
        };
    }

    private void ambient(Scene scene, List<Character> players, long now) {
        if (now < scene.nextAmbient || now - scene.lastSpeech < 20_000 || scene.map.isMuted() || players.isEmpty()) {
            return;
        }
        scene.nextAmbient = now + 35_000 + scene.random.nextInt(45_000);
        List<TownResident> audible = scene.residents.stream().filter(r -> availableForChat(scene, r, now))
                .filter(r -> players.stream().anyMatch(p -> !p.isHidden()
                        && Math.abs(p.getPosition().y - r.getPosition().y) < 130
                        && p.getPosition().distanceSq(r.getPosition()) < 500 * 500)).toList();
        if (audible.isEmpty()) {
            return;
        }
        TownResident speaker = audible.get(scene.random.nextInt(audible.size()));
        TownResident partner = scene.residents.stream().filter(r -> r != speaker && availableForChat(scene, r, now))
                .filter(r -> r.getPosition().distanceSq(speaker.getPosition()) < 230 * 230
                        && Math.abs(r.getPosition().y - speaker.getPosition().y) < 45)
                .min(Comparator.comparingDouble(r -> r.getPosition().distanceSq(speaker.getPosition()))).orElse(null);
        ResidentDialogue.Context context = routines.get(speaker).context(false);
        if (partner != null && scene.random.nextInt(4) != 0) {
            ResidentDialogue.Exchange exchange = dialogue.exchange(speaker.getProfile(), partner.getProfile(), context, scene.sequence++);
            if (!social.canSpeak(speaker.getId(), exchange.opener(), now)
                    || !social.canSpeak(partner.getId(), exchange.answer(), now)) {
                return;
            }
            long delay = 1800 + ResidentSocial.replyDelay(partner.getProfile(), exchange.answer(), scene.sequence++);
            scene.reserveSpeech(speaker.getId(), now, delay + 2000);
            scene.reserveSpeech(partner.getId(), now, delay + 2500);
            routines.get(speaker).conversation(now + delay + 2000);
            routines.get(partner).conversation(now + delay + 2500);
            speaker.faceToward(partner.getPosition());
            speaker.say(exchange.opener());
            social.recordSpeech(speaker.getId(), exchange.opener(), now);
            scene.lastSpeech = now;
            schedule(() -> {
                if (speaker.getMap() == scene.map && partner.getMap() == scene.map && !scene.map.isMuted()
                        && !scene.map.getAllPlayers().isEmpty()
                        && speaker.getPosition().distanceSq(partner.getPosition()) < 270 * 270
                        && social.canSpeak(partner.getId(), exchange.answer(), System.currentTimeMillis())) {
                    partner.faceToward(speaker.getPosition());
                    partner.say(exchange.answer());
                    social.recordSpeech(partner.getId(), exchange.answer(), System.currentTimeMillis());
                    scene.lastSpeech = System.currentTimeMillis();
                }
            }, delay, TimeUnit.MILLISECONDS);
        } else {
            String line = dialogue.ambient(speaker.getProfile(), context, scene.sequence++);
            if (social.canSpeak(speaker.getId(), line, now) && scene.reserveSpeech(speaker.getId(), now, 2500)) {
                speaker.say(line);
                social.recordSpeech(speaker.getId(), line, now);
                scene.lastSpeech = now;
            }
        }
    }

    private void observeLevels(Scene scene, List<Character> players, long now) {
        for (Character player : players) {
            Integer previous = scene.observedLevels.put(player.getId(), player.getLevel());
            if (previous == null || player.getLevel() <= previous || player.isHidden() || scene.map.isMuted()
                    || now - scene.lastSpeech < 12_000) {
                continue;
            }
            TownResident witness = scene.residents.stream().filter(r -> nearby(r, player))
                    .filter(r -> r.getFootholdId() != 0 && now >= scene.speechUntil.getOrDefault(r.getId(), 0L))
                    .min(Comparator.comparingDouble(r -> r.getPosition().distanceSq(player.getPosition()))).orElse(null);
            if (witness != null) {
                String line = dialogue.levelUp(witness.getProfile(), player.getName(), scene.sequence++);
                if (social.canSpeak(witness.getId(), line, now) && scene.reserveSpeech(witness.getId(), now, 3500)) {
                    ResidentRoutine routine = routines.get(witness);
                    if (routine != null) {
                        routine.conversation(now + 2500);
                    }
                    witness.emote(2);
                    witness.say(line);
                    social.recordSpeech(witness.getId(), line, now);
                    scene.lastSpeech = now;
                }
            }
        }
    }

    static List<TownResident> createResidents(MapleMap map) {
        List<TownResident> people = new ArrayList<>();
        List<ResidentProfiles.Profile> profiles = ResidentProfiles.all();
        // Validate every home before publishing any map objects. Retrying an
        // entry after a failed construction cannot produce duplicate actors.
        for (int i = 0; i < profiles.size(); i++) {
            var profile = profiles.get(i);
            people.add(new TownResident(ACTOR_ID_BASE + profile.id(), profile, map,
                    new Point(HOMES[i][0], HOMES[i][1])));
        }
        try {
            for (TownResident resident : people) {
                map.addMapObject(resident);
            }
        } catch (RuntimeException failure) {
            for (TownResident resident : people) {
                if (map.getMapObject(resident.getObjectId()) == resident) {
                    map.removeMapObject(resident.getObjectId());
                }
            }
            throw failure;
        }
        return List.copyOf(people);
    }

    static List<TownResident> createHunters(MapleMap map) {
        List<TownResident> people = ResidentRoster.forMap(map.getId()).stream().map(p ->
                new TownResident(ACTOR_ID_BASE + p.profile().id(), p.profile(), map, p.position())).toList();
        try {
            people.forEach(map::addMapObject);
        } catch (RuntimeException failure) {
            for (TownResident resident : people) {
                if (map.getMapObject(resident.getObjectId()) == resident) {
                    map.removeMapObject(resident.getObjectId());
                }
            }
            throw failure;
        }
        return people;
    }

    private void saveMemories() {
        try {
            memories.flush();
        } catch (IOException e) {
            log.error("Could not save resident memories; will retry", e);
        }
    }

    void close() {
        submit(() -> {
            synchronized (this) {
                long now = System.currentTimeMillis();
                for (Scene scene : List.copyOf(scenes.values())) {
                    for (TownResident resident : List.copyOf(scene.residents)) retire(scene, resident, now);
                }
                scenes.clear();
            }
        });
        simulation.shutdown();
        persistence.shutdown();
        try {
            simulation.awaitTermination(3, TimeUnit.SECONDS);
            persistence.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        saveMemories();
    }

    private void submit(Runnable action) {
        try {
            simulation.execute(serialized(action));
        } catch (RejectedExecutionException stopped) {
            if (!simulation.isShutdown()) {
                throw stopped;
            }
        }
    }

    private Runnable serialized(Runnable action) {
        return () -> {
            synchronized (this) {
                action.run();
            }
        };
    }

    private void schedule(Runnable action, long delay, TimeUnit unit) {
        try {
            simulation.schedule(serialized(action), delay, unit);
        } catch (RejectedExecutionException stopped) {
            if (!simulation.isShutdown()) throw stopped;
        }
    }

    private final class Scene {
        final MapleMap map;
        final List<TownResident> residents;
        final Map<Integer, Integer> lastPartner = new HashMap<>();
        final Map<Integer, Long> lastReply = new HashMap<>();
        final Map<Integer, Long> visits = new HashMap<>();
        final Map<Integer, Long> speechUntil = new HashMap<>();
        final Map<Integer, Integer> observedLevels = new HashMap<>();
        final Map<Integer, PendingReply> pendingReplies = new HashMap<>();
        final Map<Integer, Long> latestMovement = new HashMap<>();
        long movementSequence;
        final Random random;
        final int limit;
        long lastOccupied = System.currentTimeMillis();
        long nextPopulation;
        long nextAmbient;
        long lastSpeech;
        long sequence;
        long visitSequence;

        Scene(MapleMap map) {
            this.map = map;
            random = new Random(map.getId() * 31L + map.getChannelServer().getId());
            nextAmbient = System.currentTimeMillis() + 25_000 + random.nextInt(25_000);
            limit = ResidentCatalog.maps().stream().filter(m -> m.id() == map.getId())
                    .mapToInt(ResidentCatalog.MapSpec::capacity).findFirst().orElse(16);
            residents = new CopyOnWriteArrayList<>();
        }

        boolean isCurrentVisit(Character player, Long visit) {
            return visit != null && visit.equals(visits.get(player.getId()))
                    && player.getMap() == map && player.isLoggedin();
        }

        boolean reserveSpeech(int residentId, long now, long duration) {
            if (now < speechUntil.getOrDefault(residentId, 0L)) {
                return false;
            }
            speechUntil.put(residentId, now + duration);
            return true;
        }

        TownResident byId(int id) {
            return residents.stream().filter(r -> r.getId() == id).findFirst().orElse(null);
        }

        TownResident nearest(Point position, boolean companion) {
            return residents.stream().filter(r -> !companion || r.getProfile().companion())
                    .min(Comparator.comparingDouble(r -> r.getPosition().distanceSq(position))).orElse(null);
        }
    }
}
