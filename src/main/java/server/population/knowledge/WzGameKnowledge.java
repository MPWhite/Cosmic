package server.population.knowledge;

import provider.Data;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Loaded only by knowledge workers. Uses the installed server's scripts, WZ and positive-chance drop rows. */
public final class WzGameKnowledge implements GameKnowledge.Source {
    private record CachedDrops(long expires, List<GameKnowledge.Drop> drops) { }
    private final Map<Integer, CachedDrops> dropCache = new LinkedHashMap<>(32, .75f, true);
    private volatile List<GameKnowledge.Item> items;
    private volatile Map<Integer, String> monsterNames;
    private volatile Map<Integer, List<String>> monsterMaps;
    private volatile List<GameKnowledge.Skill> skills;
    private volatile List<GameKnowledge.QuestFact> quests;
    private static final Pattern LIFE_ENTRY = Pattern.compile("<imgdir name=\"[^\"]+\">(.*?)</imgdir>", Pattern.DOTALL);
    private static final Pattern MONSTER_ID = Pattern.compile("<string name=\"id\" value=\"(\\d+)\"");

    @Override
    public Optional<GameKnowledge.KpqRules> kpqRules() {
        try {
            String script = Files.readString(Path.of("scripts/event/KerningPQ.js"));
            return Optional.of(new GameKnowledge.KpqRules(rule(script, "minPlayers"), rule(script, "maxPlayers"),
                    rule(script, "minLevel"), rule(script, "maxLevel"), rule(script, "eventTime")));
        } catch (IOException | IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private static int rule(String script, String name) {
        var matcher = Pattern.compile("\\b" + name + "\\s*=\\s*(\\+?\\d+)\\b").matcher(script);
        if (!matcher.find()) throw new IllegalArgumentException("Missing KPQ rule");
        int value = Integer.parseInt(matcher.group(1));
        if (value < 1 || value > 255) throw new IllegalArgumentException("Invalid KPQ rule");
        return value;
    }

    @Override
    public List<GameKnowledge.Item> findItems(String query) {
        List<GameKnowledge.Item> all = items;
        if (all == null) {
            synchronized (this) {
                if (items == null) {
                    var strings = DataProviderFactory.getDataProvider(WZFiles.STRING);
                    Map<Integer, GameKnowledge.Item> found = new LinkedHashMap<>();
                    for (String file : List.of("Cash.img", "Consume.img", "Eqp.img", "Etc.img", "Ins.img", "Pet.img")) {
                        collectItemNames(strings.getData(file), found);
                    }
                    items = List.copyOf(found.values());
                }
                all = items;
            }
        }
        String normalized = GameKnowledge.normalize(query);
        List<GameKnowledge.Item> exact = all.stream()
                .filter(i -> GameKnowledge.normalize(i.name()).equals(normalized)).limit(4).toList();
        if (!exact.isEmpty()) return exact;
        if (normalized.length() < 3) return List.of();
        return all.stream().filter(i -> GameKnowledge.normalize(i.name()).contains(normalized)).limit(4).toList();
    }

    private static void collectItemNames(Data node, Map<Integer, GameKnowledge.Item> found) {
        if (node == null) return;
        if (node.getName().matches("\\d+") && node.getChildByPath("name") != null) {
            int id = Integer.parseInt(node.getName());
            String name = DataTool.getString("name", node, "");
            if (!name.isBlank()) found.put(id, new GameKnowledge.Item(id, name));
        } else for (Data child : node) collectItemNames(child, found);
    }

    @Override
    public synchronized List<GameKnowledge.Drop> drops(int itemId) {
        long now = System.currentTimeMillis();
        CachedDrops cached = dropCache.get(itemId);
        if (cached != null && cached.expires() > now) return cached.drops();
        ensureNames();
        record RawDrop(int monster, int quest, boolean global) { }
        List<RawDrop> raw = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT dropperid, questid FROM drop_data WHERE itemid = ? AND chance > 0 ORDER BY chance DESC, dropperid LIMIT 100")) {
                ps.setInt(1, itemId);
                ps.setQueryTimeout(3);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int monster = rs.getInt("dropperid");
                        if (monsterNames.containsKey(monster)) raw.add(new RawDrop(monster, rs.getInt("questid"), false));
                    }
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT questid FROM drop_data_global WHERE itemid = ? AND chance > 0 LIMIT 20")) {
                ps.setInt(1, itemId);
                ps.setQueryTimeout(3);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) raw.add(new RawDrop(0, rs.getInt("questid"), true));
                }
            }
        } catch (SQLException failure) {
            // Do not cache a database outage as a factual 'no drops' answer.
            throw new IllegalStateException("Drop data is unavailable");
        }
        List<GameKnowledge.Drop> result = raw.stream().map(drop -> drop.global()
                ? new GameKnowledge.Drop("global drops", List.of(), drop.quest(), true)
                : new GameKnowledge.Drop(monsterNames.get(drop.monster()), locations(drop.monster()), drop.quest(), false)).toList();
        List<GameKnowledge.Drop> immutable = List.copyOf(result);
        dropCache.put(itemId, new CachedDrops(now + 600_000, immutable));
        while (dropCache.size() > 128) dropCache.remove(dropCache.keySet().iterator().next());
        return immutable;
    }

    private void ensureNames() {
        if (monsterNames != null) return;
        var source = DataProviderFactory.getDataProvider(WZFiles.STRING);
        Map<Integer, String> names = new HashMap<>();
        Data data = source.getData("Mob.img");
        if (data == null) throw new IllegalStateException("Monster names unavailable");
        for (Data mob : data) {
            try { names.put(Integer.parseInt(mob.getName()), DataTool.getString("name", mob, "")); }
            catch (NumberFormatException ignored) { }
        }
        monsterNames = Map.copyOf(names);
    }

    private List<String> locations(int monster) {
        if (monsterMaps == null) loadMapLocations();
        return monsterMaps.getOrDefault(monster, List.of());
    }

    @Override
    public synchronized List<GameKnowledge.Skill> findSkills(String query) {
        if (skills == null) {
            Data data = DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Skill.img");
            List<GameKnowledge.Skill> found = new ArrayList<>();
            if (data != null) for (Data skill : data) {
                String name = DataTool.getString("name", skill, "");
                String desc = plain(DataTool.getString("desc", skill, ""));
                if (!name.isBlank() && !desc.isBlank()) found.add(new GameKnowledge.Skill(name, desc));
            }
            skills = found.stream().distinct().toList();
        }
        String normalized = GameKnowledge.normalize(query);
        List<GameKnowledge.Skill> exact = skills.stream().filter(s -> GameKnowledge.normalize(s.name()).equals(normalized)).toList();
        // Several classes share the same named skill; its generic description is sufficient without claiming numerical ranks.
        if (!exact.isEmpty()) return List.of(exact.getFirst());
        if (normalized.length() < 3) return List.of();
        return skills.stream().filter(s -> GameKnowledge.normalize(s.name()).contains(normalized)).limit(4).toList();
    }

    @Override
    public synchronized List<GameKnowledge.QuestFact> findQuests(String query) {
        if (quests == null) {
            var provider = DataProviderFactory.getDataProvider(WZFiles.QUEST);
            Data info = provider.getData("QuestInfo.img"), checks = provider.getData("Check.img");
            Data npcs = DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Npc.img");
            List<GameKnowledge.QuestFact> found = new ArrayList<>();
            if (info != null && checks != null) for (Data quest : info) {
                String name = DataTool.getString("name", quest, "");
                if (name.isBlank()) continue;
                Data start = checks.getChildByPath(quest.getName() + "/0");
                String npc = "";
                int level = 0;
                if (start != null) {
                    int npcId = DataTool.getInt("npc", start, 0);
                    Data npcData = npcs == null ? null : npcs.getChildByPath(Integer.toString(npcId));
                    if (npcData != null) npc = DataTool.getString("name", npcData, "");
                    level = DataTool.getInt("lvmin", start, 0);
                }
                String desc = plain(DataTool.getString("0", quest, ""));
                try { found.add(new GameKnowledge.QuestFact(Integer.parseInt(quest.getName()), name, npc, level, desc)); }
                catch (NumberFormatException ignored) { }
            }
            quests = List.copyOf(found);
        }
        String normalized = GameKnowledge.normalize(query);
        List<GameKnowledge.QuestFact> exact = quests.stream().filter(q -> GameKnowledge.normalize(q.name()).equals(normalized)).limit(4).toList();
        if (!exact.isEmpty()) return exact;
        if (normalized.length() < 4) return List.of();
        return quests.stream().filter(q -> GameKnowledge.normalize(q.name()).contains(normalized)).limit(4).toList();
    }

    private static String plain(String value) {
        // Do not expose unresolved item/NPC IDs as meaningful prose or fabricate their labels.
        return value.replace("\\n", " ").replace("\\r", " ")
                .replaceAll("#[pitmoq]\\d+#", "[see quest log]")
                .replaceAll("#[A-Za-z]", "").replace("#", "").replaceAll("\\s+", " ").strip();
    }

    private void loadMapLocations() {
        Map<Integer, String> names = new HashMap<>();
        Data data = DataProviderFactory.getDataProvider(WZFiles.STRING).getData("Map.img");
        if (data != null) for (Data region : data) for (Data map : region) {
            try { names.put(Integer.parseInt(map.getName()), DataTool.getString("mapName", map, "")); }
            catch (NumberFormatException ignored) { }
        }
        Map<Integer, List<String>> found = new HashMap<>();
        Path directory = WZFiles.MAP.getFile().resolve("Map");
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("\\d{9}\\.img\\.xml")).sorted().toList()) {
                int mapId = Integer.parseInt(path.getFileName().toString().substring(0, 9));
                // Event instances cannot be recommended as freely accessible farming maps.
                if (mapId >= 800_000_000 || mapId % 1000 >= 800 || !names.containsKey(mapId)) continue;
                String xml = Files.readString(path);
                int life = xml.indexOf("<imgdir name=\"life\">");
                if (life < 0) continue;
                var entry = LIFE_ENTRY.matcher(xml.substring(life + "<imgdir name=\"life\">".length()));
                while (entry.find()) {
                    String body = entry.group(1);
                    if (!body.contains("<string name=\"type\" value=\"m\"")) continue;
                    var id = MONSTER_ID.matcher(body);
                    if (!id.find()) continue;
                    List<String> maps = found.computeIfAbsent(Integer.parseInt(id.group(1)), ignored -> new ArrayList<>());
                    String name = names.get(mapId);
                    if (!name.isBlank() && maps.size() < 3 && !maps.contains(name)) maps.add(name);
                }
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Map locations unavailable");
        }
        Map<Integer, List<String>> immutable = new HashMap<>();
        found.forEach((id, maps) -> immutable.put(id, List.copyOf(maps)));
        monsterMaps = Map.copyOf(immutable);
    }
}
