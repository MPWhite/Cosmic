package server.population;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class ResidentSocialTest {
    @Test
    void actualSpeechStartsACooldownButCheckingCandidatesDoesNot() {
        var social = new ResidentSocial();
        assertTrue(social.canSpeak(1, "taking a break", 1000));
        assertTrue(social.canSpeak(1, "taking a break", 1000));
        social.recordSpeech(1, "taking a break", 1000);
        assertFalse(social.canSpeak(1, "which map next", 10_000));
        assertTrue(social.canSpeak(1, "which map next", 100_000));
    }

    @Test
    void repeatedLinesAreSuppressedAcrossSpeakersAndForLongerWithinOneSpeaker() {
        var social = new ResidentSocial();
        social.recordSpeech(1, "Just chilling", 1000);
        assertFalse(social.canSpeak(2, "  just   CHILLING  ", 50_000));
        assertTrue(social.canSpeak(2, "just chilling", 100_000));
        assertFalse(social.canSpeak(1, "just chilling", 100_000));
        assertTrue(social.canSpeak(1, "just chilling", 500_000));
        assertFalse(social.canSpeak(2, " ", 500_000));
    }

    @Test
    void replyTimingVariesButRemainsBoundedAndReproducible() {
        var resident = ResidentProfiles.all().getFirst();
        var delays = new HashSet<Long>();
        for (int sequence = 0; sequence < 10; sequence++) {
            long delay = ResidentSocial.replyDelay(resident, "hey, how's it going?", sequence);
            assertTrue(delay >= 420 && delay < 2220);
            assertEquals(delay, ResidentSocial.replyDelay(resident, "hey, how's it going?", sequence));
            delays.add(delay);
        }
        assertTrue(delays.size() > 5);
    }

    @Test
    void pairedExchangeContainsTwoShortRelatedLines() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        var exchange = dialogue.exchange(ResidentProfiles.all().getFirst(), ResidentProfiles.all().get(1),
                ResidentDialogue.Context.town(), 2);
        assertEquals("taking a break?", exchange.opener());
        assertEquals("yeah, just a minute", exchange.answer());
        assertTrue(exchange.opener().length() < 80);
        assertTrue(exchange.answer().length() < 80);
    }
}
