package server.population.knowledge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies the actual installed game assets, without a live database or paid model. */
class WzGameKnowledgeTest {
    @Test void readsActualRulesItemNamesSkillsAndQuestMetadata() {
        var source = new WzGameKnowledge();
        var rules = source.kpqRules().orElseThrow();
        assertEquals(21, rules.minimumLevel());
        assertEquals(30, rules.maximumLevel());
        assertEquals(4, rules.maximumPlayers());
        assertEquals(30, rules.minutes());
        var items = source.findItems("slime bubble");
        assertEquals(1, items.size());
        assertEquals(4000010, items.getFirst().id());
        var skills = source.findSkills("haste");
        assertEquals(1, skills.size());
        assertTrue(skills.getFirst().description().toLowerCase().contains("speed"));
        var quests = source.findQuests("johns pink flower basket");
        assertEquals(1, quests.size());
        assertEquals("John's Pink Flower Basket", quests.getFirst().name());
        assertFalse(quests.getFirst().npc().isBlank());
    }
}
