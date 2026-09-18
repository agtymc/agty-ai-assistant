package org.agty.aiassistant.core;

import org.junit.jupiter.api.Test;
import org.agty.aiassistant.chat.ChatEvent;
import static org.junit.jupiter.api.Assertions.*;

class CodexEventTest {
    @Test void readsCompletedAssistantMessage() {
        var event = CodexEvent.parse("{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":\"Привет\\nкод\"}}");
        assertEquals(ChatEvent.Kind.MESSAGE, event.kind());
        assertEquals("Привет\nкод", event.text());
    }

    @Test void doesNotExposeCommandOutputAsAssistantMessage() {
        assertEquals(ChatEvent.Kind.ACTIVITY, CodexEvent.parse(
                "{\"type\":\"item.completed\",\"item\":{\"type\":\"command_execution\",\"aggregated_output\":\"secret\"}}").kind());
    }

    @Test void handlesFailuresAndUnknownEvents() {
        assertEquals("unauthorized", CodexEvent.parse(
                "{\"type\":\"turn.failed\",\"error\":{\"message\":\"unauthorized\"}}").text());
        assertEquals(ChatEvent.Kind.DONE, CodexEvent.parse("{\"type\":\"turn.completed\"}").kind());
        assertEquals(ChatEvent.Kind.IGNORE, CodexEvent.parse("{\"type\":\"future.event\"}").kind());
        assertEquals(ChatEvent.Kind.ERROR, CodexEvent.parse("not json").kind());
    }

    @Test void reasoningAndCommandSnapshotsKeepTheirIdentity() {
        var started = CodexEvent.parse("{\"type\":\"item.started\",\"item\":{\"id\":\"step-1\",\"type\":\"command_execution\",\"command\":\"ls\",\"status\":\"in_progress\"}}");
        var finished = CodexEvent.parse("{\"type\":\"item.completed\",\"item\":{\"id\":\"step-1\",\"type\":\"command_execution\",\"command\":\"ls\",\"exit_code\":0}}");
        assertEquals(started.id(), finished.id());
        assertFalse(started.complete());
        assertTrue(finished.complete());
        assertEquals(ChatEvent.Kind.ACTIVITY, started.kind());
        var reasoning = CodexEvent.parse("{\"type\":\"item.updated\",\"item\":{\"id\":\"reason-1\",\"type\":\"reasoning\",\"text\":\"Checking dependencies\"}}");
        assertEquals(ChatEvent.Kind.ACTIVITY, reasoning.kind());
        assertEquals("Checking dependencies", reasoning.text());
    }
}
