package server.population;

import client.Client;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import server.maps.AbstractMapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import server.population.navigation.CompanionNavigation;
import tools.PacketCreator;

import java.awt.Point;

/** A hunter's private collection. Never a MapItem and never transferable to a player inventory. */
final class ResidentLoot extends AbstractMapObject {
    private final MapleMap map;
    private final int owner;
    private final int item;
    private final long created;
    private boolean removed;

    private ResidentLoot(TownResident resident, int item, Point position, long now) {
        this.map = resident.getMap();
        this.owner = resident.getId();
        this.item = item;
        this.created = now;
        setPosition(position);
    }

    static ResidentLoot fromKill(TownResident resident, ResidentCombat.Kill kill, long now) {
        int item = itemFor(kill.monsterId());
        if (item == 0) {
            return null;
        }
        Point ground = CompanionNavigation.forMap(resident.getMap())
                .groundBelow(new Point(kill.position().x, kill.position().y - 12), 160)
                .map(CompanionNavigation.Ground::position).orElse(null);
        if (ground == null) {
            return null;
        }
        ResidentLoot loot = new ResidentLoot(resident, item, ground, now);
        loot.map.addMapObject(loot); // use the map's allocator, never a guessed client-only OID
        loot.map.broadcastMessage(loot.spawn(1));
        return loot;
    }

    static int itemFor(int monsterId) {
        return switch (monsterId) {
            case 100100 -> 4000019; // snail
            case 100101 -> 4000000; // blue snail
            case 130101 -> 4000016; // red snail
            case 120100 -> 4000001; // orange mushroom
            case 1210100 -> 4000021; // leather
            case 1210102 -> 4000002; // ribbon
            case 210100 -> 4000004; // slime
            default -> 0;
        };
    }

    boolean expired(TownResident resident, long now) {
        return removed || resident.getMap() != map || now - created >= 8_000;
    }

    boolean collect(TownResident resident, long now) {
        if (expired(resident, now) || resident.getId() != owner || now - created < 850
                || resident.getFootholdId() == 0 || resident.getPosition().distanceSq(getPosition()) > 45 * 45) {
            return false;
        }
        remove(2);
        return true;
    }

    synchronized void expire() {
        remove(0);
    }

    private synchronized void remove(int animation) {
        if (!removed) {
            removed = true;
            map.removeMapObject(getObjectId());
            map.broadcastMessage(PacketCreator.removeItemFromMap(getObjectId(), animation, owner));
        }
    }

    private Packet spawn(int mode) {
        OutPacket packet = OutPacket.create(SendOpcode.DROP_ITEM_FROM_MAPOBJECT);
        packet.writeByte(mode);
        packet.writeInt(getObjectId());
        packet.writeBool(false);
        packet.writeInt(item);
        packet.writeInt(owner);
        packet.writeByte(0);
        packet.writePos(getPosition());
        packet.writeInt(owner);
        if (mode != 2) {
            packet.writePos(new Point(getPosition().x, getPosition().y - 25));
            packet.writeShort(0);
        }
        packet.writeLong(PacketCreator.getTime(-1));
        packet.writeByte(1);
        return packet;
    }

    @Override
    public MapObjectType getType() {
        return MapObjectType.TOWN_RESIDENT_LOOT;
    }

    @Override
    public synchronized void sendSpawnData(Client client) {
        if (!removed && client.getPlayer() != null && client.getPlayer().getMap() == map) {
            client.sendPacket(spawn(2));
        }
    }

    @Override
    public void sendDestroyData(Client client) {
        client.sendPacket(PacketCreator.removeItemFromMap(getObjectId(), 1, 0));
    }
}
