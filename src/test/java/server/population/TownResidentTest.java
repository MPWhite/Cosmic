package server.population;

import net.opcodes.SendOpcode;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.population.navigation.CompanionNavigation;
import server.population.navigation.CompanionNavigation.Climb;
import server.population.navigation.CompanionNavigation.Surface;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TownResidentTest {
    @Test
    void followsConnectedSlopedGroundAtCatchUpSpeedAndKeepsCompanionDistance() {
        Fixture fixture = fixture(List.of(new Surface(1, 0, 100, 100, 120),
                new Surface(2, 100, 120, 600, 120)), List.of(), new Point(30, 100));
        TownResident resident = fixture.resident();
        resident.setFollowTarget(new Point(400, 120));
        resident.tick(1000);
        Point previous = resident.getPosition();
        for (int now = 1050; now < 10_000; now += 50) {
            resident.tick(now);
            Point current = resident.getPosition();
            assertTrue(Math.abs(current.x - previous.x) <= 9, "50ms movement should be a continuous walking step");
            previous = current;
        }
        assertTrue(resident.getPosition().x >= 325);
        assertTrue(resident.getPosition().x <= 340);
        assertEquals(120, resident.getPosition().y);
        assertEquals(2, resident.getFootholdId());
        assertEquals(4, resident.getStance());
        assertEquals(MapObjectType.TOWN_RESIDENT, resident.getType());
        assertEquals(0, resident.getRecoveryCount());
    }

    @Test
    void executesAGapJumpAsAnArcAndContinuesFollowingOnTheLandingPlatform() {
        Fixture fixture = fixture(List.of(new Surface(1, 0, 120, 100, 120),
                new Surface(2, 150, 90, 500, 90)), List.of(), new Point(35, 115));
        TownResident resident = fixture.resident();
        resident.setFollowTarget(new Point(350, 90));
        resident.tick(1000);
        boolean jumped = false;
        int highestY = resident.getPosition().y;
        Point previous = resident.getPosition();
        for (int now = 1050; now < 10_000; now += 50) {
            resident.tick(now);
            Point current = resident.getPosition();
            jumped |= resident.getStance() == 6 || resident.getStance() == 7;
            highestY = Math.min(highestY, current.y);
            assertTrue(Math.abs(current.x - previous.x) <= 10, "jump cannot skip directly to its landing");
            previous = current;
        }
        assertTrue(jumped, "expected a real jump animation while crossing the gap");
        assertTrue(highestY < 90, "a jump must arc above its landing platform");
        assertEquals(90, resident.getPosition().y);
        assertTrue(resident.getPosition().x >= 275);
        assertEquals(2, resident.getFootholdId());
        assertEquals(0, resident.getRecoveryCount());
    }

    @Test
    void climbsLaddersAndRopesWithCorrectRemotePlayerStances() {
        for (boolean rope : new boolean[]{false, true}) {
            Fixture fixture = fixture(List.of(new Surface(1, 0, 220, 300, 220),
                            new Surface(2, 0, 0, 300, 0)),
                    List.of(new Climb(1, 100, 0, 220, rope)), new Point(35, 215));
            TownResident resident = fixture.resident();
            resident.setFollowTarget(new Point(180, 0));
            resident.tick(1000);
            boolean climbed = false;
            for (int now = 1050; now < 12_000; now += 50) {
                resident.tick(now);
                if (resident.getStance() == (rope ? 16 : 14)) {
                    climbed = true;
                    assertEquals(100, resident.getPosition().x, "climbing follows the actual ladder or rope");
                }
            }
            assertTrue(climbed, rope ? "expected rope animation" : "expected ladder animation");
            assertEquals(0, resident.getPosition().y);
            assertEquals(2, resident.getFootholdId());
            assertEquals(0, resident.getRecoveryCount());
        }
    }

    @Test
    void stoppingMidJumpFinishesTheLandingInsteadOfFreezingInTheAir() {
        Fixture fixture = fixture(List.of(new Surface(1, 0, 120, 100, 120),
                new Surface(2, 150, 90, 500, 90)), List.of(), new Point(35, 115));
        TownResident resident = fixture.resident();
        resident.setFollowTarget(new Point(350, 90));
        resident.tick(1000);
        int now = 1050;
        for (; now < 8000; now += 50) {
            resident.tick(now);
            if (resident.getStance() == 6) {
                break;
            }
        }
        assertEquals(6, resident.getStance());
        resident.clearFollowTarget();
        assertFalse(resident.isFollowing());
        for (now += 50; now < 10_000; now += 50) {
            resident.tick(now);
            if (resident.getStance() == 4) {
                break;
            }
        }
        assertEquals(90, resident.getPosition().y);
        assertEquals(2, resident.getFootholdId());
    }

    @Test
    void movingLeaderDoesNotDiscardTheClimbAfterAnAirborneRopeGrab() {
        Fixture fixture = fixture(List.of(new Surface(1, 0, 250, 350, 250),
                        new Surface(2, 0, 0, 350, 0)),
                List.of(new Climb(1, 100, 0, 200, true)), new Point(35, 245));
        TownResident resident = fixture.resident();
        resident.setFollowTarget(new Point(180, 0));
        resident.tick(1000);
        boolean jumped = false;
        boolean climbed = false;
        for (int now = 1050; now < 12_000; now += 50) {
            resident.tick(now);
            if (resident.getStance() == 6 && !jumped) {
                jumped = true;
                resident.setFollowTarget(new Point(260, 0));
            }
            climbed |= resident.getStance() == 16;
        }
        assertTrue(jumped, "the rope starts above the ground and must be grabbed with a jump");
        assertTrue(climbed, "the route must continue from the airborne anchor onto the rope");
        assertEquals(0, resident.getPosition().y);
        assertTrue(resident.getPosition().x >= 185);
        assertEquals(0, resident.getRecoveryCount());
    }

    @Test
    void dropsThroughAPlatformContinuouslyToReachALowerLeader() {
        Fixture fixture = fixture(List.of(new Surface(1, 0, 0, 150, 0),
                new Surface(2, 0, 240, 500, 240)), List.of(), new Point(35, -5));
        TownResident resident = fixture.resident();
        resident.setFollowTarget(new Point(300, 240));
        resident.tick(1000);
        boolean falling = false;
        boolean intermediateHeight = false;
        for (int now = 1050; now < 10_000; now += 50) {
            resident.tick(now);
            falling |= resident.getStance() == 6 || resident.getStance() == 7;
            intermediateHeight |= resident.getPosition().y > 0 && resident.getPosition().y < 240;
        }
        assertTrue(falling);
        assertTrue(intermediateHeight, "falling is simulated over time, not a snap between platforms");
        assertEquals(240, resident.getPosition().y);
        assertEquals(2, resident.getFootholdId());
        assertEquals(0, resident.getRecoveryCount());
    }

    @Test
    void unreachableTargetRecoversOnlyAfterTimeoutWithNoticeAndVisibleRespawn() {
        Fixture fixture = fixture(List.of(new Surface(1, 0, 100, 200, 100),
                new Surface(2, 1000, 100, 1400, 100)), List.of(), new Point(35, 95));
        TownResident resident = fixture.resident();
        resident.setFollowTarget(new Point(1150, 100));
        resident.tick(1000);
        for (int now = 1050; now < 10_000; now += 50) {
            resident.tick(now);
        }
        assertEquals(0, resident.getRecoveryCount());
        assertEquals(new Point(35, 100), resident.getPosition());
        resident.tick(10_000);
        assertEquals(1, resident.getRecoveryCount());
        assertEquals(new Point(1080, 100), resident.getPosition());

        var packets = ArgumentCaptor.forClass(Packet.class);
        verify(fixture.map(), atLeast(3)).broadcastMessage(packets.capture());
        List<Integer> opcodes = packets.getAllValues().stream().map(packet -> {
            byte[] bytes = packet.getBytes();
            return (bytes[0] & 255) | ((bytes[1] & 255) << 8);
        }).toList();
        assertTrue(opcodes.contains((int) SendOpcode.CHATTEXT.getValue()));
        assertTrue(opcodes.contains((int) SendOpcode.REMOVE_PLAYER_FROM_MAP.getValue()));
        assertTrue(opcodes.contains((int) SendOpcode.SPAWN_PLAYER.getValue()));

        resident.setFollowTarget(new Point(100, 100));
        for (int now = 10_050; now < 25_000; now += 50) {
            resident.tick(now);
        }
        assertEquals(1, resident.getRecoveryCount(), "recovery is not continuous teleporting");
        resident.tick(25_000);
        assertEquals(2, resident.getRecoveryCount());
    }

    @Test
    void transferPreservesIdentityAndResetsOldRouteTargetAndTiming() {
        Fixture fixture = fixture(List.of(new Surface(1, 0, 100, 500, 100)), List.of(), new Point(35, 95));
        TownResident resident = fixture.resident();
        resident.setFollowTarget(new Point(400, 100));
        resident.tick(1000);
        resident.tick(1050);
        Point beforeRejectedTransfer = resident.getPosition();
        assertThrows(IllegalArgumentException.class,
                () -> resident.relocateTo(map(List.of()), new Point(-100, 245)));
        assertSame(fixture.map(), resident.getMap());
        assertEquals(beforeRejectedTransfer, resident.getPosition());
        assertTrue(resident.isFollowing(), "failed transfer must preserve the previous journey");
        MapleMap destination = map(List.of(new Surface(8, -400, 250, 400, 250)));
        resident.relocateTo(destination, new Point(-100, 245));
        assertSame(destination, resident.getMap());
        assertEquals(new Point(-100, 250), resident.getPosition());
        assertEquals(8, resident.getFootholdId());
        assertEquals(4, resident.getStance());
        assertFalse(resident.isFollowing());
        assertEquals(1_500_000_001, resident.getId());
        assertEquals("MapleMia", resident.getName());
        resident.tick(100_000);
        assertEquals(new Point(-100, 250), resident.getPosition(), "old route cannot continue in the new map");
    }

    @Test
    void longSchedulerPauseDoesNotCauseALargeMovementJump() {
        Fixture fixture = fixture(List.of(new Surface(1, 0, 100, 500, 100)), List.of(), new Point(35, 95));
        TownResident resident = fixture.resident();
        resident.setFollowTarget(new Point(400, 100));
        resident.tick(1000);
        resident.tick(1_000_000);
        assertTrue(resident.getPosition().x - 35 <= 23, "catch-up is bounded to 150ms of simulated movement");
    }

    private record Fixture(TownResident resident, MapleMap map) {
    }

    private static Fixture fixture(List<Surface> surfaces, List<Climb> climbs, Point spawn) {
        MapleMap map = map(surfaces);
        return new Fixture(new TownResident(1_500_000_001, ResidentProfiles.all().getFirst(), map, spawn,
                CompanionNavigation.of(surfaces, climbs)), map);
    }

    private static MapleMap map(List<Surface> surfaces) {
        MapleMap map = mock(MapleMap.class);
        FootholdTree ground = new FootholdTree(new Point(-5000, -5000), new Point(5000, 5000));
        for (Surface surface : surfaces) {
            ground.insert(new Foothold(new Point(surface.x1(), surface.y1()),
                    new Point(surface.x2(), surface.y2()), surface.id()));
        }
        when(map.getFootholds()).thenReturn(ground);
        return map;
    }
}
