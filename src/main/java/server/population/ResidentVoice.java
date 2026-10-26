package server.population;

import java.util.Objects;

/** Authored chat, not simulated player history. Activity topics require the matching live context. */
public final class ResidentVoice {
    public enum Voice { SOCIAL, GRINDER, HELPER, QUIET, PLAYFUL }

    public enum Topic {
        HELLO, RETURN, THANKS, LAUGH, ACK, BRB, BYE, ACTIVITY,
        IDLE, REST, SHOP, TRAVEL, HUNT, LOOT, FOLLOW, WAIT,
        FOLLOW_ACCEPT, FOLLOW_DECLINE, WAIT_ACCEPT, LEVEL_UP, DEATH,
        BANTER, COMPLAINT, BAD_DROPS, LAG, SELF_DEPRECATION, MAP_SPACE, TRADE, PARTY, COMPANY, MOBS, UNKNOWN
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
                case SOCIAL -> "grinding can kiss my ass rn|idle, judging outfits|not doing much rn|chilling instead of suffering";
                case GRINDER -> "idle. cope|not grinding rn|break from this shit|standing around";
                case HELPER -> "Taking a break from expert opinions|The exp can wait. Somehow.|Idle. Truly advanced gameplay.|Nothing much";
                case QUIET -> "quietly judging builds|no exp, no problem|standing here. very advanced.|not grinding. tragic.";
                case PLAYFUL -> "professional shit talker rn|exp can go to hell|doing jack shit|hanging here, causing no problems";
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
            case HELLO -> "yo|heyy|oh hey|sup";
            case RETURN -> "wb|oh damn, back already|there u are|back for more bullshit?";
            case THANKS -> "np|yw, dont make it weird|yea yea|got u";
            case LAUGH -> "lmao|nah thats foul lol|ok that got me|LOL damn";
            case ACK -> "kk|sounds good|yep yep|fair";
            case BRB -> "kk, cya in a bit|oki|kk|yep, later";
            case BYE -> "cya o/|later!|byee|gl with the grind";
            case IDLE -> "hene: less exp, more shit talk|grinding can kiss my ass rn|just here judging outfits|hene chat is the real pvp";
            case REST -> "taking a break from this shit|mobs can wait|chilling for a min|not grinding every damn second";
            case SHOP -> "looking, not buying this crap|window shopping. tragic stuff|some equips look like a dare|just checking the shop";
            case TRAVEL -> "walking around like an idiot|on the move|so much damn walking|just passing through";
            case HUNT -> "this grind is some bullshit|mobs first, shit talk second|grinding rn|training. allegedly fun";
            case LOOT -> "sec, grabbing this crap|loot first|picking stuff up|hold on, floor junk";
            case FOLLOW -> "yep i'm coming|still with u|following along|right here";
            case WAIT -> "still here. thrilling stuff|yep, waiting|havent moved|standing around like a pro";
            case FOLLOW_ACCEPT -> "yea lets go|kk coming!|i'm in, lead the way|ok let's move";
            case FOLLOW_DECLINE -> "gonna do my own thing rn|not tagging along today, sry|i'm staying around here";
            case WAIT_ACCEPT -> "kk i'll stay here|yep, stopping here|ok i'll wait";
            case LEVEL_UP -> "ayy gz!|damn, grats|gz, now do it again lol|nicee gz";
            case DEATH -> "oh shit rip|damn that sucks|F4|rip, maple is rude as hell";
            case COMPLAINT -> "this grind is bullshit sometimes|pots eat meso like its their job|the exp bar has zero respect|maple really makes u work for crumbs|all this grind and ladders too. rude|cc pls is not a personality";
            case MAP_SPACE -> "oh kk, i'll chill|sure, go ahead|kk i'll stop a bit";
            case TRADE -> "can't trade, sry|no trades from me|nothing for sale here";
            case PARTY -> companion ? "can't join party, can still tag along tho|no party slot for me, sry"
                    : "can't join party, sry|not available for pq";
            case COMPANY -> "hang here, judge builds with me|yea stay, its boring as hell alone|could use someone else to talk shit with";
            case MOBS -> "mushrooms look innocent. little shits|slimes have zero damn manners|snails got the right idea, barely moving";
            case UNKNOWN -> "wait what|the hell do u mean|u lost me|huh?";
            case BANTER -> "lmao ok pro|kk mister pro|no u|cry more|talk shit, at least make it funny|u type like a failed scroll|did a snail write that for u|ur not that guy";
            case BAD_DROPS -> "rng can kiss my ass|drop hunting is such bullshit|the drop table has jokes|rare drops exist to piss people off";
            case LAG -> "lag is the actual damn boss|lag makes everything feel like shit|rip, even the packets hate grinding|lag has zero respect for pots";
            case SELF_DEPRECATION -> "nah ur learning. shit takes a bit|everyone looks dumb on a ladder|being bad is the tutorial|ur fine, maple is just rude";
            case ACTIVITY -> throw new IllegalArgumentException("Resolve activity first");
        };
    }

    private static String grinder(Topic topic, boolean companion) {
        return switch (topic) {
            case HELLO -> "yo|sup|hey|hi";
            case RETURN -> "wb|back to it|yo again|round two huh";
            case THANKS -> "np|yw|sure|all good";
            case LAUGH -> "lol|lmao damn|thats rough lol|heh";
            case ACK -> "k|kk|yep|works";
            case BRB -> "k|kk|aight|cya in a bit";
            case BYE -> "later|cya|gl|peace";
            case IDLE -> "not grinding rn. shocking|hene is an exp sink|town chat is harder than mobs|idle. cope";
            case REST -> "break from this shit|taking 5|exp can wait|not sweating rn";
            case SHOP -> "just looking. prices are ass|checking equips|not buying this shit|browsing";
            case TRAVEL -> "moving|too much damn walking|passing through|walking simulator";
            case HUNT -> "grinding|this exp bar is a bastard|back to this shit|mobs dont kill themselves";
            case LOOT -> "loot first|grabbing this crap|sec, drops|picking up";
            case FOLLOW -> "coming|with you|still here|following";
            case WAIT -> "waiting|still here. zero excitement|staying put|yep, parked";
            case FOLLOW_ACCEPT -> "k let's go|coming|lead|kk moving";
            case FOLLOW_DECLINE -> "solo rn|doing my own thing|not coming, sry";
            case WAIT_ACCEPT -> "k, waiting|kk staying here|stopping";
            case LEVEL_UP -> "gz|grats. back to work|damn, nice|gz, keep grinding";
            case DEATH -> "rip|shit happens|damn|oof, pot earlier";
            case COMPLAINT -> "grind is grind. still bullshit|pots cost too damn much|exp bar barely gives a shit|ladders waste grinding time|meso goes straight into pots|the grind is an ugly habit";
            case MAP_SPACE -> "k, i'll pause|kk go ahead|i'll stop hitting";
            case TRADE -> "can't trade|no trades|not selling anything";
            case PARTY -> companion ? "can't join party. can still help|no party slot, sry"
                    : "can't join party|no pq for me";
            case COMPANY -> "yeah hang here|fine, less boring|just dont spam pq invites";
            case MOBS -> "mushrooms are annoying little shits|slimes are a pain in the ass|snails got no urgency";
            case UNKNOWN -> "what|wdym|the hell|say that again";
            case BANTER -> "mad cuz bad|less typing, more damage|shut up and hit something|kk pro|cool story|ur not that guy|talk shit after the grind|that insult needs accuracy";
            case BAD_DROPS -> "drop hunting is ass|rng doesnt give a shit|rare means annoying as hell|the drop table is a bastard";
            case LAG -> "lag kills the grind|damn lag|cant grind through packets|lag is bullshit. always";
            case SELF_DEPRECATION -> "practice. nobody starts good|slow down a sec|pots first, ego later|learn the mobs. takes time";
            case ACTIVITY -> throw new IllegalArgumentException("Resolve activity first");
        };
    }

    private static String helper(Topic topic, boolean companion) {
        return switch (topic) {
            case HELLO -> "Hey|Yo|Hey there|Sup";
            case RETURN -> "Wb|Back for more punishment?|Hey again|Ah, you survived Maple chat";
            case THANKS -> "Np|Anytime. Dont make it a thing|Yep, you got it|No problem";
            case LAUGH -> "Damn lol|Fair enough|Okay, that was good|Heh. Brutal";
            case ACK -> "Yep|Sounds good|Alright|Got it";
            case BRB -> "Sure, see you in a bit|Alright|Yep, catch you in a bit|Okay";
            case BYE -> "Take care|Cya!|Good luck out there|See you around";
            case IDLE -> "Hene. Where everyones a build expert.|Taking a break from expert opinions|Town chat needs accuracy scrolls|The exp can wait. Somehow.";
            case REST -> "Resting. Try it sometime.|Break from the damn grind|Even tryhards need breaks|Not grinding is also an option";
            case SHOP -> "Looking. Buying sense isnt an option.|Some equips are an expensive mistake|Just browsing the shop|At least looking doesnt cost meso";
            case TRAVEL -> "Walking. Very advanced gameplay.|Heading along|Maple does love its damn ladders|Passing through";
            case HUNT -> "Grinding. Riveting stuff.|Back to the damn mobs|Training, before chat gets any worse|The mobs are less annoying than chat";
            case LOOT -> "Picking this stuff up|Loot first, opinions later|One sec, drops|Grabbing the floor junk";
            case FOLLOW -> "I'm with you|Coming along|Still here|Following along";
            case WAIT -> "Still here. Exciting, I know.|Waiting here|Yep, staying put|This is some advanced standing around";
            case FOLLOW_ACCEPT -> "Sure, let's go|Yep, coming|Alright, lead the way|I'll come along";
            case FOLLOW_DECLINE -> "Doing my own thing today, sorry|I'll stay around here|Not tagging along today";
            case WAIT_ACCEPT -> "Sure, I'll wait here|Alright, stopping here|Yep, staying put";
            case LEVEL_UP -> "Gz. Dont spend the SP all wrong.|Grats!|Damn, nice level|Gz, now the bar is empty again";
            case DEATH -> "Ah shit, rip|Pots exist for a reason. Rip.|Damn, rough one|Rip. Take a second.";
            case COMPLAINT -> "The grind is a pain in the ass|Pots are the only reliable meso sink|Maple rewards patience. Allegedly.|The exp bar has no damn sympathy|The ladder is always the real boss|Everyone has build advice. God help us.";
            case MAP_SPACE -> "Sure, I'll stop for a bit|Go ahead. I'll wait|Yep, taking a break";
            case TRADE -> "Can't trade, sorry|No items for sale from me|I can't trade items or meso";
            case PARTY -> companion ? "Can't join the party, but I can tag along|No party slot for me, sorry"
                    : "I can't join a party, sorry|Not available for a PQ";
            case COMPANY -> "Stay. Someone has to judge the builds.|You can hang out. No resume needed.|Company beats another grind lecture.";
            case MOBS -> "Mushrooms have better patience than chat|Slimes are annoying little bastards|Snails make a strong case for taking breaks";
            case UNKNOWN -> "What are you on about?|Lost me there|Come again?|That needs a translation";
            case BANTER -> "Put a skill point into that comeback.|Damn. Almost an original insult.|Try accuracy before attitude.|All that confidence in a chat box.|Good talk. Truly advanced stuff.|Would scrolls fix that attitude?|Im sure that sounded clever to you.|Thats a beginner-tier insult.";
            case BAD_DROPS -> "RNG has never given a damn|Drop hunting is patience, then swearing|Rare drops are a cruel joke|The drop table isnt your friend";
            case LAG -> "Lag. The one boss gear cant fix.|Lag makes a mess of everything|Hard to play when packets dont|Damn, lag never helps";
            case SELF_DEPRECATION -> "Good. Now you know what to practice.|Slow down. Dying faster isnt progress.|Use the pots. Saving them for retirement?|Being new isnt a damn crime.";
            case ACTIVITY -> throw new IllegalArgumentException("Resolve activity first");
        };
    }

    private static String quiet(Topic topic, boolean companion) {
        return switch (topic) {
            case HELLO -> "hi|hey|hello|oh, hi";
            case RETURN -> "wb|back for more|oh hey|still playing this, huh";
            case THANKS -> "np|sure|its fine|dont mention it";
            case LAUGH -> "heh|thats unfortunate lol|fair|quietly laughing";
            case ACK -> "okay|mhm|yep|sure";
            case BRB -> "okay|mhm|see you in a bit|sure";
            case BYE -> "bye|cya|take care|later";
            case IDLE -> "quietly judging builds|no exp, no problem|standing here. very advanced.|not grinding. tragic.";
            case REST -> "the grind can wait|resting|enough of that for a bit|doing nothing. efficiently.";
            case SHOP -> "looking. not impressed.|just browsing|not buying|gear doesnt fix everything";
            case TRAVEL -> "walking|more ladders. wonderful.|passing through|very scenic. very slow.";
            case HUNT -> "still grinding|thrilling stuff|mobs. again.|the exp bar remains unimpressed";
            case LOOT -> "picking this up|floor clutter|looting|sec. junk.";
            case FOLLOW -> "coming|still here|with you|following";
            case WAIT -> "still here|riveting|waiting|hasnt become exciting yet";
            case FOLLOW_ACCEPT -> "sure. coming|okay, let's go|i'll come|mhm, lead the way";
            case FOLLOW_DECLINE -> "staying around here|not coming today|doing my own thing";
            case WAIT_ACCEPT -> "okay. i'll wait|staying here|sure, stopping";
            case LEVEL_UP -> "gz|grats. another bar awaits.|nice, gz|grats. the grind continues.";
            case DEATH -> "rip|ah, shit|unfortunate|maple remains polite";
            case COMPLAINT -> "the exp bar is a brick wall|pots add up. shockingly.|long grind. questionable reward.|maple respects nobody's time|damn ladders|another grind. wonderful.";
            case MAP_SPACE -> "ok. i'll wait|sure, i'll stop|stopping for a bit";
            case TRADE -> "can't trade, sorry|no trades|nothing for sale";
            case PARTY -> companion ? "can't join party. i can tag along|no party slot for me"
                    : "can't join party|not doing pq";
            case COMPANY -> "you can stay. just dont yell.|company is fine|beats arguing with the exp bar";
            case MOBS -> "snails seem less annoying than chat|mushrooms everywhere. wonderful.|slimes have too much energy";
            case UNKNOWN -> "hm?|what does that mean|lost me|not following";
            case BANTER -> "noted. ignored.|ok, anyway|that sounded better in ur head|u done?|incredible insight|quite a speech for a mushroom game|ill survive the review|loud doesnt mean right";
            case BAD_DROPS -> "rng remains a bastard|the drop table has no manners|rare drops. very rare patience.|drop hunting is miserable";
            case LAG -> "lag. lovely.|even the packets need a break|hard to play through that shit|lag adds nothing of value";
            case SELF_DEPRECATION -> "bad for now. thats fixable.|everyone starts somewhere|less panic helps|welcome to maple";
            case ACTIVITY -> throw new IllegalArgumentException("Resolve activity first");
        };
    }

    private static String playful(Topic topic, boolean companion) {
        return switch (topic) {
            case HELLO -> "yo|oi|heyo|sup nerd";
            case RETURN -> "wb nerd|back for more bullshit|oh hey, round two|there u are, nerd";
            case THANKS -> "np nerd|yw|got u|dont make me be nice again";
            case LAUGH -> "LMFAO|nah thats fucked lol|LOL|F2 thats brutal";
            case ACK -> "kk|deal|aight|works for me";
            case BRB -> "kk don't get eaten|aight cya in a bit|kk|roger that";
            case BYE -> "cyaaa|peace o/|later, gl|don't let the snails win";
            case IDLE -> "hene is just pvp with typing|grinding? in this economy?|professional shit talker rn|hene builds character. badly.";
            case REST -> "resting like a damn champion|exp can go to hell for a minute|doing absolutely jack shit|mobs get a break from my greatness";
            case SHOP -> "just looking at expensive mistakes|window shopping like a pro|some gear is ugly as shit|not buying this crap";
            case TRAVEL -> "off we go, allegedly|so much damn walking|walking. peak gameplay.|just passing thru";
            case HUNT -> "back to this bullshit|mushroom violence. very educational.|grinding like an idiot|mobs mobs mobs, jesus";
            case LOOT -> "grabbing the floor crap|loot first, trash talk later|sec, loot|cleaning up this damn floor";
            case FOLLOW -> "yep still here|tagging along|coming coming|following u";
            case WAIT -> "still parked, thrilling shit|havent escaped|waiting like a damn npc|yep, staying put";
            case FOLLOW_ACCEPT -> "aight lets cause problems|kk coming, dont make it weird|lead on, brave mushroom warrior|ok, field trip";
            case FOLLOW_DECLINE -> "not coming rn, sry|gonna do my own thing|gonna stay around here";
            case WAIT_ACCEPT -> "kk parking here|ok i'll stay|stopping right here";
            case LEVEL_UP -> "gz nerd|holy shit gz|nice lvl, dont get smug|gz, now suffer another bar";
            case DEATH -> "LMFAO rip|oh shit F4|the mobs filed a complaint|rip, that game is rude as hell";
            case COMPLAINT -> "this grind is ass|pots are a damn money pit|jump quests are psychological warfare|the exp bar can go to hell|all these ladders and no dignity|cc pls, but for the entire grind";
            case MAP_SPACE -> "kk truce with the mobs|got it, taking a break|ok ok, hands off the mobs";
            case TRADE -> "can't trade, sry|no trades from me|nothing for sale F4";
            case PARTY -> companion ? "can't join party, can still come tho|no party slot for me, sry"
                    : "can't join party, sry|no pq for me";
            case COMPANY -> "stay, we can talk shit together|welcome to the bad-build support group|yea hang here, its boring as hell";
            case MOBS -> "mushrooms are smug little shits|snails are smug little bastards|slimes bounce like they pay rent";
            case UNKNOWN -> "what the hell|translation pls|u lost me|say that in maple terms";
            case BANTER -> "no u|F3|mad cuz bad|lmao ok pro|u type like a failed scroll|noob? damn, my secret is out|talk shit, at least spell it right|cry more";
            case BAD_DROPS -> "rng is a petty little shit|drop hunting is a scam with mushrooms|the drop table can kiss my ass|rare drops, common disappointment";
            case LAG -> "lag makes a shitshow out of maple|even the packets said screw this|lag is the real final boss|hard to play a damn slideshow";
            case SELF_DEPRECATION -> "skill issue. temporary one tho|congrats u unlocked the learning part|everyone fights the controls at first|pots exist for a damn reason";
            case ACTIVITY -> throw new IllegalArgumentException("Resolve activity first");
        };
    }

    private enum Subject { TOWN, MUSIC, REST, SHOP, POTS, MOBS, TRAVEL }

    /** Named, contextual exchanges; insults are opinions or hypotheticals, not invented combat events. */
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
        long seed = sequence + first.id() + second.id();
        Subject subject = subjects[(int) Math.floorMod(seed, (long) subjects.length)];
        int variant = (int) Math.floorMod(Math.floorDiv(seed, subjects.length), 2L);
        return new ResidentDialogue.Exchange(
                second.name() + ", " + exchangeLine(voice(first), subject, false, variant),
                first.name() + ", " + exchangeLine(voice(second), subject, true, variant));
    }

    private static String exchangeLine(Voice voice, Subject subject, boolean answer, int variant) {
        // Each row contains opener/answer for two related beats. Either voice can answer the other.
        String pairs = switch (subject) {
            case TOWN -> switch (voice) {
                case SOCIAL -> "u get exp for talking this much shit|look whos talking lmao|u would afk thru an event|u would spend it arguing in hene";
                case GRINDER -> "all that typing, zero grinding|ur here too genius|go grind something besides chat|u first, hene warrior";
                case HELPER -> "Does that chat box give you exp?|Bold talk from another town statue.|You could teach advanced standing around.|And you could fail the entrance exam.";
                case QUIET -> "u make standing still sound difficult|u make silence sound appealing|u would write a guide to afking|u would need to read it";
                case PLAYFUL -> "u main the damn chat box|kk mister pro|hene isnt gonna give u exp for yapping|mad cuz bad F3";
            };
            case MUSIC -> switch (voice) {
                case SOCIAL -> "map music beats listening to u|turn it up then lol|u got beef with every note or what|only the ones coming from u";
                case GRINDER -> "music on. ur advice off.|good, stop talking then|even the music wants u muted|mute button is free";
                case HELPER -> "The music has more range than your insults.|Then stop talking over it.|Try listening instead of giving advice.|A fine idea. You first.";
                case QUIET -> "the music is better than this conversation|agreed. stop contributing.|ur opinions need a volume slider|so does ur chat box";
                case PLAYFUL -> "even the map music wants u muted|cry more, turn it up|u sound like a broken portal noise|still better than ur damn advice";
            };
            case REST -> switch (voice) {
                case SOCIAL -> "taking a break from being a tryhard huh|taking a break from ur shit actually|ur really sweating the standing still|dont be jealous of my technique";
                case GRINDER -> "professional afker over here|coming from u lol|u train ur chat box harder than mobs|less typing then, pro";
                case HELPER -> "Resting or preparing another bad take?|Mostly avoiding yours.|You make doing nothing look exhausting.|Your advice is harder work.";
                case QUIET -> "u look very busy doing nothing|and u look very invested|try not to overdo the standing around|ill recover from ur concern";
                case PLAYFUL -> "damn, competitive afking|cant handle my build|u need a guide to taking a break|no u F3";
            };
            case SHOP -> switch (voice) {
                case SOCIAL -> "no amount of gear will fix that mouth|look whos talking|u would buy a scroll for ur ego|u need one for ur jokes";
                case GRINDER -> "cant buy aim, pro|cant buy brains either|buy less crap, hit more mobs|shop police is here lol";
                case HELPER -> "Sadly, the shop doesnt sell good judgment.|Perhaps theyll stock some for you.|No equip fixes a bad attitude.|Let me know if you find one.";
                case QUIET -> "window shopping for a personality|they were out of yours|expensive taste. questionable opinions.|free opinions. still overpriced.";
                case PLAYFUL -> "u cant scroll that mouth into being funny|damn, then ur screwed too|u would pay meso for an ego buff|u got the free trial F3";
            };
            case POTS -> switch (voice) {
                case SOCIAL -> "u would blame the pot shop for ur build|u would blame the damn weather|u could fund pots by charging for bad takes|ur the biggest supplier tho";
                case GRINDER -> "save the meso. cant buy skill.|cant buy a mute button for u either|u would complain if pots were free|u would find a way to waste em";
                case HELPER -> "A potion wont fix every bad decision.|Neither will another lecture.|You could budget less for opinions.|Yours seem overpriced already.";
                case QUIET -> "u would ask a pot for build advice|better than asking u|ur opinions cost more than pots|refund requested";
                case PLAYFUL -> "u would blame pots for a skill issue|mad cuz bad|u need a potion for all that damn salt|u need one for shutting up";
            };
            case MOBS -> switch (voice) {
                case SOCIAL -> "a snail could coach u|still better than ur advice|u would argue with a mushroom|and u would lose the argument";
                case GRINDER -> "less mouth, more mobs|u first, pro|u would ks a tutorial mob|u would write a guide about it";
                case HELPER -> "A snail might improve your strategy.|It would certainly improve this advice.|Try arguing with the mobs instead.|They probably have better points.";
                case QUIET -> "the snails seem less annoying than u|go talk to one then|u would give a mushroom build advice|it would ignore u too";
                case PLAYFUL -> "u would lose a debate to a damn snail|no u F3|even mushrooms would mute ur ass|at least they got good taste";
            };
            case TRAVEL -> switch (voice) {
                case SOCIAL -> "i would trust a snail for directions over u|then ask the damn snail|u would get lost following a rope|u would blame the rope lol";
                case GRINDER -> "u need a map for the minimap|u need less chat|u would call walking a training method|more exp than listening to u";
                case HELPER -> "Perhaps consult the map before your ego.|Perhaps stop giving unsolicited tours.|You could get lost in a straight line.|With your directions, certainly.";
                case QUIET -> "u would get lost in the minimap|ur directions could do that|u make walking sound complicated|u make it sound interesting. somehow.";
                case PLAYFUL -> "u would get lost on a damn ladder|better than following ur ass|u need a guide for the portal button|kk mister explorer";
            };
        };
        return phrases(pairs)[variant * 2 + (answer ? 1 : 0)];
    }

    private static String[] phrases(String alternatives) {
        return alternatives.split("\\|", -1);
    }
}
