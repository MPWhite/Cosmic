package server.population;

import java.util.Arrays;

/** Population tick wall times; retains only the latest 600 ticks, never actor or player data. */
public final class ResidentPopulationMetrics {
    private static final Window TICKS = new Window();

    private ResidentPopulationMetrics() { }

    public static void recordTick(long nanos) {
        TICKS.record(nanos);
    }

    public static Snapshot snapshot() {
        return TICKS.snapshot();
    }

    /** Samples is the lifetime count; percentiles and maximum describe the retained window. */
    public record Snapshot(long samples, double p50Millis, double p95Millis, double maxMillis) { }

    static final class Window {
        private static final int CAPACITY = 600;
        private final long[] nanos = new long[CAPACITY];
        private int next;
        private int retained;
        private long samples;

        synchronized void record(long elapsedNanos) {
            if (elapsedNanos < 0) {
                return;
            }
            nanos[next] = elapsedNanos;
            next = (next + 1) % CAPACITY;
            retained = Math.min(retained + 1, CAPACITY);
            samples++;
        }

        Snapshot snapshot() {
            long[] sorted;
            long count;
            synchronized (this) {
                sorted = Arrays.copyOf(nanos, retained);
                count = samples;
            }
            if (sorted.length == 0) {
                return new Snapshot(count, 0, 0, 0);
            }
            // Sort the copy outside the recording lock; inspection never delays a tick for sorting.
            Arrays.sort(sorted);
            return new Snapshot(count, percentile(sorted, 0.50), percentile(sorted, 0.95),
                    sorted[sorted.length - 1] / 1_000_000.0);
        }

        private static double percentile(long[] sorted, double fraction) {
            return sorted[(int) Math.ceil(sorted.length * fraction) - 1] / 1_000_000.0;
        }
    }
}
