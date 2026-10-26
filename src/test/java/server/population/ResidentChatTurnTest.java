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

    @Test
    void laughterDoesNotEraseATauntAndASwearyCorrectionStillStopsFollowing() {
        var turn = new ResidentChatTurn("PipArcher", "Pip, ur trash");
        turn.append("lol");
        assertEquals("Pip, ur trash", turn.message());
        turn.append("dont fucking follow me");
        assertEquals("dont fucking follow me", turn.message());
        assertEquals(ResidentDialogue.Intent.STOP_FOLLOWING, ResidentDialogue.intent(turn.message()));
    }

    @Test
    void anAffirmativeSurvivesTrailingLaughterButNotALaterRefusal() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        var pip = ResidentProfiles.find("PipArcher").orElseThrow();
        dialogue.reply(pip, 42, "Guest", "wanna train?");
        var turn = new ResidentChatTurn(pip.name(), "hell yeah");
        turn.append("lol");
        assertEquals(ResidentDialogue.Intent.FOLLOW, dialogue.intentFor(pip, 42, turn.message()));
        turn.append("actually stay here");
        assertEquals(ResidentDialogue.Intent.STOP_FOLLOWING, dialogue.intentFor(pip, 42, turn.message()));
    }
}
