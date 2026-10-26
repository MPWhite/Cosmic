package scripting;

import client.Character;
import client.Skill;
import client.keybind.KeyBinding;
import constants.skills.Magician;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RebirthScriptTest {
    private final AbstractScriptManager scripts = new AbstractScriptManager() {};
    private Conversation conversation;
    private Invocable script;

    @BeforeEach
    void startConversation() throws Exception {
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        ScriptEngine engine = scripts.getInvocableScriptEngine("npc/rebirth.js");
        assertNotNull(engine);
        conversation = new Conversation();
        engine.put("cm", conversation);
        script = (Invocable) engine;
        script.invokeFunction("start");
    }

    @Test
    void openingAndCancellingMenuDoNotRebirth() throws Exception {
        assertTrue(conversation.lastText.contains("level 200"));
        assertEquals(0, conversation.player.rebirths);
        script.invokeFunction("action", -1, 4, 0);
        assertEquals(List.of("dispose"), conversation.events);
    }

    @Test
    void decliningConfirmationDoesNotRebirth() throws Exception {
        script.invokeFunction("action", 1, 4, 0);
        assertTrue(conversation.lastText.contains("level 1 as a Beginner"));
        script.invokeFunction("action", 0, 1, 0);
        assertEquals(0, conversation.player.rebirths);
        assertEquals(List.of("dispose"), conversation.events);
    }

    @Test
    void disposesDialogueBeforeConfirmedRebirth() throws Exception {
        script.invokeFunction("action", 1, 4, 0);
        script.invokeFunction("action", 1, 1, 0);
        assertEquals(List.of("dispose", "rebirth"), conversation.events);
        script.invokeFunction("action", 1, 1, 0);
        assertEquals(1, conversation.player.rebirths);
    }

    @Test
    void showsServerValidationErrorToPlayer() throws Exception {
        conversation.player.error = "You must reach level 200 before rebirthing.";
        script.invokeFunction("action", 1, 4, 0);
        script.invokeFunction("action", 1, 1, 0);
        assertEquals(conversation.player.error, conversation.player.message);
    }

    @Test
    void retainedSkillMenuBindsOnlyAfterConfirmation() throws Exception {
        Character player = mock(Character.class);
        when(player.getReborns()).thenReturn(1);
        Skill guard = new Skill(Magician.MAGIC_GUARD);
        guard.setKeyBindable(true);
        when(player.getSkills()).thenReturn(Map.of(guard, new Character.SkillEntry((byte) 10, 20, -1)));
        when(player.saveCharToDB(false)).thenReturn(true);
        SkillConversation skillConversation = new SkillConversation(player);
        ScriptEngine engine = scripts.getInvocableScriptEngine("npc/rebirth.js");
        engine.put("cm", skillConversation);
        Invocable bindingScript = (Invocable) engine;
        bindingScript.invokeFunction("start");
        bindingScript.invokeFunction("action", 1, 4, 1); // Skill menu
        bindingScript.invokeFunction("action", 1, 4, 0); // Magic Guard
        assertTrue(skillConversation.lastText.contains("Choose a key"));
        bindingScript.invokeFunction("action", 1, 4, 0); // Q
        assertTrue(skillConversation.lastText.contains("#bQ#k"));
        verify(player, never()).changeKeybinding(anyInt(), any());
        bindingScript.invokeFunction("action", 1, 1, 0); // Confirm
        verify(player).changeKeybinding(eq(16), argThat((KeyBinding key) -> key.getAction() == guard.getId()));
        assertEquals("Your skill is now bound to Q.", skillConversation.lastText);
    }

    public static final class SkillConversation {
        private final Character player;
        private String lastText;

        SkillConversation(Character player) {
            this.player = player;
        }

        public Character getPlayer() {
            return player;
        }

        public void sendSimple(String text) {
            lastText = text;
        }

        public void sendYesNo(String text) {
            lastText = text;
        }

        public void sendOk(String text) {
            lastText = text;
        }

        public void dispose() {
        }
    }

    public static final class Conversation {
        private final List<String> events = new ArrayList<>();
        private final Player player = new Player(events);
        private String lastText;

        public Player getPlayer() {
            return player;
        }

        public void sendSimple(String text) {
            lastText = text;
        }

        public void sendYesNo(String text) {
            lastText = text;
        }

        public void dispose() {
            events.add("dispose");
        }
    }

    public static final class Player {
        private final List<String> events;
        private int rebirths;
        private String error;
        private String message;

        Player(List<String> events) {
            this.events = events;
        }

        public int getReborns() {
            return 0;
        }

        public String rebirth() {
            rebirths++;
            events.add("rebirth");
            return error;
        }

        public void dropMessage(int type, String text) {
            message = text;
        }
    }
}
