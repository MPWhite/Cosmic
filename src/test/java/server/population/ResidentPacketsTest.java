package server.population;

import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ResidentPacketsTest {
    @Test
    void staleMapPlacementCannotSpawnATransferredCompanionInThePreviousMap() {
        TownResident resident = resident(ResidentProfiles.all().getFirst());
        client.Client observer = mock(client.Client.class);
        client.Character player = mock(client.Character.class);
        when(observer.getPlayer()).thenReturn(player);
        when(player.getMap()).thenReturn(mock(MapleMap.class));
        resident.sendSpawnData(observer);
        verify(observer, never()).sendPacket(any());

        when(player.getMap()).thenReturn(resident.getMap());
        resident.sendSpawnData(observer);
        verify(observer).sendPacket(any());
    }

    @Test
    void browserV83DecoderCanReadTheCompleteRemotePlayerAppearance() {
        var profile = ResidentProfiles.all().getFirst();
        TownResident resident = resident(profile);
        resident.setObjectId(10001); // map registry IDs are independent of character IDs
        InPacket packet = new ByteBufInPacket(Unpooled.wrappedBuffer(ResidentPackets.spawn(resident).getBytes()));
        assertEquals(SendOpcode.SPAWN_PLAYER.getValue(), packet.readShort());
        assertEquals(1_500_000_001, packet.readInt());
        assertEquals(profile.level(), packet.readUnsignedByte());
        assertEquals(profile.name(), packet.readString());
        assertEquals("", packet.readString());
        packet.skip(6); // guild logo

        // Follow SpawnCharHandler's field boundaries, rather than just a total length.
        packet.skip(8);
        assertEquals(0, packet.readInt()); // morph
        assertEquals(0, packet.readInt()); // buff mask 1, no extra value
        assertEquals(0, packet.readInt()); // buff mask 2
        packet.skip(43);
        assertEquals(0, packet.readInt()); // mount
        packet.skip(61);
        assertEquals(profile.job(), packet.readShort());
        assertEquals(profile.gender(), packet.readByte());
        assertEquals(profile.skin(), packet.readByte());
        assertEquals(profile.face(), packet.readInt());
        assertEquals(1, packet.readByte());
        assertEquals(profile.hair(), packet.readInt());
        var equipment = new HashMap<Integer, Integer>();
        int slot;
        while ((slot = packet.readUnsignedByte()) != 255) {
            equipment.put(slot, packet.readInt());
        }
        assertEquals(profile.equipment(), equipment);
        assertEquals(255, packet.readUnsignedByte()); // masked equipment terminator
        assertEquals(0, packet.readInt()); // cash weapon
        packet.skip(24); // three pet appearances, chocolate, item effect, chair
        assertEquals(new Point(50, 100), packet.readPos());
        assertEquals(4, packet.readUnsignedByte());
        assertEquals(7, packet.readShort());
        assertEquals(0, packet.readByte());
        assertEquals(0, packet.readByte()); // no pets
        assertEquals(1, packet.readInt()); // mount level
        assertEquals(0, packet.readLong());
        assertEquals(0, packet.readByte()); // shop
        assertEquals(0, packet.readByte()); // chalkboard
        assertArrayEquals(new byte[7], packet.readBytes(7));
        assertEquals(0, packet.available());
        assertEquals(1_500_000_001, resident.getId());
        assertEquals(10001, resident.getObjectId());
    }

    @Test
    void movementUsesAnAbsoluteFragmentWithValidWalkStanceAndTiming() {
        InPacket packet = new ByteBufInPacket(Unpooled.wrappedBuffer(ResidentPackets.move(
                1_500_000_001, new Point(54, 100), new Point(80, 0), 7, 2, 50).getBytes()));
        assertEquals(SendOpcode.MOVE_PLAYER.getValue(), packet.readShort());
        assertEquals(1_500_000_001, packet.readInt());
        packet.skip(4);
        assertEquals(1, packet.readByte());
        assertEquals(0, packet.readByte());
        assertEquals(new Point(54, 100), packet.readPos());
        assertEquals(new Point(80, 0), packet.readPos());
        assertEquals(7, packet.readShort());
        assertEquals(2, packet.readByte());
        assertEquals(50, packet.readShort());
        assertEquals(0, packet.available());
    }

    @Test
    void everyCombatStyleMatchesTheForeignAttackDecoderIncludingMagicLinesAndRangedTail() {
        for (ResidentCombat.Style style : ResidentCombat.Style.values()) {
            int[] lines = style == ResidentCombat.Style.MAGIC ? new int[]{17, 18} : new int[]{35};
            InPacket packet = new ByteBufInPacket(Unpooled.wrappedBuffer(ResidentPackets.attack(
                    1_500_000_001, style, true, 777, lines).getBytes()));
            assertEquals(style.opcode().getValue(), packet.readShort());
            assertEquals(1_500_000_001, packet.readInt());
            assertEquals(0x10 | lines.length, packet.readUnsignedByte());
            assertEquals(0x5B, packet.readUnsignedByte());
            int skillLevel = packet.readUnsignedByte();
            assertEquals(style.skillId() == 0 ? 0 : 1, skillLevel);
            if (skillLevel > 0) {
                assertEquals(2001005, packet.readInt());
            }
            assertEquals(0, packet.readByte());
            assertEquals(1, packet.readByte());
            assertEquals(0, packet.readByte(), "movement stance must never override the weapon attack action");
            assertEquals(6, packet.readByte());
            assertEquals(0x0A, packet.readByte());
            assertEquals(style.projectileId(), packet.readInt());
            assertEquals(777, packet.readInt());
            assertEquals(0, packet.readByte());
            for (int damage : lines) {
                assertEquals(damage, packet.readInt());
            }
            if (style.opcode() == SendOpcode.RANGED_ATTACK) {
                assertEquals(0, packet.readInt());
            }
            assertEquals(0, packet.available());
        }
    }

    private static TownResident resident(ResidentProfiles.Profile profile) {
        MapleMap map = mock(MapleMap.class);
        FootholdTree tree = new FootholdTree(new Point(-1000, -1000), new Point(1000, 1000));
        tree.insert(new Foothold(new Point(0, 100), new Point(200, 100), 7));
        when(map.getFootholds()).thenReturn(tree);
        return new TownResident(1_500_000_001, profile, map, new Point(50, 95));
    }
}
