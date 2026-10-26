package server.population;

import client.Character;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapObject;
import server.maps.MapleMap;
import server.population.navigation.CompanionNavigation;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResidentPopulationLifecycleTest {
    @Test
    void repeatedEntriesCannotDuplicateIdentitiesAndEmptyMapsReleaseActorsForLaterVisits() {
        var population = new TownPopulation(ResidentMemoryStore.inMemory());
        var fixture = map(100000000, 1);
        try {
            population.prepareScene(fixture.map, 1_000_000);
            var original = fixture.residents().stream().map(TownResident::getId).toList();
            assertTrue(original.size() > 20, "Henesys should gain generated identities");
            assertTrue(original.size() <= 48);
            var otherChannel = map(100000000, 2);
            population.prepareScene(otherChannel.map, 1_000_000);
            assertFalse(otherChannel.residents().isEmpty());
            assertTrue(otherChannel.residents().stream().allMatch(r -> r.getProfile().id() > 28
                    && !original.contains(r.getId())), "identities must not be cloned across channels");
            population.prepareScene(fixture.map, 1_000_010);
            assertEquals(original, fixture.residents().stream().map(TownResident::getId).toList());
            fixture.players.clear();
            population.tick(1_030_000);
            assertTrue(fixture.residents().isEmpty(), "empty scenes must release actual map objects");
            fixture.players.add(player(1));
            population.prepareScene(fixture.map, 1_030_001);
            assertTrue(fixture.residents().size() >= 20);
            assertEquals(fixture.residents().size(), fixture.residents().stream().map(TownResident::getId).distinct().count());
            verify(fixture.map, never()).addPlayer(any());
        } finally {
            population.close();
        }
        assertTrue(fixture.residents().isEmpty(), "shutdown must release actors too");
    }

    @Test
    void processBudgetAndChannelIdentityOwnershipHoldAcrossManyOccupiedMaps() {
        var population = new TownPopulation(ResidentMemoryStore.inMemory());
        var fixtures = new ArrayList<Fixture>();
        try {
            for (int channel = 1; channel <= 3; channel++) {
                for (int mapId : ResidentCatalog.maps().stream().filter(ResidentCatalog.MapSpec::town)
                        .limit(9).map(ResidentCatalog.MapSpec::id).toList()) {
                    var fixture = map(mapId, channel);
                    fixtures.add(fixture);
                    population.prepareScene(fixture.map, 1_000_000);
                    assertTrue(fixture.residents().size() <= (fixture.map.isTown() ? 48 : 16));
                    if (channel != 1) assertTrue(fixture.residents().stream().allMatch(r -> r.getProfile().id() > 28));
                }
            }
            var identities = new HashSet<Integer>();
            int active = 0;
            for (var fixture : fixtures) {
                for (var resident : fixture.residents()) {
                    assertTrue(identities.add(resident.getId()), "same identity appeared twice across channels/maps");
                    active++;
                }
            }
            assertEquals(TownPopulation.MAX_ACTIVE, active, "fixture should exercise the actual global cap");
            var first = fixtures.getFirst();
            first.players.clear();
            var newcomer = map(230000000, 1); // not on the supported walking-map list
            population.prepareScene(newcomer.map, 1_000_010);
            assertTrue(newcomer.residents().isEmpty());
            var supported = map(ResidentCatalog.maps().stream().filter(ResidentCatalog.MapSpec::town)
                    .toList().getLast().id(), 1);
            population.prepareScene(supported.map, 1_000_020);
            assertTrue(first.residents().isEmpty(), "budget pressure should reclaim empty maps immediately");
            assertFalse(supported.residents().isEmpty());
        } finally {
            population.close();
        }
        assertTrue(fixtures.stream().allMatch(f -> f.residents().isEmpty()));
    }

    @Test
    void failedActorRegistrationReleasesTheIdentityAndCanRetryWithoutGhosts() {
        var population = new TownPopulation(ResidentMemoryStore.inMemory());
        var fixture = map(100000000, 1);
        try {
            doThrow(new IllegalStateException("injected registry failure")).when(fixture.map).addMapObject(any());
            population.prepareScene(fixture.map, 1_000_000);
            assertTrue(fixture.residents().isEmpty());
            register(fixture);
            population.tick(1_000_010);
            assertTrue(fixture.residents().stream().anyMatch(r -> r.getName().equals("MapleMia")));
            assertEquals(fixture.residents().size(), fixture.residents().stream().map(TownResident::getId).distinct().count());
        } finally {
            population.close();
        }
    }

    private record Fixture(MapleMap map, List<Character> players, HashMap<Integer, MapObject> objects) {
        List<TownResident> residents() {
            return objects.values().stream().filter(TownResident.class::isInstance).map(TownResident.class::cast)
                    .sorted(java.util.Comparator.comparingInt(TownResident::getId)).toList();
        }
    }

    private static Fixture map(int id, int channelId) {
        MapleMap map = mock(MapleMap.class);
        var spec = ResidentCatalog.maps().stream().filter(m -> m.id() == id).findFirst();
        when(map.getId()).thenReturn(id);
        when(map.getMapName()).thenReturn("Scale fixture " + id);
        when(map.isTown()).thenReturn(spec.map(ResidentCatalog.MapSpec::town).orElse(false));
        Channel channel = mock(Channel.class);
        when(channel.getId()).thenReturn(channelId);
        when(map.getChannelServer()).thenReturn(channel);
        World world = mock(World.class);
        when(world.getChannelsSize()).thenReturn(3);
        when(map.getWorldServer()).thenReturn(world);
        FootholdTree ground = new FootholdTree(new Point(-10000, -10000), new Point(20000, 20000));
        if (spec.isPresent()) {
            for (var s : CompanionNavigation.forMapId(id).surfaces()) {
                ground.insert(new Foothold(new Point(s.x1(), s.y1()), new Point(s.x2(), s.y2()), s.id()));
            }
        }
        when(map.getFootholds()).thenReturn(ground);
        var players = new ArrayList<>(List.of(player(1)));
        when(map.getAllPlayers()).thenAnswer(call -> List.copyOf(players));
        when(map.getPortals()).thenReturn(List.of());
        var fixture = new Fixture(map, players, new HashMap<>());
        when(map.getMapObject(anyInt())).thenAnswer(call -> fixture.objects.get(call.getArgument(0)));
        doAnswer(call -> fixture.objects.remove(call.getArgument(0))).when(map).removeMapObject(anyInt());
        register(fixture);
        return fixture;
    }

    private static void register(Fixture fixture) {
        AtomicInteger ids = new AtomicInteger(1000);
        doAnswer(call -> {
            MapObject object = call.getArgument(0);
            object.setObjectId(ids.incrementAndGet());
            fixture.objects.put(object.getObjectId(), object);
            return null;
        }).when(fixture.map).addMapObject(any());
    }

    private static Character player(int id) {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(id);
        when(player.getPosition()).thenReturn(new Point(2820, 330));
        return player;
    }
}
