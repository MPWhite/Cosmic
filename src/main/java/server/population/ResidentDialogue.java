package server.population;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Authored player-style conversation grounded in the actor's actual activity. */
public final class ResidentDialogue {
    public enum Intent { TALK, FOLLOW, STOP_FOLLOWING, PARTY, HUNT, TRADE, HELP, FAREWELL }
    public enum Activity { IDLE, ROAMING, RESTING, SHOPPING, EXPLORING, FOLLOWING, WAITING, TRAINING, LOOTING }

    public record Context(Activity activity, String mapName, boolean following) {
        public Context {
            activity = activity == null ? Activity.IDLE : activity;
            mapName = mapName == null || mapName.isBlank() ? "here" : mapName.strip();
            mapName = mapName.substring(0, Math.min(64, mapName.length()));
        }

        public static Context town() {
            return new Context(Activity.IDLE, "Henesys", false);
        }
    }

    public record Exchange(String opener, String answer) { }

    private record Conversation(int residentId, int playerId) { }

    private static final int MAX_RECENT_CONVERSATIONS = 1024;
    private final ResidentMemoryStore memories;
    private final Map<Conversation, ArrayDeque<String>> recent = new LinkedHashMap<>(32, .75f, true);

    public ResidentDialogue(ResidentMemoryStore memories) {
        this.memories = Objects.requireNonNull(memories);
    }

    public String greeting(ResidentProfiles.Profile resident, int playerId, String playerName) {
        return greeting(resident, playerId, playerName, Context.town());
    }

    public String greeting(ResidentProfiles.Profile resident, int playerId, String playerName, Context context) {
        var before = memories.get(playerId, resident.id(), playerName);
        memories.visit(playerId, resident.id(), playerName);
        long turn = before.visits() + before.conversations();
        if (!before.hasMet()) {
            return pick(resident, playerId, turn, firstHello(resident));
        }
        // Recognition should be occasional, rather than a name-and-memory recital every time a map loads.
        if (before.visits() > 0 && before.visits() % 4 == 0 && !before.lastTopic().isBlank()) {
            return pick(resident, playerId, turn, "hey again. how's " + before.lastTopic() + " going?",
                    "oh hey " + playerName + ". still on " + before.lastTopic() + "?");
        }
        if (context.activity() == Activity.TRAINING) {
            return pick(resident, playerId, turn, "wb. taking this side", "oh hey, watch behind you", "hey again");
        }
        return pick(resident, playerId, turn, "wb", "hey again", "oh hey", "back for a bit?", "yo " + playerName);
    }

    public String reply(ResidentProfiles.Profile resident, int playerId, String playerName, String message) {
        return reply(resident, playerId, playerName, message, Context.town());
    }

