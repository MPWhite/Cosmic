package scripting;

import client.Character;
import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scripting.npc.NPCConversationManager;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RebirthJobAdvancementTest {
    private final AbstractScriptManager scriptManager = new AbstractScriptManager() {};

    @BeforeAll
    static void muteGraal() {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
    }

    static Stream<Arguments> instructors() {
        return Stream.of(
                Arguments.of(2020008, 110),
                Arguments.of(2020009, 210),
                Arguments.of(2020010, 310),
                Arguments.of(2020011, 410),
                Arguments.of(2020013, 510));
    }

    @ParameterizedTest
    @MethodSource("instructors")
    void rebornCharactersCanAdvanceWithRetainedSp(int npcId, int secondJob) throws Exception {
        NPCConversationManager cm = completeThirdJobDialogue(npcId, secondJob, 1, 500);

        verify(cm).changeJobById(secondJob + 1);
        verify(cm).gainItem(4031058, (short) -1);
        verify(cm.getPlayer()).removePartyQuestItem("JBQ");
        verify(cm, never()).dispose();
    }

    @ParameterizedTest
    @MethodSource("instructors")
    void firstLifeCharactersMustStillSpendTheirSp(int npcId, int secondJob) throws Exception {
        NPCConversationManager cm = completeThirdJobDialogue(npcId, secondJob, 0, 500);

        verify(cm, never()).changeJobById(anyInt());
        verify(cm, never()).gainItem(4031058, (short) -1);
        verify(cm).sendNext("Please, use all your SP before continuing.");
        verify(cm).dispose();
    }

    @ParameterizedTest
    @MethodSource("instructors")
    void firstLifeCharactersWithSpentSpCanStillAdvance(int npcId, int secondJob) throws Exception {
        NPCConversationManager cm = completeThirdJobDialogue(npcId, secondJob, 0, 0);

        verify(cm).changeJobById(secondJob + 1);
        verify(cm).gainItem(4031058, (short) -1);
    }

    private NPCConversationManager completeThirdJobDialogue(int npcId, int secondJob, int reborns, int sp)
            throws Exception {
        Character player = mock(Character.class);
        NPCConversationManager cm = mock(NPCConversationManager.class);
        AtomicInteger job = new AtomicInteger(secondJob);
        when(cm.getPlayer()).thenReturn(player);
        when(cm.getJobId()).thenAnswer(invocation -> job.get());
        when(cm.getLevel()).thenReturn(70);
        when(cm.haveItem(4031058)).thenReturn(true);
        when(player.getLevel()).thenReturn(70);
        when(player.getReborns()).thenReturn(reborns);
        when(player.getRemainingSp()).thenReturn(sp);
        doAnswer(invocation -> {
            job.set(invocation.getArgument(0));
            return null;
        }).when(cm).changeJobById(anyInt());

        try (GraalJSScriptEngine engine = (GraalJSScriptEngine)
                scriptManager.getInvocableScriptEngine("npc/" + npcId + ".js")) {
            assertNotNull(engine);
            engine.put("cm", cm);
            engine.invokeFunction("start");
            engine.invokeFunction("action", 1, 0, 0);
            engine.invokeFunction("action", 1, 0, 0);
            engine.invokeFunction("action", 1, 1, 1);
        }
        return cm;
    }
}
