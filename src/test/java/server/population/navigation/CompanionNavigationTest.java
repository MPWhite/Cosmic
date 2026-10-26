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
    void tallRopeDetachesBesideAMiddlePlatformWithinNormalJumpPhysics() {
        var navigation = CompanionNavigation.of(List.of(new Surface(1, -80, 0, 20, 0),
                new Surface(2, 40, -450, 200, -450)), List.of(new Climb(1, 0, -1000, 0, true)));
        var route = navigation.plan(new Point(-20, 0), new Point(150, -450)).orElseThrow();
        assertContinuous(route, new Point(-20, 0), new Point(150, -450));
        Leg detach = route.stream().filter(l -> l.kind() == Kind.JUMP && l.fromFoothold() == 0
                && l.toFoothold() == 2).findFirst().orElseThrow();
        assertTrue(detach.from().y > -600, "a middle platform must not require the rope's summit");
        assertTrue(Math.abs(detach.verticalVelocity()) <= CompanionNavigation.JUMP_SPEED / 1.5 + 1);
        assertEquals(CompanionNavigation.GRAVITY, detach.gravity());
        assertTrue(Math.abs(detach.to().x - detach.from().x) * 1000.0 / detach.durationMillis()
                <= CompanionNavigation.MAX_HORIZONTAL_SPEED);
        assertTrue(route.stream().filter(l -> l.kind() == Kind.CLIMB).allMatch(l -> l.to().y > -600));
        assertGroundedWalking(navigation, route);
    }

    @Test
    void middleRopeExitsStillRespectWallsAndIntermediateFloors() {
        var blocked = CompanionNavigation.of(List.of(new Surface(1, -80, 0, 20, 0),
                new Surface(2, 40, -450, 200, -450), new Surface(3, 20, -1200, 20, 100)),
                List.of(new Climb(1, 0, -1000, 0, true)));
        assertTrue(blocked.plan(new Point(-20, 0), new Point(150, -450)).isEmpty());

        var floors = CompanionNavigation.of(List.of(new Surface(1, -80, 0, 20, 0),
                new Surface(2, 100, -450, 220, -450), new Surface(3, 10, -500, 180, -500)),
                List.of(new Climb(1, 0, -1000, 0, true)));
        var route = floors.plan(new Point(-20, 0), new Point(200, -450)).orElseThrow();
        assertContinuous(route, new Point(-20, 0), new Point(200, -450));
        assertTrue(route.stream().anyMatch(l -> l.fromFoothold() == 0 && l.toFoothold() == 3));
        assertFalse(route.stream().anyMatch(l -> l.fromFoothold() == 0 && l.toFoothold() == 2),
                "the rope exit cannot fall through the intervening floor");
    }

    @Test
    void firstKpqMapDetachesForMiddleFloorsAndUsesTheShortRopeForTheUpperIsland() {
        var navigation = CompanionNavigation.forMapId(103000800);
        Point start = new Point(-437, 84);
        Point middle = navigation.project(new Point(-200, -620), 20).orElseThrow().position();
        var middleRoute = navigation.planFromClimb(start, middle).orElseThrow();
        assertContinuous(middleRoute, start, middle);
        assertTrue(middleRoute.stream().anyMatch(l -> l.kind() == Kind.JUMP && l.fromFoothold() == 0));
        assertTrue(middleRoute.stream().filter(l -> l.kind() == Kind.CLIMB).allMatch(l -> l.to().y >= -1000),
                "the middle floor is reachable without the rope's summit: " + middleRoute);
        int oldClimbToSummitMillis = (int) ((84 + 1471) / 110.0 * 1000);
        assertTrue(middleRoute.stream().mapToInt(Leg::durationMillis).sum() < oldClimbToSummitMillis,
                "the entire middle-floor route should take less time than climbing to the old endpoint alone");
        assertGroundedWalking(navigation, middleRoute);

        // This isolated island needs the upper -902 floor via the short rope at
        // x=-337. A lower-than-1000 exit would exceed the real jump envelope.
        Point target = navigation.project(new Point(347, -672), 160).orElseThrow().position();
        var route = navigation.planFromClimb(start, target).orElseThrow();
        assertContinuous(route, start, target);
        assertTrue(route.stream().anyMatch(l -> l.kind() == Kind.JUMP && l.fromFoothold() == 0));
        assertTrue(route.stream().filter(l -> l.kind() == Kind.CLIMB).allMatch(l -> l.to().y >= -1350),
                "the island route still should leave before the unnecessary -1471 summit: " + route);
        assertGroundedWalking(navigation, route);
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

    @Test
    void canFinishMidRopeThenClimbDownOrMoveToAnotherRopeWithoutSnapping() {
        var navigation = CompanionNavigation.of(List.of(new Surface(1, 0, 300, 400, 300),
                new Surface(2, 0, 0, 400, 0)), List.of(new Climb(1, 100, 0, 300, true),
                new Climb(2, 250, 0, 300, true)));
        Point middle = new Point(100, 120);
        var arrival = navigation.planToClimb(new Point(20, 300), middle, false).orElseThrow();
        assertContinuous(arrival, new Point(20, 300), middle);
        assertEquals(Kind.CLIMB, arrival.getLast().kind());
        assertEquals(0, arrival.getLast().toFoothold());
        var departure = navigation.planFromClimb(middle, new Point(350, 300)).orElseThrow();
        assertContinuous(departure, middle, new Point(350, 300));
        assertEquals(Kind.CLIMB, departure.getFirst().kind());
        var nextRope = navigation.planToClimb(middle, new Point(250, 120), true).orElseThrow();
        assertContinuous(nextRope, middle, new Point(250, 120));
        assertTrue(navigation.planToClimb(new Point(20, 300), new Point(101, -5), false).isEmpty());
        assertTrue(navigation.planFromClimb(new Point(110, 120), new Point(350, 300)).isEmpty());
    }

    @Test
    void everyRealKpqRopeCanBeReachedHeldAndLeft() {
        var navigation = CompanionNavigation.forMapId(103000801);
        Point spawn = new Point(-1295, 102);
        spawn = navigation.project(spawn, 400).orElseThrow().position();
        for (Point target : List.of(new Point(-753, -23), new Point(-719, -257),
                new Point(-584, -251), new Point(-481, -70))) {
            var rope = navigation.climbs().stream().min(java.util.Comparator.comparingInt(c -> Math.abs(c.x() - target.x))).orElseThrow();
            Point actual = new Point(rope.x(), target.y);
            assertTrue(Math.abs(actual.x - target.x) <= 2);
            var arrival = navigation.planToClimb(spawn, actual, false).orElseThrow(() -> new AssertionError("No route to " + actual));
            assertContinuous(arrival, spawn, actual);
            var departure = navigation.planFromClimb(actual, spawn).orElseThrow(() -> new AssertionError("No route from " + actual));
            assertContinuous(departure, actual, spawn);
        }
    }

    @Test
    void knockbackUsesARealArcAndCannotPassThroughAnIntermediateFloorOrWall() {
        var flat = CompanionNavigation.of(List.of(new Surface(1, -100, 100, 300, 100)), List.of());
        Leg hit = flat.knockback(new Point(20, 100), 1).orElseThrow();
        assertEquals(Kind.JUMP, hit.kind());
        assertEquals(new Point(20, 100), hit.from());
        assertEquals(100, hit.to().y);
        assertTrue(hit.to().x > 70 && hit.to().x < 110);
        assertTrue(hit.pointAt(hit.durationMillis() / 2).y < 80);
        var wall = CompanionNavigation.of(List.of(new Surface(1, -100, 100, 300, 100),
                new Surface(2, 40, -100, 40, 120)), List.of());
        assertTrue(wall.knockback(new Point(20, 100), 1).isEmpty());
        assertTrue(flat.knockback(new Point(20, -100), 1).isEmpty(), "cannot invent ground for an airborne hit");
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
