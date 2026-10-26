package server.population;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Compact relationship snapshots, deliberately separate from account and character saves. */
public final class ResidentMemoryStore {
    public static final Path DEFAULT_PATH = Path.of(".local", "population", "memories.properties");
    private static final int MAX_MEMORIES = 5_000;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    public record Memory(int playerId, int residentId, String playerName, int visits,
                         int conversations, String lastTopic, long lastSeen) {
        public boolean hasMet() {
            return visits > 0 || conversations > 0;
        }
    }

    private record Key(int playerId, int residentId) {
    }

    private final Path file;
    private final Clock clock;
    private final Map<Key, Memory> memories = new HashMap<>();
    private boolean dirty;

    public ResidentMemoryStore(Path file) throws IOException {
        this(file, Clock.systemUTC());
    }

    ResidentMemoryStore(Path file, Clock clock) throws IOException {
        this.file = file == null ? null : file.toAbsolutePath();
        this.clock = clock;
        if (this.file != null && Files.exists(this.file)) {
            load();
        }
    }

    public static ResidentMemoryStore inMemory() {
        try {
            return new ResidentMemoryStore(null);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public synchronized Memory get(int playerId, int residentId, String playerName) {
        return memories.getOrDefault(new Key(playerId, residentId),
                new Memory(playerId, residentId, clean(playerName, 24), 0, 0, "", 0));
    }

    public synchronized Memory visit(int playerId, int residentId, String playerName) {
        Memory before = get(playerId, residentId, playerName);
        return put(new Memory(playerId, residentId, clean(playerName, 24),
                increment(before.visits()), before.conversations(), before.lastTopic(), clock.millis()));
    }

    public synchronized Memory converse(int playerId, int residentId, String playerName, String topic) {
        Memory before = get(playerId, residentId, playerName);
        return put(new Memory(playerId, residentId, clean(playerName, 24), before.visits(),
                increment(before.conversations()), topic == null || topic.isBlank() ? before.lastTopic() : clean(topic, 64),
                clock.millis()));
    }

    public synchronized int size() {
        return memories.size();
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Call periodically or during shutdown, never once per actor movement. */
    public synchronized void flush() throws IOException {
        if (file == null || !dirty) {
            return;
        }
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("count", Integer.toString(memories.size()));
        int index = 0;
        for (Memory memory : memories.values().stream()
                .sorted(Comparator.comparingInt(Memory::playerId).thenComparingInt(Memory::residentId)).toList()) {
            String prefix = "memory." + index++ + ".";
            properties.setProperty(prefix + "player", Integer.toString(memory.playerId()));
            properties.setProperty(prefix + "resident", Integer.toString(memory.residentId()));
            properties.setProperty(prefix + "name", memory.playerName());
            properties.setProperty(prefix + "visits", Integer.toString(memory.visits()));
            properties.setProperty(prefix + "conversations", Integer.toString(memory.conversations()));
            properties.setProperty(prefix + "topic", memory.lastTopic());
            properties.setProperty(prefix + "seen", Long.toString(memory.lastSeen()));
        }
        Path temporary = Files.createTempFile(file.getParent(), "memories-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "Henesys resident relationships");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Memory put(Memory memory) {
        Key key = new Key(memory.playerId(), memory.residentId());
        if (!memories.containsKey(key) && memories.size() >= MAX_MEMORIES) {
            memories.entrySet().stream().min(Comparator.comparingLong(entry -> entry.getValue().lastSeen()))
                    .map(Map.Entry::getKey).ifPresent(memories::remove);
        }
        memories.put(key, memory);
        dirty = true;
        return memory;
    }

    private void load() throws IOException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new IOException("Resident memory file exceeds its size limit: " + file);
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
            if (!"1".equals(required(properties, "version"))) {
                throw new IllegalArgumentException("Unsupported resident memory version");
            }
            int count = Integer.parseInt(required(properties, "count"));
            if (count < 0 || count > MAX_MEMORIES) {
                throw new IllegalArgumentException("Invalid memory count");
            }
            for (int index = 0; index < count; index++) {
                String prefix = "memory." + index + ".";
                Memory memory = new Memory(
                        Integer.parseInt(required(properties, prefix + "player")),
                        Integer.parseInt(required(properties, prefix + "resident")),
                        required(properties, prefix + "name"),
                        Integer.parseInt(required(properties, prefix + "visits")),
                        Integer.parseInt(required(properties, prefix + "conversations")),
                        required(properties, prefix + "topic"),
                        Long.parseLong(required(properties, prefix + "seen")));
                if (memory.playerId() <= 0 || memory.residentId() <= 0 || memory.visits() < 0
                        || memory.conversations() < 0 || memory.lastSeen() < 0
                        || memory.playerName().length() > 24 || memory.lastTopic().length() > 64) {
                    throw new IllegalArgumentException("Invalid resident memory entry");
                }
                if (memories.put(new Key(memory.playerId(), memory.residentId()), memory) != null) {
                    throw new IllegalArgumentException("Duplicate resident memory entry");
                }
            }
        } catch (IllegalArgumentException invalid) {
            // A failed load must never turn into a successful empty save over the original file.
            memories.clear();
            throw new IOException("Invalid resident memories; original file was preserved: " + file, invalid);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private static String clean(String value, int limit) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("[\\p{Cntrl}]", "").trim();
        return clean.substring(0, Math.min(clean.length(), limit));
    }
}
