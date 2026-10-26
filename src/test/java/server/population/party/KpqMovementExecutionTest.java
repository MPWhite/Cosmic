package server.population.party;

import client.Character;
import client.Client;
import client.Job;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import server.population.ResidentProfiles;
import server.population.navigation.CompanionNavigation;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes movement on installed WZ geometry; checks only the NPC's public occupancy rectangles. */
class KpqMovementExecutionTest {
    private record Travel(int stage, int from, int to) { }

    @TestFactory
    Stream<DynamicTest> everyPuzzleSlotAndOrderedSlotTransitionIsExecutable() {
        List<Travel> cases = new ArrayList<>();
        for (int stage = 2; stage <= 4; stage++) {
            int slots = stage + 2;
            for (int to = 0; to < slots; to++) cases.add(new Travel(stage, -1, to));
            for (int from = 0; from < slots; from++) {
                for (int to = 0; to < slots; to++) if (from != to) cases.add(new Travel(stage, from, to));
            }
        }
        return cases.stream().map(travel -> DynamicTest.dynamicTest(
                "stage " + travel.stage + ": " + (travel.from < 0 ? "entry" : "slot " + (travel.from + 1))
                        + " -> slot " + (travel.to + 1), () -> {
                    var fixture = fixture(travel.stage);
                    try (ResidentPartyActor actor = fixture.actor) {
                        long now = 1_000;
                        if (travel.from >= 0) now = reach(fixture, travel.from, now);
                        reach(fixture, travel.to, now);
                        assertEquals(0, actor.actor().getRecoveryCount(), "puzzle movement cannot use recovery teleports");
                    }
                }));
    }

    /** Requires a full second of valid occupancy, rather than merely passing through a rectangle mid-jump. */
    private static long reach(Fixture fixture, int slot, long start) {
        Point target = fixture.puzzle.position(slot);
        Point previous = fixture.actor.character().getPosition();
        long stableSince = -1;
        for (long now = start; now <= start + 60_000; now += 50) {
            // Match the controller's repeated commands while preserving the real in-flight actor state.
            if (fixture.stage == 2) fixture.actor.holdRope(target);
            else fixture.actor.moveTo(target);
            fixture.actor.tick(now);
            Point actual = fixture.actor.character().getPosition();
            assertTrue(Math.abs(actual.x - previous.x) <= 10, "horizontal teleport: " + previous + " -> " + actual);
            assertTrue(previous.distance(actual) <= 100, "discontinuous movement: " + previous + " -> " + actual);
            previous = new Point(actual);
            boolean stable = fixture.rectangles.get(slot).contains(actual)
                    && (fixture.stage == 2 ? fixture.actor.holdingRope() : fixture.actor.actor().getFootholdId() != 0);
            if (!stable) stableSince = -1;
            else if (stableSince < 0) stableSince = now;
            else if (now - stableSince >= 1_000) {
                assertEquals(slot, fixture.puzzle.slot(actual), "controller and actual NPC rectangle must agree");
                return now + 50;
            }
        }
        fail("stage " + fixture.stage + " slot " + (slot + 1) + " failed to settle after 60 simulated seconds; actual="
                + fixture.actor.character().getPosition() + ", target=" + target + ", movement="
                + fixture.actor.actor().getNavigationStatus() + ", foothold=" + fixture.actor.actor().getFootholdId());
        return start;
    }

    private static Fixture fixture(int stage) throws Exception {
        int mapId = 103000799 + stage;
        var navigation = CompanionNavigation.forMapId(mapId);
        MapleMap map = mock(MapleMap.class, withSettings().stubOnly());
        when(map.getId()).thenReturn(mapId);
        FootholdTree ground = new FootholdTree(new Point(-5000, -5000), new Point(5000, 5000));
        for (var surface : navigation.surfaces()) {
            Foothold foothold = new Foothold(new Point(surface.x1(), surface.y1()),
                    new Point(surface.x2(), surface.y2()), surface.id());
            foothold.setPrev(surface.previous());
            foothold.setNext(surface.next());
            ground.insert(foothold);
        }
        when(map.getFootholds()).thenReturn(ground);
        Data data = DataProviderFactory.getDataProvider(WZFiles.MAP).getData("Map/Map1/" + mapId + ".img");
        Data entry = data.getChildByPath("portal").getChildren().stream()
                .filter(portal -> DataTool.getString("pn", portal, "").equals("st00")).findFirst().orElseThrow();
        Point spawn = new Point(DataTool.getInt("x", entry), DataTool.getInt("y", entry));
        Character character = mock(Character.class, withSettings().stubOnly());
        when(character.getId()).thenReturn(1_600_000_900 + stage);
        when(character.isResidentBot()).thenReturn(true);
        when(character.isAlive()).thenReturn(true);
        when(character.getMap()).thenReturn(map);
        when(character.getMapId()).thenReturn(mapId);
        when(character.getJob()).thenReturn(Job.WARRIOR);
        when(character.getClient()).thenReturn(mock(Client.class, withSettings().stubOnly()));
        when(character.getHp()).thenReturn(500);
        when(character.getCurrentMaxHp()).thenReturn(500);
        when(character.getMp()).thenReturn(100);
        when(character.getCurrentMaxMp()).thenReturn(100);
        AtomicReference<Point> position = new AtomicReference<>(spawn);
        when(character.getPosition()).thenAnswer(call -> new Point(position.get()));
        doAnswer(call -> { position.set(new Point(call.getArgument(0))); return null; }).when(character).setPosition(any(Point.class));
        return new Fixture(stage, new ResidentPartyActor(character, ResidentProfiles.all().get(15)),
                new KpqPuzzle(stage), npcRectangles(stage));
    }

    private static List<Rectangle> npcRectangles(int stage) throws Exception {
        String script = Files.readString(Path.of("scripts/npc/9020001.js"));
        var declaration = Pattern.compile("var stage" + stage + "Rects = Array\\((.*?)\\);", Pattern.DOTALL).matcher(script);
        assertTrue(declaration.find(), "actual NPC rectangle declaration must be available");
        var coordinates = Pattern.compile("new Rectangle\\((-?\\d+),\\s*(-?\\d+),\\s*(\\d+),\\s*(\\d+)\\)")
                .matcher(declaration.group(1));
        List<Rectangle> rectangles = new ArrayList<>();
        while (coordinates.find()) rectangles.add(new Rectangle(Integer.parseInt(coordinates.group(1)),
                Integer.parseInt(coordinates.group(2)), Integer.parseInt(coordinates.group(3)), Integer.parseInt(coordinates.group(4))));
        assertEquals(stage + 2, rectangles.size());
        return List.copyOf(rectangles);
    }

    private record Fixture(int stage, ResidentPartyActor actor, KpqPuzzle puzzle, List<Rectangle> rectangles) { }
}
