package server.population.party;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KpqRecruitmentTest {
    @Test void casualPartyRequestsRecruit() {
        for (String s : new String[] {"anyone wanna KPQ?", "lfp kpq", "let's do kpq", "can we run kpq?", "kpq"})
            assertTrue(KpqPartyService.isRecruitment(s), s);
    }

    @Test void questionsAndRefusalsNeverCreateParties() {
        for (String s : new String[] {"what level is kpq?", "how do we do kpq", "where is KPQ", "kpq rewards?",
                "don't want kpq", "not doing kpq", "no kpq", "what are the kpq requirements?", "lpq?"})
            assertFalse(KpqPartyService.isRecruitment(s), s);
    }

    @Test void directLeadershipRequestsAcceptPolitenessAndKnownNames() {
        for (String s : new String[] {"I'll lead", "let me lead", "pass lead pls", "can you give me lead?", "Mia, can I lead?"})
            assertEquals(KpqLeadership.HUMAN, KpqLeadership.parse(s, "MapleMia"), s);
        for (String s : new String[] {"can you lead?", "could u lead kpq pls?", "you lead", "Mia, please take the lead", "you lead, MapleMia"})
            assertEquals(KpqLeadership.RESIDENT, KpqLeadership.parse(s, "MapleMia"), s);
    }

    @Test void leadershipQuestionsNegationsAndQuotedCommandsDoNotMutateParty() {
        for (String s : new String[] {"how do you lead KPQ?", "don't give me lead", "do not pass lead", "can you not lead?",
                "why can't you lead?", "what happens if you lead", "someone said 'give me lead'", "I don't want you to lead",
                "you lead? no, I'll lead", "do you lead every time?", "Stranger, you lead"})
            assertEquals(KpqLeadership.NONE, KpqLeadership.parse(s, "MapleMia"), s);
    }

    @Test @ResourceLock("SYSTEM_PROPERTIES")
    void disabledResidentsCannotRecruitThroughChatOrExplicitCommand() {
        String previous = System.getProperty("cosmic.residents.enabled");
        try {
            System.setProperty("cosmic.residents.enabled", "false");
            var player = mock(client.Character.class);
            assertFalse(KpqPartyService.onChat(player, "anyone wanna KPQ?", false));
            verifyNoInteractions(player);
            KpqPartyService.request(player, false);
            verify(player).dropMessage(5, "Resident parties are disabled on this server.");
            verifyNoMoreInteractions(player);
        } finally {
            if (previous == null) System.clearProperty("cosmic.residents.enabled");
            else System.setProperty("cosmic.residents.enabled", previous);
        }
    }
}
