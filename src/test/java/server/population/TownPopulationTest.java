package server.population;

import org.junit.jupiter.api.Test;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class TownPopulationTest {
    @Test
    void routesToTheFirstCompleteNameInTheMessageNotRosterOrder() {
        TownResident mia = named("MapleMia");
        TownResident pip = named("PipArcher");
        List<TownResident> people = List.of(mia, pip);
        assertSame(pip, TownPopulation.addressedResident(people, "PipArcher, MapleMia told me about you"));
        assertSame(mia, TownPopulation.addressedResident(people, "hello, MAPLEMIA!"));
        assertSame(mia, TownPopulation.addressedResident(people, "MapleMiaFan says hi, MapleMia"));
        assertNull(TownPopulation.addressedResident(people, "MapleMiaFan, hello"));
        assertNull(TownPopulation.addressedResident(people, "Hello everyone"));
        assertSame(pip, TownPopulation.addressedResident(people, "Pip, lvl?"));
        assertSame(mia, TownPopulation.addressedResident(people, "hey Mia"));
        assertNull(TownPopulation.addressedResident(people, "PipArcher told me about that map"));
        assertNull(TownPopulation.addressedResident(people, "I was talking about MapleMia yesterday"));
    }

    @Test
    void validatesAllHomesBeforeRegisteringAnyResident() {
        MapleMap map = mock(MapleMap.class);
        FootholdTree tree = mock(FootholdTree.class);
        when(map.getFootholds()).thenReturn(tree);
        Foothold ground = new Foothold(new Point(0, 334), new Point(6000, 334), 1);
        when(tree.findBelow(any())).thenReturn(ground, ground, null);

        assertThrows(IllegalArgumentException.class, () -> TownPopulation.createResidents(map));
        verify(map, never()).addMapObject(any());
        verify(map, never()).addPlayer(any());
    }

    @Test
    void failedRegistrationRollsBackBeforeTheNextMapEntryCanRetry() {
        MapleMap map = flatMap();
        Map<Integer, MapObject> objects = new HashMap<>();
        AtomicInteger ids = new AtomicInteger(1000);
        doAnswer(call -> {
            MapObject object = call.getArgument(0);
            object.setObjectId(ids.incrementAndGet());
            objects.put(object.getObjectId(), object);
            if (objects.size() == 3) {
                throw new IllegalStateException("registration failure");
            }
            return null;
        }).when(map).addMapObject(any());
        when(map.getMapObject(anyInt())).thenAnswer(call -> objects.get(call.getArgument(0)));
        doAnswer(call -> objects.remove(call.getArgument(0))).when(map).removeMapObject(anyInt());

        assertThrows(IllegalStateException.class, () -> TownPopulation.createResidents(map));
        assertTrue(objects.isEmpty());
        verify(map, never()).addPlayer(any());
    }

    @Test
    void twentyActorsAreSeparateFromTheRealPlayerList() {
        MapleMap map = flatMap();
        List<TownResident> residents = TownPopulation.createResidents(map);
        assertEquals(20, residents.size());
        assertEquals(20, residents.stream().map(TownResident::getId).distinct().count());
        verify(map, times(20)).addMapObject(any(TownResident.class));
        verify(map, never()).addPlayer(any());
    }

    private static TownResident named(String name) {
        TownResident resident = mock(TownResident.class);
        when(resident.getName()).thenReturn(name);
        return resident;
    }

    private static MapleMap flatMap() {
        MapleMap map = mock(MapleMap.class);
        FootholdTree tree = new FootholdTree(new Point(-1000, -1000), new Point(7000, 1000));
        tree.insert(new Foothold(new Point(0, 500), new Point(6000, 500), 1));
        when(map.getFootholds()).thenReturn(tree);
        return map;
    }
}
