package client;

import client.inventory.InventoryType;
import client.inventory.Item;
import client.keybind.KeyBinding;
import constants.id.MapId;
import constants.skills.Priest;
import net.server.world.Party;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scripting.event.EventInstanceManager;
import server.maps.MapleMap;
import server.maps.Portal;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

class CharacterRebirthTest {
    private Character player;
    private Client client;
    private MapleMap town;
    private MapleMap destination;

    @BeforeEach
    void createCharacter() {
        client = mock(Client.class, RETURNS_DEEP_STUBS);
        player = spy(Character.getDefault(client));
        player.setName("RebirthTest");
        player.setLevel(200);
        player.setJob(Job.BISHOP);
        player.setExp(123);
        player.setMapTransitionComplete();
        town = mock(MapleMap.class);
        when(town.isTown()).thenReturn(true);
        player.setMap(town);
        destination = mock(MapleMap.class);
        when(destination.getPortal(0)).thenReturn(mock(Portal.class));
        when(client.getChannelServer().getMapFactory().getMap(MapId.HENESYS)).thenReturn(destination);
        doReturn(true).when(player).isLoggedinWorld();
        doReturn(true).when(player).saveCharToDB(true);
        doNothing().when(player).updatePartySearchAvailability(anyBoolean());
        doNothing().when(player).finishRebirth(destination);
    }

    @Test
    void rebirthPreservesEarnedStatsSkillsItemsAndBindings() {
        player.str = 250;
        player.dex = 150;
        player.int_ = 1000;
        player.luk = 100;
        player.hp = 8000;
        player.maxhp = 12000;
        player.mp = 18000;
        player.maxmp = 25000;
        player.remainingAp = 42;
        player.remainingSp[0] = 80;
        player.remainingSp[1] = 17;
        player.hpMpApUsed = 11;
        player.setFame(99);
        player.gainMeso(123456, false);
        Item potion = new Item(2000000, (short) 1, (short) 25);
        player.getInventory(InventoryType.USE).addItem(potion);
        Skill holySymbol = new Skill(Priest.HOLY_SYMBOL);
        player.changeSkillLevel(holySymbol, (byte) 30, 30, -1);
        player.changeKeybinding(16, new KeyBinding(1, holySymbol.getId()));
        Map<Skill, Character.SkillEntry> skills = Map.copyOf(player.getSkills());
        int[] sp = player.getRemainingSps();
        clearInvocations(player, client);

        assertNull(player.rebirth());

        assertAll(
                () -> assertEquals(1, player.getLevel()),
                () -> assertEquals(0, player.getExp()),
                () -> assertEquals(Job.BEGINNER, player.getJob()),
                () -> assertEquals(1, player.getReborns()),
                () -> assertEquals(250, player.getStr()),
                () -> assertEquals(150, player.getDex()),
                () -> assertEquals(1000, player.getInt()),
                () -> assertEquals(100, player.getLuk()),
                () -> assertEquals(8000, player.getHp()),
                () -> assertEquals(12000, player.getMaxHp()),
                () -> assertEquals(18000, player.getMp()),
                () -> assertEquals(25000, player.getMaxMp()),
                () -> assertEquals(42, player.getRemainingAp()),
                () -> assertArrayEquals(sp, player.getRemainingSps()),
                () -> assertEquals(11, player.getHpMpApUsed()),
                () -> assertEquals(99, player.getFame()),
                () -> assertEquals(123456, player.getMeso()),
                () -> assertSame(potion, player.getInventory(InventoryType.USE).getItem((short) 1)),
                () -> assertEquals(skills, player.getSkills()),
                () -> assertEquals(holySymbol.getId(), player.getKeymap().get(16).getAction()));
        var order = inOrder(player);
        order.verify(player).saveCharToDB(true);
        order.verify(player).finishRebirth(destination);
        verify(player, never()).changeJob(any());
    }

    @Test
    void failedSaveRollsBackWithoutRefreshingOrAnnouncingSuccess() {
        doReturn(false).when(player).saveCharToDB(true);

        assertNotNull(player.rebirth());

        assertEquals(200, player.getLevel());
        assertEquals(123, player.getExp());
        assertEquals(Job.BISHOP, player.getJob());
        assertEquals(0, player.getReborns());
        verify(player, never()).finishRebirth(any());
    }

    @Test
    void repeatedConfirmationCannotRebirthTwice() {
        assertNull(player.rebirth());
        assertNotNull(player.rebirth());
        assertEquals(1, player.getReborns());
        verify(player, times(1)).saveCharToDB(true);
    }

