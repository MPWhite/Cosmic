package server.population.navigation;

import org.junit.jupiter.api.Test;
import server.population.navigation.CompanionNavigation.Climb;
import server.population.navigation.CompanionNavigation.Kind;
import server.population.navigation.CompanionNavigation.Leg;
import server.population.navigation.CompanionNavigation.Surface;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompanionNavigationTest {
    @Test
    void walksConnectedSlopesWithoutJumpingOrLeavingGround() {
        var navigation = CompanionNavigation.of(List.of(
                new Surface(1, 0, 100, 100, 120), new Surface(2, 100, 120, 250, 120)), List.of());
        var route = navigation.plan(new Point(10, 102), new Point(240, 120)).orElseThrow();
        assertTrue(route.stream().allMatch(leg -> leg.kind() == Kind.WALK));
        assertContinuous(route, new Point(10, 102), new Point(240, 120));
        assertGroundedWalking(navigation, route);
    }

    @Test
    void crossesASmallGapUsingABoundedBallisticJump() {
        var navigation = CompanionNavigation.of(List.of(
                new Surface(1, 0, 100, 100, 100), new Surface(2, 145, 100, 250, 100)), List.of());
        var route = navigation.plan(new Point(20, 100), new Point(220, 100)).orElseThrow();
        Leg jump = route.stream().filter(leg -> leg.kind() == Kind.JUMP).findFirst().orElseThrow();
        assertEquals(1, jump.fromFoothold());
        assertEquals(2, jump.toFoothold());
        assertTrue(jump.pointAt(jump.durationMillis() / 2).y < 40, "the gap must be crossed through the air");
        assertTrue(Math.abs(jump.to().x - jump.from().x) * 1000.0 / jump.durationMillis() <= 150);
        assertTrue(Math.abs(jump.verticalVelocity()) < 562.5, "do not exceed the browser's unbuffed jump impulse");
        assertContinuous(route, new Point(20, 100), new Point(220, 100));
    }

    @Test
    void refusesWideGapsAndPlatformsAboveTheJumpEnvelope() {
        var gap = CompanionNavigation.of(List.of(
                new Surface(1, 0, 100, 100, 100), new Surface(2, 260, 100, 400, 100)), List.of());
        assertTrue(gap.plan(new Point(20, 100), new Point(320, 100)).isEmpty());
        var high = CompanionNavigation.of(List.of(
                new Surface(1, 0, 100, 100, 100), new Surface(2, 0, 0, 100, 0)), List.of());
        assertTrue(high.plan(new Point(20, 100), new Point(20, 0)).isEmpty());
    }

    @Test
    void cannotWalkOrJumpThroughAWall() {
        var navigation = CompanionNavigation.of(List.of(
                new Surface(1, 0, 100, 100, 100), new Surface(2, 100, 100, 200, 100),
                new Surface(3, 100, -500, 100, 500)), List.of());
        assertTrue(navigation.plan(new Point(20, 100), new Point(180, 100)).isEmpty());
    }

    @Test
    void fallingLandsOnAnIntermediatePlatformBeforeContinuingDown() {
        var navigation = CompanionNavigation.of(List.of(
                new Surface(1, 0, 100, 100, 100), new Surface(2, 0, 200, 100, 200),
                new Surface(3, 0, 350, 100, 350)), List.of());
        var route = navigation.plan(new Point(40, 100), new Point(40, 350)).orElseThrow();
        assertTrue(route.stream().anyMatch(leg -> leg.kind() == Kind.DROP && leg.toFoothold() == 2));
        assertFalse(route.stream().anyMatch(leg -> leg.fromFoothold() == 1 && leg.toFoothold() == 3),
                "a falling actor must not pass through an unselected intermediate platform");
        assertContinuous(route, new Point(40, 100), new Point(40, 350));
    }

    @Test
    void jumpsToAHangingRopeBeforeClimbingIt() {
        var navigation = CompanionNavigation.of(List.of(
                new Surface(1, 0, 455, 100, 455), new Surface(2, 0, 185, 100, 185)),
                List.of(new Climb(1, 40, 187, 408, true)));
        var route = navigation.plan(new Point(10, 455), new Point(80, 185)).orElseThrow();
        int climbIndex = -1;
        for (int index = 0; index < route.size(); index++) {
            if (route.get(index).kind() == Kind.CLIMB) {
                climbIndex = index;
                break;
            }
        }
        assertTrue(climbIndex > 0);
        Leg attach = route.get(climbIndex - 1);
        Leg climb = route.get(climbIndex);
        assertEquals(Kind.JUMP, attach.kind());
        assertEquals(0, attach.toFoothold());
        assertEquals(new Point(40, 408), attach.to());
        assertTrue(climb.rope());
        assertEquals(40, climb.from().x);
        assertEquals(40, climb.to().x);
        assertEquals(40, climb.pointAt(climb.durationMillis() / 2).x);
        assertContinuous(route, new Point(10, 455), new Point(80, 185));
    }

    @Test
    void aLadderTouchingTheGroundDoesNotNeedAnInventedJump() {
        var navigation = CompanionNavigation.of(List.of(
                new Surface(1, 0, 334, 100, 334), new Surface(2, 0, 124, 100, 124)),
                List.of(new Climb(1, 40, 126, 332, false)));
        var route = navigation.plan(new Point(20, 334), new Point(80, 124)).orElseThrow();
        assertTrue(route.stream().anyMatch(leg -> leg.kind() == Kind.CLIMB && !leg.rope()));
        assertFalse(route.stream().anyMatch(leg -> leg.kind() == Kind.JUMP));
    }

    @Test
    void cannotGrabARopeThatIsTooHigh() {
        var navigation = CompanionNavigation.of(List.of(
                new Surface(1, 0, 455, 100, 455), new Surface(2, 0, 185, 100, 185)),
                List.of(new Climb(1, 40, 187, 300, true)));
        assertTrue(navigation.plan(new Point(10, 455), new Point(80, 185)).isEmpty());
    }

    @Test
    void groundProjectionNeverMovesSidewaysOrSnapsAnAirborneStartOntoAPlatform() {
        var navigation = CompanionNavigation.of(List.of(new Surface(1, 0, 100, 100, 100)), List.of());
        assertTrue(navigation.groundBelow(new Point(101, 0), 500).isEmpty());
        assertTrue(navigation.groundBelow(new Point(20, 110), 30).isEmpty());
        assertEquals(new Point(20, 100), navigation.groundBelow(new Point(20, 50), 50).orElseThrow().position());
        assertEquals(new Point(20, 100), navigation.project(new Point(20, 110), 10).orElseThrow().position());
        assertTrue(navigation.plan(new Point(20, 50), new Point(80, 100)).isEmpty());
        assertEquals(List.of(), navigation.plan(new Point(20, 100), new Point(20, 100)).orElseThrow());
    }

    @Test
    void returnedGeometryCannotBeMutatedThroughPointReferences() {
        var navigation = CompanionNavigation.of(List.of(new Surface(1, 0, 100, 100, 100)), List.of());
        var ground = navigation.project(new Point(20, 100), 0).orElseThrow();
        ground.position().move(9000, 9000);
        assertEquals(new Point(20, 100), ground.position());
        var leg = navigation.plan(new Point(20, 100), new Point(80, 100)).orElseThrow().getFirst();
        leg.from().move(9000, 9000);
        leg.to().move(9000, 9000);
        assertEquals(new Point(20, 100), leg.pointAt(-1));
        assertEquals(new Point(80, 100), leg.pointAt(Long.MAX_VALUE));
    }

    @Test
    void nodeConstructionHasAHardBound() {
        assertThrows(IllegalArgumentException.class, () -> CompanionNavigation.of(
                List.of(new Surface(1, 0, 100, 200000, 100)), List.of()));
    }

    @Test
    void realHenesysStreetAndLadderRoutesRespectUnrelatedDecorativeWallLayers() {
        var navigation = CompanionNavigation.forMapId(100000000);
        assertSame(navigation, CompanionNavigation.forMapId(100000000));
        var street = navigation.plan(new Point(3000, 334), new Point(5907, 454)).orElseThrow();
        assertContinuous(street, new Point(3000, 334), new Point(5907, 454));
        assertTrue(street.stream().anyMatch(leg -> leg.kind() == Kind.JUMP || leg.kind() == Kind.DROP));
        assertGroundedWalking(navigation, street);
        var ladder = navigation.plan(new Point(3300, 334), new Point(3500, 124)).orElseThrow();
        assertTrue(ladder.stream().anyMatch(leg -> leg.kind() == Kind.CLIMB && leg.from().x == 3398 && !leg.rope()));
        assertContinuous(ladder, new Point(3300, 334), new Point(3500, 124));
    }

    @Test
    void firstHuntingMapUsesTheActualRopeAndLadderAndReachesItsUpperPlatforms() {
        var navigation = CompanionNavigation.forMapId(100010000);
        var rope = navigation.plan(new Point(-542, 455), new Point(50, 185)).orElseThrow();
        assertTrue(rope.stream().anyMatch(leg -> leg.kind() == Kind.CLIMB && leg.rope() && leg.from().x == 6));
        assertTrue(rope.stream().anyMatch(leg -> leg.kind() == Kind.JUMP && leg.to().equals(new Point(6, 408))));
        assertContinuous(rope, new Point(-542, 455), new Point(50, 185));
        var ladder = navigation.plan(new Point(50, 185), new Point(50, -55)).orElseThrow();
        assertTrue(ladder.stream().anyMatch(leg -> leg.kind() == Kind.CLIMB && !leg.rope() && leg.from().x == 23));
        var summit = navigation.plan(new Point(-542, 455), new Point(636, -595)).orElseThrow();
        assertContinuous(summit, new Point(-542, 455), new Point(636, -595));
        assertTrue(summit.stream().filter(leg -> leg.kind() == Kind.CLIMB).count() >= 3);
        assertGroundedWalking(navigation, summit);
    }

    private static void assertContinuous(List<Leg> route, Point from, Point to) {
        Point previous = from;
        for (Leg leg : route) {
            assertEquals(previous, leg.from(), "route legs must meet without a teleport");
            assertEquals(leg.from(), leg.pointAt(0));
            assertEquals(leg.to(), leg.pointAt(leg.durationMillis()));
            previous = leg.to();
        }
        assertEquals(to, previous);
    }

    private static void assertGroundedWalking(CompanionNavigation navigation, List<Leg> route) {
        for (Leg leg : route) {
            if (leg.kind() == Kind.WALK) {
                for (int sample = 0; sample <= 10; sample++) {
                    Point point = leg.pointAt(leg.durationMillis() * sample / 10L);
                    assertTrue(navigation.project(point, 1).isPresent(), "walking in midair at " + point);
                }
            }
        }
    }
}