    public String reply(ResidentProfiles.Profile resident, int playerId, String playerName, String message, Context context) {
        String text = normalize(message);
        var before = memories.get(playerId, resident.id(), playerName);
        memories.converse(playerId, resident.id(), playerName, topic(text));
        long turn = before.conversations();
        Intent request = intent(text);

        if (has(text, "bot", "npc", "human", "real player", "are you ai")) {
            return resident.companion()
                    ? "yeah, a server-run bot. I can explore and help with mobs, but I'm not a human player."
                    : "yep, a server-run bot. the movement and chatter are simulated.";
        }
        if (has(text, "remember", "recognize", "recognise", "met before", "my name")) {
            if (!before.hasMet()) {
                return "don't think we've met yet, " + playerName + ". hey though";
            }
            if (!before.lastTopic().isBlank()) {
                return "yeah, " + before.playerName() + ". you mentioned " + before.lastTopic() + " last time.";
            }
            return "yeah, " + before.playerName() + ". we've talked before.";
        }
        if (has(text, "who are you", "your name", "about yourself", "what class", "what job", "what level", "what lvl")) {
            return "" + resident.level() + " " + jobName(resident.job()) + ". " + switch (resident.id()) {
                case 1 -> "mia. usually taking the wrong portal lol";
                case 2 -> "pip. usually around the training fields.";
                case 3 -> "juniper. taking it slow.";
                default -> "" + resident.name() + ". mostly around here.";
            };
        }
        if (request == Intent.STOP_FOLLOWING) {
            return pick(resident, playerId, turn, "kk, I'll wait here", "sure, take your time", "stopping here. just call me when you're ready");
        }
        if (request == Intent.FOLLOW) {
            if (!resident.companion()) {
                return pick(resident, playerId, turn, "sticking to my usual route rn. try Mia, Pip or Juniper",
                        "not tagging along today. Mia, Pip or Juniper can follow you though");
            }
            return pick(resident, playerId, turn, switch (resident.id()) {
                case 1 -> new String[]{"yeah let's go", "coming! which way?", "ok, you pick the portal this time lol"};
                case 2 -> new String[]{"yep. lead on", "coming. I'll help with nearby mobs", "sure, let's move"};
                default -> new String[]{"sure, coming", "right behind you", "okay. no rush"};
            });
        }
        if (request == Intent.FAREWELL) {
            return pick(resident, playerId, turn, "cya", "later o/", "take it easy", "see you around", "night, if you're heading off");
        }
        if (request == Intent.PARTY) {
            return resident.companion()
                    ? pick(resident, playerId, turn, "can't take a party slot. I can follow and help with mobs though",
                    "no party invites for me, but I can tag along. say 'follow me'")
                    : pick(resident, playerId, turn, "can't join a party. Mia, Pip or Juniper can tag along with you",
                    "not a party member, sorry. just doing my own thing here");
        }
        if (request == Intent.TRADE) {
            if (has(text, "where", "shop", "potions")) {
                return "try the town shops for supplies. I can't sell you items or trade mesos.";
            }
            return pick(resident, playerId, turn, "can't trade items or mesos, sorry", "nothing for sale from me. just browsing",
                    "no trades on my end. the town NPC shops still work");
        }
        if (request == Intent.HELP) {
            if (has(text, "level", "lvl", "train", "grind")) {
                return resident.companion()
                        ? "start with nearby mobs you can handle. ask me to follow if you want a hand."
                        : "nearby fields are a place to start. pick mobs you can handle.";
            }
            if (has(text, "quest", "task")) {
                return resident.companion() ? "I can help with nearby mobs while following, but you'll need to handle the quest NPCs."
                        : "I don't complete quests. check the quest NPC or your quest log.";
            }
            if (has(text, "stuck", "lost", "where")) {
                return context.following() ? "I'm following your route. take a moment at the next safe platform if you need to."
                        : "we're in " + context.mapName() + ". check the minimap for the portals.";
            }
            return resident.companion()
                    ? "I can follow, help with nearby mobs, or wait here. say 'follow me' or 'stay'."
                    : "for a companion, ask Mia, Pip or Juniper to follow. I'm keeping to my own route.";
        }
        if (has(text, "leveled", "levelled", "level up", "ding")) {
            return pick(resident, playerId, turn, "grats!", "nice, grats", "gz", "ayy grats");
        }
        if (has(text, "died", "dead", "rip")) {
            return pick(resident, playerId, turn, "oof", "rip. take a sec", "rough. safe spot first", "ahh, unlucky");
        }
        if (request == Intent.HUNT) {
            if (context.activity() == Activity.TRAINING) {
                return pick(resident, playerId, turn, "working on these mobs rn", "taking this side. watch the ones behind you",
                        "yeah, a few more rounds then a break", "one at a time works for me");
            }
            if (context.following()) {
                return pick(resident, playerId, turn, "yeah, I'll help with mobs when we reach them", "lead the way to the mobs",
                        "coming with you. let's find a clear spot");
            }
            return resident.companion()
                    ? pick(resident, playerId, turn, "up for it. say 'follow me' and I'll help with nearby mobs",
                    "taking a break rn. I can tag along if you ask me to follow")
                    : pick(resident, playerId, turn, "on my own route today. how's your grind going?", "taking it at my own pace rn");
        }
        if (has(text, "thank", "thanks", "ty", "thx")) {
            return pick(resident, playerId, turn, "np", "anytime", "all good", "yw");
        }
        if (has(text, "lol", "lmao", "haha", "xd", "rofl")) {
            return pick(resident, playerId, turn, resident.id() == 3 ? new String[]{"haha", "fair", ":)"}
                    : new String[]{"lol", "haha", "fair", "ok that was good"});
        }
        if (has(text, "brb", "afk", "back in a minute")) {
            return pick(resident, playerId, turn, "kk", "take your time", "no rush");
        }
        if (text.matches("(?:ok|okay|sure|yes|yep|yeah|alright)[.! ]*")) {
            return pick(resident, playerId, turn, "yep", "cool", "alright");
        }
        if (has(text, "never mind", "nvm", "nah")) {
            return pick(resident, playerId, turn, "all good", "np", "fair enough");
        }
        if (has(text, "mushroom", "slime", "snail")) {
            return pick(resident, playerId, turn, switch (resident.id()) {
                case 1 -> new String[]{"the mushrooms look so harmless lol", "it's always the one behind you", "those little hops though"};
                case 2 -> new String[]{"keep an eye behind you", "steady pulls. no need to rush", "mushrooms are all over those fields"};
                case 3 -> new String[]{"the snails are in no hurry either", "quiet until a mushroom lands on you", "one at a time"};
                default -> new String[]{"mushrooms everywhere", "watch the ones behind you", "those fields stay busy"};
            });
        }
        if (has(text, "lonely", "friend", "company", "alone", "sad")) {
            return pick(resident, playerId, turn, "you can hang here for a bit", "no need to rush off", "yeah, a bit of company helps");
        }
        if (has(text, "how are", "how's", "hows", "doing", "what's up", "whats up", "sup")) {
            return pick(resident, playerId, turn, activityLines(resident, context));
        }
        if (has(text, "hello", "hey", "hi", "hiya", "morning", "afternoon", "yo")) {
            return pick(resident, playerId, turn, before.hasMet()
                    ? new String[]{"hey again", "yo", "hey, what's up?", "oh hey"} : firstHello(resident));
        }
        if (has(text, "henesys", "town", "home", "travel", "ellinia", "kerning", "perion")) {
            return pick(resident, playerId, turn, "I'm around " + context.mapName() + " rn", "checking the next map or hanging here?",
                    "town break first, then maybe head out");
        }
        if (has(text, "weather", "rain", "sun", "cloud")) {
            return pick(resident, playerId, turn, "nice spot to idle for a bit", "yeah, I like this map", "beats rushing everywhere");
        }
        if (!before.lastTopic().isBlank() && turn % 5 == 4) {
            return pick(resident, playerId, turn, "still on " + before.lastTopic() + "?", "how did " + before.lastTopic() + " go?");
        }
        return pick(resident, playerId, turn, "what do you mean?", "wait, didn't quite catch that", "oh?", "what're you up to?");
    }

