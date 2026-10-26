package server.population.party;

import client.Character;
import client.Client;
import client.SkillFactory;
import client.Job;
import client.inventory.Equip;
import client.inventory.InventoryType;
import net.packet.Packet;
import net.server.PlayerStorage;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import net.server.world.Party;
import net.server.world.PartyOperation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import scripting.event.EventInstanceManager;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.TimerManager;
import server.ItemInformationProvider;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.population.ResidentCatalog;
import server.population.ResidentProfiles;
import tools.DatabaseConnection;
import tools.PacketCreator;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidentPartyCharacterTest {
    private static final DataProvider EQUIPMENT = DataProviderFactory.getDataProvider(WZFiles.CHARACTER);

    @BeforeAll
    static void initializeSharedItemCatalog() throws Exception {
        // The server loads this global card catalog at boot, before any bot exists.
        try (var database = mockStatic(DatabaseConnection.class)) {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet rows = mock(ResultSet.class);
            database.when(DatabaseConnection::getConnection).thenReturn(connection);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(rows);
            ItemInformationProvider.getInstance();
            SkillFactory.loadAllSkills();
        }
    }

    private static final class NoNetworkClient extends Client {
        NoNetworkClient() {
            super(Type.CHANNEL, -1, "resident test", null, 0, 1);
        }
        @Override public void sendPacket(Packet packet) { }
    }

    @Test
    void ephemeralCharactersHaveNormalEquipmentSuppliesStatsAndInspectableMetadataWithoutDatabaseAccess() throws Exception {
        var map = mock(MapleMap.class);
        when(map.getId()).thenReturn(103000000);
        var profile = ResidentProfiles.find("MapleMia").orElseThrow();
        try (var database = mockStatic(DatabaseConnection.class)) {
            Character bot = Character.createResidentBot(new NoNetworkClient(), 1_600_000_001,
                    profile, map, new Point(100, 50));
            assertTrue(bot.isResidentBot());
            assertTrue(bot.isLoggedinWorld());
            assertFalse(bot.isChangingMaps());
            assertFalse(bot.isGM());
            assertEquals(0, bot.getAccountID());
            assertEquals(profile.level(), bot.getLevel());
            assertEquals(profile.job(), bot.getJob().getId());
            assertEquals(25 + 5 * (profile.level() - 1), bot.getStr() + bot.getDex() + bot.getInt() + bot.getLuk());
            assertEquals(bot.getCurrentMaxHp(), bot.getHp());
            assertTrue(bot.getHp() < 1000);
            assertTrue(bot.getMp() < 1500);
            assertEquals(60, bot.getInventory(InventoryType.USE).countById(2000002));
            assertEquals(60, bot.getInventory(InventoryType.USE).countById(2000003));
            assertLegalBaseWeapon(bot, profile);
            assertEquals(20, bot.getSkillLevel(2001005));
            assertNotNull(PacketCreator.charInfo(bot), "inspection must not depend on a cash-shop account");
            assertFalse(bot.saveCharToDB(true));
            bot.saveCharToDB();
            bot.addCooldown(2001005, System.currentTimeMillis(), 30_000);
            bot.saveCooldowns();
            Connection connection = mock(Connection.class);
            bot.getCashShop().save(connection);
            verifyNoInteractions(connection);
            bot.logOff();
            assertFalse(bot.isLoggedin());
            database.verifyNoInteractions();
        }
    }

    @Test
    void ineligibleProfilesAndUnreservedNetworkIdsCannotBecomeKpqCharacters() {
        var map = mock(MapleMap.class);
        var mia = ResidentProfiles.find("MapleMia").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> Character.createResidentBot(
                new NoNetworkClient(), 42, mia, map, new Point()));
        var highLevel = ResidentProfiles.find("Juniper").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> Character.createResidentBot(
                new NoNetworkClient(), 1_600_000_002, highLevel, map, new Point()));
    }

    @Test
    void unregisteredCharactersCannotAcquireResidentMapTransferPrivileges() {
        var human = mock(Character.class);
        when(human.getId()).thenReturn(1_600_000_001);
        assertFalse(ResidentPartyCharacter.isActive(human));
        when(human.isResidentBot()).thenReturn(true);
        assertFalse(ResidentPartyCharacter.isActive(human));
        ResidentPartyCharacter.close(human);
        verify(human, never()).disposeResidentBot();
        assertNull(ResidentPartyCharacter.find(1_600_000_001));
    }

    @Test
    void registeredResidentsUseMapAndEventMembershipWithoutJoiningOnlineStorageAndCloseIdempotently() {
        Server server = mock(Server.class);
        World world = mock(World.class);
        Channel channel = mock(Channel.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        TimerManager timers = mock(TimerManager.class);
        ScheduledFuture<?> task = mock(ScheduledFuture.class);
        doReturn(task).when(timers).register(any(Runnable.class), anyLong());
        when(server.getWorld(0)).thenReturn(world);
        when(server.getChannel(0, 1)).thenReturn(channel);
        when(server.getCurrentTime()).thenReturn(System.currentTimeMillis());
        when(world.getPlayerStorage()).thenReturn(storage);
        when(channel.getPlayerStorage()).thenReturn(storage);
        when(world.getExpRate()).thenReturn(1);
        when(world.getDropRate()).thenReturn(1);
        when(world.getMesoRate()).thenReturn(1);
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(103000000);
        var mapCharacters = new HashMap<Integer, Character>();
        doAnswer(call -> {
            Character character = call.getArgument(0);
            mapCharacters.put(character.getId(), character);
            return null;
        }).when(map).addPlayer(any());
        when(map.getCharacterById(anyInt())).thenAnswer(call -> mapCharacters.get(call.getArgument(0)));
        doAnswer(call -> mapCharacters.remove(((Character) call.getArgument(0)).getId()))
                .when(map).removePlayer(any());
        Character host = mock(Character.class);
        when(host.getWorldServer()).thenReturn(world);
        when(host.getMap()).thenReturn(map);
        when(host.getClient()).thenReturn(new NoNetworkClient());
        var profile = ResidentProfiles.find("MapleMia").orElseThrow();
        int before = ResidentPartyCharacter.activeCount();
        try (var serverStatic = mockStatic(Server.class);
             var timerStatic = mockStatic(TimerManager.class);
             var database = mockStatic(DatabaseConnection.class)) {
            serverStatic.when(Server::getInstance).thenReturn(server);
            timerStatic.when(TimerManager::getInstance).thenReturn(timers);
            Character bot = ResidentPartyCharacter.create(profile, host, new Point(100, 50));
            try {
                assertTrue(ResidentPartyCharacter.isActive(bot));
                assertSame(bot, ResidentPartyCharacter.find(bot.getId()));
                assertSame(bot, mapCharacters.get(bot.getId()));
                assertEquals(before + 1, ResidentPartyCharacter.activeCount());
                Character impostor = mock(Character.class);
                when(impostor.isResidentBot()).thenReturn(true);
                when(impostor.getId()).thenReturn(bot.getId());
                ResidentPartyCharacter.close(impostor);
                assertTrue(ResidentPartyCharacter.isActive(bot), "cleanup must check object identity, not just an ID");
                bot.setLevel(30);
                bot.setJob(Job.IL_WIZARD);
                var currentProfile = ResidentPartyCharacter.profile(bot);
                assertEquals(30, currentProfile.level());
                assertEquals(220, currentProfile.job());
                assertEquals(profile.name(), currentProfile.name());
                var preparedEquipment = new HashMap<>(profile.equipment());
                preparedEquipment.put(11, bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11).getItemId());
                assertEquals(preparedEquipment, currentProfile.equipment());
                assertEquals(profile.face(), currentProfile.face());
                assertThrows(IllegalStateException.class, () -> ResidentPartyCharacter.create(profile, host, new Point()));
                EventInstanceManager event = mock(EventInstanceManager.class);
                bot.setEventInstance(event);
                MapleMap stageOne = mock(MapleMap.class);
                when(stageOne.getId()).thenReturn(103000800);
                when(event.getMapInstance(103000800)).thenReturn(stageOne);
                var stageCharacters = new HashMap<Integer, Character>();
                doAnswer(call -> {
                    Character character = call.getArgument(0);
                    stageCharacters.put(character.getId(), character);
                    return null;
                }).when(stageOne).addPlayer(any());
                when(stageOne.getCharacterById(anyInt())).thenAnswer(call -> stageCharacters.get(call.getArgument(0)));
                doAnswer(call -> stageCharacters.remove(((Character) call.getArgument(0)).getId()))
                        .when(stageOne).removePlayer(any());
                Party party = new Party(777, bot.getMPC());
                party.addMember(bot.getMPC());
                bot.setParty(party);
                when(world.getParty(777)).thenReturn(party);
                Point spawn = new Point(-225, 420);
                assertNull(storage.getCharacterById(bot.getId()), "residents deliberately have no online-storage entry");

                // Exercise the actual public warp pipeline, including map removal,
                // event callbacks, party metadata, and the map-transition guard.
                bot.changeMap(stageOne, spawn);

                assertSame(stageOne, bot.getMap());
                assertEquals(spawn, bot.getPosition());
                assertSame(bot, stageCharacters.get(bot.getId()));
                assertFalse(mapCharacters.containsKey(bot.getId()));
                assertTrue(bot.isAlive());
                assertTrue(bot.isLoggedinWorld());
                assertFalse(bot.isChangingMaps());
                assertSame(event, bot.getEventInstance());
                assertSame(party, bot.getParty());
                assertSame(bot.getMPC(), party.getMemberById(bot.getId()));
                assertEquals(103000800, bot.getMPC().getMapId());
                verify(event).changedMap(bot, 103000800);
                verify(event).afterChangedMap(bot, 103000800);
                verify(world).updateParty(777, PartyOperation.SILENT_UPDATE, bot.getMPC());
                ResidentPartyCharacter.close(bot);
                ResidentPartyCharacter.close(bot);
                verify(event, times(1)).unregisterPlayer(bot);
                verify(map, times(1)).removePlayer(bot);
                verify(stageOne, times(1)).removePlayer(bot);
                assertFalse(stageCharacters.containsKey(bot.getId()));
                assertFalse(bot.isLoggedinWorld());
                assertFalse(ResidentPartyCharacter.isActive(bot));
                assertNull(bot.getEventInstance());
                assertNull(bot.getParty());
                assertEquals(before, ResidentPartyCharacter.activeCount());
                assertFalse(mapCharacters.containsKey(bot.getId()));
                verify(storage, never()).addPlayer(any());
                verify(world, never()).addPlayer(any());
                verify(channel, never()).addPlayer(any());
                verify(task, times(3)).cancel(false);
                database.verifyNoInteractions();
            } finally {
                ResidentPartyCharacter.close(bot);
            }
        }
    }

    @Test
    void everyRecruitableCatalogProfileCanPrepareAnOrdinaryWeaponWithoutChangingItsBuildOrIdentity() {
        int checked = 0;
        for (var profile : ResidentCatalog.all()) {
            if (profile.level() < 21 || profile.level() > 30
                    || !List.of(100, 200, 300, 400, 500).contains(profile.job())) continue;
            Character bot = character(profile);
            assertLegalBaseWeapon(bot, profile);
            assertEquals(profile.name(), bot.getName());
            assertEquals(profile.face(), bot.getFace());
            assertEquals(profile.hair(), bot.getHair());
            assertEquals(profile.gender(), bot.getGender());
            assertEquals(profile.skin(), bot.getSkinColor().getId());
            assertEquals(25 + 5 * (profile.level() - 1), bot.getStr() + bot.getDex() + bot.getInt() + bot.getLuk());
            for (var entry : profile.equipment().entrySet()) {
                if (entry.getKey() != 11) assertEquals(entry.getValue(),
                        bot.getInventory(InventoryType.EQUIPPED).getItem((short) -entry.getKey()).getItemId());
            }
            checked++;
        }
        assertTrue(checked > 30, "exercise the real generated recruitment pool, not only named templates");
    }

    @Test
    void daggerPreparationHonorsActualStrengthAndDexterityRequirementsAtTierBoundaries() {
        var original = ResidentCatalog.find("xFadedNovax").orElseThrow();
        for (var boundary : List.of(new int[]{22, 1332013}, new int[]{24, 1332013},
                new int[]{25, 1332013}, new int[]{27, 1332013}, new int[]{28, 1332004}, new int[]{30, 1332012})) {
            var profile = new ResidentProfiles.Profile(original.id(), original.name(), original.gender(), original.skin(),
                    original.face(), original.hair(), 400, boundary[0], original.equipment(), original.role(), original.companion());
            Character bot = character(profile);
            assertEquals(boundary[1], bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11).getItemId(),
                    "level " + boundary[0] + " must use a dagger allowed by its existing AP allocation");
            assertLegalBaseWeapon(bot, profile);
            assertEquals(4, bot.getStr(), "preparation must not grant STR to qualify for an Iron Dagger");
        }
    }

    @Test
    void ordinaryPreparedDaggerImprovesRealLigatorDamageWithoutChangingStatsSkillsOrAttackTiming() {
        var profile = ResidentCatalog.find("xFadedNovax").orElseThrow();
        CombatSample original = attackSample(profile, true);
        CombatSample prepared = attackSample(profile, false);
        assertEquals(20, original.weaponAttack);
        assertEquals(37, prepared.weaponAttack);
        assertEquals(original.attacks, prepared.attacks, "equipment preparation must not accelerate attacks");
        assertEquals(original.baseStats, prepared.baseStats);
        assertEquals(original.hp, prepared.hp);
        assertEquals(original.mpSpent, prepared.mpSpent);
        assertTrue(prepared.damage > original.damage * 2,
                "a legal level-22 dagger must overcome Ligator defense: " + original + " -> " + prepared);
        assertTrue(prepared.damage < 2_000, "ordinary first-job damage must remain bounded during the ten-second sample");
    }

    private static CombatSample attackSample(ResidentProfiles.Profile profile, boolean originalWeapon) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(103000800);
        when(map.getEventInstance()).thenReturn(mock(EventInstanceManager.class));
        FootholdTree ground = new FootholdTree(new Point(-1000, -1000), new Point(1000, 1000));
        ground.insert(new Foothold(new Point(0, 100), new Point(500, 100), 1));
        when(map.getFootholds()).thenReturn(ground);
        Character bot = Character.createResidentBot(new NoNetworkClient(), 1_600_000_007, profile, map, new Point(100, 100));
        if (originalWeapon) {
            var inventory = bot.getInventory(InventoryType.EQUIPPED);
            inventory.removeSlot((short) -11);
            var knife = ItemInformationProvider.getInstance().getEquipById(profile.equipment().get(11));
            knife.setPosition((short) -11);
            inventory.addItemFromDB(knife);
            bot.equipChanged();
        }
        // Copy the actual KPQ Ligator's combat fields, without invoking global drop/DB catalogs.
        Data info = DataProviderFactory.getDataProvider(WZFiles.MOB).getData("9300001.img").getChildByPath("info");
        MonsterStats stats = new MonsterStats();
        stats.hp = DataTool.getInt("maxHP", info);
        stats.level = DataTool.getInt("level", info);
        stats.PDDamage = DataTool.getInt("PDDamage", info);
        stats.PADamage = DataTool.getInt("PADamage", info);
        stats.name = "Ligator";
        Monster ligator = new Monster(9300001, stats);
        ligator.setObjectId(777);
        ligator.setMap(map);
        ligator.setPosition(new Point(175, 100));
        when(map.getAllMonsters()).thenReturn(List.of(ligator));
        when(map.getMonsterByOid(777)).thenReturn(ligator);
        AtomicInteger damage = new AtomicInteger(), attacks = new AtomicInteger();
        when(map.damageMonster(eq(bot), eq(ligator), anyInt())).thenAnswer(call -> {
            damage.addAndGet(call.getArgument(2));
            attacks.incrementAndGet();
            return true;
        });
        int mp = bot.getMp();
        try (var actor = new ResidentPartyActor(bot, profile)) {
            actor.hunt(true);
            for (long now = 1_000; now <= 11_000; now += 50) actor.tick(now);
        }
        assertTrue(attacks.get() >= 7, "exercise repeated real actor attacks and delayed impacts");
        return new CombatSample(damage.get(), attacks.get(), bot.getTotalWatk(), bot.getStr() + bot.getDex() + bot.getInt()
                + bot.getLuk(), bot.getCurrentMaxHp(), mp - bot.getMp());
    }

    private record CombatSample(int damage, int attacks, int weaponAttack, int baseStats, int hp, int mpSpent) { }

    private static Character character(ResidentProfiles.Profile profile) {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(103000000);
        return Character.createResidentBot(new NoNetworkClient(), 1_600_000_008, profile, map, new Point(100, 100));
    }

    private static void assertLegalBaseWeapon(Character bot, ResidentProfiles.Profile profile) {
        Equip weapon = (Equip) bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        int id = weapon.getItemId();
        String message = profile.name() + " level=" + profile.level() + " weapon=" + id;
        Data info = EQUIPMENT.getData(String.format("Weapon/%08d.img", id)).getChildByPath("info");
        assertEquals(profile.equipment().get(11) / 10_000, id / 10_000, message);
        assertTrue(DataTool.getInt("reqLevel", info, 0) <= bot.getLevel(), message);
        int jobs = DataTool.getInt("reqJob", info, 0);
        assertTrue(jobs == 0 || jobs > 0 && (jobs & (1 << (profile.job() / 100 - 1))) != 0, message);
        assertTrue(DataTool.getInt("reqSTR", info, 0) <= bot.getStr(), message);
        assertTrue(DataTool.getInt("reqDEX", info, 0) <= bot.getDex(), message);
        assertTrue(DataTool.getInt("reqINT", info, 0) <= bot.getInt(), message);
        assertTrue(DataTool.getInt("reqLUK", info, 0) <= bot.getLuk(), message);
        assertTrue(DataTool.getInt("reqPOP", info, 0) <= bot.getFame(), message);
        for (String flag : List.of("cash", "tradeBlock", "only", "notSale", "timeLimited", "expireOnLogout", "incMHP", "incMMP")) {
            assertEquals(0, DataTool.getInt(flag, info, 0), message + " " + flag);
        }
        assertEquals(DataTool.getInt("incPAD", info, 0), weapon.getWatk(), message);
        assertEquals(DataTool.getInt("incMAD", info, 0), weapon.getMatk(), message);
        assertEquals(DataTool.getInt("tuc", info, 0), weapon.getUpgradeSlots(), message);
        assertEquals(0, weapon.getLevel(), "weapon must be unscrolled: " + message);
    }
}
