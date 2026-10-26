package server.population.party;

import org.junit.jupiter.api.Test;
import java.awt.Point;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KpqPuzzleTest {
    @Test void visitsEveryCombinationExactlyOnceAfterObservedFailures() {
        for (int stage = 2; stage <= 4; stage++) {
            var puzzle = new KpqPuzzle(stage);
            var seen = new HashSet<List<Integer>>();
            List<Integer> combo;
            while (!(combo = puzzle.choose(-1)).isEmpty()) {
                assertTrue(seen.add(combo), "repeated a rejected combination");
                assertEquals(combo, puzzle.choose(-1), "waiting does not advance the notebook");
                puzzle.observeWrong(combo.stream().map(puzzle::position).toList());
            }
            assertEquals(stage == 2 ? 4 : stage == 3 ? 10 : 20, seen.size());
        }
    }

    @Test void invalidOccupancyDoesNotEliminateACombination() {
        var puzzle = new KpqPuzzle(2);
        var original = puzzle.choose(-1);
        puzzle.observeWrong(List.of(puzzle.position(0), puzzle.position(0), puzzle.position(1)));
        puzzle.observeWrong(List.of(puzzle.position(0), puzzle.position(1), new Point(0, 0)));
        puzzle.observeWrong(List.of(puzzle.position(0), puzzle.position(1), puzzle.position(2), puzzle.position(3)));
        assertEquals(0, puzzle.attempts());
        assertEquals(original, puzzle.choose(-1));
    }

    @Test void adaptsToHumanPositionAndOnlyAsksThemToMoveAfterExhaustingThoseOptions() {
        var puzzle = new KpqPuzzle(3);
        for (int i = 0; i < 6; i++) {
            var combo = puzzle.choose(4);
            assertTrue(combo.contains(4));
            puzzle.observeWrong(combo.stream().map(puzzle::position).toList());
        }
        assertFalse(puzzle.choose(4).contains(4));
    }

    @Test void onlyRecordedPublicQuestionDeterminesCouponCount() {
        assertEquals(35, KpqPartySession.couponAnswer("minimum amount of STR needed to make the first job advancement as a warrior"));
        assertEquals(8, KpqPartySession.couponAnswer("minimum level required to make the first job advancement as a magician"));
        assertEquals(0, KpqPartySession.couponAnswer("an unknown future question"));
    }
}
