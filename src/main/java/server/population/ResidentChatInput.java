package server.population;

import java.text.Normalizer;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Bounded chat parsing shared by dialogue and movement-command routing. No world state is read. */
public record ResidentChatInput(String text, Intent intent, boolean addressed, boolean question) {
    public enum Intent {
        FOLLOW, STAY, LEVEL, JOB, PARTY, BUY, SELL, TRADE, MAP_COURTESY, BUFF,
        GREETING, THANKS, GRATS, BRB, LAUGH, GOODBYE, LOCATION, IDENTITY,
        HUNT, HELP, MEMORY, STATUS, LEVEL_UP, DIED, ACK, DISMISS, COMPANY, ABOUT,
        WELCOME_BACK, COMPLIMENT, PROGRESS, MONSTERS, UNKNOWN
    }

    private static final int MAX_INPUT = 512;
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}>]+");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern POLITE_PREFIX = Pattern.compile(
            "^(?:(?:actually|hey|hi|hello|yo|please|pls|plz|can you|can u|could you|could u|would you|would u|will you|will u) )+");
    private static final Pattern POLITE_SUFFIX = Pattern.compile(
            "(?: (?:please|pls|plz|now|for a sec|for a bit|thanks|thx|ty|lol|haha|lmao))+$");
    private static final Pattern FOLLOW = Pattern.compile(
            "(?:follow(?: me)?|follow me to .+|walk with me|come with me|come along|lets walk|tag along"
                    + "|lets go|keep following(?: me)?|(?:dont|do not|never) stop following(?: me)?"
                    + "|i want (?:you|u) to follow me)");
    private static final Pattern STAY = Pattern.compile(
            "(?:stop(?: following(?: me)?)?|stay(?: here)?|wait(?: here)?"
                    + "|(?:dont|do not|quit) follow(?:ing)?(?: me)?|stop tagging along|leave me alone"
                    + "|hold up|i dont want (?:you|u) to follow me)");
    private static final Pattern LEVEL = Pattern.compile(
            "(?:(?:what|whats|wat|wut)(?: is)? )?(?:(?:your|ur) )?(?:level|lvl|lv)"
                    + "(?: (?:are|r) (?:you|u))?");
    private static final Pattern JOB = Pattern.compile(
            "(?:(?:what|whats|wat|wut|which)(?: is)? )?(?:(?:your|ur) )?(?:job|class)"
                    + "(?: (?:are|r) (?:you|u))?");
    private static final Pattern JOB_GUESS = Pattern.compile(
            "(?:are|r) (?:you|u) (?:a )?(?:beginner|warrior|magician|mage|archer|bowman|thief|rogue"
                    + "|pirate|cleric|hunter|fighter|page|assassin|bandit|spearman|crossbowman|wizard)");
    private static final Pattern IDENTITY = Pattern.compile(
            "(?:(?:are|r) (?:you|u)|(?:you|u)(?:re| are)?)(?: really)?(?: a| an)? "
                    + "(?:bot|npc|human|real(?: player)?|ai)(?: or .+)?");

    public static ResidentChatInput parse(String raw) {
        return parse(raw, "", List.of());
    }

    public static ResidentChatInput parse(String raw, String residentName) {
        return parse(raw, residentName, List.of());
    }

    /** Known names may include explicit aliases; unknown words are never treated as names. */
    public static ResidentChatInput parse(String raw, String residentName, Collection<String> knownNames) {
        String bounded = raw == null ? "" : raw.substring(0, Math.min(MAX_INPUT, raw.length()));
        boolean question = bounded.indexOf('?') >= 0 || bounded.indexOf('？') >= 0;
        String text = normalize(bounded);
        String withoutAddress = stripAddress(text, residentName);
        if (withoutAddress.equals(text) && knownNames != null) {
            int checked = 0;
            for (String name : knownNames) {
                if (++checked > 128) {
                    break;
                }
                withoutAddress = stripAddress(text, name);
                if (!withoutAddress.equals(text)) {
                    break;
                }
            }
        }
        boolean addressed = !withoutAddress.equals(text);
        text = withoutAddress.replaceFirst("^b\\s*>\\s*", "wtb ")
                .replaceFirst("^s\\s*>\\s*", "wts ").strip();
        return new ResidentChatInput(text, classify(text), addressed, question);
    }

    public boolean containsPhrase(String phrase) {
        String words = normalize(phrase);
        return !words.isEmpty() && (" " + text + " ").contains(" " + words + " ");
    }

    public boolean has(String... phrases) {
        for (String phrase : phrases) {
            if (containsPhrase(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static Intent classify(String text) {
        String command = polite(text);
        if (FOLLOW.matcher(command).matches()) {
            return Intent.FOLLOW;
        }
        if (STAY.matcher(command).matches()) {
            return Intent.STAY;
        }
        if (text.isEmpty()) {
            return Intent.UNKNOWN;
        }
        if (IDENTITY.matcher(command).matches() || command.equals("are you an ai bot")) {
            return Intent.IDENTITY;
        }
        if (LEVEL.matcher(command).matches()) {
            return Intent.LEVEL;
        }
        if (JOB.matcher(command).matches() || JOB_GUESS.matcher(command).matches()) {
            return Intent.JOB;
        }

        // Specific social/game requests outrank greetings and laughter in the same message.
        if (anyPhrase(text, "wtb", "buy", "buying")) {
            return Intent.BUY;
        }
        if (anyPhrase(text, "wts", "sell", "selling")) {
            return Intent.SELL;
        }
        if (anyPhrase(text, "trade", "trading", "price", "mesos", "meso", "free items", "gotpots", "got pots", "any pots", "have pots")) {
            return Intent.TRADE;
        }
        if (command.matches("cc(?: ?(?:pls|please|plz))?")
                || anyPhrase(text, "change channel", "map taken", "maptaken", "map occupied", "my map", "this map is mine",
                "ks", "ksing", "kser", "killsteal", "kill stealing") || command.equals("taken")) {
            return Intent.MAP_COURTESY;
        }
        if (anyPhrase(text, "lfp", "lfg", "lfm", "kpq", "lpq", "opq", "hpq", "cpq", "pq", "party", "party invite")
                || command.matches("(?:invite|inv|pt)(?: me)?")) {
            return Intent.PARTY;
        }
        if (anyPhrase(text, "buff", "buffs", "hb", "hs", "haste", "se", "sharp eyes", "holy symbol", "hyper body", "bless")) {
            return Intent.BUFF;
        }
        if (anyPhrase(text, "remember", "recognize", "recognise", "met before", "whats my name", "what is my name")) {
            return Intent.MEMORY;
        }
        if (anyPhrase(text, "who are you", "your name", "ur name", "about yourself")) {
            return Intent.ABOUT;
        }
        if (anyPhrase(text, "where", "whereabouts")) {
            return Intent.LOCATION;
        }
        if (anyPhrase(text, "help", "quest", "stuck", "lost", "how do", "how to", "should i", "can i train")) {
            return Intent.HELP;
        }
        if (anyPhrase(text, "leveled", "levelled", "ding") || command.equals("level up")) {
            return Intent.LEVEL_UP;
        }
        if (anyPhrase(text, "died", "dead", "rip")) {
            return Intent.DIED;
        }
        if (anyPhrase(text, "train", "training", "grind", "grinding", "hunt", "hunting", "farm", "farming",
                "mobs", "fight", "fighting", "kill", "boss", "levels")) {
            return Intent.HUNT;
        }
        if (anyPhrase(text, "bye", "goodbye", "cya", "see you", "see ya", "gtg", "g2g", "gotta go", "good night", "goodnight", "gn")
                || command.equals("later")) {
            return Intent.GOODBYE;
        }
        if (anyPhrase(text, "never mind", "nvm", "nah", "no ty", "no thanks", "not now", "not yet")) {
            return Intent.DISMISS;
        }
        if (anyPhrase(text, "wb", "welcome back", "im back") || command.equals("back")) {
            return Intent.WELCOME_BACK;
        }
        if (anyPhrase(text, "nicehat", "nicedmg", "nice hat", "nice dmg", "nice damage", "nice outfit", "nice name", "nice gear")) {
            return Intent.COMPLIMENT;
        }
        if (anyPhrase(text, "percent", "almost leveled", "almost levelled", "almost there")) {
            return Intent.PROGRESS;
        }
        if (anyPhrase(text, "snail", "snails", "slime", "slimes", "mushroom", "mushrooms")) {
            return Intent.MONSTERS;
        }
        if (anyPhrase(text, "thank", "thanks", "thank you", "ty", "tysm", "thx", "thnx")) {
            return Intent.THANKS;
        }
        if (anyPhrase(text, "grats", "gratz", "gz", "congrats", "congratz", "congratulations")) {
            return Intent.GRATS;
        }
        if (anyPhrase(text, "brb", "afk", "bbl", "be right back", "back in a minute")) {
            return Intent.BRB;
        }
        if (anyPhrase(text, "lol", "lmao", "rofl", "haha", "xd")) {
            return Intent.LAUGH;
        }
        if (anyPhrase(text, "how are you", "how r u", "hows it going", "whats up", "sup", "wyd", "what are you doing", "what r u doing", "u coming", "you coming", "are you coming")) {
            return Intent.STATUS;
        }
        if (anyPhrase(text, "lonely", "alone", "company", "friend", "sad")) {
            return Intent.COMPANY;
        }
        if (anyPhrase(text, "hello", "hey", "hi", "hiya", "morning", "afternoon", "yo")) {
            return Intent.GREETING;
        }
        if (command.matches("(?:ok|okay|kk|k|sure|yes|ye|yea|yep|yeah|yup|alright|aight)")) {
            return Intent.ACK;
        }
        return Intent.UNKNOWN;
    }

    private static boolean anyPhrase(String text, String... phrases) {
        String padded = " " + text + " ";
        for (String phrase : phrases) {
            if (padded.contains(" " + phrase + " ")) {
                return true;
            }
        }
        return false;
    }

    private static String polite(String text) {
        return POLITE_SUFFIX.matcher(POLITE_PREFIX.matcher(text).replaceFirst("")).replaceFirst("");
    }

    private static String stripAddress(String text, String name) {
        if (name == null || name.isBlank() || name.length() > 32) {
            return text;
        }
        String canonicalName = normalize(name);
        if (canonicalName.isEmpty()) {
            return text;
        }
        if (text.equals(canonicalName)) {
            return "";
        }
        if (text.startsWith(canonicalName + " ")) {
            return text.substring(canonicalName.length()).strip();
        }
        for (String hello : List.of("hey", "hi", "hello", "yo")) {
            String prefix = hello + " " + canonicalName;
            if (text.equals(prefix)) {
                return hello;
            }
            if (text.startsWith(prefix + " ")) {
                return hello + text.substring(prefix.length());
            }
        }
        if (text.endsWith(" " + canonicalName)) {
            return text.substring(0, text.length() - canonicalName.length()).strip();
        }
        return text;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = Normalizer.normalize(raw.substring(0, Math.min(MAX_INPUT, raw.length())), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replace("'", "").replace("’", "").replace("%", " percent ");
        text = SPACE.matcher(SEPARATORS.matcher(text).replaceAll(" ")).replaceAll(" ").strip();
        if (text.isEmpty()) {
            return text;
        }
        String[] words = text.split(" ");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.matches("h+i+")) words[i] = "hi";
            else if (word.matches("h+e+y+")) words[i] = "hey";
            else if (word.matches("h+e+l+o+")) words[i] = "hello";
            else if (word.matches("h+i+y+a+")) words[i] = "hiya";
            else if (word.matches("y+o+")) words[i] = "yo";
            else if (word.matches("l(?:ol)+|lo+l+")) words[i] = "lol";
            else if (word.matches("lmao+")) words[i] = "lmao";
            else if (word.matches("h(?:ah)+a?")) words[i] = "haha";
            else if (word.matches("t+y+")) words[i] = "ty";
            else if (word.matches("thx+")) words[i] = "thx";
            else if (word.matches("pl+s+")) words[i] = "pls";
            else if (word.matches("pl+z+")) words[i] = "plz";
        }
        return String.join(" ", words);
    }
}
