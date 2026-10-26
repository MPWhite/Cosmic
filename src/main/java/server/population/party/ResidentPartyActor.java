package server.population.party;

import client.BuffStat;
import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.status.MonsterStatus;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Portal;
import server.population.ResidentProfiles;
import server.population.TownResident;
import server.population.navigation.CompanionNavigation;
import server.population.navigation.CompanionNavigation.Leg;
import tools.PacketCreator;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Physical control of a temporary party character. All inventory, kills and drops use player rules. */
public final class ResidentPartyActor implements AutoCloseable {
    private static final Set<ResidentPartyActor> ACTIVE = ConcurrentHashMap.newKeySet();
    private static final long HIT_IMMUNITY_MILLIS = 2_000;
    private static final Set<Integer> COMBAT_MAPS = Set.of(103000800, 103000804, 103000805);
    private static final Set<Integer> MONSTERS = Set.of(9300000, 9300001, 9300002, 9300003, 9300004,
            100100, 100101, 1110100, 1110101, 1120100, 1130100, 130100, 130101, 210100, 2110200);

    private final Character character;
    private final ResidentProfiles.Profile profile;
    private final Random random;
    private final ConcurrentLinkedQueue<Incoming> incoming = new ConcurrentLinkedQueue<>();
    private TownResident mover;
    private volatile MapleMap map;
    private Point goal;
    private boolean ropeGoal;
    private boolean hunting;
    private int collectItem, collectCount;
    private long nextAttack, nextSearch, nextPickup, nextPotion, invulnerableUntil, nextRoute;
    private volatile Monster target;
    private volatile MapItem pursuedDrop;
    private Swing swing;
    private Portal routePortal;
    private Point routeGoal;
    private volatile boolean closed;

    private record Swing(Monster monster, MapleMap map, int damage, long due) { }
    private record Incoming(Monster monster, MapleMap map, Point origin, boolean left,
                            AttackRegion attack, int index, long due) { }
    private record AttackRegion(Rectangle range, int power, int delay, boolean groundOnly) { }
    private record MobData(int evade, boolean contact, Rectangle body, Map<Integer, AttackRegion> attacks) { }
    private static final Map<Integer, MobData> MOB_DATA = new ConcurrentHashMap<>();
    private static final class MobSource {
        static final DataProvider DATA = DataProviderFactory.getDataProvider(WZFiles.MOB);
    }
    private record Attack(SendOpcode opcode, int skill, int level, int projectile, int range,
                          int hits, int mp, double multiplier, int magicPower) { }

    public ResidentPartyActor(Character character, ResidentProfiles.Profile profile) {
        this.character = Objects.requireNonNull(character);
        if (!character.isResidentBot()) throw new IllegalArgumentException("Only resident characters can be automated");
        Objects.requireNonNull(profile);
        var currentProfile = ResidentPartyCharacter.profile(character);
        this.profile = currentProfile == null ? profile : currentProfile;
        random = new Random(character.getId() * 971L);
        synchronizeMap();
        ACTIVE.add(this);
    }

    public Character character() { return character; }
    public TownResident actor() { return mover; }

    /** A controller may call this every tick without resetting an in-flight jump. */
    public synchronized void moveTo(Point point) {
        setGoal(point, false);
        hunting = false;
        collectItem = collectCount = 0;
        releaseClaims();
    }

    public synchronized void holdRope(Point point) {
        setGoal(point, true);
        hunting = false;
        collectItem = collectCount = 0;
        releaseClaims();
    }

    public synchronized void hold() {
        goal = null;
        routePortal = null;
        hunting = false;
        collectItem = collectCount = 0;
        releaseClaims();
        if (mover != null) mover.waitHere();
    }

    public synchronized void hunt(boolean enabled) {
        hunting = enabled;
        if (enabled) goal = null;
        else releaseClaims();
    }

    /** Gather only the requested item, up to the real required inventory count. */
    public synchronized void collect(int itemId, int desiredCount) {
        if (collectItem != itemId || collectCount != Math.max(0, desiredCount)) releaseClaims();
        collectItem = itemId;
        collectCount = Math.max(0, desiredCount);
        hunting = true;
        goal = null;
    }

