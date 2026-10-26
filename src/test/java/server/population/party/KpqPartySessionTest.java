package server.population.party;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.server.Server;
import net.server.world.Party;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import scripting.npc.NPCScriptManager;
import server.life.NPC;
import server.maps.MapleMap;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.Portal;
import server.population.TownResident;
import server.population.TownPopulation;
import server.population.navigation.CompanionNavigation;

import java.awt.Point;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class KpqPartySessionTest {
    @Test
    void passHandoffWaitsForOverlapThenUsesTheOrdinaryInventoryDrop() {
        Fixture f = fixture(103000800, 103000800);
        when(f.host.getMap()).thenReturn(f.botMap);
        when(f.host.getPosition()).thenReturn(new Point(578, 106));
        when(f.bot.getPosition()).thenReturn(new Point(527, 106));
        Inventory inventory = mock(Inventory.class);
        when(f.bot.getInventory(InventoryType.ETC)).thenReturn(inventory);
        when(inventory.findById(4001008)).thenReturn(new Item(4001008, (short) 1, (short) 1));
        when(f.bot.getItemQuantity(4001008, false)).thenReturn(1);
        Client client = f.bot.getClient();
        long now = System.currentTimeMillis();

        try (var drops = mockStatic(InventoryManipulator.class)) {
            assertTrue(f.session.tick(now));
            verify(f.actor).moveTo(new Point(578, 106));
            drops.verifyNoInteractions(); // The old 55px handoff was outside the browser's pickup reach.

            when(f.bot.getPosition()).thenReturn(new Point(576, 106));
            when(f.host.getStance()).thenReturn(6); // Wait for the leader's jump to land.
            assertTrue(f.session.tick(now + 300));
            drops.verifyNoInteractions();
            when(f.host.getStance()).thenReturn(4);
            when(f.actor.actor().getFootholdId()).thenReturn(0); // Also wait for the bot's landing.
            assertTrue(f.session.tick(now + 600));
            drops.verifyNoInteractions();
            when(f.actor.actor().getFootholdId()).thenReturn(1);
            assertTrue(f.session.tick(now + 900));
            drops.verify(() -> InventoryManipulator.drop(client, InventoryType.ETC, (short) 1, (short) 1));
        }
    }

    @Test
    void passHandoffKeepsTheItemWhenNormalDropPlacementChoosesAnOverheadBranch() {
        Fixture f = fixture(103000800, 103000800);
        when(f.host.getMap()).thenReturn(f.botMap);
        when(f.host.getPosition()).thenReturn(new Point(519, 107));
        when(f.bot.getPosition()).thenReturn(new Point(519, 107));
        when(f.bot.getItemQuantity(4001008, false)).thenReturn(1);
        MapleMap terrain = actualStageOneTerrain();
        when(f.botMap.calcDropPos(any(Point.class), any(Point.class))).thenAnswer(call ->
                terrain.calcDropPos(call.getArgument(0), call.getArgument(1)));

        try (var drops = mockStatic(InventoryManipulator.class)) {
            assertTrue(f.session.tick(System.currentTimeMillis()));
            drops.verifyNoInteractions();
            verify(f.actor).moveTo(new Point(519, 107));
            assertEquals(1, f.bot.getItemQuantity(4001008, false));
        }
    }

    @Test
    void actualKpqDropGeometryRejectsTheLowerFloorAndAllowsTheBranchOrOpenGround() {
        MapleMap terrain = actualStageOneTerrain();
        Point lowerFloor = new Point(519, 107), branch = new Point(519, 55), openGround = new Point(578, 106);
        assertEquals(branch, terrain.calcDropPos(new Point(lowerFloor), new Point(lowerFloor)),
                "The normal server searches from y-85 and catches the overhanging branch");
        assertFalse(KpqPartySession.passDropReachable(terrain, lowerFloor, lowerFloor));
        assertFalse(KpqPartySession.passDropReachable(terrain, branch, lowerFloor));
        assertFalse(KpqPartySession.passDropReachable(terrain, new Point(527, 106), openGround));
        assertTrue(KpqPartySession.passDropReachable(terrain, branch, branch));
        assertTrue(KpqPartySession.passDropReachable(terrain, new Point(576, 106), openGround));
        Point landing = terrain.calcDropPos(new Point(openGround), new Point(openGround));
        for (int bob = -4; bob <= 1; bob++) {
            assertTrue(new Point(landing.x + 16, landing.y + bob + 16).distanceSq(openGround) <= 30 * 30,
                    "The actual settled sprite must remain within ordinary pickup reach throughout its bob");
        }
    }

    private static MapleMap actualStageOneTerrain() {
        try (var servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(mock(Server.class));
            MapleMap map = new MapleMap(103000800, 0, 1, 103000000, 1);
            FootholdTree footholds = new FootholdTree(new Point(-2000, -2000), new Point(2000, 500));
            for (var surface : CompanionNavigation.forMapId(103000800).surfaces()) {
                Foothold foothold = new Foothold(new Point(surface.x1(), surface.y1()),
                        new Point(surface.x2(), surface.y2()), surface.id());
                footholds.insert(foothold);
            }
            map.setFootholds(footholds);
            map.setMapLineBoundings(-2000, 500, -2000, 2000);
            map.generateMapDropRangeCache();
            return map;
        }
    }

    @Test
    void registrationMayPrecedeTheEntryMapAndClientTransitionAcknowledgement() {
        Fixture f = fixture(103000000, 103000000);
        long now = System.currentTimeMillis();

        assertTrue(f.session.tick(now));
        verify(f.actor, never()).tick(anyLong());
        when(f.host.getMapId()).thenReturn(103000800);
        when(f.host.isChangingMaps()).thenReturn(true);
        assertTrue(f.session.tick(now + 100));
        verify(f.actor, never()).tick(anyLong());

        when(f.host.isChangingMaps()).thenReturn(false);
        assertTrue(f.session.tick(now + 200));
        verify(f.actor).tick(now + 200);
    }

    @Test
    void anEntryThatNeverFinishesIsEventuallyReleased() {
        Fixture f = fixture(103000000, 103000000);
        long now = System.currentTimeMillis();
        assertTrue(f.session.tick(now));
        assertFalse(f.session.tick(now + 15_001));
    }

    @Test
    void botsStillTakeTheClearedFirstStagePortalWhenTheHumanGoesAhead() {
        Fixture f = fixture(103000801, 103000800);
        when(f.event.getProperty("1stageclear")).thenReturn("true");
        Portal portal = mock(Portal.class);
        Point entrance = new Point(200, 0);
        when(portal.getPosition()).thenReturn(entrance);
        when(f.botMap.getPortal("next00")).thenReturn(portal);

        assertTrue(f.session.tick(System.currentTimeMillis()));

        verify(f.actor).moveTo(entrance);
        verify(f.actor, never()).hold();
    }

    @Test
    void botsStillReturnForTheirRewardsAfterTheHumanEntersTheBonusMap() {
        Fixture f = fixture(103000805, 103000804);
        when(f.event.getProperty("5stageclear")).thenReturn("true");
        NPC cloto = mock(NPC.class);
        Point rewardNpc = new Point(383, -2715);
        when(cloto.getPosition()).thenReturn(rewardNpc);
        when(f.botMap.getNPCById(9020001)).thenReturn(cloto);

        assertTrue(f.session.tick(System.currentTimeMillis()));

        verify(f.actor).moveTo(rewardNpc);
        verify(f.actor, never()).hold();
    }

    @Test
    void unclearedCombatStillWaitsForTheHumanClientInTheSameMap() {
        Fixture f = fixture(103000805, 103000804);

        assertTrue(f.session.tick(System.currentTimeMillis()));

        verify(f.actor).hold();
        verify(f.actor, never()).hunt(true);
        verify(f.actor, never()).collect(4001008, 10);
    }

    @Test
    void formerLeaderHandsOffCarriedPassesThenAsksForItsOwnQuestion() {
        Fixture f = fixture(103000800, 103000800);
        when(f.host.getMap()).thenReturn(f.botMap);
        NPC cloto = mock(NPC.class);
        Point questionNpc = new Point(200, 0);
        when(cloto.getPosition()).thenReturn(questionNpc);
        when(f.botMap.getNPCById(9020001)).thenReturn(cloto);
        Inventory inventory = mock(Inventory.class);
        when(f.bot.getInventory(InventoryType.ETC)).thenReturn(inventory);
        when(inventory.findById(4001008)).thenReturn(new Item(4001008, (short) 1, (short) 2));
        when(f.bot.getItemQuantity(4001008, false)).thenReturn(2);
        long now = System.currentTimeMillis();

        try (var drops = mockStatic(InventoryManipulator.class)) {
            assertTrue(f.session.tick(now));
            drops.verify(() -> InventoryManipulator.drop(f.bot.getClient(), InventoryType.ETC,
                    (short) 1, (short) 2));
        }
        when(f.bot.getItemQuantity(4001008, false)).thenReturn(0);
        clearInvocations(f.actor);

        assertTrue(f.session.tick(now + 300));

        verify(f.actor).moveTo(questionNpc);
        verify(f.actor, never()).moveTo(new Point(45, 0));
    }

    @Test
    void onlyTheActualNpcCouponExchangeCompletesAMembersQuestion() {
        Fixture f = fixture(103000800, 103000800);
        when(f.host.getMap()).thenReturn(f.botMap);
        NPC cloto = mock(NPC.class);
        when(cloto.getPosition()).thenReturn(new Point());
        when(f.botMap.getNPCById(9020001)).thenReturn(cloto);
        AtomicInteger coupons = new AtomicInteger(10), passes = new AtomicInteger();
        when(f.bot.getItemQuantity(4001007, false)).thenAnswer(ignored -> coupons.get());
        when(f.bot.getItemQuantity(4001008, false)).thenAnswer(ignored -> passes.get());
        Inventory inventory = mock(Inventory.class);
        when(f.bot.getInventory(InventoryType.ETC)).thenReturn(inventory);
        when(inventory.findById(4001008)).thenReturn(new Item(4001008, (short) 1, (short) 1));
        f.session.question(f.bot, "What is the minimum level to become a warrior?");
        long now = System.currentTimeMillis();
        NPCScriptManager manager = mock(NPCScriptManager.class);
        Client botClient = f.bot.getClient();

        try (var scripts = mockStatic(NPCScriptManager.class);
             var drops = mockStatic(InventoryManipulator.class)) {
            scripts.when(NPCScriptManager::getInstance).thenReturn(manager);
            doAnswer(ignored -> { coupons.set(0); passes.set(1); return null; })
                    .when(manager).start(botClient, 9020001, f.bot);
            drops.when(() -> InventoryManipulator.drop(botClient, InventoryType.ETC,
                    (short) 1, (short) 1)).thenAnswer(ignored -> { passes.set(0); return null; });

            assertTrue(f.session.tick(now)); // Cloto consumes ten coupons and gives one pass.
            assertTrue(f.session.tick(now + 300)); // Hand the pass to the human leader.
            clearInvocations(f.actor);
            assertTrue(f.session.tick(now + 3_000));

            verify(manager, times(1)).start(f.bot.getClient(), 9020001, f.bot);
            verify(f.actor).collect(0, 0);
            verify(f.actor).hunt(true); // Help remaining collectors without taking their coupons.
            verify(f.actor, never()).collect(4001007, 10);
        }
    }

    @Test
    void closingAfterTheHumanLeavesEndsTheOwnedEventExactlyOnce() throws Exception {
        Fixture f = fixture(103000804, 103000804);
        assertTrue(f.session.tick(System.currentTimeMillis()));
        when(f.host.getEventInstance()).thenReturn(null);
        when(f.host.getParty()).thenReturn(null);
        assertFalse(f.session.tick(System.currentTimeMillis() + 300));

        try (var population = mockStatic(TownPopulation.class)) {
            f.session.close();
            f.session.close();

            verify(f.event, times(1)).invokeScriptFunction("end", f.event);
            verify(f.actor, times(1)).close();
            population.verify(() -> TownPopulation.releasePartyMembers(List.of()), times(1));
        }
    }

    @Test
    void humanDeathKeepsTheEventAliveUntilNormalReviveOrExit() throws Exception {
        Fixture f = fixture(103000800, 103000800);
        when(f.host.getMap()).thenReturn(f.botMap);
        long now = System.currentTimeMillis();
        assertTrue(f.session.tick(now));
        clearInvocations(f.actor);
        when(f.host.isAlive()).thenReturn(false);

        assertTrue(f.session.tick(now + 300));
        assertTrue(f.session.tick(now + 600));

        verify(f.actor, times(2)).hold();
        verify(f.actor).tick(now + 300);
        verify(f.actor).tick(now + 600);
        verify(f.actor, never()).close();
        verify(f.event, never()).invokeScriptFunction("end", f.event);
        when(f.host.getEventInstance()).thenReturn(null); // The ordinary revive script unregisters the human.
        assertFalse(f.session.tick(now + 900));
    }

    @Test
    void immediateHostExitAfterLakelisEntryRecoversTheEventFromItsBots() throws Exception {
        Fixture f = fixture(103000800, 103000800);
        when(f.host.getEventInstance()).thenReturn(null);
        when(f.host.getParty()).thenReturn(null);
        when(f.bot.getEventInstance()).thenReturn(f.event);
        // No session tick has cached the event yet: Lakelis entered immediately
        // before the host left, but the bots still belong to the owned instance.
        try (var population = mockStatic(TownPopulation.class)) {
            f.session.close();
            f.session.close();

            verify(f.event, times(1)).invokeScriptFunction("end", f.event);
            verify(f.actor, times(1)).close();
        }
    }

    private record Fixture(KpqPartySession session, ResidentPartyActor actor, MapleMap botMap,
                           EventInstanceManager event, Character host, Character bot) { }

    private static Fixture fixture(int humanMapId, int botMapId) {
        Character host = mock(Character.class);
        Character bot = mock(Character.class);
        Party party = mock(Party.class);
        EventInstanceManager event = mock(EventInstanceManager.class);
        EventManager manager = mock(EventManager.class);
        MapleMap hostMap = mock(MapleMap.class);
        MapleMap botMap = mock(MapleMap.class);
        ResidentPartyActor actor = mock(ResidentPartyActor.class);
        when(host.getId()).thenReturn(1);
        when(bot.getId()).thenReturn(2);
        when(host.getName()).thenReturn("PartyHost");
        when(bot.getName()).thenReturn("PartyResident");
        when(bot.getClient()).thenReturn(mock(Client.class));
        when(host.isLoggedinWorld()).thenReturn(true);
        when(host.isAlive()).thenReturn(true);
        when(host.getParty()).thenReturn(party);
        when(host.getEventInstance()).thenReturn(event);
        when(host.getMap()).thenReturn(hostMap);
        when(host.getMapId()).thenReturn(humanMapId);
        when(host.getPosition()).thenReturn(new Point());
        when(host.getStance()).thenReturn(4);
        when(event.getEm()).thenReturn(manager);
        when(manager.getName()).thenReturn("KerningPQ");
        when(bot.isAlive()).thenReturn(true);
        when(bot.getParty()).thenReturn(party);
        when(bot.getMap()).thenReturn(botMap);
        when(bot.getMapId()).thenReturn(botMapId);
        when(bot.getPosition()).thenReturn(new Point());
        when(actor.character()).thenReturn(bot);
        when(actor.actor()).thenReturn(mock(TownResident.class));
        when(actor.actor().getFootholdId()).thenReturn(1);
        when(botMap.getPointBelow(any(Point.class))).thenAnswer(call -> {
            Point point = call.getArgument(0);
            return new Point(point.x, point.y + 2);
        });
        when(botMap.calcDropPos(any(Point.class), any(Point.class))).thenAnswer(call -> new Point((Point) call.getArgument(0)));
        KpqPartySession session = new KpqPartySession(host, party, List.of(actor), List.of(), Runnable::run);
        return new Fixture(session, actor, botMap, event, host, bot);
    }
}
