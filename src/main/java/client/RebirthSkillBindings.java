package client;

import client.keybind.KeyBinding;
import constants.game.GameConstants;

import java.util.Comparator;
import java.util.List;

/** Server-side validation for binding retained skills through the rebirth NPC. */
public final class RebirthSkillBindings {
    public record Key(String name, int scanCode) {
    }

    private static final List<Key> KEYS = List.of(
            new Key("Q", 16), new Key("W", 17), new Key("E", 18), new Key("R", 19),
            new Key("T", 20), new Key("Y", 21), new Key("U", 22), new Key("I", 23),
            new Key("O", 24), new Key("P", 25), new Key("A", 30), new Key("S", 31),
            new Key("D", 32), new Key("F", 33), new Key("G", 34), new Key("H", 35),
            new Key("J", 36), new Key("K", 37), new Key("L", 38), new Key("Z", 44),
            new Key("X", 45), new Key("C", 46), new Key("V", 47), new Key("B", 48),
            new Key("N", 49), new Key("M", 50));

    private RebirthSkillBindings() {
    }

    public static List<Key> getKeys() {
        return KEYS;
    }

    public static List<Skill> getBindableSkills(Character player) {
        if (player.getReborns() < 1) {
            return List.of();
        }
        return player.getSkills().entrySet().stream()
                .filter(entry -> isBindable(entry.getKey(), entry.getValue()))
                .map(java.util.Map.Entry::getKey)
                .sorted(Comparator.comparingInt(Skill::getId))
                .toList();
    }

    private static boolean isBindable(Skill skill, Character.SkillEntry entry) {
        return skill != null && entry != null && entry.skillevel > 0
                && (entry.expiration < 0 || entry.expiration > System.currentTimeMillis())
                && skill.isKeyBindable()
                && !GameConstants.isGMSkills(skill.getId())
                && !GameConstants.bannedBindSkills(skill.getId())
                && !GameConstants.isHiddenSkills(skill.getId());
    }

    /** Returns null on success, or a message suitable for the player. */
    public static String bind(Character player, int skillId, int scanCode) {
        synchronized (player) {
            if (player.getReborns() < 1) {
                return "Complete your first rebirth before binding retained skills.";
            }
            if (KEYS.stream().noneMatch(key -> key.scanCode() == scanCode)) {
                return "Choose one of the listed letter keys.";
            }
            Skill skill = getBindableSkills(player).stream()
                    .filter(candidate -> candidate.getId() == skillId)
                    .findFirst().orElse(null);
            if (skill == null) {
                return "That skill is not a learned, bindable skill.";
            }
            KeyBinding previous = player.getKeymap().get(scanCode);
            player.changeKeybinding(scanCode, new KeyBinding(1, skill.getId()));
            if (!player.saveCharToDB(false)) {
                player.changeKeybinding(scanCode, previous != null ? previous : new KeyBinding(0, 0));
                return "Your key binding could not be saved. The previous binding was kept; please try again later.";
            }
            player.sendKeymap();
            return null;
        }
    }
}
