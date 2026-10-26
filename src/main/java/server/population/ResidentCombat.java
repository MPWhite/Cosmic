package server.population;

import client.Character;
import client.status.MonsterStatus;
import net.opcodes.SendOpcode;
import server.life.Monster;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/** Small, server-authoritative fights. Residents are never players or EXP participants. */
public final class ResidentCombat {
    public enum Style {
        MELEE(SendOpcode.CLOSE_RANGE_ATTACK, 0, 0, 80, 1),
        BOW(SendOpcode.RANGED_ATTACK, 0, 2060000, 225, 1),
        CROSSBOW(SendOpcode.RANGED_ATTACK, 0, 2061000, 225, 1),
        MAGIC(SendOpcode.MAGIC_ATTACK, 2001005, 0, 210, 2);

        private final SendOpcode opcode;
        private final int skillId, projectileId, range, hits;

        Style(SendOpcode opcode, int skillId, int projectileId, int range, int hits) {
            this.opcode = opcode;
            this.skillId = skillId;
            this.projectileId = projectileId;
            this.range = range;
            this.hits = hits;
        }

        public SendOpcode opcode() { return opcode; }
        public int skillId() { return skillId; }
        public int projectileId() { return projectileId; }
    }

    public record Kill(int monsterId, Point position) {
        public Kill { position = new Point(position); }
        @Override public Point position() { return new Point(position); }
    }

    private record Swing(MapleMap map, Monster monster, int damage, long impactAt,
                         Character leader, Set<Integer> assistedPlayers) { }

    private final TownResident actor;
    private final Random random;
    private final Style style;
    private final int maxHealth;
    private int health;
    private Monster target;
    private Swing swing;
    private Kill kill;
    private long nextSearch, nextAttack, lastContact, recoverUntil, lastTick;
    private long pursuitStarted;
    private Point pursuitStart;
    private Monster avoided;
    private long avoidUntil;
    private String activity = "idle";

    public ResidentCombat(TownResident actor) {
        this.actor = Objects.requireNonNull(actor);
        random = new Random(actor.getId() * 31L + 7);
        int weapon = actor.getProfile().equipment().getOrDefault(11, 0) / 10_000;
        style = switch (weapon) {
            case 137, 138 -> Style.MAGIC;
            case 145 -> Style.BOW;
            case 146 -> Style.CROSSBOW;
            case 130, 131, 132, 133, 140, 141, 142, 143, 144 -> Style.MELEE;
            default -> null; // no pretend attack animation for unarmed or unsupported weapons
        };
        maxHealth = 70 + actor.getProfile().level() * 8;
        health = maxHealth;
    }

    public String getActivity() { return activity; }
    public int getHealthPercent() { return health * 100 / maxHealth; }

    /** Only exclusive autonomous kills produce private loot; assists use normal human ownership. */
    public Kill drainKill() {
        Kill result = kill;
        kill = null;
        return result;
    }

