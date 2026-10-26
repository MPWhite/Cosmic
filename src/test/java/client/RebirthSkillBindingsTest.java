package client;

import client.keybind.KeyBinding;
import constants.skills.Aran;
import constants.skills.Fighter;
import constants.skills.Magician;
import constants.skills.SuperGM;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RebirthSkillBindingsTest {
    @Mock
    private Character player;

    @BeforeEach
    void rebornPlayer() {
        lenient().when(player.getReborns()).thenReturn(1);
    }

    private static Skill active(int id) {
        Skill skill = new Skill(id);
        skill.setKeyBindable(true);
        return skill;
    }

    private static Character.SkillEntry learned() {
        return new Character.SkillEntry((byte) 10, 20, -1);
    }

    @Test
    void rejectsBindingBeforeFirstRebirth() {
        when(player.getReborns()).thenReturn(0);
        assertTrue(RebirthSkillBindings.getBindableSkills(player).isEmpty());
        assertNotNull(RebirthSkillBindings.bind(player, Magician.MAGIC_GUARD, 16));
        verify(player, never()).changeKeybinding(anyInt(), any());
    }

    @Test
    void listsLearnedActiveSkillsAcrossJobsInStableOrder() {
        Skill guard = active(Magician.MAGIC_GUARD);
        Skill rage = active(Fighter.RAGE);
        when(player.getSkills()).thenReturn(Map.of(guard, learned(), rage, learned()));
        assertEquals(List.of(rage, guard), RebirthSkillBindings.getBindableSkills(player));
    }

    @Test
    void excludesPassiveUnlearnedExpiredEventAndGmSkills() {
        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(new Skill(Fighter.SWORD_MASTERY), learned());
        skills.put(active(Fighter.RAGE), new Character.SkillEntry((byte) 0, 20, -1));
        skills.put(active(Magician.MAGIC_GUARD), new Character.SkillEntry((byte) 10, 20, 1));
        skills.put(active(1009), learned());
        skills.put(active(Aran.COMBO_SMASH), learned());
        skills.put(active(SuperGM.HEAL_PLUS_DISPEL), learned());
        when(player.getSkills()).thenReturn(skills);
        assertTrue(RebirthSkillBindings.getBindableSkills(player).isEmpty());
        for (Skill skill : skills.keySet()) {
            assertNotNull(RebirthSkillBindings.bind(player, skill.getId(), 16));
        }
        verify(player, never()).changeKeybinding(anyInt(), any());
    }

    @Test
    void rejectsForgedSkillAndUnsupportedKey() {
        when(player.getSkills()).thenReturn(Map.of());
        assertNotNull(RebirthSkillBindings.bind(player, Magician.MAGIC_GUARD, 16));
        assertNotNull(RebirthSkillBindings.bind(player, Magician.MAGIC_GUARD, 91));
        verify(player, never()).changeKeybinding(anyInt(), any());
        verify(player, never()).saveCharToDB(anyBoolean());
    }

    @Test
    void bindsLearnedSkillThenRefreshesAndSaves() {
        Skill guard = active(Magician.MAGIC_GUARD);
        when(player.getSkills()).thenReturn(Map.of(guard, learned()));
        when(player.saveCharToDB(false)).thenReturn(true);

        assertNull(RebirthSkillBindings.bind(player, guard.getId(), 16));

        ArgumentCaptor<KeyBinding> binding = ArgumentCaptor.forClass(KeyBinding.class);
        var order = inOrder(player);
        order.verify(player).changeKeybinding(eq(16), binding.capture());
        assertEquals(1, binding.getValue().getType());
        assertEquals(guard.getId(), binding.getValue().getAction());
        order.verify(player).saveCharToDB(false);
        order.verify(player).sendKeymap();
    }

    @Test
    void restoresPreviousBindingIfSaveFails() {
        Skill guard = active(Magician.MAGIC_GUARD);
        KeyBinding previous = new KeyBinding(4, 2);
        when(player.getSkills()).thenReturn(Map.of(guard, learned()));
        when(player.getKeymap()).thenReturn(Map.of(16, previous));

        assertNotNull(RebirthSkillBindings.bind(player, guard.getId(), 16));

        verify(player).changeKeybinding(16, previous);
        verify(player, never()).sendKeymap();
    }

    @Test
    void revalidatesSkillsThatExpireAfterMenuOpens() {
        Character.SkillEntry entry = learned();
        Skill skill = active(Magician.MAGIC_GUARD);
        when(player.getSkills()).thenReturn(Map.of(skill, entry));
        assertEquals(List.of(skill), RebirthSkillBindings.getBindableSkills(player));
        entry.expiration = 1;
        assertNotNull(RebirthSkillBindings.bind(player, skill.getId(), 16));
        verify(player, never()).changeKeybinding(anyInt(), any());
    }

    @Test
    void exposesEveryLetterExactlyOnceWithCorrectScanCodes() {
        assertEquals(26, RebirthSkillBindings.getKeys().size());
        assertEquals(26, RebirthSkillBindings.getKeys().stream().map(RebirthSkillBindings.Key::name).distinct().count());
        assertEquals(26, RebirthSkillBindings.getKeys().stream().map(RebirthSkillBindings.Key::scanCode).distinct().count());
        assertEquals(new RebirthSkillBindings.Key("Q", 16), RebirthSkillBindings.getKeys().getFirst());
        assertEquals(new RebirthSkillBindings.Key("M", 50), RebirthSkillBindings.getKeys().getLast());
    }
}
