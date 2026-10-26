package server.population;

import java.util.Locale;

/** Authored, contextual dialogue for the offline town prototype; no model service is required. */
public final class ResidentDialogue {
    public enum Intent { TALK, FOLLOW, STOP_FOLLOWING }

    private final ResidentMemoryStore memories;

    public ResidentDialogue(ResidentMemoryStore memories) {
        this.memories = memories;
    }

    public String greeting(ResidentProfiles.Profile resident, int playerId, String playerName) {
        ResidentMemoryStore.Memory before = memories.get(playerId, resident.id(), playerName);
        memories.visit(playerId, resident.id(), playerName);
        if (before.hasMet()) {
            if (!before.lastTopic().isBlank()) {
                return playerName + "! Good to see you again. Still thinking about " + before.lastTopic() + "?";
            }
            return choose(before.visits(),
                    "Welcome back, " + playerName + ". I saved you a little room in the bustle.",
                    "Hey, " + playerName + "! Henesys feels better with a familiar face.",
                    playerName + "! There you are. Taking a breather in town?");
        }
        return switch (resident.id()) {
            case 1 -> "Hi, " + playerName + "! I'm Mia. Collecting stories today. Found anything interesting?";
            case 2 -> "Hey, " + playerName + ". Pip here. Taking a break from target practice. Come say hello.";
            case 3 -> "Hello, " + playerName + ". I'm Juniper. It's a good afternoon for a slow walk.";
            default -> "Hi, " + playerName + "! I'm " + resident.name() + ". Nice to have some company.";
        };
    }

