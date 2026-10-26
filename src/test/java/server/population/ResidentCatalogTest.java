package server.population;

import client.Job;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import server.population.navigation.CompanionNavigation;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResidentCatalogTest {
    private static final List<ResidentProfiles.Profile> TEN_THOUSAND = ResidentCatalog.create(10_000);

    @Test
    void populationSizesPreserveTheOriginalIdentitiesAndEveryGeneratedPrefix() {
        assertEquals(2_500, ResidentCatalog.DEFAULT_POPULATION);
        assertEquals(28, ResidentCatalog.pinned().size());
        assertEquals(ResidentProfiles.all(), ResidentCatalog.pinned().subList(0, 20));
        assertEquals(ResidentRoster.forMap(ResidentRoster.TRAINING_GROUND).stream()
                .map(ResidentRoster.Placement::profile).toList(), ResidentCatalog.pinned().subList(20, 26));
        assertEquals(ResidentRoster.forMap(ResidentRoster.PIG_FIELD).stream()
                .map(ResidentRoster.Placement::profile).toList(), ResidentCatalog.pinned().subList(26, 28));
        for (int count : new int[]{28, 100, 1_000, 2_500, 10_000}) {
            assertEquals(TEN_THOUSAND.subList(0, count), ResidentCatalog.create(count));
        }
        assertThrows(IllegalArgumentException.class, () -> ResidentCatalog.create(27));
        assertThrows(IllegalArgumentException.class, () -> ResidentCatalog.create(10_001));
        assertThrows(UnsupportedOperationException.class, () -> ResidentCatalog.create(100).clear());
    }

    @Test
    void tenThousandIdentitiesHaveUniqueValidNamesAndDistinctVisibleAppearances() {
        var names = new HashSet<String>();
        var appearances = new HashSet<List<Object>>();
        var jobs = new HashSet<Integer>();
        for (int index = 0; index < TEN_THOUSAND.size(); index++) {
            var profile = TEN_THOUSAND.get(index);
            assertEquals(index + 1, profile.id());
            assertTrue(profile.name().matches("[A-Za-z0-9]{4,12}"), profile.name());
            assertTrue(names.add(profile.name().toLowerCase(Locale.ROOT)), profile.name());
            assertTrue(appearances.add(List.of(profile.gender(), profile.skin(), profile.face(), profile.hair(),
                    profile.equipment())), "Duplicate visible appearance: " + profile.name());
            jobs.add(profile.job());
            if (profile.id() > 28) {
                assertFalse(profile.companion(), "Only the three established companions promise to follow");
            }
        }
        assertEquals(10_000, names.size());
        assertTrue(jobs.size() >= 20, "The world should include beginners and multiple job advancements");
    }

    @Test
    void defaultIndexesResolveOriginalAndGeneratedNamesWithoutCaseSensitivity() {
        assertEquals(ResidentCatalog.configuredPopulation(), ResidentCatalog.all().size());
        assertEquals(ResidentCatalog.all().size() - 28, ResidentCatalog.generated().size());
        for (var profile : ResidentCatalog.all()) {
            assertSame(profile, ResidentCatalog.byId(profile.id()).orElseThrow());
            assertSame(profile, ResidentCatalog.find(profile.name().toUpperCase(Locale.ROOT)).orElseThrow());
        }
        assertTrue(ResidentCatalog.find(null).isEmpty());
        assertTrue(ResidentCatalog.find("definitely_not_a_resident").isEmpty());
        assertTrue(ResidentCatalog.byId(0).isEmpty());
        assertTrue(ResidentCatalog.byId(10_001).isEmpty());
    }

    @Test
    void generatedAppearanceAssetsExistAndEquipmentMatchesGenderLevelAndJob() throws Exception {
        Map<Integer, Map<String, String>> equipmentInfo = new HashMap<>();
        Set<Path> checkedAssets = new HashSet<>();
        for (var profile : TEN_THOUSAND.subList(28, TEN_THOUSAND.size())) {
            assertNotNull(Job.getById(profile.job()), "Unknown job " + profile.job());
            assertEquals(profile.gender(), profile.face() / 1000 - 20);
            assertEquals(profile.gender(), profile.hair() / 1000 - 30);
            for (Path path : List.of(appearance("Hair", profile.hair()), appearance("Face", profile.face()),
                    Path.of("wz", "Character.wz", String.format("%08d.img.xml", 2000 + profile.skin())))) {
                if (checkedAssets.add(path)) assertTrue(Files.isRegularFile(path), path.toString());
            }
            int jobMask = profile.job() == 0 ? 0 : 1 << (profile.job() / 100 - 1);
            for (int item : profile.equipment().values()) {
                Map<String, String> info = equipmentInfo.get(item);
                if (info == null) {
                    Path path = appearance(category(item), item);
                    assertTrue(Files.isRegularFile(path), path.toString());
                    info = values(child(xml(path), "info"));
                    equipmentInfo.put(item, info);
                }
                int gender = item / 1000 % 10;
                assertTrue(gender == 2 || gender == profile.gender(), "Wrong gender item " + item);
                assertTrue(integer(info, "reqLevel", 0) <= profile.level(), "Overlevel item " + item);
                int mask = integer(info, "reqJob", 0);
                assertTrue(mask == 0 || (mask & jobMask) != 0, "Wrong job item " + item);
                assertEquals(0, integer(info, "cash", 0), "Catalog should use ordinary game equipment");
            }
            int weapon = profile.equipment().get(11) / 10000;
            assertTrue(switch (profile.job() / 100) {
                case 0 -> weapon == 130;
                case 1 -> Set.of(130, 132, 143).contains(weapon);
                case 2 -> weapon == 137 || weapon == 138;
                case 3 -> weapon == 145 || weapon == 146;
                case 4 -> weapon == 133;
                default -> false;
            }, "Class and weapon must agree for " + profile.name());
            assertEquals(profile.equipment().get(5) / 10000 != 105, profile.equipment().containsKey(6));
        }
        assertTrue(equipmentInfo.size() >= 100, "Avoid recoloring a handful of starter outfits");
    }

    @Test
    void curatedMapsArePublicWalkingMapsWithSpawnPortalsAndNoBossOrEnvironmentalHazards() throws Exception {
        Set<Integer> ids = new HashSet<>();
        Set<String> regions = new HashSet<>();
        Map<Integer, Boolean> bosses = new HashMap<>();
        for (var spec : ResidentCatalog.maps()) {
            assertTrue(ids.add(spec.id()));
            regions.add(spec.region());
            Path path = Path.of("wz", "Map.wz", "Map", "Map" + spec.id() / 100_000_000,
                    String.format("%09d.img.xml", spec.id()));
            Element map = xml(path);
            Map<String, String> info = values(child(map, "info"));
            assertEquals(spec.town() ? 1 : 0, integer(info, "town", 0), path.toString());
            assertEquals(spec.town() ? 48 : 16, spec.capacity());
            for (String flag : List.of("swim", "fly", "fieldType", "fieldLimit", "timeLimit", "decHP")) {
                assertEquals(0, integer(info, flag, 0), spec.id() + " has unsupported " + flag);
            }
            assertEquals(999_999_999, integer(info, "forcedReturn", 999_999_999));
            assertTrue(info.getOrDefault("onFirstUserEnter", "").isEmpty());
            assertTrue(Set.of("", "explorationPoint").contains(info.getOrDefault("onUserEnter", "")));
            assertFalse(info.containsKey("timeMob"), "Do not populate timed world boss maps");
            assertNotNull(child(map, "foothold"), path.toString());
            Element portals = child(map, "portal");
            assertNotNull(portals, path.toString());
            boolean spawn = false;
            for (Node node = portals.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element portal) {
                    Map<String, String> data = values(portal);
                    if ("sp".equals(data.get("pn")) && integer(data, "pt", -1) == 0) spawn = true;
                }
            }
            assertTrue(spawn, "No ordinary entry spawn for " + spec.id());
            Element life = child(map, "life");
            if (life == null) continue;
            for (Node node = life.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element entry)) continue;
                Map<String, String> data = values(entry);
                if (!"m".equals(data.get("type"))) continue;
                int id = Integer.parseInt(data.get("id"));
                if (!bosses.containsKey(id)) {
                    var mob = xml(Path.of("wz", "Mob.wz", String.format("%07d.img.xml", id)));
                    bosses.put(id, integer(values(child(mob, "info")), "boss", 0) != 0);
                }
                assertFalse(bosses.get(id), "Boss " + id + " in catalog map " + spec.id());
            }
        }
        assertTrue(ids.size() >= 50);
        assertTrue(regions.size() >= 10);
        assertTrue(ids.containsAll(Set.of(100000000, 100010000, 100020000)));
    }

    @Test
    void everyCatalogMapBuildsItsActualNavigationAndSupportsGroundedResidentSpawns() throws Exception {
        for (var spec : ResidentCatalog.maps()) {
            CompanionNavigation navigation = assertDoesNotThrow(() -> CompanionNavigation.forMapId(spec.id()),
                    "Navigation cannot build for catalog map " + spec.id());
            Path path = Path.of("wz", "Map.wz", "Map", "Map" + spec.id() / 100_000_000,
                    String.format("%09d.img.xml", spec.id()));
            Element portals = child(xml(path), "portal");
            var anchors = new ArrayList<Point>();
            for (Node node = portals.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element portal) {
                    Map<String, String> data = values(portal);
                    if (integer(data, "pt", -1) == 0) {
                        anchors.add(new Point(integer(data, "x", 0), integer(data, "y", 0)));
                    }
                }
            }
            assertFalse(anchors.isEmpty(), "No spawn anchors for " + spec.id());
            for (int identity = 29; identity < 29 + spec.capacity(); identity++) {
                assertGroundedSpawn(navigation, anchors, identity, spec.id());
            }
            assertGroundedSpawn(navigation, anchors, 10_000, spec.id());
            assertGroundedSpawn(navigation, List.of(), 10_000, spec.id());
        }
    }

    private static void assertGroundedSpawn(CompanionNavigation navigation, List<Point> anchors,
                                           int identity, int mapId) {
        Point spawn = assertDoesNotThrow(() -> ResidentSpawns.choose(navigation, anchors, identity),
                "Could not place resident " + identity + " in " + mapId);
        var ground = navigation.project(spawn, 2);
        assertTrue(ground.isPresent(), "Ungrounded resident spawn " + spawn + " in " + mapId);
        assertTrue(ground.orElseThrow().position().distance(spawn) <= 2);
        assertEquals(spawn, ResidentSpawns.choose(navigation, anchors, identity), "Spawn selection must be stable");
    }

    private static int integer(Map<String, String> values, String name, int fallback) {
        return Integer.parseInt(values.getOrDefault(name, Integer.toString(fallback)));
    }

    private static String category(int item) {
        return switch (item / 10000) {
            case 100 -> "Cap";
            case 104 -> "Coat";
            case 105 -> "Longcoat";
            case 106 -> "Pants";
            case 107 -> "Shoes";
            default -> "Weapon";
        };
    }

    private static Path appearance(String category, int id) {
        return Path.of("wz", "Character.wz", category, String.format("%08d.img.xml", id));
    }

    private static Element xml(Path path) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getAttribute("name"))) return element;
        }
        return null;
    }

    private static Map<String, String> values(Element parent) {
        assertNotNull(parent);
        var result = new HashMap<String, String>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) result.put(element.getAttribute("name"), element.getAttribute("value"));
        }
        return result;
    }
}
