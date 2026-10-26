package server.population;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static server.population.ResidentChatInput.Intent.*;

class ResidentChatInputTest {
    @Test
    void understandsOrdinaryMapleShorthandWithoutConfusingTrainingWithAProfileQuestion() {
        assertEquals(LEVEL, parse("lvl?").intent());
        assertEquals(LEVEL, parse("what lvl r u").intent());
        assertEquals(LEVEL, parse("what's ur level??").intent());
        assertEquals(HUNT, parse("just grinding levels lol").intent());
        assertEquals(HELP, parse("what level should i train at?").intent());
        assertEquals(JOB, parse("what class r u?").intent());
        assertEquals(JOB, parse("job?").intent());
        assertEquals(JOB, parse("r u a cleric").intent());
    }

    @Test
    void recognizesMovementRequestsButDoesNotExecuteMentionedOrNegatedCommands() {
        assertEquals(FOLLOW, parse("follow me?").intent());
        assertEquals(FOLLOW, parse("can u pls follow me to Ellinia?").intent());
        assertEquals(FOLLOW, parse("please don't stop following me!").intent());
        assertEquals(FOLLOW, parse("do not stop following").intent());
        assertEquals(FOLLOW, parse("let's go").intent());
        assertEquals(FOLLOW, parse("plsss follow me lol").intent());
        assertEquals(STAY, parse("don't follow me pls").intent());
        assertEquals(STAY, parse("can you wait here?").intent());
        assertEquals(STAY, parse("can u wait?").intent());
        assertEquals(STAY, parse("hold up").intent());
        assertEquals(STAY, parse("actually stay here").intent());
        assertEquals(STAY, parse("actually, stay here").intent());
        assertEquals(FOLLOW, parse("actually don't stop following me").intent());
        assertEquals(STAY, parse("stay").intent());
        assertEquals(STAY, parse("stop following").intent());
        assertEquals(UNKNOWN, parse("don't stop").intent());
        assertEquals(UNKNOWN, parse("don't stay here").intent());
        assertEquals(UNKNOWN, parse("don't come with me").intent());
        assertEquals(DISMISS, parse("not yet").intent());
        assertEquals(DISMISS, parse("nah lets go later").intent());
        assertEquals(STATUS, parse("u coming?").intent());
        assertEquals(UNKNOWN, parse("why are you following me?").intent());
        assertEquals(UNKNOWN, parse("someone told me to 'follow me'").intent());
        assertEquals(HELP, parse("how do I stop dying?").intent());
        assertEquals(MAP_COURTESY, parse("stop ksing pls").intent());
    }

    @Test
    void onlyKnownNamesAreRemovedAndExactNameBoundariesAreRequired() {
        ResidentChatInput addressed = parse("@MapleMia: follow me?");
        assertEquals("follow me", addressed.text());
        assertTrue(addressed.addressed());
        assertTrue(addressed.question());
        assertEquals(FOLLOW, addressed.intent());
        assertEquals(FOLLOW, parse("hey MapleMia, follow me pls").intent());
        assertTrue(ResidentChatInput.parse("PipArcher, stay", "MapleMia", List.of("PipArcher")).addressed());
        assertEquals(FOLLOW, parse("follow me, MapleMia").intent());
        assertEquals(STAY, ResidentChatInput.parse("stay, Pip", "MapleMia", List.of("Pip")).intent());
        assertEquals(FOLLOW, ResidentChatInput.parse("follow me, Mia", "MapleMia", List.of("Mia")).intent());
        assertEquals(UNKNOWN, parse("follow me, Stranger").intent());
        assertFalse(parse("follow me SomeMapleMia").addressed());
        assertEquals("stranger stay", parse("Stranger, stay").text());
        assertEquals(UNKNOWN, parse("Stranger, stay").intent());
        assertEquals("hello there", parse("hello there").text());
        assertFalse(parse("MapleMias bow is nice").addressed());
        assertFalse(parse("I saw MapleMia earlier").addressed());
    }

