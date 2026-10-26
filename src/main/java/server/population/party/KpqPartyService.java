package server.population.party;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventInstanceManager;
import server.population.TownPopulation;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Serializes the small set of active PQ parties; background residents never run this simulation. */
public final class KpqPartyService {
    private static final Logger log = LoggerFactory.getLogger(KpqPartyService.class);
    private static final class Holder { static final KpqPartyService INSTANCE = new KpqPartyService(); }
    private final Map<Integer, KpqPartySession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "resident-kpq"); t.setDaemon(true); return t;
    });

    private KpqPartyService() {
        executor.scheduleAtFixedRate(this::tick, 50, 50, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.shutdown();
            try { executor.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            sessions.values().forEach(KpqPartySession::close);
        }, "resident-kpq-shutdown"));
    }

    private void submit(Runnable work) {
        try { executor.execute(() -> { try { work.run(); } catch (RuntimeException e) { log.error("KPQ party action failed", e); } }); }
        catch (RejectedExecutionException ignored) { /* Server is shutting down. */ }
    }

    public static boolean isRecruitment(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll(" +", " ").strip();
        if (!s.matches(".*\\bkpq\\b.*") || s.matches(".*\\b(what|where|how|level|lvl|requirement|requirements|reward|rewards)\\b.*")) return false;
        if (s.matches(".*\\b(dont|don t|not|never|no)\\b.*")) return false;
        return s.equals("kpq") || s.matches(".*\\b(lfp|lfg|lfm|anyone|anybody|lets|let s|wanna|want|join|run|do|party|invite|inv)\\b.*");
    }

    public static boolean onChat(Character player, String message, boolean partyChat) {
        if (!enabled() || player == null || player.isResidentBot() || message == null || message.isBlank()) return false;
        KpqPartyService service = Holder.INSTANCE;
        KpqPartySession session = service.sessions.get(player.getId());
        if (session != null) {
            service.submit(() -> session.chat(message, partyChat));
            return partyChat || player.getEventInstance() != null;
        }
        if (player.getMapId() == 103000000 && isRecruitment(message)) {
            boolean humanLeads = message.toLowerCase(Locale.ROOT).matches(".*\\b(lfm|i lead|ill lead|i'll lead|let me lead)\\b.*");
            service.submit(() -> service.recruit(player, humanLeads));
            return true;
        }
        return false;
    }

    public static void request(Character player, boolean humanLeads) {
        if (player == null) return;
        if (!enabled()) {
            player.dropMessage(5, "Resident parties are disabled on this server.");
            return;
        }
        if (player.isResidentBot()) return;
        Holder.INSTANCE.submit(() -> Holder.INSTANCE.recruit(player, humanLeads));
    }

    private static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("cosmic.residents.enabled", "true"));
    }

    public static void leave(Character player) {
        Holder.INSTANCE.submit(() -> {
            KpqPartySession session = Holder.INSTANCE.sessions.remove(player.getId());
            if (session != null) session.close();
        });
    }

    public static void status(Character player) {
        KpqPartySession session = Holder.INSTANCE.sessions.get(player.getId());
        player.dropMessage(5, session == null ? "Say 'anyone wanna KPQ?' in Kerning to find a party. Levels 21–30."
                : session.status());
    }

    private void recruit(Character host, boolean humanLeads) {
        if (!enabled() || host == null || host.isResidentBot() || !host.isLoggedinWorld()
                || host.getMapId() != 103000000 || host.getEventInstance() != null) return;
        if (sessions.containsKey(host.getId())) return;
        if (host.getLevel() < 21 || host.getLevel() > 30) {
            host.dropMessage(5, "KPQ is for levels 21–30. Your party has to meet the normal entry requirements."); return;
        }
        if (sessions.size() >= 8) { host.dropMessage(5, "The resident parties are busy. Try again shortly."); return; }
        if (host.getParty() != null && (host.getParty().getMembers().size() > 1 || !host.isPartyLeader())) {
            host.dropMessage(5, "You're already with a party. Finish or leave that party before recruiting a resident team."); return;
        }
        List<TownPopulation.PartyReservation> reserved = TownPopulation.reservePartyMembers(host, 3);
        if (reserved.size() != 3) {
            host.dropMessage(5, "There aren't three available level 21–30 residents nearby right now. Try another channel."); return;
        }
        List<ResidentPartyActor> actors = new ArrayList<>();
        List<Character> characters = new ArrayList<>();
        boolean createdParty = host.getParty() == null;
        try {
            if (createdParty && !Party.createParty(host, false)) throw new IllegalStateException("Party creation failed");
            Party party = host.getParty();
            for (var reservation : reserved) {
                Character bot = ResidentPartyCharacter.create(reservation.profile(), host, reservation.spawn());
                characters.add(bot);
                ResidentPartyActor actor = new ResidentPartyActor(bot, reservation.profile());
                actors.add(actor);
                if (!Party.joinParty(bot, party.getId(), false)) throw new IllegalStateException("Resident party join failed");
            }
            if (!humanLeads) host.getWorldServer().updateParty(party.getId(), PartyOperation.CHANGE_LEADER,
                    actors.getFirst().character().getMPC());
            KpqPartySession session = new KpqPartySession(host, party, actors, reserved, this::submit);
            sessions.put(host.getId(), session);
            session.greet();
            log.info("Resident KPQ party formed: host={}, party={}, botLeader={}", host.getId(), party.getId(), !humanLeads);
        } catch (RuntimeException failure) {
            for (var actor : actors) {
                try { actor.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            for (var character : characters) {
                try { ResidentPartyCharacter.close(character); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            TownPopulation.releasePartyMembers(reserved);
            if (createdParty && host.getParty() != null) Party.leaveParty(host.getParty(), host.getClient());
            host.dropMessage(5, "Couldn't gather that party. Please try again.");
            log.error("Failed to recruit a resident KPQ party", failure);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (var entry : List.copyOf(sessions.entrySet())) {
            KpqPartySession session = entry.getValue();
            try {
                if (!session.tick(now)) {
                    sessions.remove(entry.getKey(), session);
                    session.close();
                }
            } catch (RuntimeException failure) {
                log.error("Resident KPQ failed for host {}", entry.getKey(), failure);
                sessions.remove(entry.getKey(), session);
                session.close();
            }
        }
    }

    /** Called at the moment Cloto actually asks this particular participant a question. */
    public static void questionAsked(Character participant, String question) {
        if (participant == null || !participant.isResidentBot()) return;
        Holder.INSTANCE.submit(() -> Holder.INSTANCE.sessions.values().stream()
                .filter(s -> s.contains(participant)).findFirst().ifPresent(s -> s.question(participant, question)));
    }

    /** Only public check outcomes and visible positions cross the controller boundary. */
    public static void checked(EventInstanceManager event, int mapId, boolean accepted) {
        if (mapId < 103000801 || mapId > 103000803) return;
        List<Point> positions = event.getPlayers().stream().filter(p -> p.getMapId() == mapId)
                .map(p -> new Point(p.getPosition())).toList();
        Holder.INSTANCE.submit(() -> Holder.INSTANCE.sessions.values().stream().filter(s -> s.event() == event)
                .findFirst().ifPresent(s -> s.checked(mapId, accepted, positions)));
    }
}
