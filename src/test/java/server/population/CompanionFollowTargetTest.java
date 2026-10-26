package server.population;

import client.Character;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CompanionFollowTargetTest {
    private final Character leader = mock(Character.class);
    private final TownResident resident = mock(TownResident.class);
    private final MapleMap field = map(100010000);

    @Test
    void knockbackStaysOnTheLowerPlatformUntilARealJumpClearsTheUpperOne() {
        CompanionTravel trip = trip();
        when(leader.getStance()).thenReturn(6);
        when(leader.getPosition()).thenReturn(new Point(186, 390));
        trip.tick(1000);
        verify(resident).setFollowTarget(new Point(186, 425));

        when(leader.getStance()).thenReturn(7); // facing left uses the same fall state
        when(leader.getPosition()).thenReturn(new Point(186, 381));
        trip.tick(1050);
        verify(resident, times(2)).setFollowTarget(new Point(186, 425));
        verify(resident, never()).setFollowTarget(new Point(186, 365));

        when(leader.getPosition()).thenReturn(new Point(186, 355));
        trip.tick(1100);
        verify(resident).setFollowTarget(new Point(186, 365));

        when(leader.getStance()).thenReturn(4);
        when(leader.getPosition()).thenReturn(new Point(186, 365));
        trip.tick(1150);
        verify(resident, times(2)).setFollowTarget(new Point(186, 365));
    }

    @Test
    void ropeAndLadderTargetsContinueUpdatingDuringClimbing() {
        CompanionTravel trip = trip();
        when(leader.getStance()).thenReturn(16);
        when(leader.getPosition()).thenReturn(new Point(6, 300));
        trip.tick(1000);
        verify(resident).setFollowTarget(new Point(6, 300));

        when(leader.getStance()).thenReturn(14);
        when(leader.getPosition()).thenReturn(new Point(23, 75));
        trip.tick(1050);
        verify(resident).setFollowTarget(new Point(23, 75));
    }

    @Test
    void unsupportedAirPositionKeepsThePreviousTargetOnlyWithinItsOwnMap() {
        CompanionTravel trip = trip();
        when(leader.getStance()).thenReturn(4);
        when(leader.getPosition()).thenReturn(new Point(186, 425));
        trip.tick(1000);
        when(leader.getStance()).thenReturn(6);
        when(leader.getPosition()).thenReturn(new Point(30000, -1000));
        trip.tick(1050);
        verify(resident, times(2)).setFollowTarget(new Point(186, 425));

        MapleMap destination = map(100020000);
        when(leader.getMap()).thenReturn(destination);
        when(resident.getMap()).thenReturn(destination);
        when(resident.getPosition()).thenReturn(new Point(-580, 210));
        trip.tick(1100);
        verify(resident).setFollowTarget(new Point(-580, 210));
        verify(resident, times(2)).setFollowTarget(new Point(186, 425));
    }

    @Test
    void portalApproachesKeepTheirConfirmedExitTargetEvenWhenTheLeaderIsAirborne() {
        CompanionTravel trip = trip();
        MapleMap destination = map(100020000);
        Portal portal = mock(Portal.class);
        when(portal.getType()).thenReturn(Portal.MAP_PORTAL);
        when(portal.getPortalStatus()).thenReturn(true);
        when(portal.getTargetMapId()).thenReturn(100020000);
        when(portal.getPosition()).thenReturn(new Point(1855, 456));
        when(leader.getMap()).thenReturn(destination);
        when(leader.getStance()).thenReturn(6);
        when(leader.getPosition()).thenReturn(new Point(-580, 180));
        assertTrue(trip.cross(field, destination, portal, new Point(-580, 210)));
        trip.tick(1000);
        verify(resident).setFollowTarget(new Point(1855, 456), false);
        verify(resident, never()).setFollowTarget(any(Point.class));
    }

    private CompanionTravel trip() {
        when(leader.isLoggedinWorld()).thenReturn(true);
        when(leader.isAlive()).thenReturn(true);
        when(leader.getMap()).thenReturn(field);
        when(leader.getPosition()).thenReturn(new Point(186, 425));
        when(resident.getMap()).thenReturn(field);
        when(resident.getPosition()).thenReturn(new Point(186, 425));
        return new CompanionTravel(leader, resident, (actor, destination, arrival) -> fail("No transfer expected"));
    }

    private static MapleMap map(int id) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(id);
        return map;
    }
}
