package server.population.knowledge;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Authoritative facts, kept separate from voice and from commands that change gameplay. */
public final class GameKnowledge {
    public record Answer(String text, String source, List<String> facts) {
        public Answer {
            Objects.requireNonNull(text);
            Objects.requireNonNull(source);
            facts = List.copyOf(facts);
        }
    }

    public record KpqRules(int minimumPlayers, int maximumPlayers, int minimumLevel, int maximumLevel,
                           int minutes) { }
    public record Item(int id, String name) { }
    public record Skill(String name, String description) { }
    public record QuestFact(int id, String name, String npc, int minimumLevel, String description) { }
    public record Drop(String monster, List<String> maps, int questId, boolean global) {
        public Drop { maps = List.copyOf(maps); }
    }
    public interface Source {
        Optional<KpqRules> kpqRules();
        List<Item> findItems(String query);
        List<Drop> drops(int itemId);
        default List<Skill> findSkills(String query) { return List.of(); }
        default List<QuestFact> findQuests(String query) { return List.of(); }
    }

    private static final Pattern QUESTION = Pattern.compile(
            "(?:^| )(?:what|whats|where|which|how|why|when|who|explain|tell me|remind me|help me|help with)(?: |$)");
    private static final Pattern KNOWLEDGE = Pattern.compile(
            "(?:^| )(?:kpq|kerning pq|party quest|stage|coupons?|passes|ropes?|platforms?|barrels?|king slime|question|answer|"
                    + "drops?|farm|find|train|training|grind|level|lvl|job|advance|advancement|quest|quests|skill|skills)(?: |$)");
    private static final String GAME_STATS = "(?:hp|mp|health|mana|damage|dmg|stats?|str|dex|int|luk|accuracy|avoidability|"
            + "defen[cs]e|attack speed|cooldown|duration)";
    private static final String GAME_MECHANICS = "(?:" + GAME_STATS + "|equipment|weapons?|armou?r|scrolls?|potions?|buffs?|skills?|"
            + "magic guard|haste|double stab)";
    private static final Pattern MECHANIC_TERM = Pattern.compile("(?:^| )" + GAME_MECHANICS + "(?: |$)");
    private static final Pattern MECHANIC_QUESTION = Pattern.compile(
            "^(?:(?:what|whats)(?: is| are)? (?:the )?(?:(?:min|max|minimum|maximum|base|total|physical|magic) )?"
                    + GAME_STATS + "(?: |$)"
                    + "|how (?:much|many) " + GAME_STATS + "(?: |$)"
                    + "|how (?:long|often) (?!are (?:you|u)\\b|have (?:you|u)\\b).+"
                    + "|(?:does|do|can|will) .+\\b(?:block|reduce|increase|affect|stack|prevent|cancel|restore|heal|protect|last)\\b.*"
                    + "|(?:can|could|may|do) i (?:equip|wear|wield|use) .+)");
    private static final Pattern EQUIPMENT_QUESTION = Pattern.compile("^(?:can|could|may|do) i (?:equip|wear|wield) .+");
    private static final Pattern QUESTION_LEAD_IN = Pattern.compile("^(?:(?:hey|hi|hello|yo|please|pls|plz) )+");
    private static final String KPQ_RULES_SOURCE = "scripts/event/KerningPQ.js";
    private static final String KPQ_STAGES_SOURCE = "scripts/npc/9020001.js";
    private final Source source;

    public GameKnowledge(Source source) { this.source = Objects.requireNonNull(source); }

    public static boolean isQuestion(String message) {
        String text = normalize(message);
        return unsupportedMechanicsQuestion(text) || QUESTION.matcher(text).find() && (KNOWLEDGE.matcher(text).find()
                || text.matches("^(?:where .+|what does .+|how (?:do|can) i .+)"))
                && !text.matches(".*(?:what|whats) (?:is )?(?:your|ur) (?:level|lvl|job|class).*?");
    }