    public static Intent intent(String message) {
        String text = withoutAddress(normalize(message));
        if (text.matches("(?:please )?(?:stop|stay|wait)(?: here)?[.! ]*")
                || has(text, "stop following", "don't follow", "do not follow", "stay here", "wait here")) {
            return Intent.STOP_FOLLOWING;
        }
        if (text.matches("(?:please )?follow[.! ]*") || has(text, "follow me", "walk with me", "come with me", "let's walk", "lets walk", "come along")) {
            return Intent.FOLLOW;
        }
        if (has(text, "bye", "goodbye", "cya", "see you", "see ya", "gtg", "g2g", "gotta go", "good night", "goodnight", "gn")
                || text.matches("later[.! ]*")) {
            return Intent.FAREWELL;
        }
        if (has(text, "party", "pq", "lfg", "kpq", "lpq", "party invite")) {
            return Intent.PARTY;
        }
        if (has(text, "trade", "sell", "buy", "price", "mesos", "meso", "wts", "wtb", "free items")) {
            return Intent.TRADE;
        }
        if (has(text, "help", "how do", "how to", "where", "stuck", "lost", "quest")) {
            return Intent.HELP;
        }
        if (has(text, "train", "level", "lvl", "grind", "fight", "hunt", "farm", "kill", "boss")) {
            return Intent.HUNT;
        }
        return Intent.TALK;
    }

