package server.population;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidentDialogueTest {
    @TempDir
    Path temporary;

    @Test
    void remembersPlayerAndConversationAfterRestart() throws IOException {
        Path file = temporary.resolve("population/memories.properties");
        var firstStore = new ResidentMemoryStore(file);
        var dialogue = new ResidentDialogue(firstStore);
        var mia = ResidentProfiles.all().getFirst();

        assertTrue(dialogue.greeting(mia, 42, "MapleGuest").length() < 80);
        dialogue.reply(mia, 42, "MapleGuest", "I like mushrooms");
        firstStore.flush();

        var reloaded = new ResidentMemoryStore(file);
        var afterRestart = new ResidentDialogue(reloaded);
        String recognition = afterRestart.reply(mia, 42, "MapleGuest", "Do you remember me?");
        assertAll(
                () -> assertTrue(recognition.contains("MapleGuest")),
                () -> assertTrue(recognition.contains("mushrooms")),
                () -> assertEquals(1, reloaded.get(42, mia.id(), "MapleGuest").visits()),
                () -> assertEquals(2, reloaded.get(42, mia.id(), "MapleGuest").conversations())
        );
        afterRestart.greeting(mia, 42, "MapleGuest");
        assertEquals(2, reloaded.get(42, mia.id(), "MapleGuest").visits());
    }

    @Test
    void relationshipsAreIsolatedByPlayerAndResident() {
        var store = ResidentMemoryStore.inMemory();
        var dialogue = new ResidentDialogue(store);
        var mia = ResidentProfiles.all().getFirst();
        var pip = ResidentProfiles.all().get(1);

        dialogue.reply(mia, 42, "First", "mushrooms");
        String stranger = dialogue.reply(mia, 43, "Second", "remember me?");
        String otherResident = dialogue.reply(pip, 42, "First", "remember me?");

        assertTrue(stranger.contains("don't think we've met"));
        assertTrue(otherResident.contains("don't think we've met"));
        assertFalse(stranger.contains("mushrooms"));
        assertFalse(otherResident.contains("mushrooms"));
    }

    @Test
    void repeatedGreetingDoesNotEraseRememberedTopic() {
        var store = ResidentMemoryStore.inMemory();
        var dialogue = new ResidentDialogue(store);
        var pip = ResidentProfiles.all().get(1);

        dialogue.reply(pip, 8, "Ranger", "I am training today");
        dialogue.reply(pip, 8, "Ranger", "hello again");
        assertTrue(dialogue.reply(pip, 8, "Ranger", "remember me").contains("your training"));
        dialogue.greeting(pip, 8, "Ranger");
        assertEquals("your training", store.get(8, pip.id(), "Ranger").lastTopic());
    }

    @Test
    void doesNotTreatHiInsideAnotherWordAsGreeting() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        var mia = ResidentProfiles.all().getFirst();
        String response = dialogue.reply(mia, 9, "Traveler", "this is interesting");
        assertFromTopic(mia, ResidentVoice.Topic.UNKNOWN, response);
        assertFalse(Arrays.asList(ResidentVoice.lines(mia, ResidentVoice.Topic.HELLO,
                ResidentDialogue.Context.town())).contains(response));
    }

    @Test
    void stopIntentWinsOverFollowAndPartyDoesNotPromiseAnActualPartySlot() {
        assertEquals(ResidentDialogue.Intent.FOLLOW, ResidentDialogue.intent("Please follow me"));
        assertEquals(ResidentDialogue.Intent.STOP_FOLLOWING, ResidentDialogue.intent("don't follow me"));
        assertEquals(ResidentDialogue.Intent.STOP_FOLLOWING, ResidentDialogue.intent("stop following"));
        assertEquals(ResidentDialogue.Intent.STOP_FOLLOWING, ResidentDialogue.intent("stay"));
        assertEquals(ResidentDialogue.Intent.TALK, ResidentDialogue.intent("hello there"));

        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        assertTrue(dialogue.reply(ResidentProfiles.all().getFirst(), 1, "Guest", "join my party")
                .contains("party"));
        assertFalse(dialogue.reply(ResidentProfiles.all().get(3), 1, "Guest", "join my party")
                .contains("follow me"));
        var independent = ResidentProfiles.all().get(3);
        assertFromTopic(independent, ResidentVoice.Topic.FOLLOW_DECLINE,
                dialogue.reply(independent, 1, "Guest", "follow me"));
    }

    @Test
    void intentSeparatesGameRequestsAndDoesNotTreatStopInAQuestionAsACommand() {
        assertEquals(ResidentDialogue.Intent.PARTY, ResidentDialogue.intent("join my party?"));
        assertEquals(ResidentDialogue.Intent.HUNT, ResidentDialogue.intent("want to hunt mushrooms?"));
        assertEquals(ResidentDialogue.Intent.TRADE, ResidentDialogue.intent("wtb potions"));
        assertEquals(ResidentDialogue.Intent.HELP, ResidentDialogue.intent("help with this quest"));
        assertEquals(ResidentDialogue.Intent.FAREWELL, ResidentDialogue.intent("gtg"));
        assertEquals(ResidentDialogue.Intent.STOP_FOLLOWING, ResidentDialogue.intent("MapleMia, stay"));
        assertEquals(ResidentDialogue.Intent.HELP, ResidentDialogue.intent("how do I stop dying?"));
    }

    @Test
    void repeatedShortRepliesVaryWithoutRecitingThePlayersName() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        for (String name : List.of("MapleMia", "PipArcher", "Juniper", "OakWarden", "HazelHop")) {
            var resident = ResidentProfiles.find(name).orElseThrow();
            var replies = new HashSet<String>();
            String previous = "";
            for (int turn = 0; turn < 12; turn++) {
                String reply = dialogue.reply(resident, 77, "TestPlayer", "ty");
                assertNotEquals(previous, reply, name + " repeated an immediate reply");
                assertTrue(reply.length() < 30);
                assertFalse(reply.contains("TestPlayer"));
                assertFromTopic(resident, ResidentVoice.Topic.THANKS, reply);
                replies.add(reply);
                previous = reply;
            }
            assertTrue(replies.size() >= 3, name + " needs more than two alternating replies");
        }
    }

    @Test
    void activityContextChangesWhatTheResidentSaysWithoutInventingTradesOrQuests() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        var mia = ResidentProfiles.all().getFirst();
        var hunting = new ResidentDialogue.Context(ResidentDialogue.Activity.TRAINING, "Henesys Hunting Ground", true);
        var exploring = new ResidentDialogue.Context(ResidentDialogue.Activity.EXPLORING, "Henesys Hunting Ground", false);
        assertTrue(dialogue.reply(mia, 90, "Guest", "where are we?", exploring).contains("Henesys Hunting Ground"));
        String combat = dialogue.reply(mia, 90, "Guest", "want to hunt?", hunting);
        assertFalse(combat.contains("say 'follow me'"));
        String trade = dialogue.reply(mia, 90, "Guest", "trade me mesos", hunting);
        assertTrue(trade.contains("can't trade") || trade.contains("nothing for sale") || trade.contains("no trades"));
        String quest = dialogue.reply(mia, 90, "Guest", "do my quest", hunting);
        assertTrue(quest.contains("quest npc"));
        assertTrue(quest.contains("yourself"), "quest interaction must remain the player's action");
    }

    @Test
    void shortProfileQuestionsUseTheActualLevelAndSpecificJob() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        var pip = ResidentProfiles.find("PipArcher").orElseThrow();

        String level = dialogue.reply(pip, 42, "Guest", "lvl?");
        assertTrue(level.matches(".*\\b32\\b.*"), level);
        assertTrue(level.contains("hunter"), "Pip is a hunter, not just the broad archer family");
        assertEquals("hunter", dialogue.reply(pip, 42, "Guest", "job?"));
        assertEquals("hunter", dialogue.reply(pip, 42, "Guest", "r u a cleric?"));
        assertEquals(32, pip.level());
        assertEquals(310, pip.job());
    }

    @Test
    void aNumericAnswerContinuesTheLevelQuestionAndDoesNotLeakBetweenConversations() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        var mia = ResidentProfiles.find("MapleMia").orElseThrow();
        var pip = ResidentProfiles.find("PipArcher").orElseThrow();

        assertTrue(dialogue.reply(mia, 42, "First", "lvl?").endsWith("u?"));
        assertFromTopic(mia, ResidentVoice.Topic.UNKNOWN, dialogue.reply(mia, 43, "Second", "23"));
        assertFromTopic(pip, ResidentVoice.Topic.UNKNOWN, dialogue.reply(pip, 42, "First", "23"));
        String answer = dialogue.reply(mia, 42, "First", "23");
        assertFalse(Arrays.asList(ResidentVoice.lines(mia, ResidentVoice.Topic.UNKNOWN,
                ResidentDialogue.Context.town())).contains(answer), "a level answer must not receive 'huh?'");
        assertFalse(answer.contains("?"), "do not ask the answered question again");
        assertTrue(dialogue.reply(mia, 42, "First", "lvl?").contains("24"));
        assertEquals(24, mia.level(), "the player's answer must not alter the resident's profile");
    }

    @Test
    void whyContinuesARecentPartyReplyButNotAnotherPlayersOrAnExpiredConversation() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1_000L);
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory(), clock);
        var mia = ResidentProfiles.find("MapleMia").orElseThrow();

        dialogue.reply(mia, 42, "First", "party?");
        assertFromTopic(mia, ResidentVoice.Topic.UNKNOWN, dialogue.reply(mia, 43, "Second", "why?"));
        String explanation = dialogue.reply(mia, 42, "First", "why?");
        assertTrue(explanation.contains("can't accept party invites"));
        assertFalse(explanation.contains("bot"), "a capability follow-up does not need an identity lecture");

        dialogue.reply(mia, 42, "First", "party?");
        when(clock.millis()).thenReturn(91_001L);
        assertFromTopic(mia, ResidentVoice.Topic.UNKNOWN, dialogue.reply(mia, 42, "First", "why?"));
    }

    @Test
    void followOffersRequireAnUnexpiredAffirmativeFromTheSamePlayerForTheSameResident() {
        Clock clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1_000L);
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory(), clock);
        var mia = ResidentProfiles.find("MapleMia").orElseThrow();
        var pip = ResidentProfiles.find("PipArcher").orElseThrow();

        dialogue.reply(mia, 42, "First", "want to hunt?");
        assertEquals(ResidentDialogue.Intent.TALK, dialogue.intentFor(mia, 43, "yes"));
        assertEquals(ResidentDialogue.Intent.TALK, dialogue.intentFor(pip, 42, "yes"));
        assertEquals(ResidentDialogue.Intent.FOLLOW, dialogue.intentFor(mia, 42, "yes"));
        assertEquals(ResidentDialogue.Intent.FOLLOW, dialogue.intentFor(mia, 42, "yes"),
                "routing an answer must not consume it before the reply handles it");
        assertFromTopic(mia, ResidentVoice.Topic.FOLLOW_ACCEPT, dialogue.reply(mia, 42, "First", "yes"));
        assertEquals(ResidentDialogue.Intent.TALK, dialogue.intentFor(mia, 42, "yes"));

        dialogue.reply(mia, 42, "First", "want to hunt?");
        assertEquals(ResidentDialogue.Intent.TALK, dialogue.intentFor(mia, 42, "nah"));
        assertFromTopic(mia, ResidentVoice.Topic.ACK, dialogue.reply(mia, 42, "First", "nah"));
        assertEquals(ResidentDialogue.Intent.TALK, dialogue.intentFor(mia, 42, "yes"));

        dialogue.reply(mia, 42, "First", "want to hunt?");
        when(clock.millis()).thenReturn(91_001L);
        assertEquals(ResidentDialogue.Intent.TALK, dialogue.intentFor(mia, 42, "yes"));
        assertFromTopic(mia, ResidentVoice.Topic.ACK, dialogue.reply(mia, 42, "First", "yes"));
        var independent = ResidentProfiles.find("TheoBoots").orElseThrow();
        dialogue.reply(independent, 42, "First", "want to hunt?");
        assertEquals(ResidentDialogue.Intent.TALK, dialogue.intentFor(independent, 42, "yes"));
    }

    @Test
    void trainingAdviceUsesTheAnsweredLevelInsteadOfGenericAcknowledgement() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        var mia = ResidentProfiles.find("MapleMia").orElseThrow();
        assertTrue(dialogue.reply(mia, 42, "Guest", "where should i train?").contains("lvl"));
        String beginnerAdvice = dialogue.reply(mia, 42, "Guest", "15");
        assertTrue(beginnerAdvice.contains("mushrooms"));

        assertTrue(dialogue.reply(mia, 42, "Guest", "help me train").contains("lvl"));
        String advancedAdvice = dialogue.reply(mia, 42, "Guest", "99");
        assertTrue(advancedAdvice.contains("low"), "nearby beginner mobs are unsuitable for the stated level");
        assertNotEquals(beginnerAdvice, advancedAdvice);
        assertFalse(advancedAdvice.contains("mushrooms"));
        assertEquals(24, mia.level());
    }

    @Test
    void npcDirectionsDoNotTurnIntoBotIdentityAnswers() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        var mia = ResidentProfiles.find("MapleMia").orElseThrow();

        String directions = dialogue.reply(mia, 42, "Guest", "where is the potion shop npc?");
        assertTrue(directions.contains("shop"));
        assertFalse(directions.contains("bot"));
        assertFalse(directions.contains("server-run"));
        String whichNpc = dialogue.reply(mia, 42, "Guest", "where is the npc?");
        assertTrue(whichNpc.contains("npc"));
        assertFalse(whichNpc.contains("bot"));
        assertTrue(dialogue.reply(mia, 42, "Guest", "are you an npc?").contains("server-run bot"));
    }

    @Test
    void memoriesKeepSnailsAndSlimesDistinctAcrossConversationsAndRestart() throws IOException {
        Path file = temporary.resolve("mob-topics.properties");
        var store = new ResidentMemoryStore(file);
        var dialogue = new ResidentDialogue(store);
        var mia = ResidentProfiles.find("MapleMia").orElseThrow();

        dialogue.reply(mia, 42, "SnailFan", "those snails are cute");
        dialogue.reply(mia, 43, "SlimeFan", "slimes are cute too");
        dialogue.reply(mia, 42, "SnailFan", "heyyy");
        store.flush();
        var reloaded = new ResidentMemoryStore(file);
        var afterRestart = new ResidentDialogue(reloaded);

        String snails = afterRestart.reply(mia, 42, "SnailFan", "remember me?");
        String slimes = afterRestart.reply(mia, 43, "SlimeFan", "remember me?");
        assertAll(
                () -> assertTrue(snails.contains("snails")),
                () -> assertFalse(snails.contains("slimes")),
                () -> assertFalse(snails.contains("mushrooms")),
                () -> assertTrue(slimes.contains("slimes")),
                () -> assertFalse(slimes.contains("snails")),
                () -> assertFalse(slimes.contains("mushrooms"))
        );
        afterRestart.reply(mia, 42, "SnailFan", "actually slimes now");
        assertEquals("slimes", reloaded.get(42, mia.id(), "SnailFan").lastTopic());
    }

    @Test
    void ambientAndLevelReactionsStayShortAndBotIdentityIsHonestWhenAsked() {
        var dialogue = new ResidentDialogue(ResidentMemoryStore.inMemory());
        var mia = ResidentProfiles.all().getFirst();
        for (var activity : ResidentDialogue.Activity.values()) {
            var context = new ResidentDialogue.Context(activity, "Henesys", false);
            for (int sequence = 0; sequence < 8; sequence++) {
                assertTrue(dialogue.ambient(mia, context, sequence).length() < 100);
            }
        }
        assertTrue(dialogue.levelUp(mia, "Guest", 1).length() < 40);
        assertTrue(dialogue.reply(mia, 1, "Guest", "are you a real player?").contains("server-run bot"));
    }

    @Test
    void corruptMemoryFileIsPreservedAndNeverSilentlyReset() throws IOException {
        Path file = temporary.resolve("memories.properties");
        String original = "version=1\ncount=1\nmemory.0.player=not-a-number\n";
        Files.writeString(file, original);

        assertThrows(IOException.class, () -> new ResidentMemoryStore(file));
        assertEquals(original, Files.readString(file));
        assertThrows(IOException.class, () -> new ResidentMemoryStore(writeFile("unsupported.properties", "version=9\ncount=0\n")));
        assertThrows(IOException.class, () -> new ResidentMemoryStore(writeFile("truncated.properties", "version=1\ncount=1\n")));
    }

    @Test
    void memoryCountStaysBoundedAcrossRestart() throws IOException {
        var clock = Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC);
        var store = new ResidentMemoryStore(temporary.resolve("bounded.properties"), clock);
        for (int id = 1; id <= 5_001; id++) {
            store.visit(id, 1, "Guest" + id);
        }
        assertEquals(5_000, store.size());
        store.flush();
        assertEquals(5_000, new ResidentMemoryStore(temporary.resolve("bounded.properties")).size());
    }

    @Test
    void rosterHasTwentyStableUniqueNamesAndValidLocalAppearanceAssets() {
        var names = new HashSet<String>();
        var ids = new HashSet<Integer>();
        int companions = 0;
        for (var profile : ResidentProfiles.all()) {
            assertTrue(names.add(profile.name()));
            assertTrue(ids.add(profile.id()));
            assertTrue(profile.name().matches("[A-Za-z]{4,12}"));
            assertTrue(Files.exists(asset("Hair", profile.hair())), "Missing hair for " + profile.name());
            assertTrue(Files.exists(asset("Face", profile.face())), "Missing face for " + profile.name());
            for (var item : profile.equipment().entrySet()) {
                String directory = switch (item.getKey()) {
                    case 1 -> "Cap";
                    case 5 -> "Coat";
                    case 6 -> "Pants";
                    case 7 -> "Shoes";
                    case 11 -> "Weapon";
                    default -> throw new AssertionError("Unsupported equipment slot");
                };
                assertTrue(Files.exists(asset(directory, item.getValue())), "Missing item " + item.getValue());
            }
            if (profile.companion()) {
                companions++;
            }
        }
        assertEquals(20, names.size());
        assertEquals(3, companions);
    }

    private Path writeFile(String name, String contents) throws IOException {
        return Files.writeString(temporary.resolve(name), contents);
    }

    private static void assertFromTopic(ResidentProfiles.Profile resident, ResidentVoice.Topic topic, String reply) {
        assertTrue(Arrays.asList(ResidentVoice.lines(resident, topic, ResidentDialogue.Context.town())).contains(reply),
                () -> resident.name() + " answered outside " + topic + ": " + reply);
    }

    private static Path asset(String category, int id) {
        return Path.of("wz", "Character.wz", category, String.format("%08d.img.xml", id));
    }
}