    public Optional<Answer> answer(String message, PlayerContext player) {
        String text = normalize(message);
        if (!isQuestion(text)) return Optional.empty();
        boolean unverifiedMechanic = unsupportedMechanicsQuestion(text);
        boolean inKpq = player.mapId() >= 103000800 && player.mapId() <= 103000805;
        boolean kpqQuestion = text.contains("kpq") || text.contains("kerning pq") || text.contains("king slime")
                || inKpq && contains(text, "stage", "rope", "coupon", "pass", "platform", "barrel", "question", "answer", "do now", "do here");
        if (kpqQuestion && (!unverifiedMechanic || couponQuestion(text, player))) {
            return Optional.of(kpq(text, player));
        }
        if (unverifiedMechanic) return Optional.of(fact(
                "can't verify that stat or mechanic here. i can check a skill's description, item drops, or a quest's basics.",
                "unsupported", "Detailed combat formulas, durations and equipment eligibility are not verified by this knowledge service."));
        if (contains(text, "drop", "farm", "find", "where can i get", "where do i get")) {
            Optional<Answer> item = item(text, player);
            if (item.isPresent()) return item;
        }
        if (contains(text, "job", "advance", "advancement")) return Optional.of(job(text, player));
        if (contains(text, "train", "training", "grind", "level up")) return Optional.of(training(player));
        if (contains(text, "quest")) return Optional.of(quest(text));
        if (text.startsWith("what does ") || contains(text, "skill", "how does")) return Optional.of(skill(text));
        return Optional.of(fact("not sure on that one. which item, quest or skill do u mean?", "unsupported",
                "No verified answer is available for this question. Ask for the specific item, quest, or skill."));
    }

    private static boolean unsupportedMechanicsQuestion(String text) {
        text = QUESTION_LEAD_IN.matcher(text).replaceFirst("");
        return EQUIPMENT_QUESTION.matcher(text).matches()
                || MECHANIC_QUESTION.matcher(text).find() && MECHANIC_TERM.matcher(text).find();
    }

    /** Resolves a short entity/stage follow-up within one resident/player conversation. Never executes commands. */
    public Optional<String> expandFollowUp(String message, String previousQuestion, Answer previousAnswer) {
        String text = normalize(message);
        if (text.isBlank() || text.length() > 100 || text.matches("(?:hi|hey|hello|yo|ty|thx|thanks|lol|lmao|haha|ok|kk|yes|no|nah|brb|bye|gg|nice|follow me|stay|wait|stop)")) {
            return Optional.empty();
        }
        String previous = normalize(previousQuestion);
        boolean previousKpq = previousAnswer.source().equals(KPQ_RULES_SOURCE) || previousAnswer.source().equals(KPQ_STAGES_SOURCE);
        if (previousKpq && (contains(text, "stage", "rope", "platform", "barrel", "coupon", "king slime")
                || text.matches("(?:why|how|and then|what next)"))) {
            return Optional.of(text.equals("why") ? previous : "how do we do kpq " + text);
        }
        if (isQuestion(text)) return Optional.empty();
        if (text.matches("(?:and )?(?:where|where is that|where are they|where do i go|what drops it)")) return Optional.of(previous);
        String entity = text.replaceFirst("^(?:what about|how about|and|the) +", "");
        String sourceName = previousAnswer.source();
        if ((sourceName.contains("drop_data") || sourceName.equals("item-name-index") || sourceName.equals("String.wz"))
                && !source.findItems(entity).isEmpty()) return Optional.of("what drops " + entity);
        if (sourceName.contains("Skill.img") && !source.findSkills(entity).isEmpty()) return Optional.of("what does " + entity + " do");
        if (sourceName.startsWith("Quest.wz") && !source.findQuests(entity).isEmpty()) return Optional.of("how do i start " + entity + " quest");
        return Optional.empty();
    }

    private static boolean couponQuestion(String text, PlayerContext player) {
        return contains(text, "coupon") || player.mapId() == 103000800 && contains(text, "question", "answer");
    }

