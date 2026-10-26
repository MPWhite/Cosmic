package server.population;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResidentChatTurnTest {
    @Test
    void greetingBurstAnswersTheQuestionRatherThanDroppingIt() {
        var turn = new ResidentChatTurn("PipArcher", "hey");
        turn.append("Pip, lvl?");
        turn.append("lol");
        assertEquals("Pip, lvl?", turn.message());
    }

    @Test
    void latestCorrectionWinsBeforeAnyMovementCommandExecutes() {
        var turn = new ResidentChatTurn("MapleMia", "follow me");
        turn.append("actually stay here");
        assertEquals("actually stay here", turn.message());
        assertTrue(ResidentDialogue.continuesConversation("actually, stay here"));
        assertTrue(ResidentDialogue.continuesConversation("hey, follow me"));
        assertFalse(ResidentDialogue.continuesConversation("RealPlayer, hello"));
        turn.append("dont stop following");
        assertEquals("dont stop following", turn.message());
    }

    @Test
    void aBurstCannotKeepOldCommandsOrUnboundedTextAlive() {
        var turn = new ResidentChatTurn("MapleMia", "follow me");
        turn.append("hi");
        turn.append("hi");
        turn.append("hi");
        assertEquals("hi", turn.message());
        turn.append("x".repeat(2000));
        assertEquals(120, turn.message().length());
    }
}