    public String reply(ResidentProfiles.Profile resident, int playerId, String playerName, String message) {
        String text = normalize(message);
        ResidentMemoryStore.Memory before = memories.get(playerId, resident.id(), playerName);
        String topic = topic(text);
        memories.converse(playerId, resident.id(), playerName, topic);
        long variant = before.conversations();

        if (intent(text) == Intent.STOP_FOLLOWING) {
            return "I'll stay around here, " + playerName + ". Come find me when you're back.";
        }
        if (intent(text) == Intent.FOLLOW) {
            if (!resident.companion()) {
                return "I'm staying on my usual route today, " + playerName + ". Mia, Pip, or Juniper might enjoy a walk.";
            }
            return switch (resident.id()) {
                case 1 -> "A little walk? I'm in, " + playerName + "! Lead the way.";
                case 2 -> "Sure, " + playerName + ". I'll keep you company on the road.";
                case 3 -> "Of course, " + playerName + ". No need to rush. I'll walk with you.";
                default -> "Sure, " + playerName + "! Let's take a walk around Henesys.";
            };
        }
        if (contains(text, "remember", "recognize", "recognise", "met before", "my name")) {
            if (!before.hasMet()) {
                return "We're just meeting, " + playerName + ". I'm " + resident.name() + ". I'll remember you next time.";
            }
            if (!before.lastTopic().isBlank()) {
                return "Of course, " + before.playerName() + "! We talked about " + before.lastTopic() + ". I remember.";
            }
            return "Of course, " + before.playerName() + "! We met here in Henesys. It's good to see you again.";
        }
        if (contains(text, "who are you", "your name", "about yourself")) {
            return switch (resident.id()) {
                case 1 -> "I'm Mia. Apprentice magician, collector of odd little things. Mostly here for the people.";
                case 2 -> "Pip. Archer, terrible cook, fairly good listener. I practice east of town.";
                case 3 -> "I'm Juniper. A cleric who spends too much time looking at plants. Quiet suits me.";
                default -> "I'm " + resident.name() + ", a " + resident.role() + ". Henesys is my place to unwind.";
            };
        }
        if (contains(text, "bye", "goodbye", "see you", "later", "good night")) {
            return choose(variant,
                    "See you around, " + playerName + ". Come tell me how it went!",
                    "Take care, " + playerName + ". I'll be around town.",
                    "Until next time, " + playerName + "! Safe travels.");
        }
        if (contains(text, "thank", "thanks")) {
            return "Any time, " + playerName + ". That's what a familiar face is for.";
        }
        if (contains(text, "mushroom", "slime", "snail")) {
            return switch (resident.id()) {
                case 1 -> choose(variant, "Mushrooms! I keep one odd cap from each outing. My bag is a disaster.",
                        "The mushrooms look so innocent until you turn your back. I admire the commitment.");
                case 2 -> choose(variant, "Mushrooms are good practice. Pick a steady spot, then mind the ones behind you.",
                        "I count my missed shots, not just the mushrooms. Less flattering, more useful.");
                case 3 -> choose(variant, "Mushrooms after the rain are lovely. From a sensible distance, at least.",
                        "A snail followed me halfway home once. The most patient traveling companion.");
                default -> "The fields are full of little surprises. Tell me about your next mushroom adventure, " + playerName + ".";
            };
        }
        if (contains(text, "train", "level", "grind", "fight", "hunt")) {
            return switch (resident.id()) {
                case 1 -> "Training goes better with a story afterward. Come tell me what happened, " + playerName + ".";
                case 2 -> "Small goals help. A few steady rounds, then a town break. You'll get there, " + playerName + ".";
                case 3 -> "Take breaks between training. You're allowed to enjoy the walk home, too.";
                default -> "I'm taking my training slowly today. A town break is part of the routine.";
            };
        }
        if (contains(text, "party", "join", "boss", "quest")) {
            return resident.companion()
                    ? "I can't fight or join your party yet, " + playerName + ". I can explore with you, though. Just say 'follow me'."
                    : "I'm keeping to my town routine today, " + playerName + ". Come tell me about your adventure afterward.";
        }
        if (contains(text, "lonely", "friend", "company", "alone", "sad")) {
            return switch (resident.id()) {
                case 1 -> "Then stay a while, " + playerName + ". No quest required. I'm glad you stopped to talk.";
                case 2 -> "You can hang around with me, " + playerName + ". We don't have to fill every silence.";
                case 3 -> "There's room beside me, " + playerName + ". A little company can be enough.";
                default -> "You're welcome to stop and chat with me, " + playerName + ". It's nice having you here.";
            };
        }
        if (contains(text, "weather", "rain", "sun", "cloud")) {
            return switch (resident.id()) {
                case 1 -> "Good weather for wandering, bad weather for staying on schedule. I choose wandering.";
                case 2 -> "A little breeze is fine. Enough to make an archer pay attention.";
                case 3 -> "I like the pause just before rain. The whole town seems to take a breath.";
                default -> "I could spend a whole afternoon watching those clouds from here.";
            };
        }
        if (contains(text, "henesys", "town", "home", "travel", "ellinia", "kerning", "perion", "where")) {
            return switch (resident.id()) {
                case 1 -> "I always end up back in Henesys. It's where the stories cross paths.";
                case 2 -> "Henesys is home. I like knowing which corner my friends will be standing on.";
                case 3 -> "Ellinia has beautiful trees, but I like the small familiar paths here.";
                default -> "I wander sometimes, but Henesys is where I come back to rest.";
            };
        }
        if (contains(text, "how are", "how's", "hows", "doing", "what's up", "whats up")) {
            return switch (resident.id()) {
                case 1 -> "Pretty good! Found an interesting pebble. Pip says every pebble is interesting to me. He's right.";
                case 2 -> "Good. Missed fewer shots today. That's progress, however small. How about you?";
                case 3 -> "Quietly happy. A slow afternoon and someone to share it with.";
                default -> "Doing well, " + playerName + ". " + ambient(resident, variant);
            };
        }
        if (contains(text, "hello", "hey", "hi", "hiya", "morning", "afternoon")) {
            if (before.hasMet()) {
                return "Hey, " + playerName + "! " + (!before.lastTopic().isBlank()
                        ? "How's everything with " + before.lastTopic() + "?"
                        : "Good to have you back. How's your day going?");
            }
            return "Hi, " + playerName + "! I'm " + resident.name() + ". What brings you to Henesys?";
        }
        if (!before.lastTopic().isBlank()) {
            return choose(variant,
                    "I'm still thinking about " + before.lastTopic() + ". It's nice having someone to talk to, " + playerName + ".",
                    resident.companion()
                            ? "Tell me a little more, " + playerName + ". Or we can just enjoy a walk around town."
                            : "Tell me a little more, " + playerName + ". I'm glad you stopped to chat.");
        }
        return choose(variant,
                ambient(resident, variant),
                "How has your day been, " + playerName + "? Training, exploring, or just taking it easy?",
                resident.companion()
                        ? "I'm happy to talk about town, training, or mushrooms. Or say 'follow me' for a walk."
                        : "I'm happy to talk about town, training, or mushrooms. How has your adventure been?");
    }