    private Answer kpq(String text, PlayerContext player) {
        if (couponQuestion(text, player) && contains(text, "many", "answer", "question", "str", "int", "dex")) {
            String response;
            if (contains(text, "str", "strength")) response = "warrior STR question = 35 coupons.";
            else if (contains(text, "int", "intelligence")) response = "magician INT question = 20 coupons.";
            else if (contains(text, "dex", "dexterity")) response = "bowman or thief DEX question = 25 coupons.";
            else if (contains(text, "2nd", "second")) response = "second job level question = 30 coupons.";
            else if (contains(text, "magician", "mage") && contains(text, "level", "lvl")) response = "magician level question = 8 coupons.";
            else if (contains(text, "warrior") && contains(text, "level", "lvl")) response = "warrior level question = 10 coupons.";
            else response = "depends on Cloto's question: warrior lvl 10 / STR 35, mage INT 20 / lvl 8, bowman or thief DEX 25, 2nd job lvl 30. which one did u get?";
            return fact(response, KPQ_STAGES_SOURCE, response);
        }
        if (contains(text, "passes", "pass") && contains(text, "many", "need", "collect")) {
            String response = player.mapId() == 103000804 || contains(text, "stage 5", "king slime", "final")
                    ? "10 passes for the final stage. give em to leader, then leader checks with Cloto."
                    : "stage 1 needs one pass from each nonleader. stage 5 needs 10 passes total.";
            return fact(response, KPQ_STAGES_SOURCE, response);
        }
        boolean mechanic = contains(text, "stage", "rope", "platform", "barrel", "coupon", "pass", "king slime", "boss");
        if (!mechanic && contains(text, "level", "lvl", "require", "eligible", "many", "long", "time", "enter", "start", "where", "get in", "join")) {
            var rule = source.kpqRules();
            if (rule.isEmpty()) return fact("can't check the KPQ requirements rn, ask Lakelis in Kerning", "unavailable",
                    "The KPQ rule source is unavailable; do not guess requirements.");
            KpqRules r = rule.get();
            String line = "KPQ here is lvl " + r.minimumLevel() + "-" + r.maximumLevel() + ", "
                    + r.minimumPlayers() + "-" + r.maximumPlayers() + " people, " + r.minutes()
                    + " mins. leader talks to Lakelis in Kerning.";
            if (player.level() < r.minimumLevel()) line += " ur " + (r.minimumLevel() - player.level()) + " lvls short.";
            else if (player.level() > r.maximumLevel()) line += " ur over the lvl cap.";
            return fact(line, KPQ_RULES_SOURCE, line);
        }
        int stage = player.mapId() >= 103000800 && player.mapId() <= 103000805
                ? player.mapId() - 103000800 + 1 : 0;
        var matcher = Pattern.compile("(?:stage|stg) ([1-6])").matcher(text);
        if (matcher.find()) stage = Integer.parseInt(matcher.group(1));
        if (contains(text, "rope")) stage = 2;
        if (contains(text, "platform")) stage = 3;
        if (contains(text, "barrel")) stage = 4;
        if (contains(text, "king slime", "boss")) stage = 5;
        if (contains(text, "coupon")) stage = 1;
        String response = switch (stage) {
            case 1 -> "talk to Cloto for ur question, get exactly that many coupons from the crocs, trade em for a pass. drop the pass for leader.";
            case 2 -> "3 people hang near the middle of different ropes. leader checks Cloto. wrong = try another combo; clear = portal opens.";
            case 3 -> "3 people on different numbered platforms, near the middle. leader checks Cloto. keep trying combos after wrong.";
            case 4 -> "same idea with the barrels: 3 people, different barrels, stand in the middle. leader checks; switch combo after wrong.";
            case 5 -> "kill the mobs and King Slime, gather 10 passes for leader. Cloto clears it, then talk to her for ur reward and bonus stage.";
            case 6 -> "bonus stage, kill stuff and loot until we're ready to leave. talk to the exit NPC when ur done.";
            default -> "coupons first, then ropes, platforms, barrels, and King Slime. leader handles checks; everyone helps with the rest.";
        };
        if (contains(text, "answer", "question") && stage == 1) {
            response = "coupon answers: warrior lvl 10 / STR 35, mage INT 20 / lvl 8, bowman or thief DEX 25, 2nd job lvl 30. which question did u get?";
        }
        return fact(response, KPQ_STAGES_SOURCE, response,
                "The secret winning combination is unknown. Learn it only through visible wrong/clear checks.");
    }

