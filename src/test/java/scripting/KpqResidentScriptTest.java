package scripting;

import client.Character;
import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import scripting.event.EventInstanceManager;
import scripting.npc.NPCConversationManager;
import server.population.party.KpqPartyService;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Executes the real Cloto script, including resident observation hooks and ordinary checks. */
class KpqResidentScriptTest {
    private final AbstractScriptManager scripts = new AbstractScriptManager() { };

    @BeforeAll static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    @ParameterizedTest
    @CsvSource({"1,10", "2,35", "3,20", "4,25", "5,25", "6,30", "7,8"})
    void eachCouponQuestionRequiresTheExactCountAndExchangesForOneRealPass(int question, int answer) throws Exception {
        Fixture f = fixture(103000800, false);
        when(f.event.gridCheck(f.player)).thenReturn(question);
        AtomicInteger coupons = new AtomicInteger(answer + 1);
        AtomicInteger passes = new AtomicInteger();
        when(f.cm.itemQuantity(4001007)).thenAnswer(call -> coupons.get());
        doAnswer(call -> {
            int item = call.getArgument(0);
            short quantity = call.getArgument(1);
            (item == 4001007 ? coupons : passes).addAndGet(quantity);
            return null;
        }).when(f.cm).gainItem(anyInt(), anyShort());
        dialogue(f.cm);
        assertEquals(0, passes.get(), "extra coupons must not be accepted");
        assertEquals(answer + 1, coupons.get());
        coupons.set(answer - 1);
        dialogue(f.cm);
        assertEquals(0, passes.get(), "too few coupons must not be accepted");
        coupons.set(answer);
        dialogue(f.cm);
        assertEquals(0, coupons.get());
        assertEquals(1, passes.get());
        verify(f.event).gridInsert(f.player, 0);
        verify(f.event, never()).linkToNextStage(anyInt(), anyString(), anyInt());
    }

    @Test
    void receivingAnNpcQuestionOnlyRevealsThatParticipantsPublicQuestion() throws Exception {
        Fixture f = fixture(103000800, false);
        when(f.event.gridCheck(f.player)).thenReturn(-1);
        try (var observed = mockStatic(KpqPartyService.class)) {
            dialogue(f.cm);
            var text = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(f.cm).sendNext(text.capture());
            assertTrue(text.getValue().startsWith("Here's the question."));
            observed.verify(() -> KpqPartyService.questionAsked(f.player, text.getValue()));
            verify(f.cm, never()).gainItem(anyInt(), anyShort());
            verify(f.event, never()).linkToNextStage(anyInt(), anyString(), anyInt());
        }
    }

    @Test
    void stageOneLeaderNeedsTheOtherThreeMembersPasses() throws Exception {
        Fixture f = fixture(103000800, true);
        when(f.event.getPlayerCount()).thenReturn(4);
        when(f.cm.hasItem(4001008, 3)).thenReturn(false);
        dialogue(f.cm);
        verify(f.event, never()).linkToNextStage(anyInt(), anyString(), anyInt());
        when(f.cm.hasItem(4001008, 3)).thenReturn(true);
        dialogue(f.cm);
        verify(f.cm).gainItem(4001008, (short) -3);
        verify(f.event).linkToNextStage(1, "kpq", 103000800);
        verify(f.event).gridClear();
    }