    @Test
    void resetsOnlyAdvancementQuestsSoJobsCanBeChosenAgain() throws Exception {
        Map<Short, QuestStatus> quests = quests();
        QuestStatus warriorTest = mock(QuestStatus.class);
        QuestStatus pirateTest = mock(QuestStatus.class);
        QuestStatus unrelated = mock(QuestStatus.class);
        quests.put((short) 100004, warriorTest);
        quests.put((short) 2191, pirateTest);
        quests.put((short) 1000, unrelated);

        assertNull(player.rebirth());

        assertFalse(quests.containsKey((short) 100004));
        assertFalse(quests.containsKey((short) 2191));
        assertSame(unrelated, quests.get((short) 1000));
    }

    @Test
    void failedSaveRestoresAdvancementQuestProgress() throws Exception {
        Map<Short, QuestStatus> quests = quests();
        QuestStatus original = mock(QuestStatus.class);
        quests.put((short) 100004, original);
        doReturn(false).when(player).saveCharToDB(true);

        assertNotNull(player.rebirth());

        assertSame(original, quests.get((short) 100004));
        verify(player, never()).finishRebirth(any());
    }

    @Test
    void requiresEquipmentToBeStoredBeforeResettingLevel() {
        Item weapon = new Item(1302000, (short) -11, (short) 1);
        player.getInventory(InventoryType.EQUIPPED).addItemFromDB(weapon);
        assertRejectedWithoutMutation(200);
        assertSame(weapon, player.getInventory(InventoryType.EQUIPPED).getItem((short) -11));
    }

    @Test
    void retainsCashAppearanceEquipment() {
        Item cashWeapon = new Item(1702000, (short) -111, (short) 1);
        player.getInventory(InventoryType.EQUIPPED).addItemFromDB(cashWeapon);
        assertNull(player.rebirth());
        assertSame(cashWeapon, player.getInventory(InventoryType.EQUIPPED).getItem((short) -111));
    }

    @Test
    void refusesRebirthIfDestinationCannotBeLoaded() {
        when(client.getChannelServer().getMapFactory().getMap(MapId.HENESYS)).thenReturn(null);
        assertRejectedWithoutMutation(200);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 120, 199})
    void requiresLevel200(int level) {
        player.setLevel(level);
        assertRejectedWithoutMutation(level);
    }

    @Test
    void refusesDeadCharacters() {
        player.hp = 0;
        assertRejectedWithoutMutation(200);
    }

    @Test
    void refusesCombatMaps() {
        when(town.isTown()).thenReturn(false);
        assertRejectedWithoutMutation(200);
    }

    @Test
    void refusesPartiesAndEvents() {
        doReturn(mock(Party.class)).when(player).getParty();
        assertRejectedWithoutMutation(200);
        doReturn(null).when(player).getParty();
        player.setEventInstance(mock(EventInstanceManager.class));
        assertRejectedWithoutMutation(200);
    }

    @Test
    void refusesDisconnectedOrTransitioningCharacters() {
        doReturn(false).when(player).isLoggedinWorld();
        assertRejectedWithoutMutation(200);
        doReturn(true).when(player).isLoggedinWorld();
        doReturn(true).when(player).isChangingMaps();
        assertRejectedWithoutMutation(200);
    }

    @Test
    void firstJobStatResetPreservesRebornCharacters() throws Exception {
        setPrivate("reborns", 1);
        player.setJob(Job.WARRIOR);
        player.setLevel(10);
        player.str = 999;
        player.dex = 150;
        player.int_ = 200;
        player.luk = 100;
        player.remainingAp = 35;
        player.remainingSp[0] = 30;

        player.resetStats();

        assertEquals(999, player.getStr());
        assertEquals(150, player.getDex());
        assertEquals(200, player.getInt());
        assertEquals(100, player.getLuk());
        assertEquals(35, player.getRemainingAp());
        assertEquals(30, player.getRemainingSp());
    }

    private void assertRejectedWithoutMutation(int expectedLevel) {
        assertNotNull(player.rebirth());
        assertEquals(expectedLevel, player.getLevel());
        assertEquals(Job.BISHOP, player.getJob());
        assertEquals(123, player.getExp());
        assertEquals(0, player.getReborns());
        verify(player, never()).saveCharToDB(anyBoolean());
        verify(player, never()).finishRebirth(any());
    }

    private void setPrivate(String name, Object value) throws Exception {
        Field field = Character.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(player, value);
    }

    @SuppressWarnings("unchecked")
    private Map<Short, QuestStatus> quests() throws Exception {
        Field field = Character.class.getDeclaredField("quests");
        field.setAccessible(true);
        return (Map<Short, QuestStatus>) field.get(player);
    }
}