    private Optional<Answer> item(String text, PlayerContext player) {
        String query = text.replaceFirst("^.*?(?:what drops|who drops|where (?:can|do) i (?:find|farm|get)|where to (?:find|farm|get)|where (?:does|do)) +", "")
                .replaceFirst("(?: drop| drops| from| please| pls| here)$", "").strip();
        if (query.equals(text)) query = text.replaceFirst("^.*?(?:find|farm) +", "").strip();
        if (query.isBlank() || query.equals(text)) return Optional.empty();
        List<Item> items = source.findItems(query);
        if (items.isEmpty()) return Optional.of(fact("can't find that item name. what's the exact name?", "item-name-index",
                "No matching item was found; ask for its exact name."));
        if (items.size() > 1) return Optional.of(fact("which one: " + String.join(", ", items.stream().limit(3).map(Item::name).toList()) + "?",
                "String.wz", "The item name is ambiguous; ask the player to choose a specific item."));
        Item item = items.getFirst();
        List<Drop> drops = source.drops(item.id());
        List<Drop> eligible = drops.stream().filter(d -> d.questId() == 0 || player.activeQuests().contains(d.questId())).toList();
        if (eligible.isEmpty()) {
            String reply = drops.isEmpty() ? "no monster drop listed for " + item.name() + ". could be a shop, quest or crafting item."
                    : item.name() + " is listed as a quest drop; u need the matching quest active.";
            return Optional.of(fact(reply, "drop_data/drop_data_global", reply));
        }
        Drop first = eligible.stream().filter(d -> !d.global()).findFirst().orElse(eligible.getFirst());
        String where = first.maps().isEmpty() ? "" : " at " + first.maps().getFirst();
        String response = first.global() ? item.name() + " is in the global drop table; monster/region conditions still apply."
                : item.name() + ": " + first.monster() + where + (first.questId() > 0 ? " (while ur quest is active)" : "") + ".";
        if (eligible.size() > 1 && !first.global()) response += " other mobs can drop it too.";
        return Optional.of(fact(response, "drop_data/drop_data_global; String.wz; Map.wz", response,
                "Drop percentages are not provided because rates and conditions are server dependent."));
    }

    private Answer job(String text, PlayerContext player) {
        int family = player.job() / 100;
        if (contains(text, "warrior", "fighter", "page", "spearman")) family = 1;
        else if (contains(text, "mage", "magician", "cleric", "wizard")) family = 2;
        else if (contains(text, "bowman", "archer", "hunter", "crossbow")) family = 3;
        else if (contains(text, "thief", "rogue", "assassin", "bandit")) family = 4;
        else if (contains(text, "pirate", "brawler", "gunslinger")) family = 5;
        if (family < 1 || family > 5) return fact("which job? i know the explorer advances: warrior, mage, bowman, thief and pirate.",
                "explorer-job-scripts", "Only Explorer first and second advancement guidance is supported.");
        String teacher = switch (family) {
            case 1 -> "Dances with Balrog in Perion";
            case 2 -> "Grendel in Ellinia";
            case 3 -> "Athena Pierce in Henesys";
            case 4 -> "Dark Lord in Kerning";
            default -> "Kyrin on the Nautilus";
        };
        boolean second = contains(text, "2nd", "second") || player.job() > 0 && player.job() % 100 == 0
                && !contains(text, "1st", "first");
        String response;
        if (second) {
            response = "2nd job is lvl 30. talk to " + teacher + " for the test, then bring back the proof. spend ur remaining 1st-job SP.";
        } else {
            String stat = switch (family) { case 1 -> "35 STR"; case 2 -> "20 INT"; case 3, 4 -> "25 DEX"; default -> "20 DEX"; };
            response = "1st job: lvl " + (family == 2 ? 8 : 10) + ", talk to " + teacher + ". "
                    + (player.autoAssignStarterStats() ? "starter auto-assign handles the stat requirement here." : "need " + stat + ".");
        }
        return fact(response, "scripts/npc explorer instructors; AbstractPlayerInteraction.canGetFirstJob", response);
    }

