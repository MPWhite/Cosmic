package server.population.party;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.server.world.Party;
import net.server.world.PartyOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import scripting.npc.NPCScriptManager;
import server.life.NPC;
import server.maps.MapleMap;
import server.maps.Portal;
import server.population.TownPopulation;
import server.population.intelligence.ResidentIntelligence;
import server.population.knowledge.PlayerContext;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** A party knows the public KPQ procedure, never the randomly selected solution. */
final class KpqPartySession implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KpqPartySession.class);
    private static final int COUPON = 4001007, PASS = 4001008, CLOTO = 9020001, LAKELIS = 9020000;
    private final Character host;
    private final Party party;
    private final List<ResidentPartyActor> bots;
    private final List<TownPopulation.PartyReservation> reservations;
    private final Consumer<Runnable> submit;
    private final Map<Integer, Integer> questions = new HashMap<>();
    private final Set<Integer> exchanged = new HashSet<>();
    private final Map<Integer, Long> npcAfter = new HashMap<>();
    private final Map<Integer, Long> rewardAfter = new HashMap<>();
    private final Map<Integer, KpqPuzzle> puzzles = new HashMap<>();
    private final Map<String, Long> lastHints = new HashMap<>();
    private final java.util.PriorityQueue<Speech> speech = new java.util.PriorityQueue<>(java.util.Comparator.comparingLong(Speech::due));
    private record Speech(ResidentPartyActor speaker, MapleMap map, String text, long due, long epoch) { }
    private final long created = System.currentTimeMillis();
    private EventInstanceManager event;
    private long nextDecision, lastChat, chatEpoch, nextDiagnostic, entryObservedAt;
    private boolean closed, entered;
    private String lastCombo = "";
    private volatile String status = "Gathering at Lakelis.";

    KpqPartySession(Character host, Party party, List<ResidentPartyActor> bots,
                    List<TownPopulation.PartyReservation> reservations, Consumer<Runnable> submit) {
        this.host = host; this.party = party; this.bots = List.copyOf(bots);
        this.reservations = List.copyOf(reservations); this.submit = submit;
    }

    boolean contains(Character bot) { return bots.stream().anyMatch(b -> b.character() == bot); }
    EventInstanceManager event() { return event; }
    String status() { return status; }

    void greet() {
        say(bots.getFirst(), leader().isResidentBot() ? "i can lead. meet at lakelis" : "kk, u lead. we're ready at lakelis");
    }

    private Character leader() {
        var member = party.getLeader();
        return member == null || member.getPlayer() == null ? host : member.getPlayer();
    }

    boolean tick(long now) {
        if (closed || !host.isLoggedinWorld() || host.getParty() != party) return false;
        while (!speech.isEmpty() && speech.peek().due() <= now) {
            Speech line = speech.remove();
            if (line.epoch() == chatEpoch && line.map() == host.getMap() && line.speaker().character().getMap() == line.map())
                host.sendPacket(PacketCreator.multiChat(line.speaker().character().getName(), line.text(), 1));
        }
        if (bots.stream().anyMatch(b -> !b.character().isAlive() || b.character().getParty() != party)) {
            hint("lost-member", bots.getFirst(), "rip, we're short a member. regroup outside", now, 30_000);
            return false;
        }
        if (event == null && host.getEventInstance() != null) {
            if (!"KerningPQ".equals(host.getEventInstance().getEm().getName())) return false;
            event = host.getEventInstance();
            entryObservedAt = now;
        }
        if (event != null && !entered) {
            if (host.getEventInstance() != event) return false;
            // Registration sets the event before its entry script finishes warping.
            // Never mistake that brief, partially registered state for an exit.
            if (host.isChangingMaps() || host.getMapId() < 103000800 || host.getMapId() > 103000805)
                return now - entryObservedAt < 15_000;
            entered = true;
            log.info("Resident KPQ entered: host={}, event={}", host.getId(), event.getName());
        }
        if (entered && host.getEventInstance() != event) return false;
        if (!entered && (host.getMapId() != 103000000 || now - created > 10 * 60_000)) return false;
        if (host.isChangingMaps()) return true;
        if (!host.isAlive()) {
            // Keep the normal death dialog and event timer. The real revive script decides when the player exits.
            for (var bot : bots) { bot.hold(); bot.tick(now); }
            hint("host-death", bots.getFirst(), "rip. we'll wait while u revive", now, 30_000);
            return true;
        }
        for (var bot : bots) bot.tick(now);
        if (now < nextDecision) return true;
        nextDecision = now + 250;
        if (event == null) { lobby(now); return true; }
        if (host.getMapId() < 103000800 || host.getMapId() > 103000805) return false;
        status = "KPQ stage " + (host.getMapId() - 103000800 + 1) + "; leader: " + leader().getName();
        if (now >= nextDiagnostic) {
            nextDiagnostic = now + 15_000;
            log.debug("KPQ party {} host {} at {} {} bots {}", party.getId(), host.getId(), host.getMapId(), host.getPosition(),
                    bots.stream().map(b -> b.character().getName() + ":" + b.character().getMapId() + ":" + b.character().getPosition()
                            + ":" + b.actor().getNavigationStatus() + ":coupon=" + count(b.character(), COUPON) + ":pass=" + count(b.character(), PASS)).toList());
        }
        for (ResidentPartyActor bot : bots) {
            Character character = bot.character();
            int mapId = character.getMapId();
            int stage = mapId - 103000800 + 1;
            if (stage < 1 || stage > 6) continue;
            if (stage < 5 && event.getProperty(stage + "stageclear") != null) {
                nextMap(bot, now);
            } else if (stage == 5 && event.getProperty("5stageclear") != null) {
                reward(bot, now);
            } else if ((stage == 1 || stage >= 5) && character.getMap() != host.getMap()) {
                // Combat needs the human's ordinary monster controller. Cleared-stage travel does not.
                bot.hold();
            } else if (stage == 1) {
                coupons(bot, now);
            } else if (stage >= 2 && stage <= 4) {
                puzzle(bot, stage, now);
            } else if (stage == 5) {
                finale(bot, now);
            } else {
                bot.hunt(true);
                hint("bonus", bot, "gg! bonus time", now, 120_000);
            }
        }
        return true;
    }

    private void lobby(long now) {
        NPC npc = host.getMap().getNPCById(LAKELIS);
        if (npc == null) return;
        Point meeting = npc.getPosition();
        for (int i = 0; i < bots.size(); i++) bots.get(i).moveTo(new Point(meeting.x + 30 * (i - 1), meeting.y));
        if (host.getPosition().distanceSq(meeting) > 500 * 500) {
            hint("meet", bots.getFirst(), "we're at lakelis, come over", now, 30_000); return;
        }
        Character leader = leader();
        if (!leader.isResidentBot()) return; // A human leader uses Lakelis normally.
        ResidentPartyActor actor = bot(leader);
        if (actor == null || now - created < 5_000 || !near(leader, meeting, 95)) return;
        if (bots.stream().anyMatch(b -> !near(b.character(), meeting, 140))) return;
        if (!npcReady(actor, now)) return;
        var manager = host.getClient().getChannelServer().getEventSM().getEventManager("KerningPQ");
        if (manager == null) return;
        // Same eligibility and lobby allocation as Lakelis. Her ordinary script starts the event.
        npc(actor, LAKELIS, now);
        if (NPCScriptManager.getInstance().getCM(leader.getClient()) != null) {
            NPCScriptManager.getInstance().action(leader.getClient(), (byte) 1, (byte) 0, 0);
        }
        npcAfter.put(leader.getId(), now + 8_000);
        if (host.getEventInstance() == null) hint("occupied", actor, "channel's occupied, waiting for them to finish", now, 45_000);
    }

    void question(Character character, String text) {
        int answer = couponAnswer(text);
        if (answer > 0) {
            questions.put(character.getId(), answer);
            ResidentPartyActor actor = bot(character);
            if (actor != null) react(actor, "Cloto just asked you to collect exactly " + answer + " coupons.",
                    answer == 35 ? "got 35 fml" : "need " + answer);
        }
    }

    static int couponAnswer(String text) {
        String q = text.toLowerCase(Locale.ROOT);
        if (q.contains("amount of str")) return 35;
        if (q.contains("amount of int")) return 20;
        if (q.contains("amount of dex")) return 25;
        if (q.contains("2nd job")) return 30;
        if (q.contains("level") && q.contains("magician")) return 8;
        if (q.contains("level") && q.contains("warrior")) return 10;
        return 0;
    }

    private void coupons(ResidentPartyActor actor, long now) {
        Character character = actor.character(), leader = leader();
        if (character == leader) {
            actor.collect(PASS, party.getMembers().size() - 1);
            if (count(character, PASS) >= party.getMembers().size() - 1) talkToCloto(actor, now);
            else actor.hunt(true);
            return;
        }
        if (count(character, PASS) > 0) { handPasses(actor, leader, now); return; }
        if (exchanged.contains(character.getId())) {
            // Finished members help kill for the remaining collectors, leaving all
            // coupons on the ground for teammates who still need their own answer.
            actor.collect(0, 0); actor.hunt(true);
            return;
        }
        Integer amount = questions.get(character.getId());
        if (amount == null) { talkToCloto(actor, now); return; }
        actor.collect(COUPON, amount);
        if (count(character, COUPON) >= amount) {
            if (count(character, COUPON) > amount) drop(character, COUPON, count(character, COUPON) - amount);
            talkToCloto(actor, now);
        } else actor.hunt(true);
    }

    private void puzzle(ResidentPartyActor actor, int stage, long now) {
        actor.collect(0, 0); actor.hunt(false);
        KpqPuzzle puzzle = puzzles.computeIfAbsent(stage, KpqPuzzle::new);
        Character leader = leader();
        if (host.getMapId() != actor.character().getMapId()) {
            actor.hold(); hint("wait-" + stage, actor, "waiting for " + host.getName(), now, 30_000); return;
        }
        int humanSlot = leader == host ? -1 : puzzle.slot(host.getPosition());
        List<Integer> combo = puzzle.choose(humanSlot);
        if (combo.isEmpty()) { hint("exhausted-" + stage, actor, "something's off, let's check our positions", now, 30_000); return; }
        String label = stage + ":" + puzzle.label(combo);
        if (!label.equals(lastCombo)) {
            lastCombo = label;
            say(bots.getFirst(), puzzle.label(combo));
        }
        List<ResidentPartyActor> standers = bots.stream().filter(b -> b.character() != leader).toList();
        int reservedSlot = leader == host ? -1 : combo.contains(humanSlot) ? humanSlot : combo.getFirst();
        List<Integer> botSlots = combo.stream().filter(i -> i != reservedSlot).toList();
        if (leader != host && humanSlot != reservedSlot) {
            String object = stage == 2 ? "rope " : stage == 3 ? "platform " : "barrel ";
            hint("human-slot-" + stage, bots.getFirst(), host.getName() + " take " + object + (reservedSlot + 1), now, 20_000);
        }
        if (actor.character() == leader) {
            NPC cloto = actor.character().getMap().getNPCById(CLOTO);
            if (cloto != null) actor.moveTo(cloto.getPosition());
            boolean ready = humanSlot == reservedSlot && host.isAlive();
            for (int i = 0; i < standers.size(); i++) {
                Character c = standers.get(i).character();
                ready &= c.getMapId() == actor.character().getMapId() && puzzle.slot(c.getPosition()) == botSlots.get(i);
            }
            if (ready) talkToCloto(actor, now);
        } else {
            int index = standers.indexOf(actor);
            if (index < 0 || index >= botSlots.size()) return;
            Point target = puzzle.position(botSlots.get(index));
            if (stage == 2) actor.holdRope(target); else actor.moveTo(target);
            if (leader == host && standers.stream().allMatch(b -> combo.contains(puzzle.slot(b.character().getPosition())))
                    && standers.stream().map(b -> puzzle.slot(b.character().getPosition())).distinct().count() == 3) {
                hint("check-" + stage + "-" + puzzle.attempts(), bots.getFirst(), "check", now, 20_000);
            }
        }
    }

    void checked(int mapId, boolean accepted, List<Point> positions) {
        int stage = mapId - 103000800 + 1;
        if (!accepted) {
            KpqPuzzle puzzle = puzzles.computeIfAbsent(stage, KpqPuzzle::new);
            int before = puzzle.attempts();
            puzzle.observeWrong(positions);
            if (puzzle.attempts() > before) {
                lastCombo = "";
                log.info("KPQ party {} stage {} tried combination {}", party.getId(), stage, puzzle.attempts());
            }
        } else {
            log.info("KPQ party {} stage {} cleared through NPC check", party.getId(), stage);
            react(bots.get(stage % bots.size()), "Cloto just confirmed the party cleared stage " + stage + ". The portal opened.", "nice, go");
        }
    }

    private void finale(ResidentPartyActor actor, long now) {
        Character character = actor.character(), leader = leader();
        actor.collect(PASS, 10);
        if (character == leader && count(character, PASS) >= 10) { talkToCloto(actor, now); return; }
        if (character != leader && count(character, PASS) > 0) { handPasses(actor, leader, now); return; }
        actor.hunt(true);
        if (character.getMap().getAllMonsters().isEmpty()) {
            if (leader == host) hint("final-passes", actor, "bring the 10 passes back up to cloto", now, 30_000);
        }
    }

    private void handPasses(ResidentPartyActor actor, Character leader, long now) {
        actor.collect(0, 0); actor.hunt(false);
        if (leader.getMap() != actor.character().getMap()) return;
        Point recipient = new Point(leader.getPosition());
        actor.moveTo(recipient);
        int stance = leader.getStance() & ~1;
        boolean grounded = stance == 2 || stance == 4 || stance == 8 || stance == 10 || stance == 20;
        if (actor.actor().getFootholdId() != 0 && grounded
                && passDropReachable(actor.character().getMap(), actor.character().getPosition(), recipient)) {
            drop(actor.character(), PASS, count(actor.character(), PASS));
            hint("passes-" + actor.character().getId(), actor, "passes here", now, 12_000);
        } else if (grounded && actor.actor().getFootholdId() != 0 && near(actor.character(), recipient, 5)) {
            hint("pass-ground-" + actor.character().getId(), actor,
                    "step out from under the ledge a sec, pass won't land here", now, 20_000);
        }
    }

    /** Check the ordinary drop landing, including the server's search above the dropper. */
    static boolean passDropReachable(MapleMap map, Point from, Point recipient) {
        if (from.distanceSq(recipient) > 5 * 5) return false;
        Point fromGround = map.getPointBelow(new Point(from.x, from.y - 2));
        Point recipientGround = map.getPointBelow(new Point(recipient.x, recipient.y - 2));
        if (fromGround == null || recipientGround == null
                || Math.abs(fromGround.y - from.y) > 3 || Math.abs(recipientGround.y - recipient.y) > 3
                || Math.abs(fromGround.y - recipientGround.y) > 5) return false;
        // calcDropPos searches from y-85 and can choose an overhead branch even
        // when both characters are on the same floor. Keep the pass in that case.
        Point landing = map.calcDropPos(new Point(from), new Point(from));
        if (landing == null || Math.abs(landing.y - recipient.y) > 5) return false;
        // The browser picks up within 30px of the sprite's position+(16,16).
        // A settled drop bobs from landing.y-4 to landing.y+1; cover the whole cycle.
        long dx = (long) landing.x + 16 - recipient.x;
        long low = (long) landing.y + 12 - recipient.y;
        long high = (long) landing.y + 17 - recipient.y;
        return dx * dx + Math.max(low * low, high * high) <= 30 * 30;
    }

    private static int count(Character character, int item) { return character.getItemQuantity(item, false); }

    private static void drop(Character character, int item, int quantity) {
        if (quantity <= 0) return;
        Item stack = character.getInventory(InventoryType.ETC).findById(item);
        if (stack != null) InventoryManipulator.drop(character.getClient(), InventoryType.ETC, stack.getPosition(),
                (short) Math.min(quantity, stack.getQuantity()));
    }

    private boolean npcReady(ResidentPartyActor actor, long now) { return now >= npcAfter.getOrDefault(actor.character().getId(), 0L); }

    private void talkToCloto(ResidentPartyActor actor, long now) {
        actor.hunt(false);
        NPC npc = actor.character().getMap().getNPCById(CLOTO);
        if (npc == null) return;
        actor.moveTo(npc.getPosition());
        if (near(actor.character(), npc.getPosition(), 75) && npcReady(actor, now)) npc(actor, CLOTO, now);
    }

    private void npc(ResidentPartyActor actor, int npcId, long now) {
        Character character = actor.character();
        Integer required = questions.get(character.getId());
        boolean exchangingCoupons = npcId == CLOTO && character.getMapId() == 103000800
                && character != leader() && required != null && count(character, COUPON) == required;
        int passesBefore = count(character, PASS);
        npcAfter.put(character.getId(), now + 2_000 + character.getId() % 500);
        NPCScriptManager.getInstance().start(character.getClient(), npcId, character);
        // Only the ordinary NPC's observable inventory exchange proves this member
        // finished its own question. A former leader may be carrying others' passes.
        if (exchangingCoupons && count(character, COUPON) == 0 && count(character, PASS) == passesBefore + 1)
            exchanged.add(character.getId());
    }

    private void nextMap(ResidentPartyActor actor, long now) {
        actor.hunt(false); actor.collect(0, 0);
        Portal portal = actor.character().getMap().getPortal("next00");
        if (portal == null) return;
        actor.moveTo(portal.getPosition());
        if (near(actor.character(), portal.getPosition(), 30) && npcReady(actor, now)) {
            npcAfter.put(actor.character().getId(), now + 1_000);
            portal.enterPortal(actor.character().getClient());
        }
    }

    private void reward(ResidentPartyActor actor, long now) {
        Character character = actor.character();
        if (!rewardAfter.containsKey(character.getId())) {
            NPC cloto = character.getMap().getNPCById(CLOTO);
            if (cloto == null) return;
            actor.collect(0, 0); actor.moveTo(cloto.getPosition());
            if (near(character, cloto.getPosition(), 75) && npcReady(actor, now)) {
                npc(actor, CLOTO, now); rewardAfter.put(character.getId(), now + 1_500);
            }
        } else if (now >= rewardAfter.get(character.getId())) {
            if (NPCScriptManager.getInstance().getCM(character.getClient()) != null)
                NPCScriptManager.getInstance().action(character.getClient(), (byte) 1, (byte) 0, 0);
            rewardAfter.remove(character.getId());
        }
    }

    void chat(String message, boolean partyChat) {
        if (closed || System.currentTimeMillis() - lastChat < 1_000) return;
        lastChat = System.currentTimeMillis();
        ResidentPartyActor speaker = bots.stream().filter(b -> message.toLowerCase(Locale.ROOT)
                .contains(b.character().getName().toLowerCase(Locale.ROOT))).findFirst().orElse(bots.getFirst());
        String lower = message.toLowerCase(Locale.ROOT).strip();
        KpqLeadership leadership = KpqLeadership.parse(message, speaker.character().getName());
        if (leadership == KpqLeadership.HUMAN) {
            host.getWorldServer().updateParty(party.getId(), PartyOperation.CHANGE_LEADER, host.getMPC());
            if (event != null) event.setLeader(host);
            say(speaker, "kk, lead passed"); return;
        }
        if (leadership == KpqLeadership.RESIDENT) {
            host.getWorldServer().updateParty(party.getId(), PartyOperation.CHANGE_LEADER, speaker.character().getMPC());
            if (event != null) event.setLeader(speaker.character());
            say(speaker, "sure, i got lead"); return;
        }
        long epoch = ++chatEpoch;
        MapleMap map = host.getMap();
        String fallback = lower.contains("gg") ? "gg, nice run" : lower.contains("thanks") || lower.equals("ty") ? "np"
                : lower.contains("fell") ? "all good, we'll wait" : "ye?";
        String observed = status + ". You are " + speaker.character().getName() + ". Player position " + host.getPosition()
                + ". Your coupons=" + count(speaker.character(), COUPON) + ", passes=" + count(speaker.character(), PASS)
                + ". You know the public KPQ procedure but cannot know the secret winning combination. "
                + "Coordinate as an ordinary teammate; never claim an action you have not performed.";
        ResidentIntelligence.getInstance().reply(ResidentPartyCharacter.profile(speaker.character()),
                PlayerContext.snapshot(host), message, observed, fallback).thenAccept(reply -> submit.accept(() -> {
            if (!closed && epoch == chatEpoch && host.getMap() == map && host.isLoggedinWorld()) say(speaker, reply.text());
        }));
    }

    private ResidentPartyActor bot(Character character) { return bots.stream().filter(b -> b.character() == character).findFirst().orElse(null); }
    private static boolean near(Character c, Point point, int distance) { return c.getPosition().distanceSq(point) <= distance * distance; }

    private void hint(String key, ResidentPartyActor speaker, String text, long now, long interval) {
        if (now - lastHints.getOrDefault(key, 0L) >= interval) { lastHints.put(key, now); say(speaker, text); }
    }

    private void say(ResidentPartyActor speaker, String text) {
        if (closed || text == null || text.isBlank()) return;
        var lines = ResidentIntelligence.lines(text);
        for (int i = 0; i < lines.size(); i++) {
            if (i == 0) host.sendPacket(PacketCreator.multiChat(speaker.character().getName(), lines.get(i), 1));
            else speech.add(new Speech(speaker, speaker.character().getMap(), lines.get(i), System.currentTimeMillis() + i * 1200L, chatEpoch));
        }
    }

    /** Models choose a social response only; movement and legal quest transitions keep running. */
    private void react(ResidentPartyActor speaker, String observation, String fallback) {
        long epoch = chatEpoch;
        MapleMap originalMap = speaker.character().getMap();
        var profile = ResidentPartyCharacter.profile(speaker.character());
        if (profile == null) return;
        PlayerContext context = PlayerContext.snapshot(host);
        ResidentIntelligence intelligence = ResidentIntelligence.getInstance();
        String observed = observation + " You are " + speaker.character().getName() + "; your party leader is " + leader().getName()
                + ". You only know the visible outcome. Other members are still moving and playing.";
        intelligence.decide("kpq:" + party.getId(), observed, Map.of(
                "brief", "A short acknowledgement helps the party coordinate.",
                "banter", "A brief friendly joke fits this moment without interrupting play.",
                "quiet", "Nothing needs saying; let the party concentrate."), "brief")
                .thenCompose(choice -> choice.equals("quiet")
                        ? java.util.concurrent.CompletableFuture.completedFuture(new ResidentIntelligence.Reply("", "quiet", List.of()))
                        : intelligence.reply(profile, context, "React to the observed party event in one short "
                                + (choice.equals("banter") ? "playful" : "practical") + " chat message.", observed, fallback))
                .thenAccept(response -> submit.accept(() -> {
                    if (!closed && epoch == chatEpoch && host.isLoggedinWorld() && host.getMap() == originalMap
                            && speaker.character().getMap() == originalMap) say(speaker, response.text());
                }));
    }

    @Override public void close() {
        if (closed) return;
        closed = true; chatEpoch++; speech.clear();
        // Entry runs on the NPC thread. A disconnect can arrive before our next tick
        // observes it, so recover the owned instance from a registered teammate.
        if (event == null) {
            event = bots.stream().map(b -> b.character().getEventInstance())
                    .filter(java.util.Objects::nonNull)
                    .filter(e -> "KerningPQ".equals(e.getEm().getName())).findFirst().orElse(null);
        }
        if (event != null) {
            try { event.invokeScriptFunction("end", event); }
            catch (Exception e) { log.warn("Couldn't end resident KPQ normally", e); }
        }
        for (ResidentPartyActor actor : bots) {
            try { actor.close(); }
            catch (RuntimeException e) { log.warn("Couldn't retire party resident {}", actor.character().getId(), e); }
            finally {
                try { ResidentPartyCharacter.close(actor.character()); }
                catch (RuntimeException e) { log.warn("Couldn't remove party character {}", actor.character().getId(), e); }
            }
        }
        if (host.getParty() == party) {
            try {
                if (party.getLeaderId() != host.getId()) host.getWorldServer().updateParty(party.getId(), PartyOperation.CHANGE_LEADER, host.getMPC());
                Party.leaveParty(party, host.getClient());
            } catch (RuntimeException e) { log.warn("Couldn't clean up resident party {}", party.getId(), e); }
        }
        TownPopulation.releasePartyMembers(reservations);
        log.info("Resident KPQ party closed: host={}, party={}", host.getId(), party.getId());
    }
}
