package server.population;

import java.awt.Point;
import java.util.List;
import java.util.Map;

/** Permanent identities for the public training fields, separate from the town roster. */
final class ResidentRoster {
    static final int TRAINING_GROUND = 100010000;
    static final int PIG_FIELD = 100020000;

    record Placement(ResidentProfiles.Profile profile, int mapId, Point position) {
        Placement {
            position = new Point(position);
        }

        @Override
        public Point position() {
            return new Point(position);
        }
    }

    private static final List<Placement> HUNTERS = List.of(
            hunter(21, "o0Lime0o", 0, 20002, 30023, 0, 8, 1302000, TRAINING_GROUND, -260, 455),
            hunter(22, "sleepybow", 1, 21004, 31024, 300, 15, 1452000, TRAINING_GROUND, 430, 455),
            hunter(23, "MushRoomie", 0, 20000, 30007, 100, 17, 1302006, TRAINING_GROUND, 1240, 455),
            hunter(24, "blinktwice", 1, 21002, 31016, 200, 16, 1372005, TRAINING_GROUND, -210, -55),
            hunter(25, "DaggerDays", 0, 20004, 30047, 400, 21, 1332004, TRAINING_GROUND, 650, -235),
            hunter(26, "KitKatx", 1, 21000, 31033, 200, 18, 1372005, TRAINING_GROUND, 1420, -55),
            hunter(27, "xBowTheory", 0, 20002, 30024, 300, 23, 1452002, PIG_FIELD, -100, 215),
            hunter(28, "Nooblet7", 0, 20006, 30032, 100, 19, 1302006, PIG_FIELD, 930, 275)
    );

    private ResidentRoster() { }

    static boolean isPopulated(int mapId) {
        return mapId == TownPopulation.HENESYS || mapId == TRAINING_GROUND || mapId == PIG_FIELD;
    }

    static List<Placement> forMap(int mapId) {
        return HUNTERS.stream().filter(p -> p.mapId == mapId).toList();
    }

    private static Placement hunter(int id, String name, int gender, int face, int hair, int job, int level,
                                    int weapon, int mapId, int x, int y) {
        Map<Integer, Integer> equipment = gender == 0
                ? Map.of(5, 1040004, 6, 1060012, 7, 1072006, 11, weapon)
                : Map.of(5, 1041004, 6, 1061004, 7, 1072004, 11, weapon);
        return new Placement(new ResidentProfiles.Profile(id, name, gender, id % 3, face, hair, job, level,
                equipment, "casual hunter", false), mapId, new Point(x, y));
    }
}
