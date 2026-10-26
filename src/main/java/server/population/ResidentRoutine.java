package server.population;

import client.Character;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.maps.Portal;
import server.population.ResidentDialogue.Activity;
import server.population.navigation.CompanionNavigation;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** One continuing activity per resident, with independently paced decisions on the shared scheduler. */
final class ResidentRoutine {
    private final TownResident resident;
    private final MapleMap homeMap;
    private final Point home;
    private final Random random;
    private final ResidentCombat combat;
    private final CompanionTravel.Transfer transfer;
    private Activity activity = Activity.IDLE;
    private Activity arrivalActivity = Activity.IDLE;
    private Point destination;
    private Point lookAt;
    private long decisionAt;
    private long destinationUntil;
    private long huntUntil;
    private long nextReturnAttempt;
    private long nextTrip;
    private long conversationUntil;
    private boolean initialized;
    private boolean suspended;
    private boolean onTrip;
    private Portal exit;
    private ResidentLoot loot;
    private int collected;

    ResidentRoutine(TownResident resident, CompanionTravel.Transfer transfer) {
        this(resident, transfer, new ResidentCombat(resident));
    }

    ResidentRoutine(TownResident resident, CompanionTravel.Transfer transfer, ResidentCombat combat) {
        this.resident = resident;
        this.homeMap = resident.getMap();
        this.home = resident.getPosition();
        this.transfer = transfer;
        random = new Random(resident.getId() * 104729L + homeMap.getId());
        this.combat = combat;
    }

    ResidentDialogue.Context context(boolean following) {
        return new ResidentDialogue.Context(following && activity != Activity.TRAINING ? Activity.FOLLOWING : activity,
                resident.getMap().getMapName(), following);
    }

    boolean travelling() {
        return exit != null;
    }

    int collected() {
        return collected;
    }

    void maintain(long now) {
        if (loot != null && loot.expired(resident, now)) {
            expireLoot();
            if (activity == Activity.LOOTING) {
                activity = Activity.TRAINING;
            }
        }
    }

    void conversation(long until) {
        conversationUntil = Math.max(conversationUntil, until);
        combat.stop(); // a typing pause must not leave an old attack waiting to hit seconds later
    }

    void suspend() {
        if (!suspended) {
            suspended = true;
            destination = null;
            exit = null;
            combat.stop();
            expireLoot();
            resident.waitHere();
        }
    }

    void assist(long now, Character leader, boolean enabled) {
        activity = enabled ? Activity.FOLLOWING : Activity.WAITING;
        if (enabled && now >= conversationUntil && combat.tick(now, leader)) {
            activity = Activity.TRAINING;
        } else if (!enabled) {
            combat.stop();
        }
    }

    void tick(long now) {
        if (!initialized || suspended) {
            initialized = true;
            suspended = false;
            destination = null;
            exit = null;
            onTrip = false;
            activity = Activity.IDLE;
            decisionAt = now + 1200 + random.nextInt(11_000);
            nextTrip = now + (resident.getProfile().id() == 16 ? 15_000 : 65_000);
            resident.waitHere();
        }
        maintain(now);
        if (now < conversationUntil) {
            return;
        }
        if (exit != null) {
            tickPortal(now);
            return;
        }
        if (destination != null) {
            if (resident.getFootholdId() != 0 && resident.getPosition().distanceSq(destination) < 18 * 18) {
                destination = null;
                resident.waitHere();
                if (lookAt != null) {
                    resident.faceToward(lookAt);
                }
                activity = arrivalActivity;
                decisionAt = now + (activity == Activity.SHOPPING ? 12_000 : 18_000) + random.nextInt(28_000);
            } else if (now >= destinationUntil) {
                // An unavailable route is a change of plans, never a visible teleport.
                destination = null;
                resident.waitHere();
                activity = Activity.RESTING;
                decisionAt = now + 8_000 + random.nextInt(10_000);
            }
            return;
        }
        if (resident.getMap().getId() != TownPopulation.HENESYS) {
            tickField(now);
            return;
        }
        if (isCommuter() && now >= nextTrip && beginPortal(ResidentRoster.TRAINING_GROUND, now)) {
            return;
        }
        if (now >= decisionAt) {
            chooseTownActivity(now);
        }
    }

    private void tickField(long now) {
        if (loot != null) {
            activity = Activity.LOOTING;
            if (loot.collect(resident, now)) {
                if (collected < Integer.MAX_VALUE) {
                    collected++;
                }
                loot = null;
                resident.waitHere();
                activity = Activity.TRAINING;
            } else {
                resident.setFollowTarget(loot.getPosition(), false);
            }
            return;
        }
        if (onTrip && now >= huntUntil && now >= nextReturnAttempt) {
            nextReturnAttempt = now + 60_000;
            if (beginPortal(homeMap.getId(), now)) {
                return;
            }
        }
        if (activity == Activity.TRAINING) {
            if (now >= decisionAt) {
                combat.stop();
                resident.waitHere();
                activity = Activity.RESTING;
                decisionAt = now + 9_000 + random.nextInt(19_000);
                return;
            }
            boolean busy = combat.tick(now, null);
            ResidentCombat.Kill kill = combat.drainKill();
            if (kill != null) {
                loot = ResidentLoot.fromKill(resident, kill, now);
            }
            if (!busy && resident.isFollowing()) {
                resident.waitHere();
            }
            return;
        }
        if (now >= decisionAt) {
            activity = Activity.TRAINING;
            decisionAt = now + 42_000 + random.nextInt(48_000);
        }
    }