    /** Returns true while this controller owns approach, windup, or recovery movement. */
    public boolean tick(long now, Character leaderOrNull) {
        MapleMap map = actor.getMap();
        if (style == null || !safeMap(map) || !map.allowsResidentCombat(leaderOrNull)
                || leaderOrNull != null && (leaderOrNull.getMap() != map || !leaderOrNull.isAlive()
                || leaderOrNull.getPosition().distanceSq(actor.getPosition()) > 450L * 450)) {
            stop();
            return false;
        }
        if (lastTick != 0 && now - lastTick > 10_000) {
            health = maxHealth; // recover while the routine rests, rather than simulating empty maps
        }
        lastTick = now;
        Set<Integer> assisted = assistedPlayers(leaderOrNull, map);

        if (swing != null) {
            if (swing.map() != map || swing.leader() != leaderOrNull) {
                stop();
                return false;
            }
            if (now < swing.impactAt()) {
                return true;
            }
            applyImpact(now, assisted);
        }

        if (recoverUntil != 0) {
            if (now < recoverUntil) {
                activity = "taking a potion";
                return true;
            }
            recoverUntil = 0;
            health = Math.max(health, maxHealth * 3 / 4);
            lastContact = now;
            actor.emote(1);
        }

        if (target != null && (!eligible(target, map, leaderOrNull, now)
                || !target.claimResidentTarget(actor.getId(), now, assisted))) {
            releaseTarget();
        }
        if (target == null && now >= nextSearch && now >= nextAttack) {
            nextSearch = now + 600 + random.nextInt(301);
            Point position = actor.getPosition();
            // Distance-sort one snapshot; try at most twelve nearby candidates per search.
            List<Monster> nearby = map.getAllMonsters().stream()
                    .filter(mob -> eligible(mob, map, leaderOrNull, now))
                    .sorted(Comparator.comparingDouble(mob -> mob.getPosition().distanceSq(position)))
                    .limit(12).toList();
            for (Monster candidate : nearby) {
                if (candidate.claimResidentTarget(actor.getId(), now, assisted)) {
                    target = candidate;
                    pursuitStarted = now;
                    pursuitStart = position;
                    break;
                }
            }
        }
        if (target == null) {
            activity = "looking for monsters";
            return false;
        }

        Point position = actor.getPosition();
        Point monsterPosition = target.getPosition();
        if (now - lastContact >= 2_000 && position.distanceSq(monsterPosition) < 42L * 42) {
            lastContact = now;
            int damage = Math.max(2, target.getStats().PADamage / 3 + target.getLevel() / 2);
            health = Math.max(1, health - damage);
            map.broadcastMessage(PacketCreator.damagePlayer(-1, target.getId(), actor.getId(), damage,
                    0, monsterPosition.x < position.x ? 1 : 0, false, 0, false, 0, 0, 0));
            if (health * 100 / maxHealth < 30) {
                Point retreat = groundedApproach(map, new Point(position.x
                        + (monsterPosition.x <= position.x ? 95 : -95), position.y));
                releaseTarget();
                actor.setFollowTarget(retreat, false);
                actor.emote(5);
                recoverUntil = now + 2_400;
                activity = "taking a potion";
                return true;
            }
        }

        if (Math.abs(position.x - monsterPosition.x) > style.range
                || Math.abs(position.y - monsterPosition.y) > 38) {
            if (now - pursuitStarted > 12_000
                    || now - pursuitStarted > 5_000 && position.distanceSq(pursuitStart) < 20 * 20) {
                avoided = target;
                avoidUntil = now + 15_000;
                releaseTarget();
                activity = "looking for a reachable monster";
                return false;
            }
            int distance = style == Style.MELEE ? 38 : 125;
            int x = monsterPosition.x + (position.x < monsterPosition.x ? -distance : distance);
            actor.setFollowTarget(groundedApproach(map, new Point(x, monsterPosition.y)), false);
            activity = "approaching " + target.getName();
            return true;
        }

        activity = "fighting " + target.getName();
        if (now < nextAttack) {
            return true;
        }
        if (!actor.holdAction(monsterPosition, now, 1_100)) {
            return true;
        }
        int[] lines = damageLines(target);
        int totalDamage = 0;
        for (int line : lines) {
            totalDamage += line;
        }
        map.broadcastMessage(ResidentPackets.attack(actor.getId(), style,
                monsterPosition.x < position.x, target.getObjectId(), lines));
        // Foreign attacks are queued 400ms by the browser, then play their attack frames.
        swing = new Swing(map, target, totalDamage, now + 700, leaderOrNull, assisted);
        nextAttack = now + 1_150 + random.nextInt(451);
        return true;
    }

    public void stop() {
        releaseTarget();
        swing = null;
        kill = null;
        recoverUntil = 0;
        activity = "idle";
    }

