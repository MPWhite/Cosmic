package server.life;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobSkillFactoryTest {
    // Use the checked-in server data. WZFiles and the factory cache their providers,
    // so changing the global wz-path property here makes other tests order-dependent.

    @Test
    void shouldLoadExistingMobSkill() {
        Optional<MobSkill> possibleSkill = MobSkillFactory.getMobSkill(MobSkillType.ATTACK_UP, 1);

        assertTrue(possibleSkill.isPresent());
        MobSkill mobSkill = possibleSkill.get();
        assertAll("MobSkill",
                () -> assertEquals(115, mobSkill.getX()),
                () -> assertEquals(5, mobSkill.getMpCon()),
                () -> assertEquals(40_000, mobSkill.getCoolTime()),
                () -> assertEquals(30_000, mobSkill.getDuration())
        );
    }

    @Test
    void shouldThrowExceptionOnNonExisting() {
        int missingLevel = Integer.MAX_VALUE;
        assertTrue(MobSkillFactory.getMobSkill(MobSkillType.DEFENSE_UP, missingLevel).isEmpty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MobSkillFactory.getMobSkillOrThrow(MobSkillType.DEFENSE_UP, missingLevel));

        assertEquals("No MobSkill exists for type DEFENSE_UP, level " + missingLevel, error.getMessage());
    }

}