    @ParameterizedTest
    @CsvSource({"2,3", "3,9", "4,19"})
    void ropePlatformAndBarrelStagesCountActualBotPositionsBeforeOpeningThePortal(int stage, int solution) throws Exception {
        int mapId = 103000799 + stage;
        Fixture f = fixture(mapId, true);
        f.properties.put("stg" + stage + "Property", Integer.toString(solution));
        Point[] spots = switch (stage) {
            case 2 -> new Point[]{new Point(-753, -20), new Point(-719, -250), new Point(-584, -250)};
            case 3 -> new Point[]{new Point(678, -160), new Point(861, -95), new Point(1028, -160)};
            default -> new Point[]{new Point(927, -234), new Point(894, -182), new Point(963, -182)};
        };
        Character a = positioned(1_600_000_001, spots[0]);
        Character b = positioned(1_600_000_002, spots[1]);
        Character c = positioned(1_600_000_003, spots[1]); // two members in the same place must fail
        when(f.player.getPosition()).thenReturn(new Point(-2000, -2000));
        when(f.event.getPlayers()).thenReturn(List.of(f.player, a, b, c));
        when(f.event.getPlayerCount()).thenReturn(4);
        try (var observations = mockStatic(KpqPartyService.class)) {
            dialogue(f.cm);
            verify(f.event).showWrongEffect();
            verify(f.event, never()).linkToNextStage(anyInt(), anyString(), anyInt());
            observations.verify(() -> KpqPartyService.checked(f.event, mapId, false));
            when(c.getPosition()).thenReturn(spots[2]);
            dialogue(f.cm);
            observations.verify(() -> KpqPartyService.checked(f.event, mapId, true));
            verify(f.event).showClearEffect(true);
            verify(f.event).linkToNextStage(stage, "kpq", mapId);
            assertEquals("true", f.properties.get(stage + "stageclear"));
        }
    }

    @Test
    void finalStageConsumesTenPassesThenUsesTheOrdinaryRewardAndBonusWarp() throws Exception {
        Fixture f = fixture(103000804, true);
        when(f.cm.haveItem(4001008, 10)).thenReturn(false);
        dialogue(f.cm);
        verify(f.event, never()).clearPQ();
        when(f.cm.haveItem(4001008, 10)).thenReturn(true);
        dialogue(f.cm);
        verify(f.cm).gainItem(4001008, (short) -10);
        verify(f.event).clearPQ();
        verify(f.event).linkToNextStage(5, "kpq", 103000804);
        when(f.event.giveEventReward(f.player)).thenReturn(false);
        try (GraalJSScriptEngine engine = open(f.cm)) {
            engine.invokeFunction("start");
            engine.invokeFunction("action", 1, 0, 0);
        }
        verify(f.cm, never()).warp(103000805, "st00");
        when(f.event.giveEventReward(f.player)).thenReturn(true);
        try (GraalJSScriptEngine engine = open(f.cm)) {
            engine.invokeFunction("start");
            engine.invokeFunction("action", 1, 0, 0);
        }
        verify(f.cm).warp(103000805, "st00");
    }

    private Character positioned(int id, Point position) {
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(id);
        when(player.isResidentBot()).thenReturn(true);
        when(player.getPosition()).thenReturn(position);
        return player;
    }

    private record Fixture(Character player, NPCConversationManager cm, EventInstanceManager event,
                           Map<String, String> properties) { }

    private Fixture fixture(int mapId, boolean leader) {
        Character player = mock(Character.class);
        NPCConversationManager cm = mock(NPCConversationManager.class);
        EventInstanceManager event = mock(EventInstanceManager.class);
        Map<String, String> properties = new HashMap<>();
        when(cm.getPlayer()).thenReturn(player);
        when(cm.getEventInstance()).thenReturn(event);
        when(cm.getMapId()).thenReturn(mapId);
        when(cm.isEventLeader()).thenReturn(leader);
        when(event.isEventLeader(player)).thenReturn(leader);
        when(event.getProperty(anyString())).thenAnswer(call -> properties.get(call.getArgument(0)));
        doAnswer(call -> properties.put(call.getArgument(0), call.getArgument(1)))
                .when(event).setProperty(anyString(), anyString());
        return new Fixture(player, cm, event, properties);
    }

    private GraalJSScriptEngine open(NPCConversationManager cm) {
        var engine = (GraalJSScriptEngine) scripts.getInvocableScriptEngine("npc/9020001.js");
        assertNotNull(engine);
        engine.put("cm", cm);
        return engine;
    }

    private void dialogue(NPCConversationManager cm) throws Exception {
        try (GraalJSScriptEngine engine = open(cm)) { engine.invokeFunction("start"); }
    }
}
