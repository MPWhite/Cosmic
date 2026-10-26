package server.population.party;

import client.Character;
import client.Client;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.GenericPortal;
import server.maps.MapItem;
import server.maps.MapleMap;
import server.maps.Portal;
import server.population.ResidentProfiles;
import server.population.navigation.CompanionNavigation;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResidentPartyActorTest {
    @Test
    void allPuzzleTargetsAreReachableInsideTheActualScriptRegions() {
        for (int stage = 2; stage <= 4; stage++) {
            int mapId = 103000799 + stage;
            var navigation = CompanionNavigation.forMapId(mapId);
            Point rawSpawn = switch (stage) {
                case 2 -> new Point(-1295, 102);
                case 3 -> new Point(-235, 523);
                default -> new Point(759, 458);
            };
            Point spawn = navigation.project(rawSpawn, 160).orElseThrow().position();
            KpqPuzzle puzzle = new KpqPuzzle(stage);
            for (int slot = 0; slot < stage + 2; slot++) {
                Point target = puzzle.position(slot);
                var route = stage == 2 ? navigation.planToClimb(spawn, target, false)
                        : navigation.plan(spawn, target);
                assertTrue(route.isPresent(), "stage " + stage + " slot " + slot + " from " + spawn + " to " + target);
                Point landing = route.orElseThrow().getLast().to();
                assertEquals(slot, puzzle.slot(landing), "landing must satisfy the real NPC rectangle");
            }
        }
    }

    @Test
    void finalStageReturnsUpThroughRealInternalPortals() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(103000804);
        Portal top = portal(2, "in00", "in00_1", -222, -2179);
        Portal middleUp = portal(3, "in00_1", "in00", -225, -1759);
        Portal middleDown = portal(4, "in01", "in01_1", 444, -1460);
        Portal bottom = portal(5, "in01_1", "in01", 488, -743);
        for (Portal portal : List.of(top, middleUp, middleDown, bottom)) when(map.getPortal(portal.getName())).thenReturn(portal);
        when(map.getPortals()).thenReturn(List.of(top, middleUp, middleDown, bottom));
        var nav = CompanionNavigation.forMap(map);
        Point boss = nav.project(new Point(100, -435), 160).orElseThrow().position();
        Point leader = nav.project(new Point(383, -2715), 160).orElseThrow().position();
        assertTrue(nav.plan(boss, leader).isEmpty(), "the floor separation needs portals");
        assertSame(bottom, ResidentPartyActor.firstPortal(map, boss, leader).orElseThrow());
        Point middle = nav.project(middleDown.getPosition(), 160).orElseThrow().position();
        assertSame(middleUp, ResidentPartyActor.firstPortal(map, middle, leader).orElseThrow());
        Point upper = nav.project(top.getPosition(), 160).orElseThrow().position();
        assertTrue(nav.plan(upper, leader).isPresent());
    }

    @Test
    void pickupUsesPlayerOwnershipAndNeverExceedsTheRequestedCouponCount() {
        Fixture fixture = fixture();
        MapItem owned = drop(fixture, 1, true);
        MapItem oversized = drop(fixture, 2, true);
        MapItem foreign = drop(fixture, 1, false);
        when(fixture.character.countItem(4001007)).thenReturn(9);
        when(fixture.map.getMapObjects()).thenReturn(List.of(foreign, oversized, owned));
        try (ResidentPartyActor actor = new ResidentPartyActor(fixture.character, ResidentProfiles.all().get(15))) {
            actor.collect(4001007, 10);
            actor.tick(1_000);
            verify(fixture.character).pickupItem(owned);
            verify(fixture.character, never()).pickupItem(oversized);
            verify(fixture.character, never()).pickupItem(foreign);
            when(fixture.character.countItem(4001007)).thenReturn(10);
            actor.tick(2_000);
            verify(fixture.character, times(1)).pickupItem(any());
        }
    }

    @Test
    void attackWindupUsesNormalDamagePipelineAndMapChangeCancelsOldImpact() {
        Fixture fixture = fixture();
        MonsterStats stats = new MonsterStats();
        stats.hp = 1_000;
        stats.level = 32;
        stats.name = "Ligator";
        Monster monster = new Monster(9300001, stats);
        monster.setMap(fixture.map);
        monster.setObjectId(777);
        monster.setPosition(new Point(150, 100));
        when(fixture.map.getAllMonsters()).thenReturn(List.of(monster));
        when(fixture.map.getMonsterByOid(777)).thenReturn(monster);
        try (ResidentPartyActor actor = new ResidentPartyActor(fixture.character, ResidentProfiles.all().get(15))) {
            actor.hunt(true);
            actor.tick(1_000);
            verify(fixture.map, never()).damageMonster(any(), any(), anyInt());
            actor.tick(1_699);
            verify(fixture.map, never()).damageMonster(any(), any(), anyInt());
            actor.tick(1_700);
            verify(fixture.map).damageMonster(eq(fixture.character), eq(monster), intThat(n -> n > 0));
            actor.tick(3_000);
            MapleMap next = mock(MapleMap.class);
            FootholdTree sameGround = fixture.map.getFootholds();
            when(next.getFootholds()).thenReturn(sameGround);
            when(fixture.character.getMap()).thenReturn(next);
            actor.tick(3_700);
            verify(fixture.map, times(1)).damageMonster(any(), any(), anyInt());
        }
    }

    @Test
    void combatNeverRunsOutsideAnActualKpqEvent() {
        Fixture fixture = fixture();
        when(fixture.map.getEventInstance()).thenReturn(null);
        try (ResidentPartyActor actor = new ResidentPartyActor(fixture.character, ResidentProfiles.all().get(15))) {
            actor.hunt(true);
            actor.tick(1_000);
            actor.tick(20_000);
            verify(fixture.map, never()).damageMonster(any(), any(), anyInt());
            verify(fixture.map, never()).getAllMonsters();
        }
    }

    @Test
    void actorWaitsWhileTheOrdinaryCharacterMapTransferIsInProgress() {
        Fixture fixture = fixture();
        try (ResidentPartyActor actor = new ResidentPartyActor(fixture.character, ResidentProfiles.all().get(15))) {
            actor.hunt(true);
            when(fixture.character.isChangingMaps()).thenReturn(true);
            clearInvocations(fixture.map, fixture.character);

            actor.tick(1_000);

            verify(fixture.map, never()).getAllMonsters();
            verify(fixture.character, never()).setPosition(any());
            when(fixture.character.isChangingMaps()).thenReturn(false);
            actor.tick(1_050);
            verify(fixture.map, atLeastOnce()).getAllMonsters();
        }
    }

    @Test
    void actualBossAttackHasWindupRangeAndSharedInvulnerability() {
        Fixture fixture = fixture();
        MonsterStats stats = new MonsterStats();
        stats.hp = 8_000;
        stats.level = 40;
        stats.name = "King Slime";
        Monster boss = new Monster(9300003, stats);
        boss.setMap(fixture.map);
        boss.setObjectId(778);
        boss.setPosition(new Point(300, 100));
        long now = System.currentTimeMillis();
        try (ResidentPartyActor actor = new ResidentPartyActor(fixture.character, ResidentProfiles.all().get(15))) {
            ResidentPartyActor.onMonsterAttack(boss, 0);
            actor.tick(now + 800);
            verify(fixture.character, never()).addMPHP(anyInt(), anyInt());
            actor.tick(now + 1_000);
            verify(fixture.character, times(1)).addMPHP(intThat(n -> n < 0), eq(0));
            ResidentPartyActor.onMonsterAttack(boss, 0);
            actor.tick(now + 1_200);
            verify(fixture.character, times(1)).addMPHP(anyInt(), anyInt());
            fixture.character.setPosition(new Point(-500, 100));
            ResidentPartyActor.onMonsterAttack(boss, 0);
            actor.tick(now + 4_000);
            verify(fixture.character, times(1)).addMPHP(anyInt(), anyInt());
        }
    }

    @Test
    void teammatesAttackSeparateLiveMonstersUntilAClaimIsReleased() {
        Fixture first = fixture();
        Fixture second = fixture(first.map, 1_600_000_002);
        Monster near = monster(first.map, 801, new Point(150, 100));
        Monster farther = monster(first.map, 802, new Point(170, 100));
        when(first.map.getAllMonsters()).thenReturn(List.of(near, farther));
        try (var a = new ResidentPartyActor(first.character, ResidentProfiles.all().get(15));
             var b = new ResidentPartyActor(second.character, ResidentProfiles.all().get(15))) {
            a.hunt(true); b.hunt(true);
            a.tick(1_000); b.tick(1_000);
            a.tick(1_700); b.tick(1_700);
            verify(first.map).damageMonster(eq(first.character), eq(near), intThat(n -> n > 0));
            verify(first.map).damageMonster(eq(second.character), eq(farther), intThat(n -> n > 0));
            verify(first.map, never()).damageMonster(eq(second.character), eq(near), anyInt());

            a.moveTo(new Point(100, 100));
            b.hold(); b.hunt(true);
            b.tick(3_000); b.tick(3_700);
            verify(first.map).damageMonster(eq(second.character), eq(near), intThat(n -> n > 0));
        }
    }

    @Test
    void teammatesCanStillShareTheOnlyRemainingMonster() {
        Fixture first = fixture();
        Fixture second = fixture(first.map, 1_600_000_002);
        Monster last = monster(first.map, 803, new Point(150, 100));
        when(first.map.getAllMonsters()).thenReturn(List.of(last));
        try (var a = new ResidentPartyActor(first.character, ResidentProfiles.all().get(15));
             var b = new ResidentPartyActor(second.character, ResidentProfiles.all().get(15))) {
            a.hunt(true); b.hunt(true);
            a.tick(1_000); b.tick(1_000);
            a.tick(1_700); b.tick(1_700);
            verify(first.map).damageMonster(eq(first.character), eq(last), intThat(n -> n > 0));
            verify(first.map).damageMonster(eq(second.character), eq(last), intThat(n -> n > 0));
        }
    }

    @Test
    void nearbySameHeightMonsterAvoidsAnUnnecessaryPlatformTrip() {
        Fixture fixture = fixture();
        Monster upper = monster(fixture.map, 804, new Point(100, 160));
        Monster sameFloor = monster(fixture.map, 805, new Point(170, 100));
        when(fixture.map.getAllMonsters()).thenReturn(List.of(upper, sameFloor));
        try (var actor = new ResidentPartyActor(fixture.character, ResidentProfiles.all().get(15))) {
            actor.hunt(true);
            actor.tick(1_000); actor.tick(1_700);
            verify(fixture.map).damageMonster(eq(fixture.character), eq(sameFloor), intThat(n -> n > 0));
            verify(fixture.map, never()).damageMonster(any(), eq(upper), anyInt());
        }
    }

    @Test
    void collectorsPursueDifferentCouponsAndKeepTheirCommitment() {
        Fixture first = fixture();
        Fixture second = fixture(first.map, 1_600_000_002);
        MapItem near = drop(first, 1, true);
        MapItem farther = drop(first, 1, true);
        when(near.getPosition()).thenReturn(new Point(300, 100));
        when(farther.getPosition()).thenReturn(new Point(400, 100));
        when(near.canBePickedBy(second.character)).thenReturn(true);
        when(farther.canBePickedBy(second.character)).thenReturn(true);
        when(first.map.getMapObjects()).thenReturn(List.of(near, farther));
        try (var a = new ResidentPartyActor(first.character, ResidentProfiles.all().get(15));
             var b = new ResidentPartyActor(second.character, ResidentProfiles.all().get(15))) {
            a.collect(4001007, 10); b.collect(4001007, 10);
            a.tick(1_000); b.tick(1_000);
            // Passing a teammate's closer coupon does not abandon the selected drop.
            second.character.setPosition(new Point(300, 100));
            b.tick(1_050);
            verify(second.character, never()).pickupItem(any());
            first.character.setPosition(new Point(300, 100));
            second.character.setPosition(new Point(400, 100));
            a.tick(1_100); b.tick(1_100);
            verify(first.character).pickupItem(near);
            verify(second.character).pickupItem(farther);
            verify(second.character, never()).pickupItem(near);
        }
    }

    @Test
    void couponClaimsReleaseAfterPickupAttemptAndEveryInterruption() {
        for (String interruption : List.of("pickup", "hold", "move", "rope", "stop hunting", "new item", "map", "close")) {
            Fixture first = fixture();
            Fixture second = fixture(first.map, 1_600_000_002);
            MapItem coupon = drop(first, 1, true);
            when(coupon.getPosition()).thenReturn(new Point(300, 100));
            when(coupon.canBePickedBy(second.character)).thenReturn(true);
            when(first.map.getMapObjects()).thenReturn(List.of(coupon));
            try (var a = new ResidentPartyActor(first.character, ResidentProfiles.all().get(15));
                 var b = new ResidentPartyActor(second.character, ResidentProfiles.all().get(15))) {
                a.collect(4001007, 10); a.tick(1_000);
                b.collect(4001007, 10);
                second.character.setPosition(new Point(300, 100));
                b.tick(1_000);
                verify(second.character, never()).pickupItem(any());
                switch (interruption) {
                    case "pickup" -> {
                        // A failed ordinary pickup (e.g. full inventory) must not reserve the drop forever.
                        first.character.setPosition(new Point(300, 100));
                        a.tick(1_100);
                        verify(first.character).pickupItem(coupon);
                    }
                    case "hold" -> a.hold();
                    case "move" -> a.moveTo(new Point(100, 100));
                    case "rope" -> a.holdRope(new Point(100, 60));
                    case "stop hunting" -> a.hunt(false);
                    case "new item" -> a.collect(4001008, 1);
                    case "map" -> {
                        MapleMap next = mock(MapleMap.class);
                        FootholdTree ground = first.map.getFootholds();
                        when(next.getFootholds()).thenReturn(ground);
                        when(first.character.getMap()).thenReturn(next);
                        a.tick(1_100);
                    }
                    case "close" -> a.close();
                }
                second.character.setPosition(new Point(300, 100));
                b.tick(2_000);
                verify(second.character, description(interruption)).pickupItem(coupon);
            }
        }
    }

    private static Monster monster(MapleMap map, int oid, Point position) {
        MonsterStats stats = new MonsterStats();
        stats.hp = 1_000;
        stats.level = 32;
        stats.name = "Ligator";
        Monster monster = new Monster(9300001, stats);
        monster.setMap(map);
        monster.setObjectId(oid);
        monster.setPosition(position);
        when(map.getMonsterByOid(oid)).thenReturn(monster);
        return monster;
    }

    private static MapItem drop(Fixture fixture, int quantity, boolean own) {
        MapItem drop = mock(MapItem.class);
        when(drop.getItemId()).thenReturn(4001007);
        when(drop.getPosition()).thenReturn(new Point(105, 100));
        when(drop.getItem()).thenReturn(new Item(4001007, (short) 0, (short) quantity));
        when(drop.canBePickedBy(fixture.character)).thenReturn(own);
        return drop;
    }

    private static Fixture fixture() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(103000800);
        when(map.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        FootholdTree footholds = new FootholdTree(new Point(-1000, -1000), new Point(1000, 1000));
        footholds.insert(new Foothold(new Point(0, 100), new Point(500, 100), 1));
        when(map.getFootholds()).thenReturn(footholds);
        return fixture(map, 1_600_000_001);
    }

    private static Fixture fixture(MapleMap map, int id) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.isResidentBot()).thenReturn(true);
        when(chr.getJob()).thenReturn(Job.WARRIOR);
        when(chr.getMap()).thenReturn(map);
        when(chr.getClient()).thenReturn(mock(Client.class));
        when(chr.isAlive()).thenReturn(true);
        when(chr.getHp()).thenReturn(500);
        when(chr.getMp()).thenReturn(100);
        when(chr.getTotalDex()).thenReturn(500);
        when(chr.calculateMaxBaseDamage(anyInt())).thenReturn(200);
        when(chr.getInventory(InventoryType.EQUIPPED)).thenReturn(new Inventory(chr, InventoryType.EQUIPPED, (byte) 24));
        AtomicReference<Point> position = new AtomicReference<>(new Point(100, 100));
        when(chr.getPosition()).thenAnswer(invocation -> new Point(position.get()));
        doAnswer(invocation -> { position.set(new Point(invocation.getArgument(0))); return null; }).when(chr).setPosition(any(Point.class));
        return new Fixture(map, chr);
    }

    private static Portal portal(int id, String name, String target, int x, int y) {
        GenericPortal portal = new GenericPortal(2);
        portal.setId(id);
        portal.setName(name);
        portal.setTarget(target);
        portal.setPosition(new Point(x, y));
        portal.setTargetMapId(103000804);
        return portal;
    }

    private record Fixture(MapleMap map, Character character) { }
}
