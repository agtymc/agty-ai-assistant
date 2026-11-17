package org.agty.aiassistant.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolBufferTest {
    @Test void aggregateBudgetIsSharedBySubscribersAndReleasedWhenPolledOrDetached() throws Exception {
        var budget = new ProtocolBuffer.Budget(100);
        try (var first = new ProtocolBuffer(8, budget); var second = new ProtocolBuffer(8, budget)) {
            var event = new JsonObject(); event.addProperty("delta", "large result");
            assertTrue(first.offer(event, 60));
            assertThrows(java.io.IOException.class, () -> second.offer(event, 60));
            assertEquals(60, budget.retained());
            assertSame(event, first.poll(0)); assertEquals(0, budget.retained());
            assertTrue(second.offer(event, 60)); second.close(); assertEquals(0, budget.retained());
            // A publisher holding a previous subscriber snapshot cannot refill a closed buffer.
            assertTrue(second.offer(event, 60)); assertEquals(0, budget.retained());
        }
    }
    @Test void countOverflowAndClearDoNotLeakTheSizeBudget() throws Exception {
        var budget = new ProtocolBuffer.Budget(100);
        try (var buffer = new ProtocolBuffer(1, budget)) {
            var event = new JsonObject(); assertTrue(buffer.offer(event, 20));
            assertFalse(buffer.offer(event, 20)); assertEquals(20, budget.retained());
            buffer.clear(); assertEquals(0, budget.retained());
            assertTrue(buffer.offer(event, 100));
        }
        assertEquals(0, budget.retained());
    }
}