    public static Intent intent(String message) {
        String text = normalize(message);
        if (contains(text, "stop", "stay", "wait here", "don't follow", "do not follow")) {
            return Intent.STOP_FOLLOWING;
        }
        if (contains(text, "follow", "walk with me", "come with me", "let's walk", "lets walk")) {
            return Intent.FOLLOW;
        }
        return Intent.TALK;
    }

    public String ambient(ResidentProfiles.Profile resident, long sequence) {
        return switch (resident.id()) {
            case 1 -> choose(sequence, "There you are, Pip. Did the mushrooms win again?", "One more lap around town. Then I'll sort my collection.", "I wonder who has a good story today.");
            case 2 -> choose(sequence, "Only the sneaky ones, Mia. Only the sneaky ones.", "A few more arrows, then I'm done for the afternoon.", "There's always time for a break in Henesys.");
            case 3 -> choose(sequence, "The leaves are lovely today. Nobody ever seems to notice.", "I'll take the long way. It's quieter.", "It's nice seeing familiar faces around here.");
            case 4 -> choose(sequence, "I swear these boots used to fit better.", "Maybe I'll visit Perion next. After a rest.");
            case 5 -> choose(sequence, "Nothing that a good cup of tea won't improve.", "I like this corner. You can watch everyone go by.");
            case 6 -> choose(sequence, "A little polish and this old blade will be good as new.", "Always check your equipment before leaving town.");
            case 7 -> choose(sequence, "I wonder if the stars look different over Orbis.", "I'll stay in town until the lights come on.");
            case 8 -> choose(sequence, "That snail is in absolutely no hurry. Fair enough.", "The best paths are the ones with a little shade.");
            case 9 -> choose(sequence, "Remind me to tell you about the ferry sometime.", "Every town has a story. This one has several.");
            case 10 -> choose(sequence, "Wait... was the mushroom field left or right?", "New boots, empty bag. Good enough to start.");
            case 11 -> choose(sequence, "Just browsing today. Famous last words.", "Henesys is always best when the shops get busy.");
            case 12 -> choose(sequence, "Remember to bring a few potions, everyone.", "No hurry. The adventure will still be there tomorrow.");
            case 13 -> choose(sequence, "I found a tiny flower near the path. Left it there.", "Pink or orange? I can never decide.");
            case 14 -> choose(sequence, "That cloud looks a bit like a sleepy Balrog.", "Five more minutes of doing absolutely nothing.");
            case 15 -> choose(sequence, "Bet I can make it to the other end before Tobin.", "I'll scout ahead. Well, as far as the next shop.");
            case 16 -> choose(sequence, "I came back with more mushroom caps than I meant to.", "A town break is a very important part of hunting.");
            case 17 -> choose(sequence, "Everyone started somewhere. Today seems like a good day.", "Found my way back without checking the map!");
            case 18 -> choose(sequence, "Long road today. It's good to be back.", "I'll just rest here a moment.");
            case 19 -> choose(sequence, "An umbrella is useful even when it doesn't rain.", "A little breeze makes the afternoon better.");
            default -> choose(sequence, "Who's up for a friendly race around town?", "Good afternoon, Henesys!", "A little practice, a little company. Good day.");
        };
    }

    private static String topic(String text) {
        if (contains(text, "mushroom", "slime", "snail")) {
            return "mushrooms and the nearby fields";
        }
        if (contains(text, "train", "level", "grind", "fight", "hunt")) {
            return "your training";
        }
        if (contains(text, "party", "boss", "quest")) {
            return "your next adventure";
        }
        if (contains(text, "lonely", "friend", "company", "alone", "sad")) {
            return "finding a little company";
        }
        if (contains(text, "weather", "rain", "sun", "cloud")) {
            return "the weather";
        }
        if (contains(text, "henesys", "town", "home", "travel", "ellinia", "kerning", "perion")) {
            return "your travels";
        }
        return "";
    }

    private static String normalize(String message) {
        if (message == null) {
            return "";
        }
        return message.substring(0, Math.min(message.length(), 240)).toLowerCase(Locale.ROOT).trim();
    }

    private static boolean contains(String text, String... words) {
        for (String word : words) {
            // Allow inflected topic words (training, mushrooms), but never "hi" inside "this".
            String ending = word.length() > 3 && !word.contains(" ") ? "[a-z]*" : "";
            if (java.util.regex.Pattern.compile("(?<![a-z])" + java.util.regex.Pattern.quote(word) + ending + "(?![a-z])")
                    .matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static String choose(long sequence, String... lines) {
        return lines[(int) Math.floorMod(sequence, (long) lines.length)];
    }
}