    public synchronized boolean arrived(Point point, int tolerance) {
        return character.getPosition().distanceSq(point) <= (long) tolerance * tolerance;
    }

    public synchronized boolean holdingRope() {
        return mover != null && mover.isHoldingClimb();
    }

    /** NPCs/portals own map changes. The controller can explicitly resync after a same-map portal. */
    public synchronized void resynchronize() {
        map = null;
        synchronizeMap();
    }

    private void setGoal(Point point, boolean rope) {
        if (goal == null || !goal.equals(point) || ropeGoal != rope) {
            goal = new Point(point);
            ropeGoal = rope;
            routePortal = null;
            routeGoal = null;
            nextRoute = 0;
        }
    }

    public synchronized void tick(long now) {
        if (closed || character.getMap() == null || character.isChangingMaps()) {
            releaseClaims();
            return;
        }
        synchronizeMap();
        if (!character.isAlive()) {
            swing = null;
            releaseClaims();
            mover.waitHere();
            incoming.clear();
            return;
        }
        potions(now);
        takeIncoming(now);
        contactDamage(now);
        if (!character.isAlive()) {
            releaseClaims();
            return;
        }
        if (swing != null && now >= swing.due) {
            Swing impact = swing;
            swing = null;
            if (impact.map == map && eligible(impact.monster) && impact.damage > 0) {
                map.damageMonster(character, impact.monster, impact.damage);
            }
        }
        if (goal != null) navigate(goal, ropeGoal, now);
        else if (hunting && combatAllowed()) {
            boolean gathering = collectItem > 0 && character.countItem(collectItem) < collectCount;
            if (!gathering || !pickup(now)) fight(now);
        }
        mover.tick(now);
        Point position = mover.getPosition();
        if (!position.equals(character.getPosition())) {
            character.setPosition(position);
            map.movePlayer(character, position);
        }
        character.setStance(mover.getStance());
    }

    private void synchronizeMap() {
        if (map == character.getMap() && mover != null) return;
        releaseClaims();
        map = character.getMap();
        if (map == null) return;
        mover = new TownResident(character.getId(), profile, map, character.getPosition());
        mover.waitHere();
        character.setPosition(mover.getPosition());
        routePortal = null;
        routeGoal = null;
        nextRoute = 0;
        swing = null;
        incoming.clear();
    }

    private void navigate(Point destination, boolean rope, long now) {
        if (rope) {
            mover.setClimbTarget(destination);
            return;
        }
        if (routePortal != null && arrived(routePortal.getPosition(), 26)) {
            Portal portal = routePortal;
            routePortal = null;
            portal.enterPortal(character.getClient());
            resynchronize();
            return;
        }
        if (now >= nextRoute && (routeGoal == null || routeGoal.distanceSq(destination) > 24 * 24
                || routePortal == null && mover.getNavigationStatus().equals("waiting for a route"))) {
            nextRoute = now + 1_000;
            routeGoal = new Point(destination);
            if (!mover.isHoldingClimb()) {
                routePortal = firstPortal(map, mover.getPosition(), destination).orElse(null);
            }
        }
        mover.setFollowTarget(routePortal == null ? destination : routePortal.getPosition(), false);
    }

    /** Ordinary same-map portals are real graph edges, including the two pairs in KPQ's final map. */
    static Optional<Portal> firstPortal(MapleMap map, Point from, Point target) {
        CompanionNavigation navigation = CompanionNavigation.forMap(map);
        if (navigation.plan(from, target).isPresent()) return Optional.empty();
        List<Portal> portals = map.getPortals().stream()
                .filter(p -> p.getTargetMapId() == map.getId() && p.getScriptName() == null
                        && p.getPortalStatus() && map.getPortal(p.getTarget()) != null)
                .toList();
        record Route(Point at, Portal first, Set<Integer> visited) { }
        ArrayDeque<Route> pending = new ArrayDeque<>();
        pending.add(new Route(from, null, Set.of()));
        while (!pending.isEmpty()) {
            Route route = pending.removeFirst();
            if (route.first != null && navigation.plan(route.at, target).isPresent()) return Optional.of(route.first);
            if (route.visited.size() >= 8) continue;
            for (Portal portal : portals) {
                if (route.visited.contains(portal.getId()) || navigation.plan(route.at, portal.getPosition()).isEmpty()) continue;
                var visited = new java.util.HashSet<>(route.visited);
                visited.add(portal.getId());
                Point exit = map.getPortal(portal.getTarget()).getPosition();
                Optional<CompanionNavigation.Ground> ground = navigation.project(exit, 160);
                if (ground.isPresent()) pending.addLast(new Route(ground.get().position(),
                        route.first == null ? portal : route.first, Set.copyOf(visited)));
            }
        }
        return Optional.empty();
    }

