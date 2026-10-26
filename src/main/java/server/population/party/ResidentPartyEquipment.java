package server.population.party;

import client.Character;
import provider.Data;
import provider.DataDirectoryEntry;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Ordinary class weapons prepared before a resident joins a PQ, using unchanged base stats. */
public final class ResidentPartyEquipment {
    private static final Map<Integer, List<Weapon>> WEAPONS = new ConcurrentHashMap<>();
    private static final class Source {
        static final DataProvider DATA = DataProviderFactory.getDataProvider(WZFiles.CHARACTER);
    }
    private record Weapon(int id, int level, int jobs, int str, int dex, int intelligence, int luk, int fame, int power) {
        boolean fits(Character character) {
            int branch = character.getJob().getId() / 100;
            return branch >= 1 && branch <= 5 && (jobs == 0 || (jobs & (1 << (branch - 1))) != 0)
                    && level <= character.getLevel() && str <= character.getStr() && dex <= character.getDex()
                    && intelligence <= character.getInt() && luk <= character.getLuk() && fame <= character.getFame();
        }
    }

    private ResidentPartyEquipment() { }

    public static int weaponFor(Character character, int previousWeapon) {
        int category = previousWeapon / 10_000;
        return WEAPONS.computeIfAbsent(category, ResidentPartyEquipment::load).stream()
                .filter(weapon -> weapon.fits(character))
                .max(Comparator.comparingInt(Weapon::level).thenComparingInt(Weapon::power)
                        .thenComparingInt(weapon -> -weapon.id()))
                .orElseThrow(() -> new IllegalArgumentException("No legal standard weapon for resident category " + category)).id();
    }

    private static List<Weapon> load(int category) {
        var directory = (DataDirectoryEntry) Source.DATA.getRoot().getEntry("Weapon");
        return directory.getFiles().stream().filter(file -> file.getName().matches("[0-9]+\\.img"))
                .filter(file -> Integer.parseInt(file.getName().replace(".img", "")) / 10_000 == category)
                .map(file -> {
                    int id = Integer.parseInt(file.getName().replace(".img", ""));
                    Data info = Source.DATA.getData("Weapon/" + file.getName()).getChildByPath("info");
                    return normalWeapon(id, category, info);
                }).filter(java.util.Objects::nonNull).toList();
    }

    private static Weapon normalWeapon(int id, int category, Data info) {
        if (info == null) return null;
        int level = value(info, "reqLevel"), jobs = value(info, "reqJob");
        if (level < 1 || level > 30 || jobs < 0 || value(info, "tuc") <= 0) return null;
        // Exclude cash disguises, temporary/event reward variants and equipment
        // carrying HP/MP bonuses. Preparation changes only the normal weapon tier.
        for (String flag : List.of("cash", "tradeBlock", "only", "notSale", "timeLimited", "expireOnLogout", "incMHP", "incMMP")) {
            if (value(info, flag) != 0) return null;
        }
        return new Weapon(id, level, jobs, value(info, "reqSTR"), value(info, "reqDEX"),
                value(info, "reqINT"), value(info, "reqLUK"), value(info, "reqPOP"),
                value(info, category == 137 || category == 138 ? "incMAD" : "incPAD"));
    }

    private static int value(Data info, String key) { return DataTool.getInt(key, info, 0); }
}
