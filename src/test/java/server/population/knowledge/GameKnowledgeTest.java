package server.population.knowledge;

import org.junit.jupiter.api.Test;
import server.population.ResidentChatInput;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GameKnowledgeTest {
    private static PlayerContext player(int level, int job, int map, boolean auto, Set<Integer> quests) {
        return new PlayerContext(1, "Guest", level, job, map, "Kerning City", quests, auto);
    }
    private static final PlayerContext PLAYER = player(25, 100, 103000000, true, Set.of());
    private static class Source implements GameKnowledge.Source {
        @Override public Optional<GameKnowledge.KpqRules> kpqRules() { return Optional.of(new GameKnowledge.KpqRules(3, 4, 21, 30, 30)); }
        @Override public List<GameKnowledge.Item> findItems(String query) {
            return switch (query) {
                case "slime bubble" -> List.of(new GameKnowledge.Item(4000010, "Slime Bubble"));
                case "scroll" -> List.of(new GameKnowledge.Item(2040000, "Hat Scroll"), new GameKnowledge.Item(2040001, "Helmet Scroll"));
                default -> List.of();
            };
        }
        @Override public List<GameKnowledge.Drop> drops(int id) { return List.of(new GameKnowledge.Drop("Slime", List.of("The Tree That Grew I"), 0, false)); }
        @Override public List<GameKnowledge.Skill> findSkills(String query) {
            return query.equals("haste") ? List.of(new GameKnowledge.Skill("Haste", "Temporarily improves party members' speed and jumping ability.")) : List.of();
        }
        @Override public List<GameKnowledge.QuestFact> findQuests(String query) {
            return query.equals("johns pink flower basket") ? List.of(new GameKnowledge.QuestFact(2050, "John's Pink Flower Basket", "John", 15, "Bring flowers from Sleepywood.")) : List.of();
        }
    }

    @Test void factualKpqQuestionsCannotRecruitParty() {
        for (String q : List.of("what level for kpq?", "how do i enter kpq", "where is kerning pq", "how many people for this party quest")) {
            assertTrue(GameKnowledge.isQuestion(q), q);
            assertEquals(ResidentChatInput.Intent.HELP, ResidentChatInput.parse(q).intent(), q);
        }
        assertEquals(ResidentChatInput.Intent.PARTY, ResidentChatInput.parse("anyone wanna kpq?").intent());
        assertEquals(ResidentChatInput.Intent.PARTY, ResidentChatInput.parse("lfm kpq").intent());
        assertFalse(GameKnowledge.isQuestion("what's your level?"));
        assertFalse(GameKnowledge.isQuestion("how are you"));
    }

    @Test void requirementsUseSourceAndPlayerEligibility() {
        var knowledge = new GameKnowledge(new Source());
        String answer = knowledge.answer("what lvl for kpq", player(15, 100, 100000000, true, Set.of())).orElseThrow().text();
        assertTrue(answer.contains("21-30"));
        assertTrue(answer.contains("3-4"));
        assertTrue(answer.contains("6 lvls short"));
        assertTrue(knowledge.answer("how do i join kpq", player(32, 310, 100000000, true, Set.of())).orElseThrow().text().contains("over the lvl cap"));
    }

    @Test void missingRulesDoNotInventDefaults() {
        var knowledge = new GameKnowledge(new Source() {
            @Override public Optional<GameKnowledge.KpqRules> kpqRules() { return Optional.empty(); }
        });
        String answer = knowledge.answer("what level for kpq", PLAYER).orElseThrow().text();
        assertTrue(answer.contains("can't check"));
        assertFalse(answer.contains("21"));
    }

    @Test void stageAdviceUsesCurrentMapAndNeverRevealsWinningCombination() {
        var knowledge = new GameKnowledge(new Source());
        var answer = knowledge.answer("how do we do this stage", player(25, 100, 103000801, true, Set.of())).orElseThrow();
        assertTrue(answer.text().contains("middle"));
        assertTrue(answer.text().contains("wrong"));
        assertTrue(answer.facts().stream().anyMatch(f -> f.contains("unknown")));
        assertTrue(knowledge.answer("how do we kill king slime", PLAYER).orElseThrow().text().contains("10 passes"));
    }

    @Test void mechanicQuantitiesDoNotAnswerWithEntryRequirements() {
        var knowledge = new GameKnowledge(new Source());
        var stage1 = player(25, 100, 103000800, true, Set.of());
        assertEquals("warrior STR question = 35 coupons.", knowledge.answer("how many coupons for warrior str", stage1).orElseThrow().text());
        assertEquals("magician INT question = 20 coupons.", knowledge.answer("what is the answer to the int question", stage1).orElseThrow().text());
        assertEquals("magician INT question = 20 coupons.", knowledge.answer("what is the INT question?", stage1).orElseThrow().text());
        assertTrue(knowledge.answer("how many passes do we need", stage1).orElseThrow().text().contains("each nonleader"));
        assertTrue(knowledge.answer("how many ropes do we use", player(25, 100, 103000801, true, Set.of())).orElseThrow().text().startsWith("3 people"));
    }

    @Test void itemLookupUsesRealDropLocationAndClarifiesAmbiguousNames() {
        var knowledge = new GameKnowledge(new Source());
        for (String q : List.of("what drops slime bubble?", "where can i get slime bubble", "where do i farm slime bubble")) {
            var answer = knowledge.answer(q, PLAYER).orElseThrow();
            assertTrue(answer.text().contains("Slime"), q);
            assertTrue(answer.text().contains("The Tree That Grew I"), q);
            assertFalse(answer.text().contains("%"));
        }
        assertTrue(knowledge.answer("what drops scroll", PLAYER).orElseThrow().text().startsWith("which one"));
        assertTrue(knowledge.answer("what drops unknownthing", PLAYER).orElseThrow().text().contains("exact name"));
    }

    @Test void questDropRequiresActiveQuest() {
        var knowledge = new GameKnowledge(new Source() {
            @Override public List<GameKnowledge.Drop> drops(int id) { return List.of(new GameKnowledge.Drop("Slime", List.of("Forest"), 123, false)); }
        });
        assertTrue(knowledge.answer("what drops slime bubble", PLAYER).orElseThrow().text().contains("quest active"));
        var active = player(25, 100, 103000000, true, Set.of(123));
        assertTrue(knowledge.answer("what drops slime bubble", active).orElseThrow().text().contains("Forest"));
    }

    @Test void advancementHonorsStarterStatSettingAndClass() {
        var knowledge = new GameKnowledge(new Source());
        String manual = knowledge.answer("how do i get first job magician", player(8, 0, 101000000, false, Set.of())).orElseThrow().text();
        assertTrue(manual.contains("lvl 8"));
        assertTrue(manual.contains("20 INT"));
        assertTrue(manual.contains("Grendel"));
        assertTrue(knowledge.answer("how do i get first job warrior", PLAYER).orElseThrow().text().contains("auto-assign"));
        assertTrue(knowledge.answer("how do i get 2nd job", PLAYER).orElseThrow().text().contains("lvl 30"));
    }

    @Test void skillAndQuestDescriptionsStayGrounded() {
        var knowledge = new GameKnowledge(new Source());
        assertTrue(knowledge.answer("what does haste do", PLAYER).orElseThrow().text().contains("jumping"));
        assertTrue(knowledge.answer("how do i start johns pink flower basket quest", PLAYER).orElseThrow().text().contains("John"));
        assertTrue(knowledge.answer("what does fake skill do", PLAYER).orElseThrow().text().contains("exact name"));
    }

    @Test void trainingUsesLevelClassAndDoesNotClaimOptimalRates() {
        var knowledge = new GameKnowledge(new Source());
        assertTrue(knowledge.answer("where should i train", player(15, 200, 100000000, true, Set.of())).orElseThrow().text().contains("MP"));
        assertTrue(knowledge.answer("where should i train", player(25, 300, 100000000, true, Set.of())).orElseThrow().text().contains("range"));
        assertTrue(knowledge.answer("where should i train", player(90, 311, 100000000, true, Set.of())).orElseThrow().text().contains("past my early-game"));
    }

    @Test void shortEntityAndStageFollowupsResolveWithoutTreatingSocialRepliesAsQuestions() {
        var knowledge = new GameKnowledge(new Source());
        var ambiguous = knowledge.answer("what drops scroll", PLAYER).orElseThrow();
        assertEquals("what drops slime bubble", knowledge.expandFollowUp("slime bubble", "what drops scroll", ambiguous).orElseThrow());
        assertTrue(knowledge.expandFollowUp("lol", "what drops scroll", ambiguous).isEmpty());
        var kpq = knowledge.answer("how do i do kpq", PLAYER).orElseThrow();
        String followup = knowledge.expandFollowUp("and stage 2?", "how do i do kpq", kpq).orElseThrow();
        assertTrue(knowledge.answer(followup, PLAYER).orElseThrow().text().contains("ropes"));
    }

    @Test void currentStageFollowupUsesVerifiedKpqSourceWithoutRequiringThePlayerToNameKpq() {
        var knowledge = new GameKnowledge(new Source());
        var stage2 = player(25, 100, 103000801, true, Set.of());
        String original = "how do we do this stage";
        var answer = knowledge.answer(original, stage2).orElseThrow();
        assertTrue(answer.text().contains("ropes"));
        String followup = knowledge.expandFollowUp("and stage 3?", original, answer).orElseThrow();
        assertTrue(knowledge.answer(followup, stage2).orElseThrow().text().contains("platforms"));
        var unrelated = new GameKnowledge.Answer("not sure", "unsupported", List.of());
        assertTrue(knowledge.expandFollowUp("and stage 3?", "how do i do kpq", unrelated).isEmpty(),
                "unverified text alone must not establish factual KPQ context");
    }

    @Test void recognizableUnsupportedMechanicsQuestionsClarifyWithoutGuessingFacts() {
        var knowledge = new GameKnowledge(new Source());
        for (String question : List.of("what is the max damage of double stab?", "how much HP does a ligator have?",
                "does magic guard block poison damage?", "can I equip a maple kandayo at level 25?",
                "how long does haste last?", "can I wear an iron dagger?", "how much MP does magic claw use?",
                "do weapon buffs stack?", "how much HP does King Slime have?")) {
            assertTrue(GameKnowledge.isQuestion(question), question);
            var answer = knowledge.answer(question, PLAYER).orElseThrow();
            assertEquals("unsupported", answer.source(), question);
            assertTrue(answer.text().contains("can't verify"), question);
        }
    }

    @Test void socialQuestionsAndBanterDoNotBecomeUnsupportedMechanicsQuestions() {
        var knowledge = new GameKnowledge(new Source());
        for (String social : List.of("how are you?", "why is your damage so shit lol", "can you stop missing lol",
                "how long have you played?", "does your damage suck lol", "can I use the bathroom?")) {
            assertFalse(GameKnowledge.isQuestion(social), social);
            assertTrue(knowledge.answer(social, PLAYER).isEmpty(), social);
        }
        assertTrue(knowledge.answer("what does haste do?", PLAYER).orElseThrow().text().contains("jumping"));
        assertTrue(knowledge.answer("what is the skill haste?", PLAYER).orElseThrow().text().contains("jumping"));
    }
}