    private boolean pickup(long now) {
        if (now < nextPickup || swing != null) return false;
        Point position = character.getPosition();
        List<MapItem> drops = map.getMapObjects().stream().filter(MapItem.class::isInstance)
                .map(MapItem.class::cast).filter(d -> d.getItemId() == collectItem && !d.isPickedUp()
                        && d.canBePickedBy(character) && now - d.getDropTime() >= 550
                        && (collectItem != 4001008 || !d.isPlayerDrop() || character.isPartyLeader())
                        && d.getItem() != null && d.getItem().getQuantity() <= collectCount - character.countItem(collectItem))
                .filter(d -> !dropClaimedByOther(d)).toList();
        if (pursuedDrop == null || !drops.contains(pursuedDrop)) {
            pursuedDrop = drops.stream().min(Comparator.comparingDouble((MapItem d) -> pursuitCost(position, d.getPosition()))
                    .thenComparingInt(MapItem::getObjectId)).orElse(null);
        }
        if (pursuedDrop == null) return false;
        target = null;
        MapItem drop = pursuedDrop;
        if (arrived(drop.getPosition(), 40)) {
            character.pickupItem(drop);
            nextPickup = now + 300 + random.nextInt(200);
            pursuedDrop = null;
            return true;
        }
        navigate(drop.getPosition(), false, now);
        return true;
    }

    private void releaseClaims() {
        target = null;
        pursuedDrop = null;
    }

    // The party service serializes decisions. Volatile snapshots let peers inspect
    // commitments without acquiring another actor's monitor or its inventory lock.
    private boolean monsterClaimedByOther(Monster monster) {
        return ACTIVE.stream().anyMatch(other -> other != this && !other.closed
                && other.map == map && other.target == monster);
    }

    private boolean dropClaimedByOther(MapItem drop) {
        return ACTIVE.stream().anyMatch(other -> other != this && !other.closed
                && other.map == map && other.pursuedDrop == drop);
    }

    private static double pursuitCost(Point from, Point to) {
        // A nearby platform often needs a ladder detour. Within attack height,
        // distance is unchanged; larger vertical gaps get a modest extra cost.
        return from.distanceSq(to) + Math.max(0, Math.abs(from.y - to.y) - 55) * 400.0;
    }

    private boolean combatAllowed() {
        return map.getEventInstance() != null && COMBAT_MAPS.contains(map.getId());
    }

    private boolean eligible(Monster mob) {
        return combatAllowed() && mob != null && mob.getMap() == map && mob.isAlive()
                && !mob.getStats().isFriendly() && MONSTERS.contains(mob.getId())
                && map.getMonsterByOid(mob.getObjectId()) == mob;
    }

