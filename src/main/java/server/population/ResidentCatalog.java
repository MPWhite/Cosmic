package server.population;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** Stable world identities and a reviewed public-map whitelist; creates no accounts or map objects. */
public final class ResidentCatalog {
    public static final int DEFAULT_POPULATION = 2_500;
    public static final int MIN_POPULATION = 28;
    public static final int MAX_POPULATION = 10_000;

    /** Capacity is the per-map foreground limit, not a limit on background world membership. */
    public record MapSpec(int id, String region, boolean town, int capacity) {
        public int weight() { return town ? 4 : 1; }
    }

    private record Gear(int id, int level, int jobMask) {
        int gender() { return id / 1000 % 10; }
    }
    private record Appearance(int gender, int skin, int face, int hair, Map<Integer, Integer> equipment) { }

    private static final String[] PREFIXES = "Amber Ash Azure Bad Blue Bold Bored Brave Bronze Calm Cedar Choco Cloud Cobalt Cold Copper Cosy Crim Crook Clear Dapper Dark Dawn Dazed Deep Dusk Dusty Echo Ember Faded Faint Fern Fiery Fizzy Frost Fuzzy Gold Grey Grim Hazel Honey Icy Jade Jolly Lazy Lilac Lime Lost Lucky Lunar Maple Merry Mild Mint Mist Moss Mucky Navy Neon Night Nori Oak Odd Olive Pale Peach Petal Pink Plum Polar Quiet Rain Rapid Red Ripe Rose Rude Rust Sage Salty Sharp Shy Silent Silver Sleep Slow Small Smoky Snow Soft Solar Sour Spicy Star Steel Storm Sunny Swift Tea Tiny".split(" ");
    private static final String[] SUFFIXES = "Rune Koi Bow Mage Sword Staff Arrow Fang Fox Wolf Hare Bear Lynx Owl Crow Wisp Leaf Moss Fern Reed Thorn Bloom Petal Root Seed Bark Pine Oak Ash Birch Maple Cedar Wood Hill Vale Glen Brook Pond Lake River Cove Bay Reef Sand Stone Rock Flint Slate Clay Snow Frost Rain Mist Cloud Storm Wind Dawn Dusk Moon Sun Star Nova Comet Spark Ember Flare Flame Smoke Coal Steel Gold Silver Iron Pearl Jade Opal Ruby Onyx Quartz Gem Shell Scale Fin Wing Claw Paw Tail Horn Moth Bee Slime Snail Shroom Pig Rope Pots Coin Meso Loot Noob".split(" ");
    private static final int[] MALE_HAIR = {30000, 30037, 30067, 30117, 30147, 30177, 30207, 30237, 30267, 30297, 30327, 30357, 30407, 30437, 30467, 30497, 30537, 30567, 30597, 30627, 30657, 30687, 30717, 30747, 30777, 30807, 30837, 30867, 30897, 30927};
    private static final int[] FEMALE_HAIR = {31000, 31030, 31060, 31090, 31120, 31150, 31180, 31210, 31240, 31270, 31300, 31330, 31400, 31430, 31460, 31490, 31530, 31560, 31590, 31620, 31650, 31680, 31710, 31740, 31770, 31800, 31830, 31860, 31890, 31930};
    private static final int[] MALE_FACE = {20000, 20017, 20102, 20119, 20204, 20221, 20306, 20323, 20408, 20425, 20510, 20527, 20612, 20629, 20714, 20732};
    private static final int[] FEMALE_FACE = {21000, 21017, 21103, 21120, 21206, 21223, 21309, 21326, 21412, 21430, 21516, 21602, 21619, 21705, 21722, 21808};
    private static final int[] JOBS = {110, 120, 130, 210, 220, 230, 310, 320, 420};
    private static final int[] WEAPON_TYPES = {130, 132, 143, 137, 138, 137, 145, 146, 133};
    private static final String[] ROLES = {"casual grinder", "map regular", "quiet explorer", "equipment enthusiast",
            "social adventurer", "patient hunter", "restless traveler", "salty veteran"};

