package server.population;

import client.Job;
import server.population.ResidentVoice.Topic;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Short map chat with stable voices and bounded, per-player conversational context. */
public final class ResidentDialogue {
    public enum Intent {
        TALK, FOLLOW, STOP_FOLLOWING, PARTY, HUNT, TRADE, HELP, FAREWELL, MAP_SPACE
    }

    public enum Activity {
        IDLE, ROAMING, RESTING, SHOPPING, EXPLORING, FOLLOWING, WAITING, TRAINING, LOOTING
    }

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

    private static final class Turn {
        final ArrayDeque<String> recent = new ArrayDeque<>();
        ResidentChatInput.Intent previous = ResidentChatInput.Intent.UNKNOWN;
        boolean askedLevel;
        boolean trainingAdvice;
        boolean offeredFollow;
        long lastAt;
    }

    private static final List<String> ADDRESSES = addresses();
    private final ResidentMemoryStore memories;
    private final Clock clock;
    private final Map<Conversation, Turn> turns = new LinkedHashMap<>(32, .75f, true);

    public ResidentDialogue(ResidentMemoryStore memories) {
        this(memories, Clock.systemUTC());
    }

    ResidentDialogue(ResidentMemoryStore memories, Clock clock) {
        this.memories = Objects.requireNonNull(memories);
        this.clock = Objects.requireNonNull(clock);
    }

    public String greeting(ResidentProfiles.Profile resident, int playerId, String playerName) {
        return greeting(resident, playerId, playerName, Context.town());
    }

    public String greeting(ResidentProfiles.Profile resident, int playerId, String playerName,
            Context context) {
        boolean met = memories.get(playerId, resident.id(), playerName).hasMet();
        var memory = memories.visit(playerId, resident.id(), playerName);
        return say(resident, playerId, memory.visits(), met ? Topic.RETURN : Topic.HELLO, context);
    }

    public String reply(ResidentProfiles.Profile resident, int playerId, String playerName, String message) {
        return reply(resident, playerId, playerName, message, Context.town());
    }

