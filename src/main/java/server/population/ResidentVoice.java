package server.population;

import java.util.Objects;

/** Authored chat, not simulated player history. Activity topics require the matching live context. */
public final class ResidentVoice {
    public enum Voice { SOCIAL, GRINDER, HELPER, QUIET, PLAYFUL }

    public enum Topic {
        HELLO, RETURN, THANKS, LAUGH, ACK, BRB, BYE, ACTIVITY,
        IDLE, REST, SHOP, TRAVEL, HUNT, LOOT, FOLLOW, WAIT,
        FOLLOW_ACCEPT, FOLLOW_DECLINE, WAIT_ACCEPT, LEVEL_UP, DEATH,
        COMPLAINT, MAP_SPACE, TRADE, PARTY, COMPANY, MOBS, UNKNOWN
    }

    private ResidentVoice() { }

    /** Stable assignments include the eight field hunters; changing activities does not change voice. */
    public static Voice voice(ResidentProfiles.Profile profile) {
        return switch (profile.id()) {
            case 1, 5, 9, 11, 17, 26 -> Voice.SOCIAL;
            case 2, 6, 16, 20, 25, 27 -> Voice.GRINDER;
            case 12, 19, 21 -> Voice.HELPER;
            case 3, 7, 8, 18, 22, 24 -> Voice.QUIET;
            case 4, 10, 13, 14, 15, 23, 28 -> Voice.PLAYFUL;
            default -> Voice.values()[Math.floorMod(profile.id(), Voice.values().length)];
        };
    }

