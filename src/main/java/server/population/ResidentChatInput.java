package server.population;

import server.population.knowledge.GameKnowledge;

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
        WELCOME_BACK, COMPLIMENT, PROGRESS, MONSTERS, BANTER, COMPLAINT, UNKNOWN
    }

    private static final int MAX_INPUT = 512;
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}>]+");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern POLITE_PREFIX = Pattern.compile(
            "^(?:(?:actually|hey|hi|hello|yo|please|pls|plz|can you|can u|could you|could u|would you|would u|will you|will u) )+");
    private static final Pattern POLITE_SUFFIX = Pattern.compile(
            "(?: (?:please|pls|plz|now|for a sec|for a bit|thanks|thx|ty|lol|haha|lmao))+$");
    private static final Pattern COMMAND_EMPHASIS = Pattern.compile(
            "(?<!\\S)(?:fuck|fucking|fuckin|damn|goddamn|goddamned|bloody)(?!\\S)");
    private static final Pattern COMMAND_TAUNT_SUFFIX = Pattern.compile(
            "(?: (?:you )?(?:noob|nub|n00b|fucker|asshole|dumbass|idiot))+$");
    private static final Pattern SELF_DEPRECATION = Pattern.compile(
            "(?:^| )(?:i suck|we suck|im (?:such a |a complete |a total |an absolute |a |an |so |really )?(?:noobs?|nubs?|n00bs?|trash|bad|garbage|shit|terrible|awful)"
                    + "|i am (?:such a |a complete |a total |an absolute |a |an |so |really )?(?:noobs?|nubs?|n00bs?|trash|bad|garbage|shit|terrible|awful)"
                    + "|we are (?:such |so |really )?(?:noobs?|nubs?|n00bs?|trash|bad|garbage|shit|terrible|awful)"
                    + "|my (?:aim|damage|dmg|build|gear|char|character) (?:is )?(?:trash|bad|garbage|shit|sucks|terrible|awful))(?: |$)");
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

    /** Keep the dialogue's empathetic reply selection aligned with intent classification. */
    public boolean selfDeprecating() {
        return isSelfDeprecating(text);
    }

    private static Intent classify(String text) {
        String plain = withoutEmphasis(text);
        String command = polite(plain);
        String movement = polite(COMMAND_TAUNT_SUFFIX.matcher(command).replaceFirst(""));
        if (FOLLOW.matcher(movement).matches()) {
            return Intent.FOLLOW;
        }
        if (STAY.matcher(movement).matches()) {
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

        // Public ads keep their routing even when the item or recruitment pitch is irreverent.
        if (anyPhrase(text, "wtb", "buy", "buying")) {
            return Intent.BUY;
        }
        if (anyPhrase(text, "wts", "sell", "selling")) {
            return Intent.SELL;
        }
        if (anyPhrase(text, "lfp", "lfg", "lfm") || anyPhrase(text, "recruiting")
                && anyPhrase(text, "party", "pq", "kpq", "lpq", "opq", "hpq", "cpq")) {
            return Intent.PARTY;
        }

        if (movement.matches("cc(?: ?(?:pls|please|plz))?")
                || anyPhrase(text, "change channel", "map taken", "maptaken", "map occupied", "my map", "this map is mine",
                "ks", "ksing", "kser", "killsteal", "kill stealing") || command.equals("taken")) {
            return Intent.MAP_COURTESY;
        }
        if (isSelfDeprecating(text)) {
            return Intent.COMPLAINT;
        }
        if (anyPhrase(plain, "noob", "nub", "n00b", "noobs", "nubs", "n00bs", "ur trash", "youre trash", "you are trash",
                "u are trash", "you suck", "u suck", "ur bad", "youre bad", "you are bad", "ur garbage", "youre garbage",
                "you are garbage", "ur shit", "youre shit", "you are shit", "ur washed", "youre washed", "you are washed",
                "ur weak", "youre weak", "you are weak", "says you", "no u", "stfu",
                "ez", "ezpz", "git gud", "get gud", "get good", "skill issue", "roast me", "talk shit", "trash talk")
                || anyPhrase(text, "fuck you")
                || plain.matches(".*\\b(?:your|ur) (?:aim|damage|dmg|build|gear|bow|staff|hat|fit) (?:is )?(?:trash|bad|garbage|shit|sucks|washed)\\b.*")) {
            return Intent.BANTER;
        }
        if (gameComplaint(text)) {
            return Intent.COMPLAINT;
        }

        // Specific social/game requests outrank greetings and laughter in the same message.
        if (anyPhrase(text, "trade", "trading", "price", "mesos", "meso", "free items", "gotpots", "got pots", "any pots", "have pots")) {
            return Intent.TRADE;
        }
        // A factual PQ question must never be interpreted as consent to recruit or enter an instance.
        if (GameKnowledge.isQuestion(text) && anyPhrase(text, "kpq", "pq", "party quest", "party")) {
            return Intent.HELP;
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
        if (anyPhrase(withoutEmphasis(text), "nicehat", "nicedmg", "nice hat", "nice dmg", "nice damage", "nice outfit", "nice name", "nice gear")) {
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
        if (command.matches("(?:hell )?(?:ok|okay|kk|k|sure|yes|ye|yea|yep|yeah|yup|alright|aight)")) {
            return Intent.ACK;
        }
        if (anyPhrase(text, "fuck", "fucking", "fuckin", "shit", "damn", "goddamn", "goddamned", "bullshit", "wtf", "fml")) {
            return Intent.COMPLAINT;
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
        return Intent.UNKNOWN;
    }

    private static boolean isSelfDeprecating(String text) {
        String plain = withoutEmphasis(text);
        return SELF_DEPRECATION.matcher(plain).find()
                || plain.matches("^were (?:such |so |really )?(?:noobs?|nubs?|n00bs?|trash|bad|garbage|shit)(?: .*)?");
    }

    private static boolean gameComplaint(String text) {
        if (anyPhrase(text, "lag", "laggy", "lagging", "dc", "dced", "disconnected", "rng", "bullshit", "wtf", "fml",
                "fuck this", "screw this", "this sucks", "this is trash", "this is shit", "im tilted", "im salty",
                "no drops", "nothing drops", "zero drops")) {
            return true;
        }
        String plain = withoutEmphasis(text);
        boolean game = anyPhrase(text, "grind", "grinding", "drops", "drop", "loot", "scroll", "scrolls", "scrolled", "exp",
                "game", "server", "damage", "dmg", "aim", "mesos", "meso", "pots", "potions", "ladders", "mobs", "mushrooms");
        return game && (anyPhrase(plain, "shit", "trash", "garbage", "sucks", "suck", "terrible", "awful", "hate", "slow",
                "brutal", "painful", "cursed", "broke", "wasted", "useless", "scam", "rigged", "impossible", "expensive",
                "too much", "forever", "boring")
                || text.matches(".*\\b(?:this|these|that|those) (?:fucking|damn|goddamn) .+"));
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

    private static String withoutEmphasis(String text) {
        return SPACE.matcher(COMMAND_EMPHASIS.matcher(text).replaceAll(" ")).replaceAll(" ").strip();
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