    public synchronized String reply(ResidentProfiles.Profile resident, int playerId, String playerName, String message,
            Context context) {
        ResidentChatInput input = ResidentChatInput.parse(message, resident.name(), ADDRESSES);
        var before = memories.get(playerId, resident.id(), playerName);
        memories.converse(playerId, resident.id(), playerName, topic(input));
        long sequence = before.conversations();
        Turn turn = turn(resident, playerId);
        if (clock.millis() - turn.lastAt > 90_000) {
            turn.previous = ResidentChatInput.Intent.UNKNOWN;
            turn.askedLevel = false;
            turn.offeredFollow = false;
        }
        if (acceptsOffer(turn, input)) {
            input = new ResidentChatInput(input.text(), ResidentChatInput.Intent.FOLLOW,
                    input.addressed(), input.question());
        }
        turn.offeredFollow = false;
        turn.lastAt = clock.millis();
        var previous = turn.previous;
        turn.previous = input.intent();
        if (turn.askedLevel && input.text().matches("(?:im |i am |lvl |level )?\\d{1,3}")) {
            turn.askedLevel = false;
            int level = Integer.parseInt(input.text().replaceAll("\\D", ""));
            if (level >= 1 && level <= 255) {
                if (turn.trainingAdvice) {
                    turn.trainingAdvice = false;
                    return level <= 20 ? "try the mushrooms east of hene"
                            : level <= 30 ? "tried the pigs east of hene?" : "these mobs are pretty low for u";
                }
                return pick(resident, playerId, sequence, "nice", "oh nice, gl with the grind", "ah kk");
            }
        }
        turn.askedLevel = false;
        turn.trainingAdvice = false;
        if (input.text().matches("(?:and )?(?:you|u)")) {
            if (previous == ResidentChatInput.Intent.LEVEL) {
                return resident.level() + " " + jobName(resident.job());
            }
            return say(resident, playerId, sequence, Topic.ACTIVITY, context);
        }
        return switch (input.intent()) {
            case IDENTITY -> "yeah, server-run bot";
            case MEMORY -> !before.hasMet() ? "don't think we've met, " + playerName
                    : before.lastTopic().isBlank() ? "yeah, " + before.playerName() + " :)"
                    : "yeah " + before.playerName() + ", we talked about " + before.lastTopic();
            case LEVEL -> {
                boolean askBack = ResidentVoice.voice(resident) == ResidentVoice.Voice.SOCIAL
                        || ResidentVoice.voice(resident) == ResidentVoice.Voice.PLAYFUL;
                turn.askedLevel = askBack;
                yield resident.level() + " " + jobName(resident.job()) + (askBack ? ", u?" : "");
            }
            case JOB, ABOUT -> jobName(resident.job())
                    + (input.intent() == ResidentChatInput.Intent.ABOUT ? ", lvl " + resident.level() : "");
            case FOLLOW -> say(resident, playerId, sequence,
                    resident.companion() ? Topic.FOLLOW_ACCEPT : Topic.FOLLOW_DECLINE, context);
            case STAY -> say(resident, playerId, sequence, Topic.WAIT_ACCEPT, context);
            case GOODBYE -> say(resident, playerId, sequence, Topic.BYE, context);
            case GREETING -> say(resident, playerId, sequence, before.hasMet() ? Topic.RETURN : Topic.HELLO, context);
            case WELCOME_BACK -> say(resident, playerId, sequence, Topic.RETURN, context);
            case THANKS, GRATS -> say(resident, playerId, sequence, Topic.THANKS, context);
            case ACK, DISMISS -> say(resident, playerId, sequence, Topic.ACK, context);
            case BANTER -> say(resident, playerId, sequence, Topic.BANTER, context);
            case COMPLAINT -> say(resident, playerId, sequence, complaintTopic(input), context);
            case LAUGH -> input.has("fell", "fall", "jump")
                    ? pick(resident, playerId, sequence, "lol that jump", "F4", "oof, try the rope")
                    : say(resident, playerId, sequence, Topic.LAUGH, context);
            case BRB -> say(resident, playerId, sequence, Topic.BRB, context);
            case DIED -> say(resident, playerId, sequence, Topic.DEATH, context);
            case LEVEL_UP -> levelUp(resident, playerName, sequence);
            case PARTY -> say(resident, playerId, sequence, Topic.PARTY, context);
            case BUY, SELL, TRADE -> say(resident, playerId, sequence, Topic.TRADE, context);
            case BUFF -> "can't buff, sry";
            case MAP_COURTESY -> input.has("taken", "anyone here", "this map yours", "your map")
                    ? pick(resident, playerId, sequence, "u can train here", "room for both of us", "nah, go ahead")
                    : say(resident, playerId, sequence, Topic.MAP_SPACE, context);
            case COMPLIMENT -> input.has("hat") && !resident.equipment().containsKey(1)
                    ? "my hair? ty lol" : pick(resident, playerId, sequence, "ty :)", "tyty", "thx");
            case PROGRESS -> input.text().matches(".*\\b9[5-9]\\b.*")
                    ? pick(resident, playerId, sequence, "don't die now lmao", "last 1% takes a year", "one more mob. allegedly")
                    : pick(resident, playerId, sequence, "exp bar moves when it feels like it", "back to the mushroom mines");
            case LOCATION -> {
                if (input.has("train", "training", "level", "lvl", "grind")) {
                    turn.askedLevel = true;
                    turn.trainingAdvice = true;
                    yield "what lvl?";
                }
                yield location(input, context);
            }
            case HELP -> {
                if (input.has("quest", "quests")) {
                    yield "which quest? gotta talk to the quest npc yourself";
                }
                if (input.has("train", "training", "level", "lvl", "grind")) {
                    turn.askedLevel = true;
                    turn.trainingAdvice = true;
                    yield "what lvl?";
                }
                yield context.following() ? "ye, tag a mob first" : "with what?";
            }
            case HUNT -> {
                if (context.activity() == Activity.TRAINING) {
                    yield say(resident, playerId, sequence, Topic.HUNT, context);
                }
                if (context.following()) {
                    yield "ye, hit something and i'll help";
                }
                turn.offeredFollow = resident.companion();
                yield resident.companion() ? "sure, want me to come?"
                        : say(resident, playerId, sequence, Topic.ACTIVITY, context);
            }
            case STATUS -> input.has("coming", "come", "there", "following") && context.following()
                    ? say(resident, playerId, sequence, Topic.FOLLOW, context)
                    : say(resident, playerId, sequence, Topic.ACTIVITY, context);
            case COMPANY -> say(resident, playerId, sequence, Topic.COMPANY, context);
            case MONSTERS -> mobComment(input, resident, playerId, sequence, context);
            default -> {
                if (input.text().equals("why") && previous == ResidentChatInput.Intent.PARTY) {
                    yield resident.companion() ? "can't accept party invites. can still run with u"
                            : "can't accept party invites";
                }
                yield say(resident, playerId, sequence, Topic.UNKNOWN, context);
            }
        };
    }

