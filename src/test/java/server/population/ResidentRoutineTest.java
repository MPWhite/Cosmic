package server.population;

import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Portal;
import net.server.channel.Channel;

import java.awt.Point;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidentRoutineTest {
    @Test
    void aRequestForSpacePausesHuntingAndEventuallyAllowsItToResume() {
        var placement = ResidentRoster.forMap(ResidentRoster.TRAINING_GROUND).getFirst();
        TownResident actor = actor(map(), placement);
        ResidentCombat combat = mock(ResidentCombat.class);
        ResidentRoutine routine = new ResidentRoutine(actor, (r, m, p) -> fail(), combat);
        routine.tick(1_000);
        routine.tick(15_000);
        routine.makeRoom(135_000);
        routine.tick(20_000);
        routine.tick(134_999);
        assertEquals(ResidentDialogue.Activity.RESTING, routine.context(false).activity());
        verify(combat, never()).tick(anyLong(), any());
        routine.tick(135_000);
        routine.tick(135_050);
        verify(combat).tick(135_050, null);
        verify(actor, atLeastOnce()).waitHere();
    }

    @Test
    void huntersStartAtDifferentTimesAndTakeBreaksWithoutAnObserverFollowingThem() {
        Set<Long> starts = new HashSet<>();
        for (ResidentRoster.Placement placement : ResidentRoster.forMap(ResidentRoster.TRAINING_GROUND)) {
            MapleMap map = map();
            TownResident actor = actor(map, placement);
            ResidentCombat combat = mock(ResidentCombat.class);
            ResidentRoutine routine = new ResidentRoutine(actor, (r, m, p) -> fail("hunter unexpectedly warped"), combat);
            long started = 0;
            boolean rested = false;
            for (long now = 1_000; now <= 120_000; now += 250) {
                routine.tick(now);
                if (routine.context(false).activity() == ResidentDialogue.Activity.TRAINING && started == 0) {
                    started = now;
                    starts.add(now);
                }
                if (started != 0 && routine.context(false).activity() == ResidentDialogue.Activity.RESTING) {
                    rested = true;
                    break;
                }
            }
            assertTrue(started > 1_000 && started < 14_000, placement.profile().name());
            assertTrue(rested, "A hunt should not continue forever: " + placement.profile().name());
            verify(combat, atLeastOnce()).tick(anyLong(), isNull());
            verify(combat).stop();
        }
        assertTrue(starts.size() >= 4, "Residents should not begin hunting in lockstep");
    }

    @Test
    void aConversationPausesDecisionsWithoutExtendingTheWholeHuntingSession() {
        var placement = ResidentRoster.forMap(ResidentRoster.TRAINING_GROUND).getFirst();
        TownResident actor = actor(map(), placement);
        ResidentCombat combat = mock(ResidentCombat.class);
        ResidentRoutine routine = new ResidentRoutine(actor, (r, m, p) -> fail(), combat);
        routine.tick(1_000);
        routine.tick(15_000);
        routine.conversation(25_000);
        routine.tick(20_000);
        verify(combat, never()).tick(anyLong(), any());
        routine.tick(25_000);
        verify(combat).tick(25_000, null);
        routine.tick(120_000);
        assertEquals(ResidentDialogue.Activity.RESTING, routine.context(false).activity());
    }

    @Test
    void suspensionCancelsAutonomousCombatOnlyOnceAndKeepsTravelTargetsIntact() {
        var placement = ResidentRoster.forMap(ResidentRoster.TRAINING_GROUND).getFirst();
        TownResident actor = actor(map(), placement);
        ResidentCombat combat = mock(ResidentCombat.class);
        ResidentRoutine routine = new ResidentRoutine(actor, (r, m, p) -> fail(), combat);
        routine.suspend();
        routine.suspend();
        verify(combat).stop();
        verify(actor).waitHere();
        routine.assist(10_000, null, false);
        verify(actor, never()).clearFollowTarget();
        assertEquals(ResidentDialogue.Activity.WAITING, routine.context(false).activity());
    }

    @Test
    void anUnavailableReturnPortalIsRetriedWithBackoffRatherThanEverySimulationTick() {
        MapleMap town = mock(MapleMap.class);
        MapleMap field = map();
        Channel channel = mock(Channel.class, RETURNS_DEEP_STUBS);
        when(town.getId()).thenReturn(TownPopulation.HENESYS);
        when(town.getChannelServer()).thenReturn(channel);
        when(field.getChannelServer()).thenReturn(channel);
        when(channel.getMapFactory().getMap(ResidentRoster.TRAINING_GROUND)).thenReturn(field);
        Portal exit = mock(Portal.class);
        when(exit.getType()).thenReturn(Portal.MAP_PORTAL);
        when(exit.getPortalStatus()).thenReturn(true);
        when(exit.getTargetMapId()).thenReturn(ResidentRoster.TRAINING_GROUND);
        when(exit.getPosition()).thenReturn(new Point(5907, 455));
        when(exit.getTarget()).thenReturn("west00");
        when(town.getPortals()).thenReturn(List.of(exit));
        Portal arrival = mock(Portal.class);
        when(arrival.getPosition()).thenReturn(new Point(-542, 455));
        when(field.getPortal("west00")).thenReturn(arrival);
        when(field.getPortals()).thenReturn(List.of());
        AtomicReference<MapleMap> current = new AtomicReference<>(town);
        AtomicReference<Point> position = new AtomicReference<>(new Point(5907, 455));
        TownResident actor = mock(TownResident.class);
        when(actor.getId()).thenReturn(1_800_000_016);
        when(actor.getProfile()).thenReturn(ResidentProfiles.all().get(15));
        when(actor.getMap()).thenAnswer(call -> current.get());
        when(actor.getPosition()).thenAnswer(call -> new Point(position.get()));
        when(actor.getFootholdId()).thenReturn(1);
        ResidentRoutine routine = new ResidentRoutine(actor, (r, m, p) -> {
            current.set(m);
            position.set(new Point(p));
        }, mock(ResidentCombat.class));
        routine.tick(1_000);
        routine.tick(16_000);
        assertTrue(routine.travelling());
        routine.tick(16_100);
        assertSame(field, current.get());
        routine.tick(300_000);
        for (long now = 300_050; now < 350_000; now += 50) {
            routine.tick(now);
        }
        verify(field, times(1)).getPortals();
        routine.tick(360_001);
        verify(field, times(2)).getPortals();
        assertSame(field, current.get(), "There must be no recovery teleport for an independent resident");
    }

    private static MapleMap map() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(ResidentRoster.TRAINING_GROUND);
        when(map.getMapName()).thenReturn("The Hill East of Henesys");
        return map;
    }

    private static TownResident actor(MapleMap map, ResidentRoster.Placement placement) {
        TownResident actor = mock(TownResident.class);
        when(actor.getMap()).thenReturn(map);
        when(actor.getId()).thenReturn(1_800_000_000 + placement.profile().id());
        when(actor.getProfile()).thenReturn(placement.profile());
        when(actor.getPosition()).thenReturn(new Point(placement.position()));
        when(actor.getFootholdId()).thenReturn(1);
        return actor;
    }
}