    // Local v83 assets, with their actual minimum level and job masks. No WZ IO on the hot path.
    private static final Gear[] HATS = gear("1000025:0:0 1002001:15:0 1002002:10:1 1002004:35:1 1002005:25:1 1002006:42:0 1002008:5:0 1002010:10:4 1002012:20:0 "
            + "1002013:30:2 1002016:20:2 1002017:10:2 1002021:40:1 1002022:55:1 1002029:60:1 1002030:70:1 1002107:15:8 1002117:20:4 1002127:20:8 "
            + "1002135:35:4 1002151:40:2 1002166:40:4 1002171:30:8 1002181:40:8 1002207:50:8 1002211:50:4 1002215:50:2 1002242:60:2 1002247:60:8 "
            + "1002252:70:2 1002267:60:4 1002281:70:8 1002286:70:4 1002357:50:0 1002430:60:0 1002436:35:0 1002511:70:0");
    private static final Gear[] TOPS = gear("1040000:40:1 1040002:0:0 1040003:20:4 1040004:8:2 1040007:15:4 1040009:15:1 1040012:20:1 1040013:11:0 1040018:18:2 1040021:30:1 "
            + "1040031:10:8 1040042:20:8 1040057:30:8 1040067:30:4 1040079:40:4 1040082:40:8 1040087:50:1 1040090:60:1 1040094:50:8 1040098:60:8 "
            + "1040102:70:1 1040105:70:8 1041002:0:0 1041003:20:8 1041004:11:0 1041007:10:4 1041013:20:4 1041014:10:1 1041015:8:2 1041017:13:2 "
            + "1041019:20:1 1041036:10:8 1041041:28:2 1041047:30:8 1041051:33:2 1041054:30:4 1041074:40:8 1041077:50:8 1041081:40:4 1041084:40:1 "
            + "1041087:50:1 1041091:60:1 1041094:60:8 1041097:70:1 1041100:70:8 1041148:35:1 1042003:0:0 1050000:35:1 1050001:23:2 1050002:33:2 "
            + "1050005:20:1 1050008:13:2 1050018:30:0 1050045:48:2 1050051:50:4 1050053:58:2 1050058:60:4 1050061:70:4 1050067:68:2 1050100:20:0 "
            + "1050131:0:0 1051000:15:1 1051001:35:1 1051003:23:2 1051006:35:8 1051017:30:0 1051023:38:2 1051030:48:2 1051037:50:4 1051041:60:4 "
            + "1051044:58:2 1051052:68:2 1051062:70:4 1051098:20:0 1051150:0:0 1052081:0:0 1052165:10:0 1052166:30:0 1052167:50:0");
    private static final Gear[] PANTS = gear("1060000:40:1 1060002:0:0 1060004:11:0 1060005:26:0 1060008:10:1 1060010:20:1 1060012:8:2 1060014:18:2 1060016:30:1 1060021:10:8 "
            + "1060031:20:8 1060043:30:8 1060056:30:4 1060068:40:4 1060071:40:8 1060076:50:1 1060079:60:1 1060083:50:8 1060087:60:8 1060090:70:1 "
            + "1060093:70:8 1060128:30:0 1060129:40:0 1060130:60:0 1060132:70:0 1061002:0:0 1061003:20:8 1061006:15:4 1061010:8:2 1061012:13:2 "
            + "1061014:10:1 1061015:25:1 1061024:20:4 1061029:10:8 1061034:28:2 1061043:30:8 1061047:33:2 1061050:30:4 1061069:40:8 1061076:50:8 "
            + "1061080:40:4 1061083:40:1 1061086:50:1 1061090:60:1 1061093:60:8 1061096:70:1 1061099:70:8 1061149:20:0 1061150:30:0 1061151:40:0 "
            + "1061152:60:0 1061154:70:0 1061156:35:1 1062000:16:0 1062002:20:4 1062004:10:4 1062007:0:0");
    private static final Gear[] SHOES = gear("1072000:40:1 1072001:0:0 1072002:35:1 1072004:11:0 1072006:10:2 1072007:20:1 1072009:15:1 1072012:26:0 1072015:10:4 1072018:31:0 "
            + "1072019:20:2 1072022:20:8 1072025:20:4 1072028:15:8 1072032:30:8 1072075:30:2 1072079:30:4 1072107:40:8 1072114:40:2 1072118:40:4 "
            + "1072122:50:4 1072128:50:8 1072132:50:1 1072136:60:2 1072140:50:2 1072144:60:4 1072147:60:1 1072150:60:8 1072154:70:1 1072157:70:2 "
            + "1072161:70:8 1072164:70:4 1072239:50:0 1072376:68:0");
    private static final Gear[] WEAPONS = gear("1302000:0:0 1302001:10:8 1302002:20:1 1302004:35:1 1302009:40:1 1302010:50:1 1302011:60:1 1302012:70:1 1322000:15:3 1322002:20:3 "
            + "1322003:30:0 1322005:0:0 1322016:40:1 1322017:50:1 1322018:60:1 1322019:70:1 1332000:12:8 1332001:32:8 1332003:50:8 1332004:27:8 "
            + "1332005:5:0 1332011:40:8 1332015:60:8 1332018:70:8 1372000:38:2 1372002:18:2 1372003:28:2 1372005:8:0 1372007:48:2 1372008:60:2 "
            + "1372011:50:2 1372035:70:2 1382000:10:2 1382001:45:2 1382002:25:2 1382006:55:2 1382007:65:2 1382009:35:2 1382010:75:2 1432000:10:13 "
            + "1432002:30:1 1432004:50:1 1432005:40:1 1432006:60:1 1432007:70:1 1432008:20:0 1452000:25:4 1452002:10:4 1452004:60:4 1452005:30:4 "
            + "1452007:40:4 1452008:50:4 1452009:70:4 1462000:28:4 1462001:12:4 1462004:32:4 1462006:42:4 1462007:50:4 1462008:60:4 1462009:70:4 "
            + "1322006:10:0");