    public static Intent intent(String message) {
        return intent(ResidentChatInput.parse(message, "", ADDRESSES));
    }

    private static Intent intent(ResidentChatInput input) {
        return switch (input.intent()) {
            case FOLLOW -> Intent.FOLLOW;
            case STAY -> Intent.STOP_FOLLOWING;
            case GOODBYE -> Intent.FAREWELL;
            case PARTY -> Intent.PARTY;
            case BUY, SELL, TRADE -> Intent.TRADE;
            case HUNT -> Intent.HUNT;
            case HELP, LOCATION -> Intent.HELP;
            case MAP_COURTESY -> Intent.MAP_SPACE;
            default -> Intent.TALK;
        };
    }

    public synchronized Intent intentFor(ResidentProfiles.Profile resident, int playerId, String message) {
        Turn turn = turns.get(new Conversation(resident.id(), playerId));
        ResidentChatInput input = ResidentChatInput.parse(message, resident.name(), ADDRESSES);
        if (turn != null && clock.millis() - turn.lastAt <= 90_000
                && acceptsOffer(turn, input)) {
            return Intent.FOLLOW;
        }
        return intent(input);
    }

    private static boolean acceptsOffer(Turn turn, ResidentChatInput input) {
        return turn.offeredFollow && input.intent() == ResidentChatInput.Intent.ACK;
    }

    static boolean continuesConversation(String message) {
        var input = ResidentChatInput.parse(message);
        if (switch (input.intent()) {
            case FOLLOW, STAY, DISMISS -> true;
            default -> false;
        }) {
            return true;
        }
        if (message.matches("(?is)^\\s*[a-z][a-z0-9_]{2,12}\\s*[:,].*")) {
            return false;
        }
        return switch (input.intent()) {
            case BUY, SELL -> false;
            case PARTY -> !input.has("lfg", "lfp", "lfm", "recruiting");
            default -> true;
        };
    }

    public String ambient(ResidentProfiles.Profile resident, long sequence) {
        return ambient(resident, Context.town(), sequence);
    }

    public String ambient(ResidentProfiles.Profile resident, Context context, long sequence) {
        return say(resident, 0, sequence, Topic.ACTIVITY, context);
    }

    public String levelUp(ResidentProfiles.Profile resident, String playerName, long sequence) {
        return say(resident, playerName == null ? 0 : playerName.hashCode(), sequence,
                Topic.LEVEL_UP, Context.town());
    }

    public Exchange exchange(ResidentProfiles.Profile first, ResidentProfiles.Profile second, Context context,
            long sequence) {
        return ResidentVoice.exchange(first, second, context, sequence);
    }

