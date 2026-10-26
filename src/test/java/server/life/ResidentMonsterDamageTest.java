package server.life;

import client.Character;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidentMonsterDamageTest {
    @Test
    void partyResidentsNeverBecomeClientSideMonsterControllers() {
        Monster monster = spy(monster(100));
        var map = mock(server.maps.MapleMap.class);
        Character bot = player(1_600_000_001);
        when(bot.isResidentBot()).thenReturn(true);
        when(bot.isAlive()).thenReturn(true);
        Character human = player(42);
        when(human.isAlive()).thenReturn(true);
        when(map.getAllPlayers()).thenReturn(List.of(bot, human));
        monster.setMap(map);
        doNothing().when(monster).aggroSwitchController(any(), anyBoolean());
        monster.aggroUpdateController();
        verify(monster).aggroSwitchController(human, false);
        verify(monster, never()).aggroSwitchController(eq(bot), anyBoolean());

        Monster noController = monster(100);
        noController.aggroSwitchController(bot, true);
        noController.aggroMonsterDamage(bot, 10);
        assertNull(noController.getController(), "a socketless resident cannot take monster simulation ownership");
    }

    @Test
    void residentDamageChangesHpWithoutCreatingHumanExperienceContributions() throws Exception {
        Monster monster = monster(100);
        assertTrue(monster.claimResidentTarget(700, 1_000, Set.of()));
        assertEquals(new Monster.ResidentHit(70, false), monster.damageByResident(700, 1_700, 70, Set.of()));
        assertEquals(30, monster.getHp());
        assertTrue(contributions(monster).isEmpty());

        Character player = player(42);
        monster.damage(player, 10, false);
        assertEquals(10, contributions(monster).get(42).get());
        assertEquals(100, totalRewardHp(monster), "NPC damage must not inflate the player's EXP share");
        assertEquals(new Monster.ResidentHit(0, false), monster.damageByResident(700, 1_800, 20, Set.of()));
        assertEquals(20, monster.getHp(), "autonomous hunter must yield when a human tags its monster");
    }

    @Test
    void assistRequiresAnExistingHumanFightAndNeverClaimsAnotherPartysTarget() {
        Monster monster = monster(100);
        assertFalse(monster.claimResidentTarget(700, 1_000, Set.of(42)));
        monster.damage(player(42), 5, false);
        assertTrue(monster.claimResidentTarget(700, 1_100, Set.of(42, 43)));
        monster.damage(player(99), 1, false);
        assertEquals(new Monster.ResidentHit(0, false),
                monster.damageByResident(700, 1_800, 95, Set.of(42, 43)));
        assertEquals(94, monster.getHp());
    }

    @Test
    void claimsPreventResidentsDogpilingAndExpireWithoutASeparateCleanupTask() {
        Monster monster = monster(100);
        assertTrue(monster.claimResidentTarget(700, 1_000, Set.of()));
        assertFalse(monster.claimResidentTarget(701, 1_100, Set.of()));
        assertTrue(monster.claimResidentTarget(701, 4_001, Set.of()));
        assertEquals(new Monster.ResidentHit(0, false), monster.damageByResident(700, 4_100, 99, Set.of()));
        monster.releaseResidentTarget(700);
        assertEquals(new Monster.ResidentHit(100, true), monster.damageByResident(701, 4_200, 999, Set.of()));
        assertEquals(new Monster.ResidentHit(0, false), monster.damageByResident(701, 4_300, 1, Set.of()));
    }

    private static Monster monster(int hp) {
        MonsterStats stats = new MonsterStats();
        stats.hp = hp;
        stats.level = 1;
        return new Monster(100100, stats);
    }

    private static Character player(int id) {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(id);
        return player;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, AtomicLong> contributions(Monster monster) throws Exception {
        Field field = Monster.class.getDeclaredField("takenDamage");
        field.setAccessible(true);
        return (Map<Integer, AtomicLong>) field.get(monster);
    }

    private static long totalRewardHp(Monster monster) throws Exception {
        Field field = Monster.class.getDeclaredField("maxHpPlusHeal");
        field.setAccessible(true);
        return ((AtomicLong) field.get(monster)).get();
    }
}