    private Answer training(PlayerContext p) {
        if (p.level() > 30) return fact("ur past my early-game routes. tell me a map ur considering and check how fast u can kill there.",
                "early-training-guide", "Only early-game training recommendations through level 30 are supported; do not invent optimal EXP/hour.");
        String base = p.level() < 10 ? "stay with beginner quests and low-level snails for now"
                : p.level() < 20 ? "try low-level mushrooms near Henesys; move up when they die quickly"
                : "KPQ is an option at 21-30; for solo, try pigs near Henesys and compare ur kill speed";
        String classAdvice = p.job() / 100 == 2 ? ". watch ur MP/potion cost" : p.job() / 100 == 3 || p.job() / 100 == 4
                ? ". pick a map where u can keep range" : ". avoid mobs u keep missing";
        return fact(base + classAdvice + ".", "reviewed early-game guide", base + classAdvice,
                "This is a practical suggestion, not a measured claim of optimal EXP/hour.");
    }

    private Answer skill(String text) {
        String query = text.replaceFirst("^(?:what does|how does|what is|whats|tell me about|explain) +", "")
                .replaceFirst("^(?:the )?skill +", "").replaceFirst(" +(?:do|work|skill)$", "").strip();
        List<Skill> skills = source.findSkills(query);
        if (skills.isEmpty()) return fact("which skill? need the exact name to check its description.", "String.wz/Skill.img",
                "No exact skill description is available for the requested name.");
        if (skills.size() > 1) return fact("which skill: " + String.join(", ", skills.stream().limit(3).map(Skill::name).toList()) + "?",
                "String.wz/Skill.img", "The skill name is ambiguous.");
        Skill skill = skills.getFirst();
        return fact(skill.name() + ": " + shorten(skill.description(), 190), "String.wz/Skill.img", skill.description());
    }

    private Answer quest(String text) {
        String query = text.replaceFirst("^(?:how do i (?:do|start|finish|complete)|where (?:do|can) i start|tell me about|help (?:me )?with|explain) +", "")
                .replaceFirst("^(?:the )?quest +", "").replaceFirst(" +quest$", "").strip();
        List<QuestFact> quests = source.findQuests(query);
        if (quests.isEmpty()) return fact("what's the quest's exact name in ur quest log?", "Quest.wz", "No matching quest was found.");
        if (quests.size() > 1) return fact("which quest: " + String.join(", ", quests.stream().limit(2).map(QuestFact::name).toList()) + "?",
                "Quest.wz", "The quest name is ambiguous.");
        QuestFact quest = quests.getFirst();
        String textAnswer = quest.name() + ": " + (quest.npc().isBlank() ? "" : "starts with " + quest.npc() + ". ")
                + (quest.minimumLevel() > 0 ? "lvl " + quest.minimumLevel() + "+. " : "") + shorten(quest.description(), 130);
        return fact(textAnswer, "Quest.wz/QuestInfo.img; Quest.wz/Check.img; String.wz/Npc.img",
                textAnswer, "This is quest-log guidance; scripted prerequisites and event availability may also apply.");
    }

    private static String shorten(String text, int limit) {
        if (text.length() <= limit) return text;
        int sentence = text.lastIndexOf('.', limit);
        return sentence > 35 ? text.substring(0, sentence + 1) : text.substring(0, limit - 3).stripTrailing() + "...";
    }

    private static Answer fact(String text, String source, String... facts) {
        return new Answer(text, source, List.of(facts));
    }
    private static boolean contains(String text, String... words) {
        for (String word : words) if (text.contains(word)) return true;
        return false;
    }
    public static String normalize(String text) {
        return text == null ? "" : text.substring(0, Math.min(512, text.length())).toLowerCase(Locale.ROOT)
                .replace("'", "").replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").strip();
    }
}
