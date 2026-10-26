package server.population;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.population.navigation.CompanionNavigation;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TownResidentTravelControlTest {
    @Test
    void waitingCompanionStaysPutAndCanResumeFollowing() {
        var navigation = CompanionNavigation.of(List.of(new CompanionNavigation.Surface(1, 0, 100, 500, 100)), List.of());
        MapleMap map = mock(MapleMap.class);
        FootholdTree footholds = new FootholdTree(new Point(-1000, -1000), new Point(1000, 1000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(500, 100), 1));
        when(map.getFootholds()).thenReturn(footholds);
        var resident = new TownResident(1_500_000_001, ResidentProfiles.all().getFirst(), map,
                new Point(30, 95), navigation);
        resident.setFollowTarget(new Point(250, 100));
        resident.tick(1000);
        for (int now = 1050; now < 5000; now += 50) {
            resident.tick(now);
        }
        resident.waitHere();
        Point waitingPosition = resident.getPosition();
        for (int now = 5000; now < 20_000; now += 50) {
            resident.tick(now);
            assertEquals(waitingPosition, resident.getPosition(), "stay must suspend the ambient wandering routine");
        }
        assertEquals("waiting", resident.getNavigationStatus());
        resident.setFollowTarget(new Point(440, 100));
        for (int now = 20_000; now < 25_000; now += 50) {
            resident.tick(now);
        }
        assertTrue(resident.getPosition().x > waitingPosition.x + 100);
    }

    @Test
    void portalApproachReachesTheActualEndpointEvenOnASteepSlope() {
        var surface = new CompanionNavigation.Surface(1, 0, 100, 500, 400);
        var navigation = CompanionNavigation.of(List.of(surface), List.of());
        MapleMap map = mock(MapleMap.class);
        FootholdTree footholds = new FootholdTree(new Point(-1000, -1000), new Point(1000, 1000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(500, 400), 1));
        when(map.getFootholds()).thenReturn(footholds);
        var resident = new TownResident(1_500_000_001, ResidentProfiles.all().getFirst(), map,
                new Point(30, 115), navigation);
        Point portal = new Point(400, 340);

        resident.setFollowTarget(portal);
        resident.tick(1000);
        for (int now = 1050; now < 7000; now += 50) {
            resident.tick(now);
        }
        assertTrue(Math.abs(resident.getPosition().y - portal.y) > 25,
                "ordinary companion spacing is not close enough for a portal on this slope");

        resident.setFollowTarget(portal, false);
        for (int now = 7000; now < 10_000; now += 50) {
            resident.tick(now);
        }
        assertTrue(resident.getPosition().distance(portal) <= 5,
                "portal navigation must finish the route rather than retaining companion spacing");
        assertTrue(Math.abs(resident.getPosition().y - portal.y) <= 25);
    }
}
