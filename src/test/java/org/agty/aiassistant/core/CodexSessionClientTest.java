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
    @Test void lineLimitIsEnforcedBeforeReadingAnUnboundedServerLine() throws Exception {
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        var hostile = new java.io.Reader() {
            public int read(char[] buffer, int offset, int length) {
                if (consumed.incrementAndGet() > 33) fail("Read past bounded limit");
                buffer[offset] = 'x'; return 1;
            }
            public void close() { }
        };
        assertThrows(java.io.IOException.class, () -> CodexSessionClient.readBoundedLine(hostile, 32));
        assertEquals(33, consumed.get());
        var input = new java.io.StringReader("abc\nlast");
        assertEquals("abc", CodexSessionClient.readBoundedLine(input, 4));
        assertEquals("last", CodexSessionClient.readBoundedLine(input, 4));
        assertNull(CodexSessionClient.readBoundedLine(input, 4));
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

    @Test void independentPendingRequestsAndResolutionByAnotherClientNeverPermitSecondAnswer() throws Exception {
        try (var client = connection(); var subscription = client.subscribe()) {
            client.call("fixture/pending", new com.google.gson.JsonObject());
            var first = subscription.next(); var second = subscription.next();
            assertEquals(910, first.get("id").getAsInt()); assertEquals(911, second.get("id").getAsInt());
            String firstHandle = CodexSessionClient.serverRequestHandle(first), secondHandle = CodexSessionClient.serverRequestHandle(second);
            client.call("fixture/resolve", new com.google.gson.JsonObject());
            assertEquals("serverRequest/resolved", subscription.next().get("method").getAsString());
            var response = JsonParser.parseString("{\"decision\":\"decline\"}").getAsJsonObject();
            assertThrows(java.io.IOException.class, () -> client.respond(firstHandle, response));
            client.respond(secondHandle, response);
            assertThrows(java.io.IOException.class, () -> client.respond(secondHandle, response));
        }
    }
    @Test void staleApprovalCannotAnswerReusedRpcIdInAnotherConnectionOrSameConnection() throws Exception {
        try (var old = connection(); var oldSubscription = old.subscribe(); var replacement = connection(); var replacementSubscription = replacement.subscribe()) {
            old.call("fixture/pending", new com.google.gson.JsonObject());
            String stale = CodexSessionClient.serverRequestHandle(oldSubscription.next()); oldSubscription.next();
            replacement.call("fixture/pending", new com.google.gson.JsonObject());
            String fresh = CodexSessionClient.serverRequestHandle(replacementSubscription.next()); replacementSubscription.next();
            var approve = JsonParser.parseString("{\"decision\":\"accept\"}").getAsJsonObject();
            assertNotEquals(stale, fresh);
            assertThrows(java.io.IOException.class, () -> replacement.respond(stale, approve));
            replacement.respond(fresh, approve);
            old.call("fixture/resolve", new com.google.gson.JsonObject()); oldSubscription.next();
            old.call("fixture/reuse-resolved", new com.google.gson.JsonObject());
            String reused = CodexSessionClient.serverRequestHandle(oldSubscription.next());
            assertNotEquals(stale, reused);
            assertThrows(java.io.IOException.class, () -> old.respond(stale, approve)); old.respond(reused, approve);
        }
    }
    @Test void pendingRequestFloodFailsClosedWithoutGrantingPermission() throws Exception {
        try (var client = connection(); var subscription = client.subscribe()) {
            client.call("fixture/burst-pending", new com.google.gson.JsonObject());
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
                while (client.alive()) Thread.sleep(10);
            });
            assertThrows(java.io.IOException.class, () -> client.respond("1000", JsonParser.parseString("{\"decision\":\"accept\"}").getAsJsonObject()));
        }
    }
    @Test void historyLoadsPagesWithoutCollidingItemIdsAcrossTurns() throws Exception {
        try (var client = connection()) {
            String id = "019e309f-5fe7-7a93-90d0-ce794686cdfd";
            var newest = client.historyPage(id, ""); assertEquals("older", newest.nextCursor());
            var older = client.historyPage(id, newest.nextCursor()); assertEquals("", older.nextCursor());
            assertEquals("Latest", newest.messages().getFirst().markdown); assertEquals("Earlier", older.messages().getFirst().markdown);
            assertNotEquals(newest.messages().getFirst().id, older.messages().getFirst().id);
        }
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