    private void fight(long now) {
        if (collectItem > 0 && character.countItem(collectItem) >= collectCount) {
            releaseClaims();
            mover.waitHere();
            return;
        }
        if (!eligible(target)) target = null;
        if (now >= nextSearch && (target == null || monsterClaimedByOther(target))) {
            nextSearch = now + 700;
            Point position = character.getPosition();
            List<Monster> monsters = map.getAllMonsters().stream().filter(this::eligible).toList();
            Comparator<Monster> nearest = Comparator.comparingDouble((Monster m) -> pursuitCost(position, m.getPosition()))
                    .thenComparingInt(Monster::getObjectId);
            target = monsters.stream().filter(m -> !monsterClaimedByOther(m)).min(nearest)
                    .orElseGet(() -> target != null ? target : monsters.stream().min(nearest).orElse(null));
        }
        if (target == null || swing != null) return;
        Attack attack = attack();
        Point position = character.getPosition();
        Point monsterPosition = target.getPosition();
        if (Math.abs(position.x - monsterPosition.x) > attack.range || Math.abs(position.y - monsterPosition.y) > 55) {
            int offset = attack.range <= 90 ? 32 : 110;
            int x = monsterPosition.x + (position.x < monsterPosition.x ? -offset : offset);
            Point approach = CompanionNavigation.forMap(map).project(new Point(x, monsterPosition.y), 60)
                    .map(CompanionNavigation.Ground::position).orElse(monsterPosition);
            navigate(approach, false, now);
            return;
        }
        if (now < nextAttack || !mover.holdAction(monsterPosition, now, 850)) return;
        if (attack.mp > character.getMp()) return;
        if (attack.projectile != 0 && !consumeProjectile(attack.projectile, attack.hits)) return;
        character.addMP(-attack.mp);
        int[] damage = damageLines(target, attack);
        int total = java.util.Arrays.stream(damage).sum();
        map.broadcastMessage(attackPacket(character.getId(), attack, monsterPosition.x < position.x,
                target.getObjectId(), damage));
        swing = new Swing(target, map, total, now + 700);
        nextAttack = now + 1_000 + random.nextInt(300);
    }

    private Attack attack() {
        Item equippedWeapon = character.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        int weapon = equippedWeapon == null ? 0 : equippedWeapon.getItemId() / 10_000;
        int skillId = switch (weapon) {
            case 137, 138 -> 2001005;
            case 145, 146 -> 3001005;
            case 147 -> 4001344;
            case 133 -> 4001334;
            case 149 -> 5001003;
            case 148 -> 5001001;
            default -> character.getJob().getId() / 100 == 1 ? 1001004 : 0;
        };
        Skill skill = skillId == 0 ? null : SkillFactory.getSkill(skillId);
        int level = skill == null ? 0 : character.getSkillLevel(skill);
        StatEffect effect = level == 0 ? null : skill.getEffect(level);
        if (effect != null && character.getMp() < effect.getMpCon()) { effect = null; level = 0; }
        boolean magic = weapon == 137 || weapon == 138;
        int projectile = switch (weapon) {
            case 145 -> 2060000;
            case 146 -> 2061000;
            case 147 -> 2070000;
            case 149 -> 2330000;
            default -> 0;
        };
        if (projectile != 0 && character.countItem(projectile) < 2) {
            projectile = 0;
            effect = null;
            level = 0;
        }
        SendOpcode opcode = magic && effect != null ? SendOpcode.MAGIC_ATTACK
                : projectile != 0 ? SendOpcode.RANGED_ATTACK : SendOpcode.CLOSE_RANGE_ATTACK;
        int hits = effect == null ? 1 : Math.max(1, projectile != 0 ? effect.getBulletCount() : effect.getAttackCount());
        return new Attack(opcode, level == 0 ? 0 : skillId, level, projectile,
                opcode == SendOpcode.CLOSE_RANGE_ATTACK ? 80 : 230, Math.min(6, hits),
                effect == null ? 0 : effect.getMpCon(), effect == null ? 1.0 : effect.getDamage() / 100.0,
                effect == null ? 0 : effect.getMatk());
    }

