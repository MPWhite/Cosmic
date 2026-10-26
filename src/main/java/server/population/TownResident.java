package server.population;

import client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.AbstractMapObject;
import server.maps.Foothold;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.population.navigation.CompanionNavigation;
import server.population.navigation.CompanionNavigation.Leg;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/** A lightweight town actor. It never logs in or occupies the real player list. */
public final class TownResident extends AbstractMapObject {
    private static final Logger log = LoggerFactory.getLogger(TownResident.class);
    private final int id;
    private final ResidentProfiles.Profile profile;
    private MapleMap map;
    private final Point home;
    private final Random random;
    private final int walkingSpeed;
    private int footholdId;
    private int stance = 4;
    private int destinationX;
    private double preciseX;
    private long lastTick;
    private long nextDecision;
    private long pauseUntil;
    private boolean walking;
    private Point following;
    private CompanionNavigation navigation;
    private final ArrayDeque<Leg> route = new ArrayDeque<>();
    private Leg activeLeg;
    private long legElapsed;
    private Point plannedTarget;
    private long nextPlan;
    private long lastProgress;
    private Point progressPosition;
    private long lastRecovery;
    private int recoveryCount;
    private boolean walkOnlyRoute;
    private boolean settling;
    private boolean waiting;
    private boolean retired;
    private boolean keepFollowDistance = true;
    private String navigationStatus = "wandering";

    private static final long REPLAN_INTERVAL = 350;
    private static final long STUCK_TIMEOUT = 9_000;
    private static final long RECOVERY_COOLDOWN = 15_000;
    private static final int FOLLOW_DISTANCE = 70;

    public TownResident(int id, ResidentProfiles.Profile profile, MapleMap map, Point spawn) {
        if (id <= 0) {
            throw new IllegalArgumentException("Resident network IDs must be positive");
        }
        this.id = id;
        this.profile = Objects.requireNonNull(profile);
        this.map = Objects.requireNonNull(map);
        random = new Random(id);
        walkingSpeed = 115 + random.nextInt(31);
        Point ground = new Point(Objects.requireNonNull(spawn));
        Foothold foothold = map.getFootholds().findBelow(new Point(ground.x, ground.y - 8));
        if (foothold == null || foothold.isWall()) {
            throw new IllegalArgumentException("Resident spawn must have ground below it: " + spawn);
        }
        ground.y = footingAt(foothold, ground.x);
        footholdId = foothold.getId();
        home = new Point(ground);
        preciseX = ground.x;
        destinationX = ground.x;
        setPosition(ground);
    }

    TownResident(int id, ResidentProfiles.Profile profile, MapleMap map, Point spawn,
                 CompanionNavigation navigation) {
        this(id, profile, map, spawn);
        this.navigation = Objects.requireNonNull(navigation);
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return profile.name();
    }

    public ResidentProfiles.Profile getProfile() {
        return profile;
    }

    public synchronized MapleMap getMap() {
        return map;
    }

    public synchronized boolean isFollowing() {
        return following != null;
    }

    public synchronized String getNavigationStatus() {
        return navigationStatus;
    }

    public synchronized int getRecoveryCount() {
        return recoveryCount;
    }

    synchronized void restoreHomeAnchor(Point position) {
        home.setLocation(Objects.requireNonNull(position));
    }

    /** The population service moves map membership and publishes the new spawn separately. */
    public synchronized void relocateTo(MapleMap destination, Point spawn) {
        Objects.requireNonNull(destination);
        Point grounded = new Point(Objects.requireNonNull(spawn));
        Foothold foothold = destination.getFootholds().findBelow(new Point(grounded.x, grounded.y - 8));
        if (foothold == null || foothold.isWall()) {
            throw new IllegalArgumentException("Resident transfer must have ground below it: " + spawn);
        }
        grounded.y = footingAt(foothold, grounded.x);
        map = destination;
        home.setLocation(grounded);
        setPosition(grounded);
        footholdId = foothold.getId();
        preciseX = grounded.x;
        destinationX = grounded.x;
        stance = 4;
        walking = false;
        following = null;
        settling = false;
        waiting = false;
        keepFollowDistance = true;
        navigation = null;
        route.clear();
        activeLeg = null;
        legElapsed = 0;
        plannedTarget = null;
        nextPlan = 0;
        lastTick = 0;
        nextDecision = 0;
        pauseUntil = 0;
        lastProgress = 0;
        progressPosition = null;
        navigationStatus = "arrived";
    }

    public synchronized int getStance() {
        return stance;
    }

    public synchronized int getFootholdId() {
        return footholdId;
    }

    @Override
    public synchronized Point getPosition() {
        return super.getPosition();
    }

    @Override
    public MapObjectType getType() {
        return MapObjectType.TOWN_RESIDENT;
    }