    /** Returns fresh candidate arrays. Selection, memory and cooldowns belong to the dialogue service. */
    public static String[] lines(ResidentProfiles.Profile profile, Topic topic, ResidentDialogue.Context context) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(topic);
        context = context == null ? ResidentDialogue.Context.town() : context;
        if (topic == Topic.ACTIVITY) {
            topic = switch (context.activity()) {
                case IDLE -> Topic.IDLE;
                case RESTING -> Topic.REST;
                case SHOPPING -> Topic.SHOP;
                case ROAMING, EXPLORING -> Topic.TRAVEL;
                case TRAINING -> Topic.HUNT;
                case LOOTING -> Topic.LOOT;
                case FOLLOWING -> Topic.FOLLOW;
                case WAITING -> Topic.WAIT;
            };
        }
        if (topic == Topic.FOLLOW_ACCEPT && !profile.companion()) {
            topic = Topic.FOLLOW_DECLINE;
        }
        if (topic == Topic.IDLE && !context.mapName().equalsIgnoreCase("Henesys")) {
            return phrases(switch (voice(profile)) {
                case SOCIAL -> "just hanging out|idle for a bit|not doing much rn|chilling here";
                case GRINDER -> "idle|not grinding rn|standing around|nothing much";
                case HELPER -> "Just hanging out|Taking a minute here|Idle for a bit|Nothing much";
                case QUIET -> "just here|hanging around|idle for a bit|quiet minute";
                case PLAYFUL -> "standing around again|exp can wait|not doing much F2|hanging here";
            });
        }
        return phrases(switch (voice(profile)) {
            case SOCIAL -> social(topic, profile.companion());
            case GRINDER -> grinder(topic, profile.companion());
            case HELPER -> helper(topic, profile.companion());
            case QUIET -> quiet(topic, profile.companion());
            case PLAYFUL -> playful(topic, profile.companion());
        });
    }

    private static String social(Topic topic, boolean companion) {
        return switch (topic) {
            case HELLO -> "heyy|hey o/|hii|oh hi";
            case RETURN -> "wb!|oh hey again|back already :D|heyy wb";
            case THANKS -> "np :)|yw|ofc|all good";
            case LAUGH -> "lmao|haha yeah|ok that got me|F2";
            case ACK -> "kk|sounds good|yep yep|fair";
            case BRB -> "kk, cya in a bit|oki|kk|yep, later";
            case BYE -> "cya o/|later!|byee|gl with the grind";
            case IDLE -> "hene is the endgame|just hanging out|grinding can wait a bit|town time";
            case REST -> "break timee|chilling for a min|taking a little break|mobs can wait";
            case SHOP -> "just looking, not buying|window shopping rn|the shop menu is dangerous|looking at equips again";
            case TRAVEL -> "on the move|wandering a bit|passing through|walking around again";
            case HUNT -> "grinding a bit rn|mobs first, typing second|back to bonking things|getting some training in";
            case LOOT -> "sec, picking this up|gotta grab the drops|loot first|one sec, floor stuff";
            case FOLLOW -> "yep i'm coming|still with u|following along|right here";
            case WAIT -> "still here :)|yep, waiting|hanging here til you're back|haven't moved";
            case FOLLOW_ACCEPT -> "yea lets go|kk coming!|i'm in, lead the way|ok let's move";
            case FOLLOW_DECLINE -> "gonna do my own thing rn|not tagging along today, sry|i'm staying around here";
            case WAIT_ACCEPT -> "kk i'll stay here|yep, stopping here|ok i'll wait";
            case LEVEL_UP -> "ayy grats!|gz!!|grats :D|nicee, gz";
            case DEATH -> "nooo rip|oof :(|ahh rip|that hurts";
            case COMPLAINT -> "pots eat meso so fast|the grind is a grind|ladders are annoying|cc pls gets old fast";
            case MAP_SPACE -> "oh kk, i'll chill|sure, go ahead|kk i'll stop a bit";
            case TRADE -> "can't trade, sry|no trades from me|nothing for sale here";
            case PARTY -> companion ? "can't join party, can still tag along tho|no party slot for me, sry"
                    : "can't join party, sry|not available for pq";
            case COMPANY -> "hang out here :)|yea stay a bit|more people around is always good";
            case MOBS -> "mushrooms look way too innocent|snails just minding their business|slimes have no respect for personal space";
            case UNKNOWN -> "huh?|wait what|lost me there|hm?";
            case ACTIVITY -> throw new IllegalArgumentException("Resolve activity first");
        };
    }

    private static String grinder(Topic topic, boolean companion) {
        return switch (topic) {
            case HELLO -> "yo|sup|hey|hi";
            case RETURN -> "wb|yo again|back|hey again";
            case THANKS -> "np|yw|sure|all good";
            case LAUGH -> "lol|heh|fair|lmao ok";
            case ACK -> "k|kk|yep|works";
            case BRB -> "k|kk|aight|cya in a bit";
            case BYE -> "later|cya|gl|peace";
            case IDLE -> "not grinding rn|town break|idle a bit|hene again";
            case REST -> "break|resting a sec|taking 5|not grinding for a bit";
            case SHOP -> "just checking equips|browsing|not buying|looking through the shop";
            case TRAVEL -> "moving|passing through|walking over|on the way";
            case HUNT -> "grinding|training rn|killing mobs|exp time";
            case LOOT -> "looting|sec, drops|picking up|grab these first";
            case FOLLOW -> "coming|with you|still here|following";
            case WAIT -> "waiting|here|still waiting|staying put";
            case FOLLOW_ACCEPT -> "k let's go|coming|lead|kk moving";
            case FOLLOW_DECLINE -> "solo rn|doing my own thing|not coming, sry";
            case WAIT_ACCEPT -> "k, waiting|kk staying here|stopping";
            case LEVEL_UP -> "gz|grats|nice lvl|gz, nice";
            case DEATH -> "rip|oof|rough|ouch";
            case COMPLAINT -> "pots cost too much|ladders waste time|meso goes straight into pots|exp bar is stubborn";
            case MAP_SPACE -> "k, i'll pause|kk go ahead|i'll stop hitting";
            case TRADE -> "can't trade|no trades|not selling anything";
            case PARTY -> companion ? "can't join party. can still help|no party slot, sry"
                    : "can't join party|no pq for me";
            case COMPANY -> "yeah hang here|all good|fine by me";
            case MOBS -> "mushrooms everywhere|slimes are annoying|snails are easy enough";
            case UNKNOWN -> "huh|wdym|what|didn't catch that";
            case ACTIVITY -> throw new IllegalArgumentException("Resolve activity first");
        };
    }

    private static String helper(Topic topic, boolean companion) {
        return switch (topic) {
            case HELLO -> "Hey|Hi there|Heya|Hello";
            case RETURN -> "Welcome back|Hey again|Wb!|Good to see you";
            case THANKS -> "Np!|Anytime|You're welcome|No problem";
            case LAUGH -> "Haha|Fair enough|That was good|Heh, yep";
            case ACK -> "Yep|Sounds good|Alright|Got it";
            case BRB -> "Sure, see you in a bit|Alright|Yep, catch you in a bit|Okay";
            case BYE -> "Take care|Cya!|Good luck out there|See you around";
            case IDLE -> "Just hanging out|Town break for me|A little Hene downtime|Taking a minute here";
            case REST -> "Break time|Resting for a bit|Resting a minute|Taking a breather";
            case SHOP -> "Just browsing the shop|Looking at equips|Window shopping|Not buying anything yet";
            case TRAVEL -> "Passing through|Heading along|Stretching my legs|Walking for a bit";
            case HUNT -> "Getting some training in|Busy with the mobs|Grinding for a bit|Back to training";
            case LOOT -> "Just collecting these|One sec, picking up|Grabbing the drops|Looting first";
            case FOLLOW -> "I'm with you|Coming along|Still here|Following along";
            case WAIT -> "Still here|Waiting here|Yep, staying put|Right where you left me";
            case FOLLOW_ACCEPT -> "Sure, let's go|Yep, coming|Alright, lead the way|I'll come along";
            case FOLLOW_DECLINE -> "Doing my own thing today, sorry|I'll stay around here|Not tagging along today";
            case WAIT_ACCEPT -> "Sure, I'll wait here|Alright, stopping here|Yep, staying put";
            case LEVEL_UP -> "Grats!|Gz!|Nice level, grats|Well earned!";
            case DEATH -> "Ah, rip|Ouch, sorry|That's rough|Unlucky";
            case COMPLAINT -> "The potion bill adds up|Ladders take some practice|That exp bar takes patience|Pots are never free";
            case MAP_SPACE -> "Sure, I'll stop for a bit|Go ahead. I'll wait|Yep, taking a break";
            case TRADE -> "Can't trade, sorry|No items for sale from me|I can't trade items or meso";
            case PARTY -> companion ? "Can't join the party, but I can tag along|No party slot for me, sorry"
                    : "I can't join a party, sorry|Not available for a PQ";
            case COMPANY -> "You're welcome to hang out|Glad for the company|There's room to hang out here";
            case MOBS -> "Mushrooms are everywhere|Slimes are annoying up close|Snails are a decent start";
            case UNKNOWN -> "Not sure what you mean|Didn't catch that|Hm?|Sorry, what?";
            case ACTIVITY -> throw new IllegalArgumentException("Resolve activity first");
        };
    }

    private static String quiet(Topic topic, boolean companion) {
        return switch (topic) {
            case HELLO -> "hi|hey|hello|oh, hi";
            case RETURN -> "wb|hey again|oh hey|welcome back";
            case THANKS -> "np|sure|you're welcome|it's fine";
            case LAUGH -> "heh|haha|:)|fair";
            case ACK -> "okay|mhm|yep|sure";
            case BRB -> "okay|mhm|see you in a bit|sure";
            case BYE -> "bye|cya|take care|later";
            case IDLE -> "just here|hanging around|idle for a bit|quiet minute";
            case REST -> "resting|a little break|stopping for a bit|just a minute";
            case SHOP -> "just looking|browsing the shop|looking at equips|not buying";
            case TRAVEL -> "walking|passing through|wandering|moving along";
            case HUNT -> "training a bit|grinding|mobs for now|still training";
            case LOOT -> "picking these up|one sec|looting|the drops first";
            case FOLLOW -> "coming|still here|with you|following";
            case WAIT -> "here|still waiting|staying here|haven't moved";
            case FOLLOW_ACCEPT -> "sure. coming|okay, let's go|i'll come|mhm, lead the way";
            case FOLLOW_DECLINE -> "staying around here|not coming today|doing my own thing";
            case WAIT_ACCEPT -> "okay. i'll wait|staying here|sure, stopping";
            case LEVEL_UP -> "grats|gz|nice, grats|grats :)";
            case DEATH -> "ah, rip|oh no|ouch|sorry";
            case COMPLAINT -> "pots add up|ladders are awkward|long grind|stubborn exp bar";
            case MAP_SPACE -> "ok. i'll wait|sure, i'll stop|stopping for a bit";
            case TRADE -> "can't trade, sorry|no trades|nothing for sale";
            case PARTY -> companion ? "can't join party. i can tag along|no party slot for me"
                    : "can't join party|not doing pq";
            case COMPANY -> "you can stay|company is nice|fine with me";
            case MOBS -> "snails are alright|mushrooms everywhere|slimes bounce a lot";
            case UNKNOWN -> "hm?|didn't follow|sorry, what?|not sure";
            case ACTIVITY -> throw new IllegalArgumentException("Resolve activity first");
        };
    }

    private static String playful(Topic topic, boolean companion) {
        return switch (topic) {
            case HELLO -> "heyo|yo o/|oh hi|hiya";
            case RETURN -> "wb wb|oh hey, round two|look who's back|wb :D";
            case THANKS -> "np np|yw :D|ez|got u";
            case LAUGH -> "LOL|F2|ok that's good|haha rip";
            case ACK -> "kk|deal|aight|works for me";
            case BRB -> "kk don't get eaten|aight cya in a bit|kk|roger that";
            case BYE -> "cyaaa|peace o/|later, gl|don't let the snails win";
            case IDLE -> "hene > grinding|hanging around again|town time again|exp can wait";
            case REST -> "break time F2|not bonking anything rn|taking a min|mobs get a break";
            case SHOP -> "just looking F2|window shopping|those equips tho|not spending anything rn";
            case TRAVEL -> "off we go|walking around again|just passing thru|field trip";
            case HUNT -> "bonking mobs|grinding rn|mobs mobs mobs|back to training";
            case LOOT -> "sec, loot|picking stuff up|grabbing these|can't leave drops behind";
            case FOLLOW -> "yep still here|tagging along|coming coming|following u";
            case WAIT -> "still parked|yep i'm here|haven't moved F2|waiting";
            case FOLLOW_ACCEPT -> "aight adventure time|kk let's move|coming, lead on|ok, field trip";
            case FOLLOW_DECLINE -> "not coming rn, sry|gonna do my own thing|gonna stay around here";
            case WAIT_ACCEPT -> "kk parking here|ok i'll stay|stopping right here";
            case LEVEL_UP -> "ayy gz!|F2 grats|gz gz|nice lvl :D";
            case DEATH -> "F4|rippp|the mobs win this round|oh nooo";
            case COMPLAINT -> "ladders hate me|pots eat all the meso|jump quests are pain|mobs never respect the chat box";
            case MAP_SPACE -> "kk truce with the mobs|got it, taking a break|ok ok, hands off the mobs";
            case TRADE -> "can't trade, sry|no trades from me|nothing for sale F4";
            case PARTY -> companion ? "can't join party, can still come tho|no party slot for me, sry"
                    : "can't join party, sry|no pq for me";
            case COMPANY -> "yea hang here|could use more people around|stay a bit F2";
            case MOBS -> "mushrooms have no chill|snails taking their time|slimes bounce too much";
            case UNKNOWN -> "wait what|u lost me|translation pls|huh :D";
            case ACTIVITY -> throw new IllegalArgumentException("Resolve activity first");
        };
    }

    private enum Subject { TOWN, MUSIC, REST, SHOP, POTS, MOBS, TRAVEL }

    /** Both sides keep their own voice; answers express opinions, never invent the second actor's activity. */
    public static ResidentDialogue.Exchange exchange(ResidentProfiles.Profile first, ResidentProfiles.Profile second,
                                                       ResidentDialogue.Context context, long sequence) {
        context = context == null ? ResidentDialogue.Context.town() : context;
        Subject[] subjects = switch (context.activity()) {
            case TRAINING, LOOTING -> new Subject[]{Subject.POTS, Subject.MOBS};
            case SHOPPING -> new Subject[]{Subject.SHOP, Subject.POTS};
            case RESTING, WAITING -> new Subject[]{Subject.REST, Subject.MUSIC};
            case EXPLORING, ROAMING, FOLLOWING -> new Subject[]{Subject.TRAVEL, Subject.MUSIC};
            case IDLE -> context.mapName().equalsIgnoreCase("Henesys")
                    ? new Subject[]{Subject.TOWN, Subject.MUSIC, Subject.REST}
                    : new Subject[]{Subject.MUSIC, Subject.REST};
        };
        Subject subject = subjects[(int) Math.floorMod(sequence + first.id() + second.id(), (long) subjects.length)];
        return new ResidentDialogue.Exchange(exchangeLine(voice(first), subject, false),
                exchangeLine(voice(second), subject, true));
    }

    private static String exchangeLine(Voice voice, Subject subject, boolean answer) {
        String pair = switch (subject) {
            case TOWN -> switch (voice) {
                case SOCIAL -> "hene time counts as playing btw|obviously :)";
                case GRINDER -> "town time isn't exp time|exp bar disagrees";
                case HELPER -> "Hene is half the game|That's part of Maple too";
                case QUIET -> "i like just hanging here|fine by me";
                case PLAYFUL -> "hene is the main game F2|exp can wait lol";
            };
            case MUSIC -> switch (voice) {
                case SOCIAL -> "map music makes the grind better|yea the music helps";
                case GRINDER -> "music's decent here|better than silence";
                case HELPER -> "The map music holds up|Yep, good Maple music";
                case QUIET -> "i like the music here|same. it's nice";
                case PLAYFUL -> "map music carrying the experience|the real party buff";
            };
            case REST -> switch (voice) {
                case SOCIAL -> "grinding is optional for a minute|breaks are important :)";
                case GRINDER -> "break from the grind|fair. need those";
                case HELPER -> "A break does some good|Yep, mobs aren't going anywhere";
                case QUIET -> "good to stop a bit|mhm";
                case PLAYFUL -> "standing around is underrated|yea, break time";
            };
            case SHOP -> switch (voice) {
                case SOCIAL -> "window shopping is free at least|the best price tbh";
                case GRINDER -> "looking is cheaper than buying|meso saved";
                case HELPER -> "Nothing wrong with just browsing|Always nice to look at equips";
                case QUIET -> "just looking costs nothing|true";
                case PLAYFUL -> "looking is free F2|best price";
            };
            case POTS -> switch (voice) {
                case SOCIAL -> "pots are the real meso sink|meso really does vanish";
                case GRINDER -> "pots cost too much|always the pots";
                case HELPER -> "The potion bill adds up|It does. Every little bit";
                case QUIET -> "pots aren't cheap|yeah";
                case PLAYFUL -> "all roads lead to buying pots|pots win again";
            };
            case MOBS -> switch (voice) {
                case SOCIAL -> "mobs do not respect a typing break|literally no manners";
                case GRINDER -> "mobs don't wait for chat|type faster lol";
                case HELPER -> "Typing near mobs is risky|Yep, they keep moving";
                case QUIET -> "mobs aren't very patient|they never are";
                case PLAYFUL -> "chat box open, mobs say hi|they know somehow";
            };
            case TRAVEL -> switch (voice) {
                case SOCIAL -> "so much of maple is just getting places|and getting distracted";
                case GRINDER -> "lots of walking in this game|part of the grind";
                case HELPER -> "Maple has a lot of walking|Plenty of maps to get through";
                case QUIET -> "a lot of walking|and ladders";
                case PLAYFUL -> "walking is most of maple|jumping is the other half";
            };
        };
        return phrases(pair)[answer ? 1 : 0];
    }

    private static String[] phrases(String alternatives) {
        return alternatives.split("\\|", -1);
    }
}