    public String ambient(ResidentProfiles.Profile resident, long sequence) {
        return ambient(resident, Context.town(), sequence);
    }

    /** Only call for a level increase actually observed by the population service. */
    public String levelUp(ResidentProfiles.Profile resident, String playerName, long sequence) {
        String name = playerName == null ? "" : playerName;
        return pick(resident, name.hashCode(), sequence, switch (resident.id()) {
            case 1 -> new String[]{"ayy grats!", "gz!", "nice, grats " + name, "there it is. grats!"};
            case 2 -> new String[]{"grats", "nice level", "gz", "good stuff"};
            default -> new String[]{"grats :)", "nice, grats", "gz", "well done"};
        });
    }

    public String ambient(ResidentProfiles.Profile resident, Context context, long sequence) {
        String[] lines = activityLines(resident, context);
        return lines[(int) Math.floorMod(sequence + resident.id(), (long) lines.length)];
    }

    /** Use only for a nearby, available pair; the caller schedules the answer after the opener. */
    public Exchange exchange(ResidentProfiles.Profile first, ResidentProfiles.Profile second, Context context, long sequence) {
        if (context.activity() == Activity.TRAINING) {
            return switch ((int) Math.floorMod(sequence, 3L)) {
                case 0 -> new Exchange("taking this side?", "yep, watching behind us");
                case 1 -> new Exchange("break after this?", "yeah, sounds good");
                default -> new Exchange("those respawns though", "barely a breather lol");
            };
        }
        return switch ((int) Math.floorMod(sequence + first.id() + second.id(), 5L)) {
            case 0 -> new Exchange("taking a break?", "yeah, just a minute");
            case 1 -> new Exchange("heading out again?", "in a bit. chilling rn");
            case 2 -> new Exchange("which map next?", "still deciding");
            case 3 -> new Exchange("one more lap?", first.id() == 1 ? "you and your laps lol" : "maybe after a break");
            default -> new Exchange("still here?", "yep, no rush");
        };
    }

    private static String[] activityLines(ResidentProfiles.Profile resident, Context context) {
        return switch (context.activity()) {
            case TRAINING -> switch (resident.id()) {
                case 1 -> new String[]{"these things respawn so fast lol", "one more round", "watch the one behind you", "break after this?"};
                case 2 -> new String[]{"taking this side", "keeping it steady", "watch the next spawn", "a few more then a break"};
                case 3 -> new String[]{"one at a time", "watch behind you", "no rush", "taking this slowly"};
                default -> new String[]{"one more round", "taking this side", "these respawns", "break soon"};
            };
            case LOOTING -> new String[]{"one sec, drops", "checking the ground", "picking these up first"};
            case RESTING -> resident.id() == 3
                    ? new String[]{"quiet break", "just resting", "no rush", "this spot's fine"}
                    : new String[]{"brb a sec", "taking a breather", "just chilling", "break time"};
            case SHOPPING -> new String[]{"just looking", "window shopping", "checking the shops", "not buying anything rn"};
            case FOLLOWING -> new String[]{"right behind you", "which way?", "coming", "take your time at the next platform"};
            case WAITING -> new String[]{"I'll be here", "take your time", "waiting here", "no rush"};
            case EXPLORING -> new String[]{"checking the next map", "which portal was it again?", "taking the long way", "let's see what's over here"};
            case ROAMING -> resident.id() == 1
                    ? new String[]{"one more lap then a break", "might head east in a bit", "anyone heading out?", "just stretching my legs"}
                    : new String[]{"just passing through", "taking a lap", "might head out in a bit", "back through town"};
            case IDLE -> idleLines(resident);
        };
    }