    @Override
    public synchronized void sendSpawnData(Client client) {
        if (retired) return;
        if (client.getPlayer() != null && client.getPlayer().getMap() != map) {
            return;
        }
        client.sendPacket(ResidentPackets.spawn(this));
    }

    /** A retired instance cannot be republished by a stale map-placement snapshot. */
    synchronized void retire() {
        retired = true;
    }

    @Override
    public void sendDestroyData(Client client) {
        client.sendPacket(ResidentPackets.remove(id));
    }

    /** Update the companion target without granting it player or combat semantics. */
    public synchronized void setFollowTarget(Point target) {
        setFollowTarget(target, true);
    }

    /** Portal approaches need their actual endpoint, rather than the usual companion spacing. */
    public synchronized void setFollowTarget(Point target, boolean keepDistance) {
        if (following == null) {
            if (!isTraversing() && footholdId != 0) {
                route.clear();
                activeLeg = null;
                legElapsed = 0;
            }
            plannedTarget = null;
            nextPlan = 0;
            lastProgress = 0;
            progressPosition = getPosition();
        }
        if (keepFollowDistance != keepDistance) {
            plannedTarget = null;
            nextPlan = 0;
        }
        keepFollowDistance = keepDistance;
        settling = false;
        waiting = false;
        following = new Point(Objects.requireNonNull(target));
    }

    public synchronized void clearFollowTarget() {
        following = null;
        waiting = false;
        plannedTarget = null;
        nextPlan = 0;
        nextDecision = 0;
        // Finish an airborne/climbing leg before standing; never freeze a companion mid-jump.
        if (isTraversing() || footholdId == 0) {
            settling = true;
        } else {
            route.clear();
            activeLeg = null;
            settling = false;
            stop(100);
            navigationStatus = "wandering";
        }
    }

    public synchronized void waitHere() {
        clearFollowTarget();
        waiting = true;
        navigationStatus = settling ? "finding safe ground to wait" : "waiting";
    }

    public synchronized void faceToward(Point target) {
        if (isTraversing() || footholdId == 0) {
            return;
        }
        int direction = target.x < getPosition().x ? 1 : 0;
        stance = 4 | direction;
        walking = false;
        pauseUntil = System.currentTimeMillis() + 1800;
        broadcastMovement(new Point(), 100);
    }

    public synchronized void say(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (!isTraversing() && footholdId != 0) {
            stop(100);
            pauseUntil = Math.max(pauseUntil, System.currentTimeMillis()
                    + Math.min(2600, 600 + message.length() * 22L + random.nextInt(201)));
        }
        map.broadcastMessage(ResidentPackets.chat(id, message.length() > 240 ? message.substring(0, 240) : message));
    }

    /** Begin a grounded action without interrupting a jump or a ladder traversal. */
    public synchronized boolean holdAction(Point face, long now, int durationMillis) {
        if (isTraversing() || footholdId == 0 || now < pauseUntil) {
            return false;
        }
        route.clear();
        activeLeg = null;
        legElapsed = 0;
        plannedTarget = null;
        nextPlan = 0;
        stance = 4 | (face.x < getPosition().x ? 1 : 0);
        walking = false;
        pauseUntil = now + Math.max(1, durationMillis);
        broadcastMovement(new Point(), 100);
        return true;
    }

    public synchronized void emote(int expression) {
        if (expression >= 0 && expression <= 17) {
            map.broadcastMessage(ResidentPackets.emote(id, expression));
        }
    }

    /** Called by the shared population scheduler; no task or thread per resident. */
    public synchronized void tick(long nowMillis) {
        if (retired) return;
        if (lastTick == 0) {
            lastTick = nowMillis;
            nextDecision = nowMillis + 1000 + random.nextInt(6000);
            lastProgress = nowMillis;
            progressPosition = getPosition();
            return;
        }
        int elapsed = (int) Math.max(0, Math.min(150, nowMillis - lastTick));
        lastTick = nowMillis;
        if (elapsed == 0 || nowMillis < pauseUntil) {
            return;
        }

        if (following != null) {
            tickFollowing(nowMillis, elapsed);
            return;
        }
        if (settling) {
            tickSettling(elapsed);
            return;
        }
        if (waiting) {
            stop(elapsed);
            navigationStatus = "waiting";
            return;
        }
        tickWandering(nowMillis, elapsed);
    }