    @Test
    void tradingMapEtiquetteAndPartyRequestsSurvivePunctuationAndSmallTalk() {
        assertEquals(BUY, parse("B> arrows").intent());
        assertEquals(SELL, parse("MapleMia: S> 60% scroll").intent());
        assertEquals(BUY, parse("wtb pots lol").intent());
        assertEquals(SELL, parse("wts claw").intent());
        assertEquals(TRADE, parse("trade me mesos").intent());
        assertEquals(TRADE, parse("gotpots?").intent());
        assertEquals(PARTY, parse("lfp kpq").intent());
        assertEquals(PARTY, parse("LPQ anyone??").intent());
        assertEquals(PARTY, parse("inv me pls").intent());
        assertEquals(MAP_COURTESY, parse("cc pls").intent());
        assertEquals(MAP_COURTESY, parse("ccpls").intent());
        assertEquals(MAP_COURTESY, parse("map taken?").intent());
        assertEquals(MAP_COURTESY, parse("hey stop ks").intent());
        assertEquals(UNKNOWN, parse("success takes practice").intent());
        assertEquals(BUFF, parse("hs pls?").intent());
        assertEquals(BUFF, parse("hey haste please").intent());
    }

    @Test
    void npcDirectionsDoNotTriggerAnIdentityDisclosureResponse() {
        assertEquals(LOCATION, parse("where is the npc?").intent());
        assertEquals(LOCATION, parse("where is the potion shop npc").intent());
        assertEquals(IDENTITY, parse("are you a bot?").intent());
        assertEquals(IDENTITY, parse("r u a real player?").intent());
        assertEquals(IDENTITY, parse("you an npc?").intent());
        assertEquals(ABOUT, parse("who are you").intent());
        assertEquals(UNKNOWN, parse("the npc near the bowman place").intent());
    }

    @Test
    void casualReactionsRemainDistinctAndRepeatedLettersOnlyAffectKnownSocialWords() {
        assertEquals(GREETING, parse("hiiii!!").intent());
        assertEquals(GREETING, parse("heyyyyyyy").intent());
        assertEquals(GREETING, parse("hellooooo").intent());
        assertEquals(THANKS, parse("tyyyy").intent());
        assertEquals(GRATS, parse("gz!!").intent());
        assertEquals(LEVEL_UP, parse("i just leveled!").intent());
        assertEquals(BRB, parse("brb a sec").intent());
        assertEquals(LAUGH, parse("lololol").intent());
        assertEquals(LAUGH, parse("hahahaha").intent());
        assertEquals(DISMISS, parse("no ty").intent());
        assertEquals(ACK, parse("kk").intent());
        assertEquals(STATUS, parse("wyd").intent());
        assertEquals(WELCOME_BACK, parse("wb").intent());
        assertEquals(WELCOME_BACK, parse("im back").intent());
        assertEquals(COMPLIMENT, parse("nicehat").intent());
        assertEquals(COMPLIMENT, parse("nice dmg").intent());
        assertEquals(PROGRESS, parse("99.9%!").intent());
        assertEquals(PROGRESS, parse("99.9% lol").intent());
        assertEquals(COMPLIMENT, parse("nicehat lol").intent());
        assertEquals(MONSTERS, parse("those snails are cute").intent());
        assertTrue(parse("slimes and mushrooms").has("slimes", "mushrooms"));
        assertEquals("ill check the skill book", parse("I'll check the skill book").text());
        assertEquals(UNKNOWN, parse("this hillside is nice").intent());
    }

    @Test
    void phraseMatchingUsesWholeWordsAndInputWorkIsBounded() {
        ResidentChatInput input = parse("this is a skill book");
        assertFalse(input.containsPhrase("hi"));
        assertFalse(input.containsPhrase("kill"));
        assertTrue(input.containsPhrase("skill book"));
        assertTrue(input.has("not here", "skill"));
        assertFalse(input.containsPhrase(""));
        assertEquals(UNKNOWN, parse(null).intent());
        assertTrue(parse("a".repeat(100_000)).text().length() <= 512);
        assertEquals(UNKNOWN, parse("a".repeat(512) + " follow me").intent());
        assertEquals(LEVEL, parse("ＬＶＬ？").intent());
    }

    private static ResidentChatInput parse(String text) {
        return ResidentChatInput.parse(text, "MapleMia");
    }
}