    private String say(ResidentProfiles.Profile resident, int playerId, long sequence, Topic topic,
            Context context) {
        return pick(resident, playerId, sequence, ResidentVoice.lines(resident, topic, context));
    }

    private synchronized String pick(ResidentProfiles.Profile resident, int playerId, long sequence,
            String... lines) {
        Turn turn = turn(resident, playerId);
        int start = (int) Math.floorMod(sequence + resident.id() * 13L + playerId * 7L, (long) lines.length);
        String selected = lines[start];
        for (int offset = 0; offset < lines.length; offset++) {
            String candidate = lines[(start + offset) % lines.length];
            if (!turn.recent.contains(candidate)) {
                selected = candidate;
                break;
            }
        }
        if (selected.equals(turn.recent.peekLast()) && lines.length > 1) {
            selected = lines[(start + 1) % lines.length];
        }
        turn.recent.addLast(selected);
        while (turn.recent.size() > 6) {
            turn.recent.removeFirst();
        }
        return selected;
    }

    private Turn turn(ResidentProfiles.Profile resident, int playerId) {
        Turn turn = turns.computeIfAbsent(new Conversation(resident.id(), playerId), ignored -> new Turn());
        while (turns.size() > 1024) {
            turns.remove(turns.keySet().iterator().next());
        }
        return turn;
    }

    private static String location(ResidentChatInput input, Context context) {
        if (input.has("pots", "potions", "shop", "store")) {
            return "pot shop in town";
        }
        if (input.has("npc")) {
            return "which npc?";
        }
        if (input.has("train", "grind", "level", "training")) {
            return "tried the mushrooms east of hene?";
        }
        return context.mapName();
    }

    private String mobComment(ResidentChatInput input, ResidentProfiles.Profile resident, int playerId, long sequence,
            Context context) {
        if (input.has("snail", "snails")) {
            return pick(resident, playerId, sequence,
                    "snails got more patience than me", "imagine losing a race to a snail", "snail population owes us an apology");
        }
        if (input.has("slime", "slimes")) {
            return pick(resident, playerId, sequence,
                    "slimes are just angry toothpaste", "that squish sound is so damn gross", "green little bastards");
        }
        return say(resident, playerId, sequence, Topic.MOBS, context);
    }

    private static Topic complaintTopic(ResidentChatInput input) {
        if (input.selfDeprecating()) {
            return Topic.SELF_DEPRECATION;
        }
        if (input.has("lag", "laggy", "lagging", "dc", "dced", "disconnect", "disconnected")) {
            return Topic.LAG;
        }
        if (input.has("drop", "drops", "droprate", "loot", "rng", "scroll", "scrolls", "scrolling", "scrolled")) {
            return Topic.BAD_DROPS;
        }
        return Topic.COMPLAINT;
    }

    private static String topic(ResidentChatInput input) {
        if (input.intent() == ResidentChatInput.Intent.MEMORY) {
            return "";
        }
        if (input.has("snail", "snails")) {
            return "snails";
        }
        if (input.has("slime", "slimes")) {
            return "slimes";
        }
        if (input.has("mushroom", "mushrooms")) {
            return "mushrooms";
        }
        return switch (input.intent()) {
            case HUNT, PROGRESS -> "your training";
            case PARTY -> "party quests";
            case BUY, SELL, TRADE -> "shopping";
            case COMPANY -> "hanging out";
            case BANTER -> "talking shit";
            case COMPLAINT -> "the grind";
            default -> "";
        };
    }

    private static String jobName(int job) {
        Job known = Job.getById(job);
        return known == null ? "beginner" : known.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static List<String> addresses() {
        List<String> names = new ArrayList<>(List.of("Mia", "Pip", "Juniper"));
        ResidentProfiles.all().forEach(r -> names.add(r.name()));
        for (int map : new int[]{ResidentRoster.TRAINING_GROUND, ResidentRoster.PIG_FIELD}) {
            ResidentRoster.forMap(map).forEach(p -> names.add(p.profile().name()));
        }
        return List.copyOf(names);
    }
}