    private void tickWandering(long nowMillis, int elapsed) {
        Point current = getPosition();
        navigationStatus = "wandering";
        if (!walking) {
            if (nowMillis < nextDecision) {
                return;
            }
            destinationX = home.x - 200 + random.nextInt(401);
            if (Math.abs(destinationX - current.x) < 25) {
                nextDecision = nowMillis + 2000 + random.nextInt(7000);
                return;
            }
            walking = true;
        }

        int direction = Integer.compare(destinationX, current.x);
        double distance = walkingSpeed * elapsed / 1000.0;
        preciseX += direction * Math.min(distance, Math.abs(destinationX - preciseX));
        int newX = (int) Math.round(preciseX);
        int oldStance = stance;
        stance = direction < 0 ? 3 : 2;
        if (newX == current.x) {
            if (oldStance != stance) {
                broadcastMovement(new Point(), elapsed);
            }
            return;
        }

        // Probe each pixel so short steps cannot skip a gap, wall, or platform edge.
        Point destination = new Point(current);
        boolean blocked = false;
        for (int x = current.x + direction; direction > 0 ? x <= newX : x >= newX; x += direction) {
            Foothold foothold = map.getFootholds().findBelow(new Point(x, destination.y - 5));
            if (foothold == null || foothold.isWall()) {
                blocked = true;
                break;
            }
            int y = footingAt(foothold, x);
            if (Math.abs(y - destination.y) > 5
                    || map.getFootholds().findWall(new Point(Math.min(destination.x, x), destination.y - 3),
                    new Point(Math.max(destination.x, x), destination.y - 3)) != null) {
                blocked = true;
                break;
            }
            destination.setLocation(x, y);
            footholdId = foothold.getId();
        }
        setPosition(destination);
        if (blocked || destination.x == destinationX) {
            preciseX = destination.x;
            walking = false;
            stance = 4 | (direction < 0 ? 1 : 0);
            nextDecision = nowMillis + 2000 + random.nextInt(9000);
        }
        Point velocity = new Point((destination.x - current.x) * 1000 / elapsed,
                (destination.y - current.y) * 1000 / elapsed);
        broadcastMovement(velocity, elapsed);
    }

    private void tickFollowing(long nowMillis, int elapsed) {
        if (navigation == null) {
            navigation = CompanionNavigation.forMap(map);
        }
        if (lastProgress == 0) {
            lastProgress = nowMillis;
            progressPosition = getPosition();
        }

        // Once committed to a jump or ladder, reach its safe endpoint before choosing another route.
        if (!isTraversing() && footholdId != 0 && nowMillis >= nextPlan
                && (plannedTarget == null || plannedTarget.distanceSq(following) > 24 * 24
                || activeLeg == null && route.isEmpty())) {
            replan(nowMillis);
        }

        int arrivalDistance = keepFollowDistance ? FOLLOW_DISTANCE : 5;
        if (!isTraversing() && walkOnlyRoute && getPosition().distanceSq(following) <= arrivalDistance * arrivalDistance) {
            route.clear();
            activeLeg = null;
            stop(elapsed);
            navigationStatus = "alongside";
            lastProgress = nowMillis;
            progressPosition = getPosition();
            return;
        }

        if (activeLeg == null && !route.isEmpty()) {
            Leg next = route.removeFirst();
            // A plan is only executable from its actual departure point. Replan instead of snapping.
            if (getPosition().distanceSq(next.from()) > 8 * 8) {
                route.clear();
                plannedTarget = null;
                nextPlan = nowMillis + REPLAN_INTERVAL;
                navigationStatus = "replanning";
            } else {
                beginLeg(next);
            }
        }
        if (activeLeg != null) {
            advanceLeg(elapsed);
        } else {
            stop(elapsed);
        }

        Point position = getPosition();
        if (progressPosition == null || progressPosition.distanceSq(position) >= 12 * 12) {
            progressPosition = position;
            lastProgress = nowMillis;
        }
        // Exact targets are portal approaches. Their cross-map recovery belongs
        // to CompanionTravel, which can notify the leader in the destination map.
        if (keepFollowDistance && !isTraversing() && nowMillis - lastProgress >= STUCK_TIMEOUT) {
            recoverNearLeader(nowMillis);
        }
    }

    private void tickSettling(int elapsed) {
        if (activeLeg == null && !route.isEmpty()) {
            beginLeg(route.removeFirst());
        }
        if (activeLeg != null) {
            advanceLeg(elapsed);
        }
        if (activeLeg == null && footholdId != 0) {
            route.clear();
            settling = false;
            stop(elapsed);
            navigationStatus = waiting ? "waiting" : "wandering";
        }
    }

    private void replan(long nowMillis) {
        nextPlan = nowMillis + REPLAN_INTERVAL;
        plannedTarget = new Point(following);
        Optional<List<Leg>> planned = navigation.plan(getPosition(), following);
        route.clear();
        activeLeg = null;
        legElapsed = 0;
        walkOnlyRoute = false;
        if (planned.isEmpty()) {
            navigationStatus = "waiting for a route";
            return;
        }
        route.addAll(planned.get());
        walkOnlyRoute = route.stream().allMatch(TownResident::isWalking);
        if (route.isEmpty()) {
            navigationStatus = "alongside";
            lastProgress = nowMillis;
            progressPosition = getPosition();
        } else {
            navigationStatus = "following";
        }
    }

