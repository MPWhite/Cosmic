package server.population;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompanionTravelTest {
    private final Character leader = mock(Character.class);
    private final TownResident resident = mock(TownResident.class);
    private final MapleMap home = map(100000000);
    private final MapleMap field = map(100010000);
    private final List<MapleMap> arrivals = new ArrayList<>();

    private CompanionTravel trip() {
        when(leader.isLoggedinWorld()).thenReturn(true);
        when(leader.isAlive()).thenReturn(true);
        when(leader.getMap()).thenReturn(home);
        when(leader.getPosition()).thenReturn(new Point(3000, 334));
        when(resident.getMap()).thenReturn(home);
        when(resident.getPosition()).thenReturn(new Point(3000, 334));
        when(resident.getName()).thenReturn("PipArcher");
        return new CompanionTravel(leader, resident, (actor, destination, point) -> {
            arrivals.add(destination);
            when(actor.getMap()).thenReturn(destination);
            when(actor.getPosition()).thenReturn(new Point(point));
        });
    }

    @Test
    void waitsForTheCompanionToReachTheActualPortalAndReturnsWithTheSameActor() {
        CompanionTravel trip = trip();
        Portal out = portal(field.getId(), new Point(5907, 455));
        when(leader.getMap()).thenReturn(field);
        assertTrue(trip.cross(home, field, out, new Point(-542, 457)));
        assertTrue(trip.tick(1000));
        verify(resident).setFollowTarget(new Point(5907, 455), false);
        assertTrue(arrivals.isEmpty());

        // A nearby horizontal position on another floor must not use the portal.
        when(resident.getPosition()).thenReturn(new Point(5880, 334));
        trip.tick(1100);
        assertTrue(arrivals.isEmpty());
        when(resident.getPosition()).thenReturn(new Point(5880, 454));
        trip.tick(1200);
        assertEquals(List.of(field), arrivals);

        when(leader.getMap()).thenReturn(home);
        assertTrue(trip.cross(field, home, portal(home.getId(), new Point(-542, 457)), new Point(5907, 455)));
        trip.tick(1300);
        assertEquals(List.of(field, home), arrivals);
        assertSame(resident, trip.resident);
        assertSame(home, trip.homeMap);
    }

    @Test
    void quickSuccessiveCrossingsAreFollowedInOrder() {
        CompanionTravel trip = trip();
        MapleMap further = map(100010100);
        assertTrue(trip.cross(home, field, portal(field.getId(), new Point(3000, 334)), new Point(100, 455)));
        assertTrue(trip.cross(field, further, portal(further.getId(), new Point(100, 455)), new Point(50, 200)));
        when(leader.getMap()).thenReturn(further);
        trip.tick(1000);
        trip.tick(1100);
        assertEquals(List.of(field, further), arrivals);
    }

    @Test
    void closedScriptedAndMismatchedPortalsCannotBeFollowed() {
        CompanionTravel trip = trip();
        Portal closed = portal(field.getId(), new Point(3000, 334));
        when(closed.getPortalStatus()).thenReturn(false);
        assertFalse(trip.cross(home, field, closed, new Point()));
        Portal scripted = portal(field.getId(), new Point());
        when(scripted.getScriptName()).thenReturn("partyQuestEntrance");
        assertFalse(trip.cross(home, field, scripted, new Point()));
        assertFalse(trip.cross(home, field, portal(999999999, new Point()), new Point()));
        assertFalse(trip.cross(field, home, portal(home.getId(), new Point()), new Point()));
        assertTrue(arrivals.isEmpty());
    }

    @Test
    void disconnectDeathOrUnexpectedWarpReturnsTheCompanionHomeOnce() {
        CompanionTravel trip = trip();
        when(leader.getMap()).thenReturn(field);
        trip.cross(home, field, portal(field.getId(), new Point(3000, 334)), new Point(100, 455));
        trip.tick(1000);
        when(leader.isLoggedinWorld()).thenReturn(false);
        assertFalse(trip.tick(1100));
        assertFalse(trip.tick(1200));
        assertEquals(List.of(field, home), arrivals);

        arrivals.clear();
        trip = trip();
        when(leader.getMap()).thenReturn(field); // no validated portal crossing
        assertTrue(trip.tick(1000));
        assertFalse(trip.tick(4100));
        assertTrue(arrivals.isEmpty()); // already home; never copied to the warp target
    }

    @Test
    void waitingCompanionDoesNotFollowPortalsAndCanBeResumed() {
        CompanionTravel trip = trip();
        trip.stay();
        assertFalse(trip.isFollowing());
        assertFalse(trip.cross(home, field, portal(field.getId(), new Point()), new Point()));
        verify(resident).waitHere();
        trip.follow();
        assertTrue(trip.isFollowing());
        assertTrue(trip.cross(home, field, portal(field.getId(), new Point()), new Point()));
    }

    @Test
    void deathInTheFieldReturnsTheSameCompanionHomeExactlyOnce() {
        CompanionTravel trip = trip();
        when(leader.getMap()).thenReturn(field);
        assertTrue(trip.cross(home, field, portal(field.getId(), new Point(3000, 334)), new Point(100, 455)));
        assertTrue(trip.tick(1000));
        assertSame(field, resident.getMap());

        when(leader.isAlive()).thenReturn(false);
        assertFalse(trip.tick(1100));
        assertFalse(trip.tick(1200));
        trip.returnHome();
        assertEquals(List.of(field, home), arrivals);
        assertSame(home, resident.getMap());
        assertSame(resident, trip.resident);
        verify(resident, times(1)).clearFollowTarget();
    }

    @Test
    void waitingInTheFieldEndsWhenTheLeaderLeavesWithoutAValidatedCrossing() {
        CompanionTravel trip = trip();
        when(leader.getMap()).thenReturn(field);
        assertTrue(trip.cross(home, field, portal(field.getId(), new Point(3000, 334)), new Point(100, 455)));
        assertTrue(trip.tick(1000));
        trip.stay();
        assertFalse(trip.isFollowing());
        assertFalse(trip.cross(field, home, portal(home.getId(), new Point(100, 455)), new Point(3000, 334)));

        when(leader.getMap()).thenReturn(home);
        assertTrue(trip.tick(1100), "allow the same short map-transition grace period while waiting");
        assertEquals(List.of(field), arrivals);
        assertFalse(trip.tick(4101));
        assertFalse(trip.tick(4200));
        assertEquals(List.of(field, home), arrivals);
        assertSame(home, resident.getMap());
        verify(resident).waitHere();
    }

    @Test
    void stalledPortalRecoveryIsDelayedAndAnnounced() {
        CompanionTravel trip = trip();
        when(leader.getMap()).thenReturn(field);
        trip.cross(home, field, portal(field.getId(), new Point(5907, 455)), new Point(-542, 457));
        trip.tick(1000);
        trip.tick(30_000);
        assertTrue(arrivals.isEmpty());
        trip.tick(31_001);
        assertEquals(List.of(field), arrivals);
        verify(leader).dropMessage(eq(5), contains("found another way"));
    }

    @Test
    void dismissalAfterARoundTripRestoresTheOriginalTownSpotInsteadOfTheArrivalPortal() {
        CompanionTravel trip = trip();
        when(leader.getMap()).thenReturn(field);
        assertTrue(trip.cross(home, field, portal(field.getId(), new Point(3000, 334)), new Point(-542, 457)));
        assertTrue(trip.tick(1000));
        when(leader.getMap()).thenReturn(home);
        assertTrue(trip.cross(field, home, portal(home.getId(), new Point(-542, 457)), new Point(5907, 455)));
        assertTrue(trip.tick(1100));
        assertSame(home, resident.getMap());
        assertEquals(new Point(5907, 455), resident.getPosition());

        trip.returnHome();
        trip.returnHome();
        assertEquals(List.of(field, home, home), arrivals);
        assertEquals(new Point(3000, 334), resident.getPosition());
        verify(resident).restoreHomeAnchor(new Point(3000, 334));
        assertFalse(trip.tick(1200));
    }

    @Test
    void dismissalNearHomeRestoresTheWanderAnchorWithoutAnUnnecessaryVisibleTransfer() {
        CompanionTravel trip = trip();
        when(resident.getPosition()).thenReturn(new Point(3005, 334));
        trip.returnHome();
        assertTrue(arrivals.isEmpty());
        verify(resident).restoreHomeAnchor(new Point(3000, 334));
    }

    private static MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        return map;
    }

    private static Portal portal(int target, Point position) {
        Portal portal = mock(Portal.class);
        when(portal.getType()).thenReturn(Portal.MAP_PORTAL);
        when(portal.getTargetMapId()).thenReturn(target);
        when(portal.getPortalStatus()).thenReturn(true);
        when(portal.getPosition()).thenReturn(position);
        return portal;
    }
}
