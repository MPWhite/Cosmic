package server.population;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded scheduling policy for unsolicited chatter; direct replies use the conversation limiter. */
public final class ResidentSocial {
    private static final int MAX_SPEAKERS = 1024;
    private static final long MIN_AMBIENT_GAP = 45_000;
    private static final long GLOBAL_REPEAT_GAP = 90_000;
    private static final long PERSONAL_REPEAT_GAP = 8 * 60_000;

    private record Spoken(String text, long at) { }
    private static final class Speaker {
        final ArrayDeque<Spoken> recent = new ArrayDeque<>();
        long nextSpeech;
        long turns;
    }

    private final Map<Integer, Speaker> speakers = new LinkedHashMap<>(32, .75f, true);
    private final Map<String, Long> recentlyHeard = new LinkedHashMap<>();

    public synchronized boolean canSpeak(int residentId, String text, long now) {
        String normalized = normalize(text);
        if (normalized.isEmpty() || normalized.length() > 180) {
            return false;
        }
        Speaker speaker = speakers.get(residentId);
        if (speaker != null) {
            if (now < speaker.nextSpeech) {
                return false;
            }
            if (speaker.recent.stream().anyMatch(line -> line.text().equals(normalized) && now - line.at() < PERSONAL_REPEAT_GAP)) {
                return false;
            }
        }
        Long globalLast = recentlyHeard.get(normalized);
        return globalLast == null || now - globalLast >= GLOBAL_REPEAT_GAP;
    }

    /** Record only a line the caller has reserved or actually spoken; rejected candidates consume nothing. */
    public synchronized void recordSpeech(int residentId, String text, long now) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return;
        }
        Speaker speaker = speakers.computeIfAbsent(residentId, ignored -> new Speaker());
        speaker.turns++;
        speaker.nextSpeech = now + MIN_AMBIENT_GAP + Math.floorMod(residentId * 31L + speaker.turns * 7919L, 45_000);
        speaker.recent.addLast(new Spoken(normalized, now));
        while (speaker.recent.size() > 8) {
            speaker.recent.removeFirst();
        }
        recentlyHeard.remove(normalized);
        recentlyHeard.put(normalized, now);
        while (recentlyHeard.size() > 256) {
            recentlyHeard.remove(recentlyHeard.keySet().iterator().next());
        }
        while (speakers.size() > MAX_SPEAKERS) {
            speakers.remove(speakers.keySet().iterator().next());
        }
    }

    /** A deterministic variation makes tests reproducible while avoiding identical response timing. */
    public static long replyDelay(ResidentProfiles.Profile resident, String text, long sequence) {
        int length = text == null ? 0 : Math.min(120, text.length());
        long variation = Math.floorMod(resident.id() * 65_537L + sequence * 33_421L
                + (text == null ? 0 : text.hashCode()), 900);
        return 420 + variation + Math.min(900, length * 8L);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
