package server.population;

import net.packet.Packet;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapObject;
import server.maps.MapleMap;
import server.population.navigation.CompanionNavigation;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Manual CPU/wire-format benchmark, deliberately excluded from automatic *Test discovery.
 * Uses production town routines, physics, navigation, profiles and packet serialization.
 * Maps are Mockito stubs: no sockets, players, combat, portals, ambient dialogue or render work.
 * Reported client bytes assume one observer in each scene, receiving its map broadcasts.
 */
public final class ResidentScaleBenchmark {
    private static final int MAP_ID = 100000000;
    private static final int TICK_MILLIS = 50;
    private static final int WARMUP_TICKS = 600;
    private static final int MEASURED_TICKS = 600;
    private static final int[][] HOMES = {
            {2820, 330}, {3000, 330}, {3200, 330}, {2490, 330}, {2610, 330},
            {2730, 330}, {2910, 330}, {3090, 330}, {3290, 330}, {3350, 330},
            {150, 260}, {300, 260}, {470, 260}, {610, 260}, {740, 260},
            {4800, 440}, {4990, 440}, {5250, 440}, {3550, 110}, {3890, 110}
    };

    private record Geometry(FootholdTree ground, List<Point> npcs) { }
    private record Actor(TownResident resident, ResidentRoutine routine) { }
    private static final class Counter {
        long packets;
        long bytes;
        void receive(Packet packet) {
            packets++;
            bytes += packet.getBytes().length;
        }
        void reset() { packets = bytes = 0; }
    }
    private record Scene(MapleMap map, Counter counter, List<Actor> actors) { }

    private ResidentScaleBenchmark() { }

    public static void main(String[] args) {
        long started = System.nanoTime();
        Geometry geometry = loadGeometry();
        CompanionNavigation navigation = CompanionNavigation.forMapId(MAP_ID);
        List<ResidentProfiles.Profile> profiles = ResidentCatalog.create(256);
        System.out.printf(Locale.ROOT,
                "{\"benchmark\":\"resident_active_town\",\"warmNavigationMillis\":%.3f,"
                        + "\"warmupTicks\":%d,\"measuredTicks\":%d,\"tickMillis\":%d,"
                        + "\"limitations\":\"mock map calls included; no socket, combat, portal, ambient or rendering cost\"}%n",
                (System.nanoTime() - started) / 1_000_000.0, WARMUP_TICKS, MEASURED_TICKS, TICK_MILLIS);
        run(48, 1, profiles, geometry, navigation);
        run(128, 4, profiles, geometry, navigation);
        run(256, 6, profiles, geometry, navigation);
    }

    private static void run(int count, int sceneCount, List<ResidentProfiles.Profile> profiles,
                            Geometry geometry, CompanionNavigation navigation) {
        var scenes = new ArrayList<Scene>();
        long[] spawnBytes = new long[sceneCount];
        int profileIndex = 0;
        for (int sceneIndex = 0; sceneIndex < sceneCount; sceneIndex++) {
            Scene scene = scene(geometry);
            scenes.add(scene);
            int sceneSize = count / sceneCount + (sceneIndex < count % sceneCount ? 1 : 0);
            if (sceneSize > 48) throw new IllegalArgumentException("Town scene exceeds production cap");
            for (int i = 0; i < sceneSize; i++) {
                var profile = profiles.get(profileIndex++);
                int[] home = HOMES[i % HOMES.length];
                Point seed = new Point(home[0] + (i / HOMES.length) * 10, home[1]);
                var resident = new TownResident(1_800_000_000 + profile.id(), profile, scene.map(), seed, navigation);
                var routine = new ResidentRoutine(resident, (actor, map, point) -> {
                    throw new IllegalStateException("Portals are intentionally absent in this benchmark");
                });
                scene.actors().add(new Actor(resident, routine));
                spawnBytes[sceneIndex] += ResidentPackets.spawn(resident).getBytes().length;
            }
        }
        long now = 1_000_000;
        for (int tick = 0; tick < WARMUP_TICKS; tick++) {
            advance(scenes, now += TICK_MILLIS);
        }
        int recoveriesBefore = recoveries(scenes);
        scenes.forEach(scene -> scene.counter().reset());
        long[] durations = new long[MEASURED_TICKS];
        for (int tick = 0; tick < MEASURED_TICKS; tick++) {
            long started = System.nanoTime();
            advance(scenes, now += TICK_MILLIS);
            durations[tick] = System.nanoTime() - started;
        }
        Arrays.sort(durations);
        double seconds = MEASURED_TICKS * TICK_MILLIS / 1000.0;
        double[] bytesPerClientSecond = scenes.stream().mapToDouble(s -> s.counter().bytes / seconds).toArray();
        long packets = scenes.stream().mapToLong(s -> s.counter().packets).sum();
        long bytes = scenes.stream().mapToLong(s -> s.counter().bytes).sum();
        System.out.printf(Locale.ROOT,
                "{\"actors\":%d,\"scenes\":%d,\"perScene\":%s,\"p50Millis\":%.3f,"
                        + "\"p95Millis\":%.3f,\"maxMillis\":%.3f,\"totalPacketsPerSecond\":%.3f,"
                        + "\"totalPayloadBytesPerSecond\":%.3f,\"perClientPayloadBytesPerSecond\":%s,"
                        + "\"spawnPayloadBytesPerClient\":%s,\"recoveries\":%d}%n",
                count, sceneCount, scenes.stream().map(s -> s.actors().size()).toList(),
                percentile(durations, .50), percentile(durations, .95), percentile(durations, 1),
                packets / seconds, bytes / seconds, Arrays.toString(bytesPerClientSecond),
                Arrays.toString(spawnBytes), recoveries(scenes) - recoveriesBefore);
        scenes.forEach(scene -> scene.actors().forEach(actor -> actor.routine().close()));
    }

