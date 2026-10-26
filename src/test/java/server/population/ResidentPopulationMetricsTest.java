package server.population;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ResidentPopulationMetricsTest {
    @Test
    void emptyWindowAndInvalidDurationsDoNotInventSamples() {
        var window = new ResidentPopulationMetrics.Window();
        window.record(-1);
        assertEquals(new ResidentPopulationMetrics.Snapshot(0, 0, 0, 0), window.snapshot());
        window.record(0);
        assertEquals(new ResidentPopulationMetrics.Snapshot(1, 0, 0, 0), window.snapshot());
    }

    @Test
    void percentilesUseDurationsRatherThanArrivalOrderAndPreserveSubmillisecondPrecision() {
        var window = new ResidentPopulationMetrics.Window();
        for (int value = 20; value >= 1; value--) {
            window.record(value * 100_000L);
        }
        assertEquals(new ResidentPopulationMetrics.Snapshot(20, 1.0, 1.9, 2.0), window.snapshot());
        assertEquals(window.snapshot(), window.snapshot(), "reading metrics must not consume samples");
    }

    @Test
    void evictsOldSpikesAfterSixHundredTicksWithoutResettingTotalCount() {
        var window = new ResidentPopulationMetrics.Window();
        window.record(10_000_000_000L);
        for (int value = 1; value <= 600; value++) {
            window.record(value * 1_000_000L);
        }
        assertEquals(new ResidentPopulationMetrics.Snapshot(601, 300, 570, 600), window.snapshot());
    }

    @Test
    void concurrentRecordingAndInspectionKeepAllCountsAndConsistentDurations() throws Exception {
        var window = new ResidentPopulationMetrics.Window();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Future<?>>();
            for (int worker = 0; worker < 4; worker++) {
                tasks.add(executor.submit(() -> {
                    for (int sample = 0; sample < 1000; sample++) {
                        window.record(2_000_000L);
                        if (sample % 40 == 0) {
                            var snapshot = window.snapshot();
                            assertEquals(2, snapshot.p50Millis());
                            assertEquals(2, snapshot.maxMillis());
                        }
                    }
                }));
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            for (var task : tasks) {
                task.get();
            }
        }
        assertEquals(new ResidentPopulationMetrics.Snapshot(4000, 2, 2, 2), window.snapshot());
    }
}
