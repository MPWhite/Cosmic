package server.population;

import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import tools.PacketCreator;

import java.awt.Point;
import java.util.Map;

/** The ordinary v83 remote-player wire format, without a connected Character. */
public final class ResidentPackets {
    private ResidentPackets() {
    }

    public static Packet spawn(TownResident resident) {
        ResidentProfiles.Profile profile = resident.getProfile();
        OutPacket packet = OutPacket.create(SendOpcode.SPAWN_PLAYER);
        packet.writeInt(resident.getId());
        packet.writeByte(profile.level());
        packet.writeString(profile.name());
        packet.writeString(""); // no guild
        packet.skip(6);

        // Unbuffed foreign character: the same 128-byte block as
        // PacketCreator.writeForeignBuffs (including the v83 header).
        packet.writeInt(0);
        packet.writeShort(0);
        packet.writeByte(0xFC);
        packet.writeByte(1);
        packet.skip(120);

        packet.writeShort(profile.job());
        packet.writeByte(profile.gender());
        packet.writeByte(profile.skin());
        packet.writeInt(profile.face());
        packet.writeBool(true);
        packet.writeInt(profile.hair());
        profile.equipment().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            packet.writeByte(entry.getKey());
            packet.writeInt(entry.getValue());
        });
        packet.writeByte(0xFF); // end visible equipment
        packet.writeByte(0xFF); // no masked equipment
        packet.writeInt(0); // no cash weapon
        packet.skip(12); // no pet appearances
        packet.skip(12); // chocolate, item effect, chair
        packet.writePos(resident.getPosition());
        packet.writeByte(resident.getStance());
        packet.writeShort(resident.getFootholdId());
        packet.writeByte(0);
        packet.writeByte(0); // end pets
        packet.writeInt(1); // default mount level
        packet.writeLong(0);
        packet.writeByte(0); // no shop
        packet.writeByte(0); // no chalkboard
        packet.skip(7); // rings, marriage, new year cards, reserved fields, team
        return packet;
    }

    public static Packet move(int id, Point position, Point velocity, int foothold, int stance, int durationMillis) {
        OutPacket packet = OutPacket.create(SendOpcode.MOVE_PLAYER);
        packet.writeInt(id);
        packet.writeInt(0);
        packet.writeByte(1); // one absolute movement fragment
        packet.writeByte(0);
        packet.writePos(position);
        packet.writePos(velocity);
        packet.writeShort(foothold);
        packet.writeByte(stance);
        packet.writeShort(Math.max(1, Math.min(Short.MAX_VALUE, durationMillis)));
        return packet;
    }

    public static Packet remove(int id) {
        return PacketCreator.removePlayerFromMap(id);
    }

    public static Packet chat(int id, String text) {
        return PacketCreator.getChatText(id, text, false, 0);
    }
}
