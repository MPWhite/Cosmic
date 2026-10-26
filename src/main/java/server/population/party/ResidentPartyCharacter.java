package server.population.party;

import client.Character;
import client.Client;
import client.inventory.InventoryType;
import net.packet.Packet;
import net.server.world.Party;
import net.server.world.PartyOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import server.population.ResidentProfiles.Profile;

import java.awt.Point;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Active resident PQ participants. They occupy real map/party/event slots, but never
 * account sessions, channel/world player storage, character saves or account counters.
 */
public final class ResidentPartyCharacter {
    private static final Logger log = LoggerFactory.getLogger(ResidentPartyCharacter.class);
    private static final int MAX_ACTIVE = 192; // 64 simultaneous three-bot parties
    private static final AtomicInteger IDS = new AtomicInteger(1_600_000_000);
    private static final Map<Integer, Member> MEMBERS = new ConcurrentHashMap<>();
    private record Member(Character character, Profile profile) { }

    private ResidentPartyCharacter() { }

    public static synchronized Character create(Profile profile, Character host, Point spawn) {
        Objects.requireNonNull(profile);
        Objects.requireNonNull(host);
        Objects.requireNonNull(spawn);
        MapleMap map = Objects.requireNonNull(host.getMap());
        if (MEMBERS.size() >= MAX_ACTIVE) throw new IllegalStateException("Resident party capacity reached");
        if (MEMBERS.values().stream().anyMatch(member -> member.character().getWorld() == host.getWorld()
                && member.profile().id() == profile.id())) {
            throw new IllegalStateException("Resident is already in a party");
        }
        if (host.getWorldServer().getPlayerStorage().getCharacterByName(profile.name()) != null) {
            throw new IllegalStateException("Resident name is occupied by a player");
        }
        int id = IDS.incrementAndGet();
        if (id >= 1_700_000_000) throw new IllegalStateException("Resident network IDs exhausted");
        ResidentClient client = new ResidentClient(host.getWorld(), host.getClient().getChannel());
        Character character = Character.createResidentBot(client, id, profile, map, spawn);
        MEMBERS.put(id, new Member(character, profile));
        try {
            character.setWorldRates();
            character.setPlayerRates();
            map.addPlayer(character);
            character.visitMap(map);
            character.setMapTransitionComplete();
            character.buffExpireTask();
            character.diseaseExpireTask();
            character.skillCooldownTask();
            return character;
        } catch (RuntimeException failure) {
            close(character);
            throw failure;
        }
    }

    public static Character find(int id) {
        Member member = MEMBERS.get(id);
        return member == null ? null : member.character();
    }

    public static boolean isActive(Character character) {
        return character != null && character.isResidentBot() && find(character.getId()) == character;
    }

    public static Profile profile(Character character) {
        Member member = character == null ? null : MEMBERS.get(character.getId());
        if (member == null || member.character() != character) return null;
        Profile profile = member.profile();
        var weapon = character.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        int weaponId = weapon == null ? 0 : weapon.getItemId();
        if (character.getLevel() == profile.level() && character.getJob().getId() == profile.job()
                && weaponId == profile.equipment().getOrDefault(11, 0)) return profile;
        var equipment = new java.util.HashMap<>(profile.equipment());
        if (weaponId == 0) equipment.remove(11);
        else equipment.put(11, weaponId);
        return new Profile(profile.id(), profile.name(), profile.gender(), profile.skin(), profile.face(), profile.hair(),
                character.getJob().getId(), character.getLevel(), equipment, profile.role(), profile.companion());
    }

    public static int activeCount() {
        return MEMBERS.size();
    }

    public static List<Character> active() {
        return MEMBERS.values().stream().map(Member::character).toList();
    }

    /** Idempotent and safe during script callbacks which may also request cleanup. */
    public static void close(Character character) {
        if (character == null || !character.isResidentBot()) return;
        Member current = MEMBERS.get(character.getId());
        if (current == null || current.character() != character || !MEMBERS.remove(character.getId(), current)) return;
        try {
            character.getClient().closePlayerScriptInteractions();
        } catch (RuntimeException failure) {
            log.warn("Failed to close resident {} script interaction", character.getName(), failure);
        }
        try {
            var event = character.getEventInstance();
            if (event != null) event.unregisterPlayer(character);
        } catch (RuntimeException failure) {
            log.warn("Failed to unregister resident {} from its event", character.getName(), failure);
        }
        try {
            Party party = character.getParty();
            if (party != null && party.getMemberById(character.getId()) != null
                    && character.getWorldServer().getParty(party.getId()) == party) {
                if (party.getLeaderId() == character.getId()) {
                    var successor = party.getMembers().stream()
                            .filter(member -> member.getId() != character.getId() && member.isOnline())
                            .sorted(java.util.Comparator.comparing(candidate -> candidate.getPlayer() != null
                                    && candidate.getPlayer().isResidentBot()))
                            .findFirst();
                    if (successor.isPresent()) {
                        character.getWorldServer().updateParty(party.getId(), PartyOperation.CHANGE_LEADER, successor.get());
                    } else {
                        character.getWorldServer().updateParty(party.getId(), PartyOperation.DISBAND, character.getMPC());
                    }
                }
                if (character.getWorldServer().getParty(party.getId()) == party) {
                    character.getWorldServer().updateParty(party.getId(), PartyOperation.LEAVE, character.getMPC());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("Failed to detach resident {} from its party", character.getName(), failure);
        } finally {
            try {
                MapleMap map = character.getMap();
                if (map != null && map.getCharacterById(character.getId()) == character) map.removePlayer(character);
            } catch (RuntimeException failure) {
                log.warn("Failed to remove resident {} from its map", character.getName(), failure);
            } finally {
                try {
                    character.getWorldServer().removePlayerHpDecrease(character);
                } finally {
                    character.disposeResidentBot();
                }
            }
        }
    }

    /** Script/NPC calls use an ordinary Client API; packet output deliberately has no socket. */
    private static final class ResidentClient extends Client {
        ResidentClient(int world, int channel) {
            super(Type.CHANNEL, -1, "resident", null, world, channel);
            setAccountName("resident");
        }

        @Override
        public void sendPacket(Packet packet) { }

        @Override
        public boolean isLoggedIn() {
            return getPlayer() != null && getPlayer().isLoggedin();
        }
    }
}
