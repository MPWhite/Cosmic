package client;

import constants.skills.Fighter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillBindingMetadataTest {
    @Test
    void identifiesTypedActiveSkillsWithoutChangingCombatActionFlag() {
        SkillFactory.loadAllSkills();
        Skill booster = SkillFactory.getSkill(Fighter.SWORD_BOOSTER);
        assertNotNull(booster);
        assertTrue(booster.isKeyBindable());
        assertFalse(booster.getAction());
        assertFalse(SkillFactory.getSkill(Fighter.SWORD_MASTERY).isKeyBindable());
        assertTrue(SkillFactory.getSkill(Fighter.RAGE).isKeyBindable());
    }
}