    private static final List<MapSpec> MAPS = List.of(
            new MapSpec(100000000, "Victoria", true, 48),
            new MapSpec(100000100, "Victoria", true, 48),
            new MapSpec(100010000, "Victoria", false, 16),
            new MapSpec(100020000, "Victoria", false, 16),
            new MapSpec(100030000, "Victoria", false, 16),
            new MapSpec(100040000, "Victoria", false, 16),
            new MapSpec(100050000, "Victoria", false, 16),
            new MapSpec(101010000, "Victoria", false, 16),
            new MapSpec(101010100, "Victoria", false, 16),
            new MapSpec(101030000, "Victoria", false, 16),
            new MapSpec(101040000, "Victoria", false, 16),
            new MapSpec(102000000, "Victoria", true, 48),
            new MapSpec(102010000, "Victoria", false, 16),
            new MapSpec(102020000, "Victoria", false, 16),
            new MapSpec(102020100, "Victoria", false, 16),
            new MapSpec(102030000, "Victoria", false, 16),
            new MapSpec(102040000, "Victoria", false, 16),
            new MapSpec(103000000, "Victoria", true, 48),
            new MapSpec(103010000, "Victoria", false, 16),
            new MapSpec(103020000, "Victoria", false, 16),
            new MapSpec(103020100, "Victoria", false, 16),
            new MapSpec(103030000, "Victoria", false, 16),
            new MapSpec(104000000, "Victoria", true, 48),
            new MapSpec(104000100, "Victoria", false, 16),
            new MapSpec(104000200, "Victoria", false, 16),
            new MapSpec(104010000, "Victoria", false, 16),
            new MapSpec(104020000, "Victoria", false, 16),
            new MapSpec(104040000, "Victoria", false, 16),
            new MapSpec(105030000, "Victoria", false, 16),
            new MapSpec(105040000, "Victoria", false, 16),
            new MapSpec(105040300, "Victoria", true, 48),
            new MapSpec(105050000, "Victoria", false, 16),
            new MapSpec(105050100, "Victoria", false, 16),
            new MapSpec(200000000, "Orbis", true, 48),
            new MapSpec(200000200, "Orbis", false, 16),
            new MapSpec(200010000, "Orbis", false, 16),
            new MapSpec(200020000, "Orbis", false, 16),
            new MapSpec(200040000, "Orbis", false, 16),
            new MapSpec(211000000, "El Nath", true, 48),
            new MapSpec(211000100, "El Nath", true, 48),
            new MapSpec(220000000, "Ludibrium", true, 48),
            new MapSpec(220000300, "Ludibrium", true, 48),
            new MapSpec(220010000, "Ludibrium", false, 16),
            new MapSpec(220010100, "Ludibrium", false, 16),
            new MapSpec(220010400, "Ludibrium", false, 16),
            new MapSpec(220020000, "Ludibrium", false, 16),
            new MapSpec(220020100, "Ludibrium", false, 16),
            new MapSpec(222000000, "Korean Folk Town", true, 48),
            new MapSpec(222010000, "Korean Folk Town", false, 16),
            new MapSpec(222010100, "Korean Folk Town", false, 16),
            new MapSpec(240000000, "Leafre", true, 48),
            new MapSpec(250000000, "Mu Lung", true, 48),
            new MapSpec(250000100, "Mu Lung", true, 48),
            new MapSpec(250010000, "Mu Lung", false, 16),
            new MapSpec(250010100, "Mu Lung", false, 16),
            new MapSpec(251000000, "Herb Town", true, 48),
            new MapSpec(251000100, "Herb Town", false, 16),
            new MapSpec(251010100, "Herb Town", false, 16),
            new MapSpec(260000000, "Ariant", true, 48),
            new MapSpec(260000200, "Ariant", true, 48),
            new MapSpec(260010000, "Ariant", false, 16),
            new MapSpec(260010100, "Ariant", false, 16),
            new MapSpec(260020500, "Ariant", false, 16),
            new MapSpec(260020600, "Ariant", false, 16),
            new MapSpec(261000000, "Magatia", true, 48),
            new MapSpec(261020100, "Magatia", false, 16)
    );

