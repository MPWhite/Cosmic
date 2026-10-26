package server.population;

import org.junit.jupiter.api.Test;
import server.population.navigation.CompanionNavigation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class ResidentRosterTest {
    @Test
    void trainingFieldsHaveDistinctIdentitiesValidAssetsAndRealGround() {
        var ids = new HashSet<Integer>();
        var names = new HashSet<String>();
        ResidentProfiles.all().forEach(p -> { ids.add(p.id()); names.add(p.name()); });
        int hunters = 0;
        for (int map : new int[]{ResidentRoster.TRAINING_GROUND, ResidentRoster.PIG_FIELD}) {
            for (var placement : ResidentRoster.forMap(map)) {
                var profile = placement.profile();
                assertTrue(ids.add(profile.id()));
                assertTrue(names.add(profile.name()));
                assertTrue(profile.name().matches("[A-Za-z0-9]{4,12}"));
                assertTrue(Files.exists(asset("Hair", profile.hair())));
                assertTrue(Files.exists(asset("Face", profile.face())));
                profile.equipment().forEach((slot, item) -> {
                    String type = switch (slot) {
                        case 5 -> "Coat";
                        case 6 -> "Pants";
                        case 7 -> "Shoes";
                        case 11 -> "Weapon";
                        default -> throw new AssertionError("Unvalidated equipment slot");
                    };
                    assertTrue(Files.exists(asset(type, item)), "Missing " + item);
                });
                var ground = CompanionNavigation.forMapId(map).project(placement.position(), 8);
                assertTrue(ground.isPresent(), "Hunter must start on a real platform: " + profile.name());
                hunters++;
            }
        }
        assertEquals(8, hunters);
        assertEquals(28, ids.size());
    }

    private static Path asset(String type, int id) {
        return Path.of("wz", "Character.wz", type, String.format("%08d.img.xml", id));
    }
}