    private void chooseTownActivity(long now) {
        if (random.nextInt(100) < 34) {
            activity = Activity.RESTING;
            resident.waitHere();
            decisionAt = now + 25_000 + random.nextInt(45_000);
            return;
        }
        MapleMap map = resident.getMap();
        CompanionNavigation navigation = CompanionNavigation.forMap(map);
        List<MapObject> nearby = new ArrayList<>(map.getMapObjectsInRange(home, 1000 * 1000,
                List.of(MapObjectType.NPC)));
        nearby.sort(Comparator.comparingInt(MapObject::getObjectId));
        Point goal;
        if (!nearby.isEmpty() && random.nextBoolean()) {
            lookAt = nearby.get(random.nextInt(nearby.size())).getPosition();
            goal = new Point(lookAt.x + (random.nextBoolean() ? 1 : -1) * (40 + random.nextInt(75)), lookAt.y);
            arrivalActivity = Activity.SHOPPING;
        } else {
            lookAt = null;
            goal = new Point(home.x - 550 + random.nextInt(1101), home.y - 10);
            arrivalActivity = Activity.IDLE;
        }
        Point grounded = navigation.project(goal, 120).map(CompanionNavigation.Ground::position).orElse(null);
        if (grounded == null || grounded.distanceSq(resident.getPosition()) < 60 * 60
                || navigation.plan(resident.getPosition(), grounded).isEmpty()) {
            decisionAt = now + 7_000 + random.nextInt(10_000);
            resident.waitHere();
            activity = Activity.IDLE;
            return;
        }
        destination = grounded;
        destinationUntil = now + 25_000;
        resident.setFollowTarget(destination, false);
        activity = Activity.ROAMING;
    }

    private boolean isCommuter() {
        int id = resident.getProfile().id();
        return homeMap.getId() == TownPopulation.HENESYS && (id == 16 || id == 18);
    }

    private boolean beginPortal(int targetMap, long now) {
        exit = resident.getMap().getPortals().stream()
                .filter(p -> p.getType() == Portal.MAP_PORTAL && p.getTargetMapId() == targetMap
                        && p.getPortalStatus() && (p.getScriptName() == null || p.getScriptName().isBlank()))
                .min(Comparator.comparingDouble(p -> p.getPosition().distanceSq(resident.getPosition()))).orElse(null);
        if (exit == null || CompanionNavigation.forMap(resident.getMap()).plan(resident.getPosition(), exit.getPosition()).isEmpty()) {
            exit = null;
            nextTrip = now + 60_000;
            return false;
        }
        combat.stop();
        expireLoot();
        destination = null;
        destinationUntil = now + 35_000;
        resident.setFollowTarget(exit.getPosition(), false);
        activity = Activity.EXPLORING;
        return true;
    }

    private void tickPortal(long now) {
        Point position = resident.getPosition();
        if (Math.abs(position.x - exit.getPosition().x) <= 45 && Math.abs(position.y - exit.getPosition().y) <= 25
                && resident.getFootholdId() != 0) {
            MapleMap source = resident.getMap();
            MapleMap destinationMap = source.getChannelServer().getMapFactory().getMap(exit.getTargetMapId());
            Portal arrival = destinationMap.getPortal(exit.getTarget());
            if (arrival != null && CompanionTravel.canCross(source, destinationMap, exit)) {
                transfer.move(resident, destinationMap, arrival.getPosition());
                onTrip = destinationMap != homeMap;
                if (!onTrip) {
                    resident.restoreHomeAnchor(home);
                }
                activity = Activity.IDLE;
                decisionAt = now + 1000 + random.nextInt(3000);
                huntUntil = now + 90_000 + random.nextInt(120_000);
                nextReturnAttempt = huntUntil;
                nextTrip = now + 180_000 + random.nextInt(180_000);
                exit = null;
                resident.waitHere();
                return;
            }
        }
        if (now >= destinationUntil) {
            exit = null;
            resident.waitHere();
            activity = Activity.RESTING;
            decisionAt = now + 20_000;
            nextTrip = now + 90_000;
            nextReturnAttempt = now + 90_000;
        }
    }

    private void expireLoot() {
        if (loot != null) {
            loot.expire();
            loot = null;
        }
    }

    void close() {
        combat.stop();
        expireLoot();
    }
}
