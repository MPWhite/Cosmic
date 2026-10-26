package server.population;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stable identities; these are town actors, not accounts or loot-generating players. */
public final class ResidentProfiles {
    public record Profile(int id, String name, int gender, int skin, int face, int hair,
                          int job, int level, Map<Integer, Integer> equipment,
                          String role, boolean companion) {
        public Profile {
            equipment = Map.copyOf(equipment);
        }
    }

    private static final List<Profile> ALL = List.of(
            profile(1, "MapleMia", 1, 0, 21004, 31013, 200, 24, 1041004, 1061004, 1072004, 1372005, 0, "curious collector", true),
            profile(2, "PipArcher", 0, 0, 20002, 30023, 310, 32, 1040008, 1060006, 1072006, 1452001, 1002018, "careful archer", true),
            profile(3, "Juniper", 1, 3, 21002, 31027, 230, 37, 1041010, 1061005, 1072005, 1372000, 0, "quiet gardener", true),
            profile(4, "TheoBoots", 0, 1, 20000, 30007, 100, 19, 1040004, 1060012, 1072006, 1302000, 0, "restless explorer", false),
            profile(5, "PeachTea", 1, 0, 21000, 31033, 0, 12, 1041005, 1061004, 1072004, 0, 0, "tea enthusiast", false),
            profile(6, "CopperFinn", 0, 3, 20004, 30040, 110, 36, 1040003, 1060003, 1072007, 1302008, 1002014, "equipment tinkerer", false),
            profile(7, "LunaLeaf", 1, 0, 21004, 31016, 200, 21, 1041000, 1061000, 1072004, 1372006, 0, "stargazer", false),
            profile(8, "Mossy", 0, 1, 20002, 30024, 300, 16, 1040007, 1060006, 1072005, 1452000, 0, "patient naturalist", false),
            profile(9, "NoriCloud", 1, 0, 21002, 31005, 400, 28, 1041009, 1061007, 1072006, 1332006, 0, "traveling storyteller", false),
            profile(10, "Bramble", 0, 3, 20006, 30032, 0, 9, 1040014, 1060002, 1072005, 0, 1002012, "new adventurer", false),
            profile(11, "Saffron", 1, 1, 21000, 31022, 300, 23, 1041006, 1061005, 1072004, 1452002, 0, "market regular", false),
            profile(12, "OakWarden", 0, 0, 20000, 30050, 120, 43, 1040009, 1060009, 1072000, 1302007, 0, "helpful veteran", false),
            profile(13, "PetalWish", 1, 0, 21005, 31031, 200, 18, 1041004, 1061002, 1072001, 1372005, 0, "flower collector", false),
            profile(14, "BlueKite", 0, 1, 20001, 30025, 0, 14, 1040013, 1060007, 1072004, 0, 0, "daydreamer", false),
            profile(15, "HazelHop", 1, 3, 21002, 31037, 400, 26, 1041002, 1061008, 1072006, 1332000, 0, "playful scout", false),
            profile(16, "Tobin", 0, 0, 20002, 30000, 100, 22, 1040005, 1060004, 1072006, 1302006, 1002019, "mushroom hunter", false),
            profile(17, "CloverJoy", 1, 0, 21004, 31024, 0, 11, 1041010, 1061005, 1072005, 0, 0, "optimistic beginner", false),
            profile(18, "AshTrail", 0, 3, 20004, 30047, 400, 29, 1040001, 1060001, 1072007, 1332004, 0, "quiet traveler", false),
            profile(19, "RumiRain", 1, 1, 21000, 31040, 220, 35, 1041001, 1061001, 1072004, 1302017, 0, "weather watcher", false),
            profile(20, "SunnySol", 0, 0, 20006, 30063, 300, 17, 1040014, 1060002, 1072005, 1452000, 0, "friendly competitor", false)
    );

    private ResidentProfiles() {
    }

    public static List<Profile> all() {
        return ALL;
    }

    public static Optional<Profile> find(String name) {
        return ALL.stream().filter(profile -> profile.name().equalsIgnoreCase(name)).findFirst();
    }

    private static Profile profile(int id, String name, int gender, int skin, int face, int hair,
                                   int job, int level, int coat, int pants, int shoes, int weapon,
                                   int hat, String role, boolean companion) {
        var equipment = new java.util.LinkedHashMap<Integer, Integer>();
        if (hat != 0) {
            equipment.put(1, hat);
        }
        equipment.put(5, coat);
        equipment.put(6, pants);
        equipment.put(7, shoes);
        if (weapon != 0) {
            equipment.put(11, weapon);
        }
        return new Profile(id, name, gender, skin, face, hair, job, level, equipment, role, companion);
    }
}
