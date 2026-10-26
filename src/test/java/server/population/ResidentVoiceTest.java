package server.population;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResidentVoiceTest {
    @Test
    void recurringCharactersRetainDistinctStableVoicesAndAllFiveAreRepresented() {
        var expected = Map.of(
                "MapleMia", ResidentVoice.Voice.SOCIAL,
                "PipArcher", ResidentVoice.Voice.GRINDER,
                "Juniper", ResidentVoice.Voice.QUIET,
                "OakWarden", ResidentVoice.Voice.HELPER,
                "HazelHop", ResidentVoice.Voice.PLAYFUL);
        expected.forEach((name, voice) -> assertEquals(voice,
                ResidentVoice.voice(ResidentProfiles.find(name).orElseThrow()), name));

        var voices = EnumSet.noneOf(ResidentVoice.Voice.class);
        for (var profile : allProfiles()) {
            var voice = ResidentVoice.voice(profile);
            voices.add(voice);
            var leveledProfile = new ResidentProfiles.Profile(profile.id(), profile.name(), profile.gender(),
                    profile.skin(), profile.face(), profile.hair(), profile.job(), profile.level() + 1,
                    profile.equipment(), profile.role(), profile.companion());
            assertEquals(voice, ResidentVoice.voice(leveledProfile), "level changes must not change a personality");
        }
        assertEquals(EnumSet.allOf(ResidentVoice.Voice.class), voices);
    }

    @Test
    void allVoiceAndActivityCandidatesFitAShortChatBubbleAndProvideABroadRepertoire() {
        var distinct = new HashSet<String>();
        for (var profile : allProfiles()) {
            for (var topic : ResidentVoice.Topic.values()) {
                checkLines(ResidentVoice.lines(profile, topic, ResidentDialogue.Context.town()), distinct);
            }
            for (var activity : ResidentDialogue.Activity.values()) {
                var context = new ResidentDialogue.Context(activity, "Henesys Hunting Ground", false);
                checkLines(ResidentVoice.lines(profile, ResidentVoice.Topic.ACTIVITY, context), distinct);
            }
        }
        assertTrue(distinct.size() >= 200, "voices need more than a handful of stock phrases");
    }

    @Test
    void independentResidentsCannotAcceptFollowingAndFieldIdlingDoesNotClaimToBeInTown() {
        var field = new ResidentDialogue.Context(ResidentDialogue.Activity.IDLE, "Pig Field", false);
        for (var profile : allProfiles()) {
            if (!profile.companion()) {
                assertArrayEquals(ResidentVoice.lines(profile, ResidentVoice.Topic.FOLLOW_DECLINE, field),
                        ResidentVoice.lines(profile, ResidentVoice.Topic.FOLLOW_ACCEPT, field));
            }
            for (String line : ResidentVoice.lines(profile, ResidentVoice.Topic.ACTIVITY, field)) {
                String lower = line.toLowerCase(Locale.ROOT);
                assertFalse(lower.contains("hene") || lower.contains("town"), line);
            }
        }
    }

    private static void checkLines(String[] lines, HashSet<String> distinct) {
        assertTrue(lines.length >= 2, "each topic needs alternatives for repetition suppression");
        for (String line : lines) {
            assertFalse(line.isBlank());
            assertTrue(line.length() <= 80, line);
            assertFalse(line.contains("\n"), "one message should fit one chat bubble");
            distinct.add(line);
        }
    }

    private static List<ResidentProfiles.Profile> allProfiles() {
        var profiles = new ArrayList<>(ResidentProfiles.all());
        for (int map : new int[]{ResidentRoster.TRAINING_GROUND, ResidentRoster.PIG_FIELD}) {
            ResidentRoster.forMap(map).forEach(placement -> profiles.add(placement.profile()));
        }
        return profiles;
    }
}
