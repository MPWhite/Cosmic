package server.population;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Town actors have no login session and never count as players or control monsters. */
public final class TownPopulation {
    public static final int HENESYS = 100000000;
    private static final Logger log = LoggerFactory.getLogger(TownPopulation.class);
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("cosmic.residents.enabled", "true"));
    private static final int ACTOR_ID_BASE = 1_800_000_000;
    private static final double TALK_RANGE_SQUARED = 650 * 650;
    // Homes remain stable across visits and restarts. Main-street seeds skip raised benches.
    private static final int[][] HOMES = {
            {2820, 330}, {3000, 330}, {3200, 330}, {2490, 330}, {2610, 330},
            {2730, 330}, {2910, 330}, {3090, 330}, {3290, 330}, {3350, 330},
            {150, 260}, {300, 260}, {470, 260}, {610, 260}, {740, 260},
            {4800, 440}, {4990, 440}, {5250, 440}, {3550, 110}, {3890, 110}
    };

    private final Map<MapleMap, Scene> scenes = new ConcurrentHashMap<>();
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

    private TownPopulation() {
        ResidentMemoryStore store;
        boolean loaded = true;
        try {
            store = new ResidentMemoryStore(Path.of(System.getProperty("cosmic.residents.memory", ".local/population/memories.properties")));
        } catch (IOException e) {
            log.error("Resident memory could not be opened; preserving the file and using session-only memories", e);
            store = ResidentMemoryStore.inMemory();
            loaded = false;
        }
        memories = store;
        persistent = loaded;
        dialogue = new ResidentDialogue(memories);
        simulation.scheduleWithFixedDelay(this::tick, 50, 50, TimeUnit.MILLISECONDS);
        persistence.scheduleWithFixedDelay(this::saveMemories, 5, 5, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "save-resident-memories"));
    }

    public static void prepare(MapleMap map) {
        if (ENABLED && map.getId() == HENESYS && map.getEventInstance() == null) {
            try {
                Holder.INSTANCE.scenes.computeIfAbsent(map, Scene::new);
            } catch (RuntimeException failure) {
                // Town atmosphere is optional: a bad spawn must not abort the
                // real player's map entry or leave half a roster to duplicate.
                log.error("Unable to prepare Henesys residents; normal map entry will continue", failure);
            }
        }
    }

    public static void onEnter(Character player) {
        if (!ENABLED || player.getMapId() != HENESYS) {
            return;
        }
        TownPopulation population = Holder.INSTANCE;
        Scene scene = population.scenes.get(player.getMap());
        if (scene == null) {
            return;
        }
        population.simulation.execute(() -> {
            long visit = ++scene.visitSequence;
            scene.visits.put(player.getId(), visit);
            population.simulation.schedule(() -> {
                if (!scene.isCurrentVisit(player, visit)) {
                    return;
                }
                player.dropMessage(5, "Henesys has familiar faces. Say a resident's name to chat, or type @residents.");
                long now = System.currentTimeMillis();
                if (player.isHidden() || scene.map.isMuted() || now - scene.lastSpeech < 3000) {
                    return;
                }
                TownResident greeter = scene.nearest(player.getPosition(), true);
                if (greeter != null && nearby(greeter, player) && scene.reserveSpeech(greeter.getId(), now, 600)) {
                    greeter.faceToward(player.getPosition());
                    greeter.say(population.dialogue.greeting(greeter.getProfile(), player.getId(), player.getName()));
                    scene.lastSpeech = now;
                }
            }, 2500, TimeUnit.MILLISECONDS);
        });
    }

    public static void onLeave(Character player) {
        if (!ENABLED || player.getMapId() != HENESYS) {
            return;
        }
        Scene scene = Holder.INSTANCE.scenes.get(player.getMap());
        if (scene != null) {
            Holder.INSTANCE.simulation.execute(() -> {
                scene.visits.remove(player.getId());
                scene.lastReply.remove(player.getId());
                scene.lastPartner.remove(player.getId());
                scene.followers.entrySet().removeIf(entry -> {
                    if (entry.getValue() == player.getId()) {
                        scene.byId(entry.getKey()).clearFollowTarget();
                        return true;
                    }
                    return false;
                });
            });
        }
    }

    public static void showResidents(Character player) {
        if (!ENABLED) {
            player.dropMessage(5, "Town residents are disabled on this server.");
            return;
        }
        if (player.getMapId() != HENESYS) {
            player.dropMessage(5, "The residents are in Henesys. Visit its market street to meet MapleMia, PipArcher and Juniper.");
            return;
        }
        Scene scene = Holder.INSTANCE.scenes.get(player.getMap());
        if (scene == null) {
            return;
        }
        player.dropMessage(5, "Henesys neighbors: " + scene.residents.size() + " town residents. Companions: MapleMia, PipArcher, Juniper.");
        String names = scene.residents.stream().filter(r -> nearby(r, player)).map(TownResident::getName)
                .reduce((a, b) -> a + ", " + b).orElse("Walk toward the market to meet someone.");
        player.dropMessage(5, "Nearby: " + names);
        player.dropMessage(5, "Say 'MapleMia, hello' or @talk MapleMia hello. Try 'follow me', 'stay', or 'remember me'.");
        if (!Holder.INSTANCE.persistent) {
            player.dropMessage(5, "Memories are available for this session; the server could not open the memory file.");
        }
    }

    public static void onChat(Character player, String text) {
        if (!ENABLED || player.getMapId() != HENESYS || text.isBlank()) {
            return;
        }
        Scene scene = Holder.INSTANCE.scenes.get(player.getMap());
        if (scene == null) {
            return;
        }
        Holder.INSTANCE.simulation.execute(() -> {
            TownResident resident = addressedResident(scene.residents, text);
            if (resident != null) {
                Holder.INSTANCE.respond(scene, player, resident, text, false);
                return;
            }
            // Only continue an existing conversation briefly; ordinary map chat is not a bot summons.
            Integer partner = scene.lastPartner.get(player.getId());
            long since = System.currentTimeMillis() - scene.lastReply.getOrDefault(player.getId(), 0L);
            if (partner != null && since < 45_000) {
                Holder.INSTANCE.respond(scene, player, scene.byId(partner), text, false);
            }
        });
    }

    public static void talk(Character player, String name, String text, boolean echo) {
        if (!ENABLED || player.getMapId() != HENESYS) {
            player.dropMessage(5, "Visit Henesys to talk to its residents.");
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
        Holder.INSTANCE.simulation.execute(() -> Holder.INSTANCE.respond(scene, player, resident, text, echo));
    }

    /** Consume a normal remote-character click when its network ID belongs to a resident. */
    public static boolean interact(Character player, int networkId) {
        if (!ENABLED || player.getMapId() != HENESYS) {
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
        population.simulation.execute(() -> population.respond(scene, player, resident, "hello", false));
        return true;
    }

    static TownResident addressedResident(List<TownResident> residents, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        TownResident first = null;
        int firstPosition = Integer.MAX_VALUE;
        for (TownResident resident : residents) {
            String name = resident.getName().toLowerCase(Locale.ROOT);
            int start = lower.indexOf(name);
            while (start >= 0 && start < firstPosition) {
                if ((start == 0 || !java.lang.Character.isLetterOrDigit(lower.charAt(start - 1)))
                        && (start + name.length() == lower.length() || !java.lang.Character.isLetterOrDigit(lower.charAt(start + name.length())))) {
                    first = resident;
                    firstPosition = start;
                    break;
                }
                start = lower.indexOf(name, start + name.length());
            }
        }
        return first;
    }

    private void respond(Scene scene, Character player, TownResident resident, String message, boolean echo) {
        if (player.getMap() != scene.map || !player.isLoggedin() || scene.map.isMuted()) {
            return;
        }
        if (!nearby(resident, player)) {
            if (echo) {
                player.dropMessage(5, resident.getName() + " is too far away to hear you. Walk a little closer.");
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now - scene.lastReply.getOrDefault(player.getId(), 0L) < 1800) {
            return;
        }
        if (!scene.reserveSpeech(resident.getId(), now, 1800)) {
            if (echo) {
                player.dropMessage(5, resident.getName() + " is finishing a conversation. Try again in a moment.");
            }
            return;
        }
        String text = message.strip();
        if (text.isEmpty()) {
            text = "hello";
        }
        text = text.substring(0, Math.min(120, text.length()));
        scene.lastReply.put(player.getId(), now);
        scene.lastPartner.put(player.getId(), resident.getId());
        scene.lastSpeech = now; // also reserve the pause before the spoken reply
        Long visit = scene.visits.get(player.getId());
        if (echo) {
            if (player.isHidden()) {
                player.dropMessage(5, "You to " + resident.getName() + ": " + text);
            } else {
                scene.map.broadcastMessage(PacketCreator.getChatText(player.getId(), resident.getName() + ", " + text, false, 0));
            }
        }
        ResidentDialogue.Intent intent = ResidentDialogue.intent(text);
        String contextualReply = dialogue.reply(resident.getProfile(), player.getId(), player.getName(), text);
        String reply;
        if (intent == ResidentDialogue.Intent.FOLLOW) {
            if (!resident.getProfile().companion()) {
                reply = "I'm staying around here today. MapleMia, PipArcher or Juniper might join you for a walk.";
            } else if (scene.followers.containsKey(resident.getId()) && scene.followers.get(resident.getId()) != player.getId()) {
                reply = "I'm walking with someone right now. Catch me when we're back!";
            } else {
                scene.followers.entrySet().removeIf(entry -> {
                    if (entry.getValue() == player.getId()) {
                        scene.byId(entry.getKey()).clearFollowTarget();
                        return true;
                    }
                    return false;
                });
                scene.followers.put(resident.getId(), player.getId());
                reply = "Sure, " + player.getName() + ". I'll walk with you along this street. Say 'stay' when you want to stop.";
            }
        } else if (intent == ResidentDialogue.Intent.STOP_FOLLOWING) {
            if (scene.followers.getOrDefault(resident.getId(), -1) == player.getId()) {
                scene.followers.remove(resident.getId());
                resident.clearFollowTarget();
                reply = "I'll be around here, " + player.getName() + ". Come say hello again.";
            } else {
                reply = "I'm just enjoying the town. Come find me whenever you'd like to chat.";
            }
        } else {
            reply = contextualReply;
        }
        resident.faceToward(player.getPosition());
        String answer = reply;
        simulation.schedule(() -> {
            if (scene.isCurrentVisit(player, visit) && nearby(resident, player) && !scene.map.isMuted()) {
                resident.faceToward(player.getPosition());
                resident.say(answer);
                scene.lastSpeech = System.currentTimeMillis();
            }
        }, 1000 + Math.min(700, text.length() * 12L), TimeUnit.MILLISECONDS);
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Scene scene : scenes.values()) {
            try {
                List<Character> players = scene.map.getAllPlayers();
                if (players.isEmpty()) {
                    // Logout usually calls onLeave. Also retire stale links if a
                    // disconnect path removed a player before that callback ran.
                    for (Integer residentId : scene.followers.keySet()) {
                        scene.byId(residentId).clearFollowTarget();
                    }
                    scene.followers.clear();
                    scene.visits.clear();
                    scene.lastReply.clear();
                    scene.lastPartner.clear();
                    continue;
                }
                for (TownResident resident : scene.residents) {
                    Integer following = scene.followers.get(resident.getId());
                    if (following != null) {
                        Character leader = players.stream().filter(p -> p.getId() == following).findFirst().orElse(null);
                        if (leader == null) {
                            scene.followers.remove(resident.getId());
                            resident.clearFollowTarget();
                        } else {
                            resident.setFollowTarget(leader.getPosition());
                        }
                    }
                    resident.tick(now);
                }
                if (now >= scene.nextAmbient && now - scene.lastSpeech > 10_000 && !scene.map.isMuted()) {
                    scene.nextAmbient = now + 18_000 + (scene.sequence % 5) * 3000;
                    List<TownResident> audible = scene.residents.stream().filter(r -> players.stream()
                            .anyMatch(p -> !p.isHidden() && p.getPosition().distanceSq(r.getPosition()) < 420 * 420)).toList();
                    if (!audible.isEmpty()) {
                        TownResident speaker = audible.get((int) (scene.sequence % audible.size()));
                        speaker.say(dialogue.ambient(speaker.getProfile(), scene.sequence++));
                        scene.lastSpeech = now;
                    }
                }
            } catch (Exception e) {
                log.error("Unable to update Henesys residents", e);
            }
        }
    }

    private static boolean nearby(TownResident resident, Character player) {
        Point a = resident.getPosition();
        Point b = player.getPosition();
        return a.distanceSq(b) <= TALK_RANGE_SQUARED && Math.abs(a.y - b.y) < 170;
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

    private void saveMemories() {
        try {
            memories.flush();
        } catch (IOException e) {
            log.error("Could not save resident memories; will retry", e);
        }
    }

    private void close() {
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

    private static final class Scene {
        final MapleMap map;
        final List<TownResident> residents;
        final Map<Integer, Integer> followers = new HashMap<>();
        final Map<Integer, Integer> lastPartner = new HashMap<>();
        final Map<Integer, Long> lastReply = new HashMap<>();
        final Map<Integer, Long> visits = new HashMap<>();
        final Map<Integer, Long> speechUntil = new HashMap<>();
        long nextAmbient = System.currentTimeMillis() + 10_000;
        long lastSpeech;
        long sequence;
        long visitSequence;

        Scene(MapleMap map) {
            this.map = map;
            residents = createResidents(map);
            log.info("Populated Henesys with {} residents", residents.size());
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
            return residents.stream().filter(r -> r.getId() == id).findFirst().orElseThrow();
        }

        TownResident nearest(Point position, boolean companion) {
            return residents.stream().filter(r -> !companion || r.getProfile().companion())
                    .min(Comparator.comparingDouble(r -> r.getPosition().distanceSq(position))).orElse(null);
        }
    }
}
