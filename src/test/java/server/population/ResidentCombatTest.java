package server.population;

import client.Character;
import net.opcodes.SendOpcode;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.population.navigation.CompanionNavigation;

import java.awt.Point;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResidentCombatTest {
    @Test
    void broadcastsAClassAttackBeforeApplyingRealDamageAndProducesOnlyPrivateAutonomousLoot() {
        Fixture fixture = fixture(12, new Point(120, 100));
        assertTrue(fixture.combat.tick(1_000, null));
        assertEquals(12, fixture.monster.getHp());
        assertTrue(fixture.combat.tick(1_699, null));
        assertEquals(12, fixture.monster.getHp(), "visible remote windup must precede lethal damage");
        fixture.combat.tick(1_700, null);
        assertEquals(0, fixture.monster.getHp());
        verify(fixture.map).killMonster(fixture.monster, null, false, (short) 0);
        ResidentCombat.Kill kill = fixture.combat.drainKill();
        assertNotNull(kill);
        assertEquals(100100, kill.monsterId());
        assertEquals(new Point(120, 100), kill.position());
        assertNull(fixture.combat.drainKill());

        ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
        verify(fixture.map, atLeastOnce()).broadcastMessage(packets.capture());
        assertEquals(1, packets.getAllValues().stream().filter(p -> opcode(p) == SendOpcode.MAGIC_ATTACK.getValue()).count());
    }

    @Test
    void aHumanTagDuringTheWindupCancelsUnattributedDamageAndLoot() {
        Fixture fixture = fixture(80, new Point(120, 100));
        fixture.combat.tick(1_000, null);
        fixture.monster.damage(player(42, fixture.map), 1, false);
        fixture.combat.tick(1_700, null);
        assertEquals(79, fixture.monster.getHp());
        verify(fixture.map, never()).killMonster(any(), any(), anyBoolean(), anyShort());
        assertNull(fixture.combat.drainKill());
    }

    @Test
    void assistantsOnlyJoinExistingLeaderFightsAndRetainHumanDropOwnership() {
        Fixture fixture = fixture(30, new Point(120, 100));
        Character leader = player(42, fixture.map);
        assertFalse(fixture.combat.tick(1_000, leader));
        fixture.monster.damage(leader, 5, false);
        assertTrue(fixture.combat.tick(2_000, leader));
        fixture.combat.tick(2_700, leader);
        assertEquals(0, fixture.monster.getHp());
        verify(fixture.map).killMonster(fixture.monster, leader, true, (short) 0);
        assertNull(fixture.combat.drainKill(), "a human fight never becomes bot-private loot");
    }

    @Test
    void humanDeathAfterANonlethalResidentHitCannotBeReclassifiedAsAResidentKill() {
        Fixture fixture = fixture(100, new Point(120, 100));
        doAnswer(invocation -> {
            Monster.ResidentHit hit = (Monster.ResidentHit) invocation.callRealMethod();
            assertFalse(hit.killed());
            fixture.monster.setHpZero(); // real attack wins the race after the native hit unlocks
            return hit;
        }).when(fixture.monster).damageByResident(anyInt(), anyLong(), anyInt(), anySet());
        fixture.combat.tick(1_000, null);
        fixture.combat.tick(1_700, null);
        verify(fixture.map, never()).killMonster(any(), any(), anyBoolean(), anyShort());
        assertNull(fixture.combat.drainKill());
    }

    @Test
    void anUnreachableTargetIsAbandonedAndStopPreservesTheJourneysNewMovement() {
        Fixture fixture = fixture(100, new Point(550, 100));
        assertTrue(fixture.combat.tick(1_000, null));
        assertTrue(fixture.actor.isFollowing());
        assertFalse(fixture.combat.tick(6_001, null));
        fixture.actor.setFollowTarget(new Point(900, 100));
        fixture.combat.stop();
        assertTrue(fixture.actor.isFollowing(), "idle combat must not clear a newly set portal target");
        assertTrue(fixture.monster.claimResidentTarget(999, 6_100, Set.of()));
    }

    @Test
    void changingMapDuringAWindupCannotHitAnOldMapsMonster() {
        Fixture fixture = fixture(50, new Point(120, 100));
        fixture.combat.tick(1_000, null);
        MapleMap destination = map();
        fixture.actor.relocateTo(destination, new Point(50, 100));
        assertFalse(fixture.combat.tick(1_700, null));
        assertEquals(50, fixture.monster.getHp());
        verify(fixture.map, never()).killMonster(any(), any(), anyBoolean(), anyShort());
    }

    @Test
    void ownershipRestrictionsAndBossesPreventAnyAttack() {
        Fixture fixture = fixture(100, new Point(120, 100));
        when(fixture.map.allowsResidentCombat(null)).thenReturn(false);
        assertFalse(fixture.combat.tick(1_000, null));
        when(fixture.map.allowsResidentCombat(null)).thenReturn(true);
        fixture.monster.getStats().boss = true;
        assertFalse(fixture.combat.tick(2_000, null));
        assertEquals(100, fixture.monster.getHp());
    }

    @Test
    void repeatedContactProducesHitFeedbackAndABoundedRetreatForPotionRecovery() {
        Fixture fixture = fixture(10_000, new Point(55, 100));
        fixture.monster.getStats().PADamage = 450;
        fixture.combat.tick(2_000, null);
        assertTrue(fixture.combat.getHealthPercent() < 100);
        fixture.combat.tick(2_700, null);
        fixture.combat.tick(4_000, null);
        assertEquals("taking a potion", fixture.combat.getActivity());
        assertTrue(fixture.actor.isFollowing());
        fixture.combat.tick(6_400, null);
        assertTrue(fixture.combat.getHealthPercent() >= 30);
        ArgumentCaptor<Packet> packets = ArgumentCaptor.forClass(Packet.class);
        verify(fixture.map, atLeastOnce()).broadcastMessage(packets.capture());
        assertTrue(packets.getAllValues().stream().anyMatch(p -> opcode(p) == SendOpcode.DAMAGE_PLAYER.getValue()));
    }

    private static Fixture fixture(int hp, Point position) {
        MapleMap map = map();
        TownResident actor = new TownResident(1_500_000_001, ResidentProfiles.all().getFirst(), map,
                new Point(50, 100), CompanionNavigation.of(
                List.of(new CompanionNavigation.Surface(1, 0, 100, 1_000, 100)), List.of()));
        MonsterStats stats = new MonsterStats();
        stats.hp = hp;
        stats.level = 1;
        stats.name = "Snail";
        Monster monster = spy(new Monster(100100, stats));
        monster.setMap(map);
        monster.setObjectId(777);
        monster.setPosition(position);
        when(map.getAllMonsters()).thenReturn(List.of(monster));
        when(map.getMonsterByOid(777)).thenReturn(monster);
        return new Fixture(map, actor, monster, new ResidentCombat(actor));
    }

    private static MapleMap map() {
        MapleMap map = mock(MapleMap.class);
        FootholdTree tree = new FootholdTree(new Point(-1_000, -1_000), new Point(2_000, 2_000));
        tree.insert(new Foothold(new Point(0, 100), new Point(1_000, 100), 1));
        when(map.getFootholds()).thenReturn(tree);
        when(map.getId()).thenReturn(100010000);
        when(map.allowsResidentCombat(any())).thenReturn(true);
        return map;
    }

    private static Character player(int id, MapleMap map) {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(id);
        when(player.getMap()).thenReturn(map);
        when(player.isAlive()).thenReturn(true);
        when(player.getPosition()).thenReturn(new Point(50, 100));
        when(player.getPartyMembersOnSameMap()).thenReturn(List.of());
        return player;
    }

    private static int opcode(Packet packet) {
        byte[] bytes = packet.getBytes();
        return (bytes[0] & 255) | (bytes[1] & 255) << 8;
    }

    private record Fixture(MapleMap map, TownResident actor, Monster monster, ResidentCombat combat) { }
}