    private static String[] idleLines(ResidentProfiles.Profile resident) {
        return switch (resident.id()) {
            case 1 -> new String[]{"hene break", "anyone heading east?", "might check the next map", "one more lap maybe"};
            case 2 -> new String[]{"taking a minute", "back in town", "might head to the fields", "not rushing it"};
            case 3 -> new String[]{"just hanging here", "quiet spot", "no rush", "taking it slow"};
            case 4, 9, 18 -> new String[]{"which map next", "checking the route", "town stop", "not staying long"};
            case 5, 7, 14, 19 -> new String[]{"just chilling", "good place for a break", "hanging here a bit", "not in a hurry"};
            case 6, 11, 13 -> new String[]{"might check the shops", "that outfit though", "window shopping later", "just looking around"};
            case 10, 17 -> new String[]{"still figuring this place out", "which portal was it", "made it back at least", "taking it slow"};
            default -> new String[]{"back for a bit", "anyone heading out?", "town break", "taking a minute"};
        };
    }

    private static String[] firstHello(ResidentProfiles.Profile resident) {
        return switch (resident.id()) {
            case 1 -> new String[]{"hey o/", "yo", "hey, mia here", "hey, what's up?"};
            case 2 -> new String[]{"hey", "yo", "sup", "hey, taking a break?"};
            case 3 -> new String[]{"hey", "hi :)", "oh, hi", "taking a break too?"};
            default -> new String[]{"hey", "yo", "hi", "what's up?"};
        };
    }

    private synchronized String pick(ResidentProfiles.Profile resident, int playerId, long sequence, String... lines) {
        Conversation key = new Conversation(resident.id(), playerId);
        ArrayDeque<String> history = recent.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        int start = (int) Math.floorMod(sequence + resident.id() * 13L + playerId * 7L, (long) lines.length);
        String selected = lines[start];
        for (int offset = 0; offset < lines.length; offset++) {
            String candidate = lines[(start + offset) % lines.length];
            if (!history.contains(candidate)) {
                selected = candidate;
                break;
            }
        }
        if (selected.equals(history.peekLast()) && lines.length > 1) {
            selected = lines[(start + 1) % lines.length];
        }
        history.addLast(selected);
        while (history.size() > 6) {
            history.removeFirst();
        }
        if (recent.size() > MAX_RECENT_CONVERSATIONS) {
            recent.remove(recent.keySet().iterator().next());
        }
        return selected;
    }

    private static String topic(String text) {
        if (has(text, "remember", "recognize", "recognise", "my name")) return "";
        if (has(text, "mushroom", "slime", "snail")) return "mushrooms";
        if (has(text, "train", "level", "lvl", "grind", "fight", "hunt", "farm")) return "your training";
        if (has(text, "party", "boss", "quest")) return "your next adventure";
        if (has(text, "trade", "shop", "buy", "sell")) return "shopping";
        if (has(text, "lonely", "friend", "company", "alone", "sad")) return "finding some company";
        if (has(text, "weather", "rain", "sun", "cloud")) return "the weather";
        if (has(text, "henesys", "town", "home", "travel", "ellinia", "kerning", "perion")) return "your travels";
        return "";
    }

    private static String jobName(int job) {
        return switch (job / 100) {
            case 1 -> "warrior";
            case 2 -> job == 230 ? "cleric" : "mage";
            case 3 -> "archer";
            case 4 -> "thief";
            case 5 -> "pirate";
            default -> "beginner";
        };
    }

    private static String withoutAddress(String text) {
        for (var resident : ResidentProfiles.all()) {
            String name = resident.name().toLowerCase(Locale.ROOT);
            if (text.startsWith(name) && text.length() > name.length() && !Character.isLetterOrDigit(text.charAt(name.length()))) {
                return text.substring(name.length()).replaceFirst("^[, :]+", "").strip();
            }
        }
        return text;
    }

    private static String normalize(String message) {
        if (message == null) return "";
        return message.substring(0, Math.min(message.length(), 240)).toLowerCase(Locale.ROOT).strip();
    }

    private static boolean has(String text, String... terms) {
        for (String term : terms) {
            String ending = term.length() > 3 && !term.contains(" ") ? "[a-z]*" : "";
            if (Pattern.compile("(?<![a-z])" + Pattern.quote(term) + ending + "(?![a-z])").matcher(text).find()) return true;
        }
        return false;
    }
}