    private ResidentCatalog() { }

    private static final class Holder {
        static final List<ResidentProfiles.Profile> ALL = create(configuredPopulation());
        static final Map<String, ResidentProfiles.Profile> NAMES = nameIndex(ALL);
        static final Map<Integer, ResidentProfiles.Profile> IDS = idIndex(ALL);
    }

    public static List<ResidentProfiles.Profile> all() { return Holder.ALL; }
    public static List<ResidentProfiles.Profile> generated() { return Holder.ALL.subList(MIN_POPULATION, Holder.ALL.size()); }
    public static List<MapSpec> maps() { return MAPS; }
    public static Optional<ResidentProfiles.Profile> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(Holder.NAMES.get(name.toLowerCase(Locale.ROOT)));
    }
    public static Optional<ResidentProfiles.Profile> byId(int id) { return Optional.ofNullable(Holder.IDS.get(id)); }

    /** These existing identities and appearances stay unchanged across every population size. */
    public static List<ResidentProfiles.Profile> pinned() {
        var profiles = new ArrayList<>(ResidentProfiles.all());
        for (int map : new int[]{ResidentRoster.TRAINING_GROUND, ResidentRoster.PIG_FIELD}) {
            ResidentRoster.forMap(map).forEach(placement -> profiles.add(placement.profile()));
        }
        return List.copyOf(profiles);
    }

    public static int configuredPopulation() {
        try {
            return Math.max(MIN_POPULATION, Math.min(MAX_POPULATION,
                    Integer.parseInt(System.getProperty("cosmic.residents.population", "2500"))));
        } catch (NumberFormatException ignored) {
            return DEFAULT_POPULATION;
        }
    }

    /** Deterministic prefixes make changes in configured population preserve every existing identity. */
    public static List<ResidentProfiles.Profile> create(int count) {
        if (count < MIN_POPULATION || count > MAX_POPULATION) {
            throw new IllegalArgumentException("Population must be between 28 and 10000");
        }
        var result = new ArrayList<>(pinned());
        Set<String> names = new HashSet<>();
        Set<Appearance> appearances = new HashSet<>();
        result.forEach(profile -> {
            names.add(profile.name().toLowerCase(Locale.ROOT));
            appearances.add(appearance(profile));
        });
        for (int candidate = 0; result.size() < count; candidate++) {
            if (candidate >= PREFIXES.length * SUFFIXES.length) {
                throw new IllegalStateException("Resident name catalog exhausted");
            }
            String name = name(candidate);
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            int id = result.size() + 1;
            ResidentProfiles.Profile profile;
            int variation = 0;
            do {
                profile = profile(id, name, variation++);
            } while (!appearances.add(appearance(profile)));
            result.add(profile);
        }
        return List.copyOf(result);
    }

    private static String name(int candidate) {
        int index = (candidate * 7919 + 317) % (PREFIXES.length * SUFFIXES.length);
        String base = PREFIXES[index % PREFIXES.length] + SUFFIXES[index / PREFIXES.length];
        return switch (index % 8) {
            case 0 -> base.toLowerCase(Locale.ROOT);
            case 1 -> base.length() <= 10 ? "x" + base + "x" : base;
            case 2 -> base.length() <= 11 ? base + "7" : base;
            case 3 -> base.length() <= 8 ? "o0" + base + "0o" : base;
            default -> base;
        };
    }

    private static ResidentProfiles.Profile profile(int id, String name, int variation) {
        Random random = new Random(0x6D61706C654C6966L ^ id * 104729L ^ variation * 1_000_003L);
        int gender = random.nextInt(2);
        int band = random.nextInt(100);
        int level = band < 8 ? 5 + random.nextInt(5) : band < 40 ? 10 + random.nextInt(20)
                : band < 78 ? 30 + random.nextInt(40) : 70 + random.nextInt(31);
        int archetype = random.nextInt(JOBS.length);
        int job = level < 10 ? 0 : level < 30 ? JOBS[archetype] / 100 * 100
                : level < 70 ? JOBS[archetype] : JOBS[archetype] + 1;
        int mask = job == 0 ? 0 : 1 << (job / 100 - 1);
        int weaponType = job == 0 ? 130 : WEAPON_TYPES[archetype];
        if (weaponType == 146 && level < 12) {
            weaponType = 145;
        }
        Map<Integer, Integer> equipment = new LinkedHashMap<>();
        if (random.nextInt(4) != 0) equipment.put(1, choose(HATS, gender, mask, level, 0, random));
        int top = choose(TOPS, gender, mask, level, 0, random);
        equipment.put(5, top);
        if (top / 10000 != 105) equipment.put(6, choose(PANTS, gender, mask, level, 0, random));
        equipment.put(7, choose(SHOES, gender, mask, level, 0, random));
        equipment.put(11, choose(WEAPONS, gender, mask, level, weaponType, random));
        int[] hair = gender == 0 ? MALE_HAIR : FEMALE_HAIR;
        int[] face = gender == 0 ? MALE_FACE : FEMALE_FACE;
        return new ResidentProfiles.Profile(id, name, gender, random.nextInt(6),
                face[random.nextInt(face.length)], hair[random.nextInt(hair.length)], job, level,
                equipment, ROLES[random.nextInt(ROLES.length)], false);
    }

    private static int choose(Gear[] pool, int gender, int mask, int level, int weaponType, Random random) {
        List<Gear> eligible = new ArrayList<>();
        int highest = 0;
        for (Gear item : pool) {
            if (item.level <= level && (item.gender() == 2 || item.gender() == gender)
                    && (item.jobMask == 0 || (item.jobMask & mask) != 0)
                    && (weaponType == 0 || item.id / 10000 == weaponType)) {
                eligible.add(item);
                highest = Math.max(highest, item.level);
            }
        }
        int floor = Math.max(0, highest - 15);
        eligible.removeIf(item -> item.level < floor);
        if (eligible.isEmpty()) throw new IllegalStateException("No compatible resident equipment");
        return eligible.get(random.nextInt(eligible.size())).id;
    }

    private static Gear[] gear(String encoded) {
        String[] entries = encoded.split(" ");
        Gear[] result = new Gear[entries.length];
        for (int i = 0; i < entries.length; i++) {
            String[] parts = entries[i].split(":");
            result[i] = new Gear(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        return result;
    }

    private static Appearance appearance(ResidentProfiles.Profile profile) {
        return new Appearance(profile.gender(), profile.skin(), profile.face(), profile.hair(), profile.equipment());
    }

    private static Map<String, ResidentProfiles.Profile> nameIndex(List<ResidentProfiles.Profile> profiles) {
        Map<String, ResidentProfiles.Profile> result = new HashMap<>();
        profiles.forEach(profile -> result.put(profile.name().toLowerCase(Locale.ROOT), profile));
        return Map.copyOf(result);
    }

    private static Map<Integer, ResidentProfiles.Profile> idIndex(List<ResidentProfiles.Profile> profiles) {
        Map<Integer, ResidentProfiles.Profile> result = new HashMap<>();
        profiles.forEach(profile -> result.put(profile.id(), profile));
        return Map.copyOf(result);
    }
}
