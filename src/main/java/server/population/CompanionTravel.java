package server.population;

import client.Character;
import server.maps.MapleMap;
import server.maps.Portal;
import server.population.navigation.CompanionNavigation;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.Deque;

/** One player's companion reservation and ordered, server-confirmed portal crossings. */
final class CompanionTravel {
    @FunctionalInterface
    interface Transfer {
        void move(TownResident resident, MapleMap destination, Point arrival);
    }

    private record Crossing(MapleMap source, MapleMap destination, Point exit, Point arrival) { }

    final Character leader;
    final TownResident resident;
    final MapleMap homeMap;
    final Point homePosition;
    private final Transfer transfer;
    private final Deque<Crossing> crossings = new ArrayDeque<>();
    private boolean following = true;
    private long mismatchSince;
    private long crossingSince;
    private boolean returned;
    private MapleMap lastTargetMap;
    private Point lastFollowTarget;

    CompanionTravel(Character leader, TownResident resident, Transfer transfer) {
        this.leader = leader;
        this.resident = resident;
        this.homeMap = resident.getMap();
        this.homePosition = resident.getPosition();
        this.transfer = transfer;
    }

    static boolean canCross(MapleMap source, MapleMap destination, Portal portal) {
        return source != destination && source.getEventInstance() == null && destination.getEventInstance() == null
                && source.getWorld() == destination.getWorld() && source.getChannelServer() == destination.getChannelServer()
                && portal.getType() == Portal.MAP_PORTAL && portal.getPortalStatus()
                && (portal.getScriptName() == null || portal.getScriptName().isBlank())
                && portal.getTargetMapId() == destination.getId();
    }

    boolean cross(MapleMap source, MapleMap destination, Portal portal, Point arrival) {
        MapleMap expected = crossings.isEmpty() ? resident.getMap() : crossings.getLast().destination();
        if (!following || returned || source != expected || !canCross(source, destination, portal) || crossings.size() >= 8) {
            return false;
        }
        crossings.addLast(new Crossing(source, destination, new Point(portal.getPosition()), new Point(arrival)));
        mismatchSince = 0;
        return true;
    }

    boolean isFollowing() {
        return following;
    }

    void follow() {
        following = true;
        mismatchSince = 0;
    }

    void stay() {
        following = false;
        crossings.clear();
        crossingSince = 0;
        resident.waitHere();
    }

    /** Returns false when the reservation has ended and can be removed. */
    boolean tick(long now) {
        if (returned) {
            return false;
        }
        if (!leader.isLoggedinWorld() || !leader.isAlive()) {
            returnHome();
            return false;
        }
        MapleMap expected = crossings.isEmpty() ? resident.getMap() : crossings.getLast().destination();
        if (leader.getMap() != expected) {
            // Map entry fires before the successful portal callback. Leave a short
            // grace period, but never follow arbitrary warps, deaths, or channels.
            if (mismatchSince == 0) {
                mismatchSince = now;
            } else if (now - mismatchSince > 3000) {
                leader.dropMessage(5, resident.getName() + " headed back to Henesys. You can meet again there.");
                returnHome();
                return false;
            }
            return true;
        }
        mismatchSince = 0;
        if (!following) {
            return true;
        }
        Crossing crossing = crossings.peekFirst();
        if (crossing != null) {
            if (crossingSince == 0) {
                crossingSince = now;
            }
            resident.setFollowTarget(crossing.exit(), false);
            Point position = resident.getPosition();
            boolean atExit = Math.abs(position.x - crossing.exit().x) <= 85 && Math.abs(position.y - crossing.exit().y) <= 25;
            boolean catchUp = now - crossingSince > 30_000;
            if (atExit || catchUp) {
                if (catchUp) {
                    leader.dropMessage(5, resident.getName() + " found another way to the portal and is catching up.");
                }
                transfer.move(resident, crossing.destination(), crossing.arrival());
                crossings.removeFirst();
                crossingSince = 0;
                leader.dropMessage(5, resident.getName() + " followed you through the portal.");
            }
        } else {
            resident.setFollowTarget(followTarget());
        }
        return true;
    }

    private Point followTarget() {
        MapleMap currentMap = leader.getMap();
        if (lastTargetMap != currentMap) {
            lastTargetMap = currentMap;
            lastFollowTarget = null;
        }
        Point position = leader.getPosition();
        Point target = position;
        if ((leader.getStance() & ~1) == 6) {
            // Knockback can lift a player closer to the underside of another
            // platform without clearing it. Follow the landing surface below
            // their feet, not the nearest platform above them. Actual jumps
            // select the upper platform once the player's feet have cleared it.
            target = CompanionNavigation.forMap(currentMap)
                    .groundBelow(new Point(position.x, position.y - 3), 600)
                    .map(CompanionNavigation.Ground::position)
                    .orElseGet(() -> lastFollowTarget == null ? resident.getPosition() : new Point(lastFollowTarget));
        }
        // Ladder and rope coordinates remain live targets; their grounded
        // route endpoints are selected by the navigation planner as before.
        lastFollowTarget = new Point(target);
        return target;
    }

    void returnHome() {
        if (returned) {
            return;
        }
        resident.clearFollowTarget();
        if (resident.getMap() != homeMap || resident.getPosition().distanceSq(homePosition) > 8 * 8) {
            transfer.move(resident, homeMap, homePosition);
        }
        // A round trip resets the actor's wander anchor to the arrival portal.
        // Restore it even when the player has already walked the companion home.
        resident.restoreHomeAnchor(homePosition);
        crossings.clear();
        returned = true;
    }
}
