package org.agty.aiassistant.core;

import com.google.gson.JsonParser;
import org.agty.aiassistant.chat.ChatEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Timeout(10)
class CodexSessionClientTest {
    private CodexSessionClient connection() throws Exception {
        return new CodexSessionClient(java.util.List.of(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("fixture.classes"), RpcFixture.class.getName(), "rpc"),
                java.nio.file.Path.of("."), () -> false);
    }
    @Test void routesOutOfOrderRepliesAndPreservesNotifications() throws Exception {
        try (var client = connection(); var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> client.call("fixture/first", new com.google.gson.JsonObject()));
            var second = executor.submit(() -> client.call("fixture/second", new com.google.gson.JsonObject()));
            assertEquals(2, second.get().get("value").getAsInt());
            assertEquals(1, first.get().get("value").getAsInt());
            assertEquals("fixture/event", client.nextNotification().get("method").getAsString());
        }
    }
    @Test void disconnectFailsPendingCallPromptly() throws Exception {
        try (var client = connection()) {
            assertThrows(java.io.IOException.class,
                    () -> client.call("fixture/crash", new com.google.gson.JsonObject()));
        }
    }
    @Test void closedConnectionRejectsNewCalls() throws Exception {
        var client = connection();
        client.close();
        assertThrows(java.io.IOException.class,
                () -> client.call("fixture/first", new com.google.gson.JsonObject()));
    }

    @Test void importsPublicMessagesAndKeepsNativeUid() {
        var thread = JsonParser.parseString("""
            {"id":"019e309f-5fe7-7a93-90d0-ce794686cdfd","name":"My session","turns":[{"items":[
             {"type":"userMessage","id":"u","content":[{"type":"text","text":"Question"}]},
             {"type":"agentMessage","id":"a","text":"```java\\n  class A {}\\n```"},
             {"type":"reasoning","id":"r","text":"not public chat"}
            ]}]}
            """).getAsJsonObject();
        var session = CodexSessionClient.decode(thread);
        assertEquals("019e309f-5fe7-7a93-90d0-ce794686cdfd", session.codexId);
        assertEquals("My session", session.title);
        assertEquals(2, session.messages.size());
        assertEquals("Вы", session.messages.getFirst().title);
        assertTrue(session.messages.getLast().markdown.contains("\n  class"));
        assertTrue(session.toString().contains(session.codexId));
    }
    @Test void nativeResumeUsesSpecificUidAndPersistsNewSessions() {
        String id = "019e309f-5fe7-7a93-90d0-ce794686cdfd";
        var command = CodexCommand.chat("codex", id);
        assertEquals(id, command.get(command.indexOf("resume") + 1));
        assertEquals("-", command.getLast());
        assertTrue(command.contains("read-only"));
        assertFalse(command.contains("--ephemeral"));
        assertFalse(CodexCommand.chat("codex").contains("--ephemeral"));
        assertThrows(IllegalArgumentException.class, () -> CodexCommand.chat("codex", "--last"));
        var event = CodexEvent.parse("{\"type\":\"thread.started\",\"thread_id\":\"" + id + "\"}");
        assertEquals(ChatEvent.Kind.SESSION, event.kind()); assertEquals(id, event.text());
    }
}