    private int[] damageLines(Monster mob, Attack attack) {
        boolean magic = attack.opcode == SendOpcode.MAGIC_ATTACK;
        int[] lines = new int[attack.hits];
        if (mob.isBuffed(magic ? MonsterStatus.MAGIC_IMMUNITY : MonsterStatus.WEAPON_IMMUNITY)
                || mob.isBuffed(magic ? MonsterStatus.MAGIC_REFLECT : MonsterStatus.WEAPON_REFLECT)) return lines;
        int levelDelta = Math.max(0, mob.getLevel() - character.getLevel());
        double accuracy = character.getTotalDex() * .8 + character.getTotalLuk() * .5 + equipmentStat(true);
        double hitChance = magic ? Math.min(1, (character.getTotalInt() / 10.0 + character.getTotalLuk() / 10.0)
                / Math.max(1, mobData(mob.getId()).evade * (1 + levelDelta * .02)))
                : Math.max(.01, Math.min(1, accuracy / ((1.84 + .07 * levelDelta) * mobData(mob.getId()).evade + 1)));
        double maximum;
        if (magic) {
            double matk = character.getTotalMagic();
            maximum = ((matk * matk / 1000 + matk) / 30 + character.getTotalInt() / 200.0)
                    * Math.max(1, attack.magicPower);
        } else {
            maximum = character.calculateMaxBaseDamage(character.getTotalWatk()) * Math.max(.1, attack.multiplier);
        }
        double defense = magic ? mob.getStats().MDDamage * (1 + .01 * levelDelta) : mob.getStats().PDDamage;
        if (!magic) maximum *= Math.max(.2, 1 - .01 * levelDelta);
        for (int i = 0; i < lines.length; i++) {
            if (random.nextDouble() < hitChance) lines[i] = Math.max(1,
                    (int) (maximum * (.45 + random.nextDouble() * .55) - defense * (.5 + random.nextDouble() * .1)));
        }
        return lines;
    }

    private boolean consumeProjectile(int itemId, int count) {
        Item item = character.getInventory(InventoryType.USE).findById(itemId);
        if (item == null || item.getQuantity() < count) return false;
        InventoryManipulator.removeFromSlot(character.getClient(), InventoryType.USE, item.getPosition(),
                (short) count, false, true);
        return true;
    }

    private int equipmentStat(boolean accuracy) {
        int value = 0;
        for (Item item : character.getInventory(InventoryType.EQUIPPED).list()) {
            if (item instanceof Equip equip) value += accuracy ? equip.getAcc() : equip.getWdef();
        }
        return value;
    }

    private void potions(long now) {
        if (now < nextPotion) return;
        int itemId = character.getHp() < character.getCurrentMaxHp() * .45 ? 2000002
                : character.getMp() < character.getCurrentMaxMp() * .3 ? 2000003 : 0;
        if (itemId == 0) return;
        Item potion = character.getInventory(InventoryType.USE).findById(itemId);
        if (potion == null || potion.getQuantity() <= 0) return;
        InventoryManipulator.removeFromSlot(character.getClient(), InventoryType.USE, potion.getPosition(), (short) 1, false);
        ItemInformationProvider.getInstance().getItemEffect(itemId).applyTo(character);
        nextPotion = now + 700;
    }

    private void contactDamage(long now) {
        if (!combatAllowed() || now < invulnerableUntil) return;
        Rectangle body = playerBody(character.getPosition());
        for (Monster monster : map.getAllMonsters()) {
            if (!eligible(monster)) continue;
            MobData data = mobData(monster.getId());
            Rectangle monsterBody = new Rectangle(data.body);
            monsterBody.translate(monster.getPosition().x, monster.getPosition().y);
            if (data.contact && monsterBody.intersects(body)) {
                receiveDamage(monster, -1, monster.getStats().PADamage, monster.getPosition(), now);
                break;
            }
        }
    }

    /** Called only for a validated monster attack animation from the ordinary client controller. */
    public static void onMonsterAttack(Monster monster, int index) {
        if (monster == null || monster.getMap() == null || !COMBAT_MAPS.contains(monster.getMap().getId())) return;
        AttackRegion attack = mobData(monster.getId()).attacks.get(index);
        if (attack == null) return;
        long now = System.currentTimeMillis();
        Point position = new Point(monster.getPosition());
        for (ResidentPartyActor actor : ACTIVE) {
            if (!actor.closed && actor.character.getMap() == monster.getMap() && actor.incoming.size() < 32) {
                actor.incoming.add(new Incoming(monster, monster.getMap(), position, monster.isFacingLeft(),
                        attack, index, now + attack.delay));
            }
        }
    }