    private void applyImpact(long now, Set<Integer> assisted) {
        Swing impact = swing;
        swing = null;
        Monster mob = impact.monster();
        if (impact.map().getMonsterByOid(mob.getObjectId()) != mob || !mob.isAlive()
                || !impact.assistedPlayers().equals(assisted)
                || !eligible(mob, impact.map(), impact.leader(), now)) {
            return;
        }
        Monster.ResidentHit hit = mob.damageByResident(actor.getId(), now, impact.damage(), assisted);
        if (hit.damage() <= 0) {
            return;
        }
        if (hit.killed()) {
            Point position = mob.getPosition();
            // null means no EXP, quest credit, drops, or player callbacks for autonomous kills.
            impact.map().killMonster(mob, impact.leader(), impact.leader() != null, (short) 0);
            if (impact.leader() == null) {
                kill = new Kill(mob.getId(), position);
            }
            releaseTarget();
            actor.holdAction(position, now, 350);
        } else if (impact.leader() != null) {
            mob.broadcastMobHpBar(impact.leader());
        }
    }

    private void releaseTarget() {
        if (target != null) {
            target.releaseResidentTarget(actor.getId());
            target = null;
        }
    }

    private boolean eligible(Monster mob, MapleMap map, Character leader, long now) {
        if (mob == null || mob.getMap() != map || !mob.isAlive() || mob.isBoss()
                || mob.getStats().isFriendly() || mob.getStats().selfDestruction() != null
                || !mob.getStats().getRevives().isEmpty() || mob.getCP() > 0 || mob.getBuffToGive() != -1
                || mob.getStats().getBanishInfo() != null || mob.getRemoveAfter() > 0
                || mob.getLevel() > actor.getProfile().level() + 5 || mob.getMaxHp() > 15_000
                || mob == avoided && now < avoidUntil) {
            return false;
        }
        if (mob.isBuffed(style == Style.MAGIC ? MonsterStatus.MAGIC_IMMUNITY : MonsterStatus.WEAPON_IMMUNITY)
                || mob.isBuffed(style == Style.MAGIC ? MonsterStatus.MAGIC_REFLECT : MonsterStatus.WEAPON_REFLECT)) {
            return false;
        }
        Point from = actor.getPosition();
        Point to = mob.getPosition();
        return Math.abs(from.x - to.x) <= 650 && Math.abs(from.y - to.y) <= 320
                && (leader == null || leader.getPosition().distanceSq(to) <= 330L * 330);
    }

    private int[] damageLines(Monster mob) {
        int defense = style == Style.MAGIC ? mob.getStats().MDDamage : mob.getStats().PDDamage;
        double base = (style == Style.MAGIC ? 10 : 5) + actor.getProfile().level() * 1.7;
        int total = Math.max(style.hits, (int) ((base - defense * 0.35) * (0.82 + random.nextDouble() * 0.36)));
        int[] lines = new int[style.hits];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = Math.max(1, total / lines.length + (i < total % lines.length ? 1 : 0));
        }
        return lines;
    }

    private static boolean safeMap(MapleMap map) {
        return !map.isTown() && !map.isEventMap() && !map.isCPQMap()
                && map.getEventInstance() == null && map.getId() < 900_000_000;
    }

    private static Set<Integer> assistedPlayers(Character leader, MapleMap map) {
        if (leader == null) {
            return Set.of();
        }
        Set<Integer> ids = new HashSet<>();
        ids.add(leader.getId());
        for (Character member : leader.getPartyMembersOnSameMap()) {
            if (member.getMap() == map && member.isAlive()) {
                ids.add(member.getId());
            }
        }
        return Set.copyOf(ids);
    }

    private static Point groundedApproach(MapleMap map, Point desired) {
        var foothold = map.getFootholds().findBelow(new Point(desired.x, desired.y - 8));
        if (foothold != null && !foothold.isWall()) {
            double ratio = (desired.x - foothold.getX1()) / (double) (foothold.getX2() - foothold.getX1());
            int y = (int) Math.round(foothold.getY1() + ratio * (foothold.getY2() - foothold.getY1()));
            if (Math.abs(y - desired.y) <= 48) {
                return new Point(desired.x, y);
            }
        }
        return new Point(desired);
    }
}