    private void advanceLeg(int elapsed) {
        Leg leg = activeLeg;
        Point previous = getPosition();
        legElapsed = Math.min(leg.durationMillis(), legElapsed + elapsed);
        Point next = leg.pointAt(legElapsed);
        int facing = leg.to().x < leg.from().x ? 1 : 0;
        stance = switch (leg.kind()) {
            case WALK -> 2 | facing;
            case JUMP, DROP -> 6 | facing;
            case CLIMB -> leg.rope() ? 16 : 14;
        };
        navigationStatus = leg.kind().name().toLowerCase(java.util.Locale.ROOT);
        walking = isWalking(leg);
        footholdId = isWalking(leg) ? leg.fromFoothold() : 0;
        setPosition(next);
        preciseX = next.x;
        if (legElapsed >= leg.durationMillis()) {
            setPosition(leg.to());
            preciseX = leg.to().x;
            footholdId = leg.toFoothold();
            activeLeg = null;
            walking = false;
            stance = footholdId != 0 ? 4 | facing
                    : !route.isEmpty() && route.peekFirst().kind() == CompanionNavigation.Kind.CLIMB
                    ? route.peekFirst().rope() ? 16 : 14 : 6 | facing;
            walkOnlyRoute = route.stream().allMatch(TownResident::isWalking);
        }
        Point position = getPosition();
        broadcastMovement(new Point((position.x - previous.x) * 1000 / elapsed,
                (position.y - previous.y) * 1000 / elapsed), elapsed);
    }

    private void beginLeg(Leg leg) {
        activeLeg = leg;
        legElapsed = 0;
        if (!isWalking(leg) && log.isDebugEnabled()) {
            log.debug("Companion {} in map {} started {} from {} to {}", getName(), map.getId(),
                    leg.kind(), leg.from(), leg.to());
        }
    }

    private void recoverNearLeader(long nowMillis) {
        if (lastRecovery != 0 && nowMillis - lastRecovery < RECOVERY_COOLDOWN) {
            return;
        }
        int side = getPosition().x <= following.x ? -1 : 1;
        for (int offset : new int[]{side * FOLLOW_DISTANCE, side * 35, 0}) {
            Optional<CompanionNavigation.Ground> ground = navigation.groundBelow(
                    new Point(following.x + offset, following.y - 12), 150);
            if (ground.isEmpty() || ground.get().position().distanceSq(following) > 170 * 170
                    || ground.get().position().distanceSq(getPosition()) < 35 * 35) {
                continue;
            }
            log.warn("Companion {} recovered in map {} from {} to {} after {}ms without progress",
                    getName(), map.getId(), getPosition(), ground.get().position(), nowMillis - lastProgress);
            map.broadcastMessage(ResidentPackets.chat(id, "I lost the path for a moment. Catching up beside you!"));
            map.broadcastMessage(ResidentPackets.remove(id));
            setPosition(ground.get().position());
            preciseX = getPosition().x;
            footholdId = ground.get().footholdId();
            stance = 4 | (side > 0 ? 1 : 0);
            walking = false;
            route.clear();
            activeLeg = null;
            plannedTarget = null;
            nextPlan = nowMillis + REPLAN_INTERVAL;
            lastRecovery = nowMillis;
            lastProgress = nowMillis;
            progressPosition = getPosition();
            recoveryCount++;
            navigationStatus = "recovered beside leader";
            map.broadcastMessage(ResidentPackets.spawn(this));
            return;
        }
        navigationStatus = "waiting for safe ground";
    }

    private static boolean isWalking(Leg leg) {
        return switch (leg.kind()) {
            case WALK -> true;
            default -> false;
        };
    }

    private boolean isTraversing() {
        return activeLeg != null && !isWalking(activeLeg);
    }

    private void stop(int duration) {
        if (walking || stance < 4) {
            walking = false;
            stance = 4 | (stance & 1);
            preciseX = getPosition().x;
            broadcastMovement(new Point(), duration);
        }
    }

    private void broadcastMovement(Point velocity, int duration) {
        map.broadcastMessage(ResidentPackets.move(id, getPosition(), velocity, footholdId, stance, duration));
    }

    static int footingAt(Foothold foothold, int x) {
        if (foothold.isWall()) {
            throw new IllegalArgumentException("Cannot walk on a wall");
        }
        return foothold.getY1() + (int) Math.round((double) (x - foothold.getX1())
                * (foothold.getY2() - foothold.getY1()) / (foothold.getX2() - foothold.getX1()));
    }
}