    private static void advance(List<Scene> scenes, long now) {
        for (Scene scene : scenes) {
            for (Actor actor : scene.actors()) {
                actor.routine().maintain(now);
                actor.routine().tick(now);
                actor.resident().tick(now);
            }
        }
    }

    private static int recoveries(List<Scene> scenes) {
        return scenes.stream().flatMap(scene -> scene.actors().stream())
                .mapToInt(actor -> actor.resident().getRecoveryCount()).sum();
    }

    private static double percentile(long[] sorted, double percentile) {
        return sorted[(int) Math.ceil(sorted.length * percentile) - 1] / 1_000_000.0;
    }

    private static Scene scene(Geometry geometry) {
        var map = mock(MapleMap.class, withSettings().stubOnly());
        var counter = new Counter();
        when(map.getId()).thenReturn(MAP_ID);
        when(map.getMapName()).thenReturn("Henesys");
        when(map.isTown()).thenReturn(true);
        when(map.getFootholds()).thenReturn(geometry.ground());
        when(map.getPortals()).thenReturn(List.of());
        when(map.getAllMonsters()).thenReturn(List.of());
        List<MapObject> npcs = new ArrayList<>();
        for (Point position : geometry.npcs()) {
            var npc = mock(MapObject.class, withSettings().stubOnly());
            when(npc.getPosition()).thenReturn(position);
            when(npc.getObjectId()).thenReturn(npcs.size() + 1);
            npcs.add(npc);
        }
        when(map.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenAnswer(call -> {
            Point from = call.getArgument(0);
            double range = call.getArgument(1);
            return npcs.stream().filter(npc -> npc.getPosition().distanceSq(from) <= range).toList();
        });
        doAnswer(call -> { counter.receive(call.getArgument(0)); return null; })
                .when(map).broadcastMessage(any(Packet.class));
        return new Scene(map, counter, new ArrayList<>());
    }

    private static Geometry loadGeometry() {
        Data map = DataProviderFactory.getDataProvider(WZFiles.MAP).getData("Map/Map1/100000000.img");
        var ground = new FootholdTree(new Point(-30_000, -30_000), new Point(30_000, 30_000));
        for (Data layer : map.getChildByPath("foothold")) {
            for (Data group : layer) {
                for (Data foothold : group) {
                    ground.insert(new Foothold(new Point(DataTool.getInt("x1", foothold), DataTool.getInt("y1", foothold)),
                            new Point(DataTool.getInt("x2", foothold), DataTool.getInt("y2", foothold)),
                            Integer.parseInt(foothold.getName())));
                }
            }
        }
        List<Point> npcs = new ArrayList<>();
        for (Data life : map.getChildByPath("life")) {
            if (DataTool.getString("type", life, "").equals("n")) {
                npcs.add(new Point(DataTool.getInt("x", life), DataTool.getInt("cy", life)));
            }
        }
        return new Geometry(ground, List.copyOf(npcs));
    }
}
