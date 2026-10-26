package server.population;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TownResidentTest {
    @Test
    void followsConnectedGroundAndStopsBeforeACliffInsteadOfTeleporting() {
        FootholdTree ground = new FootholdTree(new Point(-1000, -1000), new Point(1000, 1000));
        ground.insert(new Foothold(new Point(0, 100), new Point(100, 100), 1));
        ground.insert(new Foothold(new Point(101, 250), new Point(500, 250), 2));
        TownResident resident = create(ground, new Point(30, 95));
        resident.setFollowTarget(new Point(300, 100));
        resident.tick(1000);
        Point previous = resident.getPosition();
        for (int now = 1050; now < 20_000; now += 50) {
            resident.tick(now);
            Point current = resident.getPosition();
            assertEquals(100, current.y);
            assertTrue(Math.abs(current.x - previous.x) <= 5, "50ms movement must remain a small walk step");
            assertTrue(current.x <= 100, "must not fall down or jump over an unsupported cliff");
            previous = current;
        }
        assertEquals(100, resident.getPosition().x);
        assertEquals(MapObjectType.TOWN_RESIDENT, resident.getType());
        assertTrue(resident.getStance() == 4 || resident.getStance() == 5);
    }

    @Test
    void walksSlopedAndAdjacentFootholdsAndStopsAtCompanionDistance() {
        FootholdTree ground = new FootholdTree(new Point(-1000, -1000), new Point(1000, 1000));
        ground.insert(new Foothold(new Point(0, 100), new Point(100, 120), 1));
        ground.insert(new Foothold(new Point(100, 120), new Point(500, 120), 2));
        TownResident resident = create(ground, new Point(30, 100));
        resident.setFollowTarget(new Point(280, 120));
        resident.tick(1000);
        for (int now = 1050; now < 10_000; now += 50) {
            resident.tick(now);
        }
        assertTrue(resident.getPosition().x > 200);
        assertTrue(resident.getPosition().x <= 225);
        assertEquals(120, resident.getPosition().y);
        assertEquals(2, resident.getFootholdId());
        assertEquals(4, resident.getStance());
    }

    @Test
    void aLongSchedulerPauseDoesNotCauseALargeMovementJump() {
        FootholdTree ground = new FootholdTree(new Point(-1000, -1000), new Point(1000, 1000));
        ground.insert(new Foothold(new Point(0, 100), new Point(500, 100), 1));
        TownResident resident = create(ground, new Point(30, 95));
        resident.setFollowTarget(new Point(400, 100));
        resident.tick(1000);
        resident.tick(1_000_000);
        assertTrue(resident.getPosition().x - 30 <= 13);
    }

    private static TownResident create(FootholdTree tree, Point spawn) {
        MapleMap map = mock(MapleMap.class);
        when(map.getFootholds()).thenReturn(tree);
        return new TownResident(1_500_000_001, ResidentProfiles.all().getFirst(), map, spawn);
    }
}
