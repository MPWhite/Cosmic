package server.population;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ResidentWorldTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final List<ResidentCatalog.MapSpec> MAPS = List.of(
            new ResidentCatalog.MapSpec(100000000, "Victoria", true, 48),
            new ResidentCatalog.MapSpec(100010000, "Victoria", false, 16),
            new ResidentCatalog.MapSpec(100020000, "Victoria", false, 16),
            new ResidentCatalog.MapSpec(200000000, "Orbis", true, 48),
            new ResidentCatalog.MapSpec(200010000, "Orbis", false, 16),
            new ResidentCatalog.MapSpec(200020000, "Orbis", false, 16));

    @Test
    void theOriginalTwentyEightKeepTheirHomesOnOneChannelWhileGeneratedIdentitiesAreDistributed() {
        var world = world(2500, 3, NOW);
        for (int id = 1; id <= 28; id++) {
            var presence = world.state(id);
            assertEquals(1, presence.channel());
            assertEquals(id <= 20 ? 100000000 : id <= 26 ? 100010000 : 100020000, presence.mapId());
        }
        world.tick(NOW + 100 * 86_400_000L);
        for (int id = 1; id <= 28; id++) {
            assertEquals(1, world.state(id).channel());
            assertEquals(Long.MAX_VALUE, world.state(id).nextChangeMillis());
        }
        Set<Integer> channels = new HashSet<>();
        for (int id = 29; id <= 2500; id++) {
            channels.add(world.state(id).channel());
        }
        assertEquals(Set.of(1, 2, 3), channels);
        assertEquals(2500, indexedIdentities(world, 3, NOW + 100 * 86_400_000L).size());
    }

    @Test
    void restartingOrExpandingThePopulationDoesNotChangeExistingIdentitiesSchedules() {
        var first = world(2500, 3, NOW);
        List<ResidentCatalog.MapSpec> reversed = new ArrayList<>(MAPS);
        Collections.reverse(reversed);
        var larger = new ResidentWorld(profiles(10_000), reversed, 3, NOW);
        for (int id = 1; id <= 2500; id++) {
            assertEquals(first.state(id), larger.state(id));
        }
        long afterDowntime = NOW + 365 * 86_400_000L;
        first.tick(afterDowntime);
        var restarted = world(2500, 3, afterDowntime);
        for (int id = 1; id <= 2500; id++) {
            assertEquals(restarted.state(id), first.state(id), "downtime must not require replaying each missed step");
        }
    }

    @Test
    void aBackgroundRoutineActuallyVisitsRegionalFieldsAndTownsAndNeverTrainsInATown() {
        var world = world(100, 2, NOW);
        Map<Integer, ResidentCatalog.MapSpec> maps = new HashMap<>();
        MAPS.forEach(map -> maps.put(map.id(), map));
        Set<ResidentWorld.Activity> activities = new HashSet<>();
        Set<Integer> locations = new HashSet<>();
        Set<String> regions = new HashSet<>();
        int channel = world.state(70).channel();
        for (int minute = 0; minute < 180; minute++) {
            long now = NOW + minute * 60_000L;
            world.tick(now);
            var presence = world.state(70);
            activities.add(presence.activity());
            locations.add(presence.mapId());
            regions.add(maps.get(presence.mapId()).region());
            assertEquals(channel, presence.channel());
            assertTrue(presence.nextChangeMillis() > now);
            if (presence.activity() == ResidentWorld.Activity.TRAIN) {
                assertFalse(maps.get(presence.mapId()).town());
            }
        }
        assertEquals(Set.of(ResidentWorld.Activity.values()), activities);
        assertTrue(locations.size() >= 2, "a schedule must move between actual map IDs");
        assertEquals(1, regions.size(), "one coarse session must remain in its coherent region");
    }

    @Test
    void visibleActorsRemainClaimedUntilExplicitReleaseAndCannotBeMaterializedTwice() {
        var world = world(100, 2, NOW);
        var start = world.state(70);
        assertFalse(world.claim(70, start.mapId(), 3));
        assertTrue(world.claim(70, start.mapId(), start.channel()));
        assertFalse(world.claim(70, start.mapId(), start.channel()));
        assertEquals(1, world.claimedCount());
        long tomorrow = NOW + 86_400_000L;
        world.tick(tomorrow);
        assertEquals(start.mapId(), world.state(70).mapId());
        assertEquals(start.channel(), world.state(70).channel());
        assertEquals(Long.MAX_VALUE, world.state(70).nextChangeMillis());
        assertEquals(99, indexedIdentities(world, 2, tomorrow).size());
        assertFalse(indexedIdentities(world, 2, tomorrow).contains(70));
        world.release(70, tomorrow);
        assertEquals(0, world.claimedCount());
        assertEquals(world(100, 2, tomorrow).state(70), world.state(70));
        assertEquals(100, indexedIdentities(world, 2, tomorrow).size());
    }

    @Test
    void realCompanionsCanTravelOutsideTheSpawnWhitelistAndReturnToTheirOriginalHome() {
        var world = world(100, 2, NOW);
        assertTrue(world.claim(1, 100000000, 1));
        assertTrue(world.moveClaim(1, 220080000, 2));
        assertEquals(220080000, world.state(1).mapId());
        assertEquals(2, world.state(1).channel());
        assertTrue(world.candidates(220080000, 2, NOW).isEmpty());
        assertFalse(world.moveClaim(2, 220080000, 2), "unclaimed actors cannot be relocated by a background override");
        assertFalse(world.moveClaim(1, 0, 2));
        assertFalse(world.moveClaim(1, 220080000, 3));
        world.release(1, NOW + 1_000_000);
        assertEquals(100000000, world.state(1).mapId());
        assertEquals(1, world.state(1).channel());
        assertFalse(world.state(1).claimed());
    }

    @Test
    void claimsRemainAtomicAcrossConcurrentSceneRequests() throws Exception {
        var world = world(100, 2, NOW);
        var state = world.state(70);
        try (var executor = Executors.newFixedThreadPool(8)) {
            Callable<Boolean> claim = () -> world.claim(70, state.mapId(), state.channel());
            var results = executor.invokeAll(Collections.nCopies(16, claim));
            int successes = 0;
            for (var result : results) {
                if (result.get()) successes++;
            }
            assertEquals(1, successes);
            assertEquals(1, world.claimedCount());
        }
    }

    @Test
    void snapshotsAreImmutableAndRepeatedSchedulerTicksDoNotReplaceUnchangedStates() {
        var world = world(100, 2, NOW);
        var state = world.state(70);
        for (int i = 0; i < 500; i++) {
            world.tick(NOW + i * 50L);
        }
        assertSame(state, world.state(70));
        assertThrows(UnsupportedOperationException.class, () -> world.candidates(100000000, 1, NOW).clear());
        assertThrows(UnsupportedOperationException.class, () -> world.activityCounts().clear());
        assertEquals(100, world.activityCounts().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void townCapacityWeightsKeepLargeRegionsPopulatedAndMostBackgroundTimeInTowns() {
        List<ResidentCatalog.MapSpec> maps = new ArrayList<>(MAPS);
        maps.add(new ResidentCatalog.MapSpec(200000100, "Orbis", true, 48));
        var world = new ResidentWorld(profiles(10_000), maps, 3, NOW);
        Map<Integer, ResidentCatalog.MapSpec> specs = new HashMap<>();
        maps.forEach(map -> specs.put(map.id(), map));
        int orbis = 0, towns = 0;
        for (int id = 29; id <= 10_000; id++) {
            var spec = specs.get(world.state(id).mapId());
            if (spec.region().equals("Orbis")) orbis++;
            if (spec.town()) towns++;
        }
        // Region weights are176 vs320; equal region weighting would strand the second town.
        assertTrue(orbis > 6_000 && orbis < 7_000, "population should follow total regional town/field capacity");
        assertTrue(towns > 6_000 && towns < 7_300, "four of six long phases should keep towns lively");
    }

    @ParameterizedTest
    @ValueSource(ints = {2500, 10000})
    void backgroundCapacityHarnessUpdatesEveryIdentityWithoutReplayOrActorObjects(int count) {
        assertTimeout(Duration.ofSeconds(15), () -> {
            long catalogStart = System.nanoTime();
            var profiles = ResidentCatalog.create(count);
            long buildStart = System.nanoTime();
            var world = new ResidentWorld(profiles, ResidentCatalog.maps(), 3, NOW);
            long built = System.nanoTime();
            long worst = 0;
            for (int step = 1; step <= 120; step++) {
                long started = System.nanoTime();
                world.tick(NOW + step * 30_000L);
                worst = Math.max(worst, System.nanoTime() - started);
            }
            long done = System.nanoTime();
            assertEquals(count, world.total());
            assertEquals(count, indexedIdentities(world, 3, NOW + 3_600_000L, ResidentCatalog.maps()).size());
            assertEquals(count, world.activityCounts().values().stream().mapToInt(Integer::intValue).sum());
            assertEquals(0, world.claimedCount());
            System.out.printf("ResidentWorld: %,d identities / %d maps; catalog %.2fms; model %.2fms; 120 coarse ticks %.2fms; mean %.3fms; worst %.3fms%n",
                    count, ResidentCatalog.maps().size(), (buildStart - catalogStart) / 1_000_000d,
                    (built - buildStart) / 1_000_000d, (done - built) / 1_000_000d,
                    (done - built) / 120_000_000d, worst / 1_000_000d);
        });
    }

    private static Set<Integer> indexedIdentities(ResidentWorld world, int channels, long now) {
        return indexedIdentities(world, channels, now, MAPS);
    }

    private static Set<Integer> indexedIdentities(ResidentWorld world, int channels, long now, List<ResidentCatalog.MapSpec> maps) {
        Set<Integer> ids = new HashSet<>();
        for (ResidentCatalog.MapSpec map : maps) {
            for (int channel = 1; channel <= channels; channel++) {
                for (var presence : world.candidates(map.id(), channel, now)) {
                    assertEquals(map.id(), presence.mapId());
                    assertEquals(channel, presence.channel());
                    assertTrue(ids.add(presence.profile().id()), "one identity cannot inhabit two channels or maps");
                }
            }
        }
        return ids;
    }

    private static ResidentWorld world(int count, int channels, long now) {
        return new ResidentWorld(profiles(count), MAPS, channels, now);
    }

    private static List<ResidentProfiles.Profile> profiles(int count) {
        List<ResidentProfiles.Profile> result = new ArrayList<>(count);
        for (int id = 1; id <= count; id++) {
            result.add(new ResidentProfiles.Profile(id, "Sim" + Integer.toString(id, 36), id % 2, id % 4,
                    20000, 30000, 100, 20, Map.of(11, 1302000), "test adventurer", id <= 3));
        }
        return result;
    }
}
