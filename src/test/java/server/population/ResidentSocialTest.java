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
    void pairedExchangesKeepEachSpeakersVoiceAndVaryAcrossRelatedSubjects() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        var mia = ResidentProfiles.find("MapleMia").orElseThrow();
        var pip = ResidentProfiles.find("PipArcher").orElseThrow();
        var juniper = ResidentProfiles.find("Juniper").orElseThrow();
        var oak = ResidentProfiles.find("OakWarden").orElseThrow();
        var town = ResidentDialogue.Context.town();
        var exchange = dialogue.exchange(mia, pip, town, 0);
        assertEquals(exchange, dialogue.exchange(mia, pip, town, 0));

        // Compensate for the identity seed so the subject stays the same while one voice changes.
        var differentAnswer = dialogue.exchange(mia, juniper, town, pip.id() - juniper.id());
        assertEquals(afterAddress(exchange.opener(), pip), afterAddress(differentAnswer.opener(), juniper));
        assertNotEquals(exchange.answer(), differentAnswer.answer());
        var differentOpener = dialogue.exchange(oak, pip, town, mia.id() - oak.id());
        assertNotEquals(exchange.opener(), differentOpener.opener());
        assertEquals(afterAddress(exchange.answer(), mia), afterAddress(differentOpener.answer(), oak));

        var variants = new HashSet<ResidentDialogue.Exchange>();
        for (int sequence = 0; sequence < 12; sequence++) {
            var pair = dialogue.exchange(mia, pip, town, sequence);
            assertFalse(afterAddress(pair.opener(), pip).isBlank());
            assertFalse(afterAddress(pair.answer(), mia).isBlank());
            assertTrue(pair.opener().length() < 80);
            assertTrue(pair.answer().length() < 80);
            variants.add(pair);
        }
        assertTrue(variants.size() >= 6, "the same pair needs multiple exchanges per subject");
    }

    private static String afterAddress(String line, ResidentProfiles.Profile addressed) {
        String prefix = addressed.name() + ", ";
        assertTrue(line.startsWith(prefix), "make the target of each exchange visible: " + line);
        return line.substring(prefix.length());
    }
}
