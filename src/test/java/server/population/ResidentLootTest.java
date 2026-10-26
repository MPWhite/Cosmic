package server.population;

import client.Client;
import org.junit.jupiter.api.Test;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidentLootTest {
    @Test
    void onlyTheOwningResidentCanCollectAfterTheDropHasLanded() {
        Fixture f = fixture();
        ResidentLoot loot = ResidentLoot.fromKill(f.owner, new ResidentCombat.Kill(100101, new Point(-185, 455)), 1_000);
        assertNotNull(loot);
        assertEquals(MapObjectType.TOWN_RESIDENT_LOOT, loot.getType());
        assertFalse(server.maps.MapItem.class.isInstance(loot)); // outside player inventory semantics
        assertFalse(loot.collect(f.other, 2_000));
        assertFalse(loot.collect(f.owner, 1_200));
        when(f.owner.getPosition()).thenReturn(new Point(0, 455));
        assertFalse(loot.collect(f.owner, 2_000));
        when(f.owner.getPosition()).thenReturn(new Point(-185, 455));
        assertTrue(loot.collect(f.owner, 2_000));
        assertFalse(loot.collect(f.owner, 2_100));
        loot.expire();
        verify(f.map, times(1)).removeMapObject(1234);
    }

    @Test
    void lootExpiresOnDepartureAndNeverSpawnsIntoAnotherMap() {
        Fixture f = fixture();
        ResidentLoot loot = ResidentLoot.fromKill(f.owner, new ResidentCombat.Kill(100101, new Point(-185, 455)), 1_000);
        assertFalse(loot.expired(f.owner, 8_999));
        assertTrue(loot.expired(f.owner, 9_000));
        MapleMap elsewhere = mock(MapleMap.class);
        when(f.owner.getMap()).thenReturn(elsewhere);
        assertTrue(loot.expired(f.owner, 2_000));
        Client client = mock(Client.class);
        client.Character player = mock(client.Character.class);
        when(client.getPlayer()).thenReturn(player);
        when(player.getMap()).thenReturn(elsewhere);
        loot.sendSpawnData(client);
        verify(client, never()).sendPacket(any());
        loot.expire();
        when(player.getMap()).thenReturn(f.map);
        loot.sendSpawnData(client);
        verify(client, never()).sendPacket(any());
    }

    @Test
    void unsupportedMonstersDoNotInventItems() {
        Fixture f = fixture();
        assertNull(ResidentLoot.fromKill(f.owner, new ResidentCombat.Kill(9999999, new Point(-185, 455)), 1_000));
        verify(f.map, never()).addMapObject(any());
    }

    private record Fixture(MapleMap map, TownResident owner, TownResident other) { }

    private static Fixture fixture() {
        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(ResidentRoster.TRAINING_GROUND);
        doAnswer(call -> { ((ResidentLoot) call.getArgument(0)).setObjectId(1234); return null; }).when(map).addMapObject(any());
        TownResident owner = mock(TownResident.class);
        when(owner.getMap()).thenReturn(map);
        when(owner.getId()).thenReturn(1_800_000_021);
        when(owner.getPosition()).thenReturn(new Point(-185, 455));
        when(owner.getFootholdId()).thenReturn(1);
        TownResident other = mock(TownResident.class);
        when(other.getMap()).thenReturn(map);
        when(other.getId()).thenReturn(1_800_000_022);
        when(other.getPosition()).thenReturn(new Point(-185, 455));
        when(other.getFootholdId()).thenReturn(1);
        return new Fixture(map, owner, other);
    }
}
