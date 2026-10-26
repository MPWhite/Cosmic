package server.population;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

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
        String response = dialogue.reply(ResidentProfiles.all().getFirst(), 9, "Traveler", "this is interesting");
        assertFalse(response.startsWith("Hi,"));
        assertFalse(response.startsWith("Hey,"));
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
        assertTrue(dialogue.reply(ResidentProfiles.all().get(3), 1, "Guest", "follow me")
                .contains("usual route"));
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
        var mia = ResidentProfiles.all().getFirst();
        var replies = new HashSet<String>();
        String previous = "";
        for (int turn = 0; turn < 8; turn++) {
            String reply = dialogue.reply(mia, 77, "TestPlayer", "thanks");
            assertNotEquals(previous, reply);
            assertTrue(reply.length() < 30);
            assertFalse(reply.contains("TestPlayer"));
            replies.add(reply);
            previous = reply;
        }
        assertTrue(replies.size() >= 3);
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
        assertTrue(quest.contains("handle the quest NPCs"));
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

    private static Path asset(String category, int id) {
        return Path.of("wz", "Character.wz", category, String.format("%08d.img.xml", id));
    }
}
