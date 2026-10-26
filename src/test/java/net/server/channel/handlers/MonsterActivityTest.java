package net.server.channel.handlers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonsterActivityTest {
    @Test void bothFacingsDecodeToTheSameAttackWithoutChangingItsWireValue() {
        assertEquals(12, MoveLifeHandler.decodedActivity((byte) 24));
        assertEquals(12, MoveLifeHandler.decodedActivity((byte) 25));
        assertEquals(20, MoveLifeHandler.decodedActivity((byte) 41));
        assertEquals(21, MoveLifeHandler.decodedActivity((byte) 42));
        assertEquals(-1, MoveLifeHandler.decodedActivity((byte) 0xff));
    }

    @Test void movementAndSkillsAreNeverMisclassifiedAsAttacks() {
        for (int wire = -1; wire < 60; wire++) {
            int activity = MoveLifeHandler.decodedActivity((byte) wire);
            assertEquals(wire >= 24 && wire <= 41, MoveLifeHandler.inRangeInclusive(activity, 12, 20), "wire=" + wire);
            assertEquals(wire >= 42 && wire <= 59, MoveLifeHandler.inRangeInclusive(activity, 21, 29), "wire=" + wire);
        }
    }
}