    private void takeIncoming(long now) {
        for (Incoming hit : incoming) {
            if (now < hit.due) continue;
            if (!incoming.remove(hit) || hit.map != map || !hit.monster.isAlive()) continue;
            if (hit.attack.groundOnly && mover.getFootholdId() == 0) continue;
            Rectangle range = new Rectangle(hit.attack.range);
            if (!hit.left) range.x = -range.x - range.width;
            range.translate(hit.origin.x, hit.origin.y);
            if (range.intersects(playerBody(character.getPosition()))) {
                receiveDamage(hit.monster, hit.index, hit.attack.power, hit.origin, now);
            }
        }
    }

    private void receiveDamage(Monster monster, int attackIndex, int power, Point origin, long now) {
        if (now < invulnerableUntil || !character.isAlive()) return;
        int defense = Math.max(1, equipmentStat(false));
        // This is the same physical incoming-damage rule used by this project's browser client.
        int damage = Math.max(1, power / 2 + power / defense);
        Integer guard = character.getBuffedValue(BuffStat.MAGIC_GUARD);
        int mpLoss = guard == null ? 0 : Math.min(character.getMp(), damage * guard / 100);
        character.addMPHP(-(damage - mpLoss), -mpLoss);
        invulnerableUntil = now + HIT_IMMUNITY_MILLIS;
        if (character.isAlive()) mover.knockback(origin.x < character.getPosition().x ? 1 : -1, now);
        map.broadcastMessage(PacketCreator.damagePlayer(attackIndex, monster.getId(), character.getId(), damage,
                0, origin.x < character.getPosition().x ? 1 : 0, false, 0, false,
                monster.getObjectId(), 0, 0));
    }

    private static Rectangle playerBody(Point point) { return new Rectangle(point.x - 12, point.y - 48, 24, 48); }

    private static MobData mobData(int monsterId) {
        return MOB_DATA.computeIfAbsent(monsterId, id -> {
            Data data = MobSource.DATA.getData(String.format("%07d.img", id));
            Data info = data.getChildByPath("info");
            Rectangle body = rectangle(data.getChildByPath("stand/0"), new Rectangle(-25, -45, 50, 45));
            Map<Integer, AttackRegion> attacks = new HashMap<>();
            for (int i = 0; i < 9; i++) {
                Data attack = data.getChildByPath("attack" + (i + 1) + "/info");
                if (attack != null) attacks.put(i, new AttackRegion(rectangle(attack.getChildByPath("range"), body),
                        DataTool.getInt("PADamage", attack, DataTool.getInt("PADamage", info, 1)),
                        DataTool.getInt("attackAfter", attack, 600), DataTool.getInt("jumpAttack", attack, 0) != 0));
            }
            return new MobData(DataTool.getInt("eva", info, 0), DataTool.getInt("bodyAttack", info, 1) != 0,
                    body, Map.copyOf(attacks));
        });
    }

    private static Rectangle rectangle(Data data, Rectangle fallback) {
        if (data == null || data.getChildByPath("lt") == null || data.getChildByPath("rb") == null) return new Rectangle(fallback);
        Point lt = DataTool.getPoint(data.getChildByPath("lt"));
        Point rb = DataTool.getPoint(data.getChildByPath("rb"));
        return new Rectangle(lt.x, lt.y, Math.max(1, rb.x - lt.x + 1), Math.max(1, rb.y - lt.y + 1));
    }

    private static Packet attackPacket(int id, Attack attack, boolean left, int monsterOid, int[] damage) {
        OutPacket packet = OutPacket.create(attack.opcode);
        packet.writeInt(id);
        packet.writeByte(0x10 | damage.length);
        packet.writeByte(0x5B);
        packet.writeByte(attack.level);
        if (attack.skill != 0) packet.writeInt(attack.skill);
        packet.writeByte(0);
        packet.writeByte(left ? 1 : 0);
        packet.writeByte(0);
        packet.writeByte(6);
        packet.writeByte(0x0A);
        packet.writeInt(attack.projectile);
        packet.writeInt(monsterOid);
        packet.writeByte(0);
        for (int line : damage) packet.writeInt(line);
        if (attack.opcode == SendOpcode.RANGED_ATTACK) packet.writeInt(0);
        return packet;
    }

    @Override public synchronized void close() {
        closed = true;
        ACTIVE.remove(this);
        incoming.clear();
        swing = null;
        releaseClaims();
        if (mover != null) mover.waitHere();
    }
}
