package org.agty.aiassistant.core;

import org.agty.aiassistant.chat.ChatEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CodexStreamingTest {
    @TempDir Path directory;
    private CodexChatProvider provider(String mode) {
        return new CodexChatProvider(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("fixture.classes"), RpcFixture.class.getName(), mode));
    }
    @Test void interactiveApprovalIsAnsweredExactlyOnceWithoutRestartingTurn() throws Exception {
        var request = provider("interactive").newRequest(new org.agty.aiassistant.chat.RequestOptions(directory, "", "", "",
                org.agty.aiassistant.chat.RequestOptions.Access.READ_ONLY,
                org.agty.aiassistant.chat.RequestOptions.Mode.WORK, List.of(), false, true));
        var events = new ArrayList<ChatEvent>();
        request.run(directory, "question", event -> {
            events.add(event);
            if (event.kind() == ChatEvent.Kind.SERVER_REQUEST) {
                var answer = new com.google.gson.JsonObject(); answer.addProperty("decision", "accept");
                try {
                    request.respond(event.id(), answer);
                    assertThrows(java.io.IOException.class, () -> request.respond(event.id(), answer));
                } catch (Exception e) { throw new AssertionError(e); }
            }
        });
        assertTrue(events.stream().anyMatch(e -> e.kind() == ChatEvent.Kind.SERVER_RESOLVED));
        assertEquals(ChatEvent.Kind.DONE, events.getLast().kind());
    }
    @Test void deliversChunksBeforeFinalAndDoesNotDuplicateCompletedText() throws Exception {
        var events = new ArrayList<ChatEvent>();
        provider("read").newRequest().run(directory, "question", events::add);
        var messages = events.stream().filter(e -> e.kind() == ChatEvent.Kind.MESSAGE).toList();
        assertEquals(List.of("Привет", "Привет, мир", "Привет, мир!"), messages.stream().map(ChatEvent::text).toList());
        assertFalse(messages.getFirst().complete()); assertTrue(messages.getLast().complete());
        assertEquals(ChatEvent.Kind.DONE, events.getLast().kind());
        assertTrue(events.stream().anyMatch(e -> e.kind() == ChatEvent.Kind.USAGE));
    }
    @Test void writeModeIsAppliedOnResumeAndEveryTurn() throws Exception {
        var events = new ArrayList<ChatEvent>();
        provider("write").newRequest("019e309f-5fe7-7a93-90d0-ce794686cdfd", true).run(directory, "edit", events::add);
        assertEquals(ChatEvent.Kind.DONE, events.getLast().kind());
        var sandbox = CodexChatProvider.turnParams(directory, "id", "edit", true).getAsJsonObject("sandboxPolicy");
        assertEquals(directory.toString(), sandbox.getAsJsonArray("writableRoots").get(0).getAsString());
        assertTrue(sandbox.get("excludeSlashTmp").getAsBoolean());
    }
    @Test void unrestrictedModeDisablesFilesystemAndNetworkSandbox() throws Exception {
        var events = new ArrayList<ChatEvent>();
        provider("full").newRequest("", true, "", "", false, true).run(directory, "connect to db", events::add);
        assertEquals(ChatEvent.Kind.DONE, events.getLast().kind());
        var sandbox = CodexChatProvider.turnParams(directory, "id", "connect to db", true, true)
                .getAsJsonObject("sandboxPolicy");
        assertEquals("dangerFullAccess", sandbox.get("type").getAsString());
        assertFalse(sandbox.has("networkAccess"));
        assertFalse(sandbox.has("writableRoots"));
    }
    @Test void modelOverrideIsPassedToNewAndResumedThreadsAndTurns() throws Exception {
        for (String id : List.of("", "019e309f-5fe7-7a93-90d0-ce794686cdfd")) {
            var events = new ArrayList<ChatEvent>();
            provider("model").newRequest(id, false, "engine-b", "high").run(directory, "question", events::add);
            assertEquals(ChatEvent.Kind.DONE, events.getLast().kind());
        }
    }
    @Test void forksOldThreadWithSelectedModelBeforeStartingTurn() throws Exception {
        var events = new ArrayList<ChatEvent>();
        provider("fork").newRequest("019e309f-5fe7-7a93-90d0-ce794686cdfd", false, "engine-b", "high", true)
                .run(directory, "question", events::add);
        assertTrue(events.stream().anyMatch(event -> event.kind() == ChatEvent.Kind.SESSION
                && event.text().equals("019e309f-5fe7-7a93-90d0-ce794686cdfa")));
        assertEquals(ChatEvent.Kind.DONE, events.getLast().kind());
    }
    @Test void refusesUnconfirmedModelBeforeStartingTurn() {
        for (String mode : List.of("mismatch", "no-model")) {
            var events = new ArrayList<ChatEvent>();
            var error = assertThrows(IllegalStateException.class,
                    () -> provider(mode).newRequest("", false, "engine-b", "high").run(directory, "question", events::add));
            assertTrue(error.getMessage().contains("Запрос не отправлен"));
            assertFalse(events.stream().anyMatch(event -> event.kind() == ChatEvent.Kind.SESSION || event.kind() == ChatEvent.Kind.MESSAGE));
        }
    }
    @Test void stopsStreamWhenCodexReroutesAwayFromSelectedModel() {
        var events = new ArrayList<ChatEvent>();
        var error = assertThrows(IllegalStateException.class,
                () -> provider("rerouted").newRequest("", false, "engine-b", "high").run(directory, "question", events::add));
        assertTrue(error.getMessage().contains("вместо engine-b"));
        assertFalse(events.stream().anyMatch(event -> event.kind() == ChatEvent.Kind.MESSAGE || event.kind() == ChatEvent.Kind.DONE));
    }
    @Test void refusesUnsupportedReasoningEffortBeforeCreatingThread() {
        var error = assertThrows(IllegalStateException.class,
                () -> provider("model").newRequest("", false, "engine-b", "medium").run(directory, "question", event -> {}));
        assertTrue(error.getMessage().contains("не поддерживает уровень"));
    }
    @Test void failedTurnAndUnexpectedExitCannotBeReportedAsSuccess() {
        for (String mode : List.of("fail", "crash")) {
            var events = new ArrayList<ChatEvent>();
            assertThrows(Exception.class, () -> provider(mode).newRequest().run(directory, "question", events::add));
            assertFalse(events.stream().anyMatch(e -> e.kind() == ChatEvent.Kind.DONE));
        }
    }
    @Test void cancellationInterruptsStreamingProcess() throws Exception {
        var request = provider("hang").newRequest();
        var received = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> {
                try { request.run(directory, "question", e -> { if (e.kind() == ChatEvent.Kind.MESSAGE) received.countDown(); }); }
                catch (Exception expected) { return; }
                fail("Cancelled stream completed successfully");
            });
            assertTrue(received.await(5, TimeUnit.SECONDS)); request.cancel(); future.get(3, TimeUnit.SECONDS);
        }
    }
    @Test void reconnectsToTheSameTurnWithoutStartingItTwice() throws Exception {
        var events = new ArrayList<ChatEvent>();
        provider("reconnect").newRequest().run(directory, "question", events::add);
        assertTrue(events.stream().anyMatch(event -> event.kind() == ChatEvent.Kind.STATUS
                && event.text().contains("Восстановление наблюдения")));
        assertTrue(events.stream().anyMatch(event -> event.kind() == ChatEvent.Kind.MESSAGE
                && event.text().equals("Привет после восстановления")));
        assertEquals(ChatEvent.Kind.DONE, events.getLast().kind());
    }
    @Test void acceptsActiveThreadBeforeItsTurnsSnapshotIsAvailable() throws Exception {
        var events = new ArrayList<ChatEvent>();
        provider("reconnect-active").newRequest().run(directory, "question", events::add);
        long attempts = events.stream().filter(event -> event.kind() == ChatEvent.Kind.STATUS
                && event.text().contains("Восстановление наблюдения")).count();
        assertEquals(1, attempts);
        assertTrue(events.stream().anyMatch(event -> event.kind() == ChatEvent.Kind.STATUS
                && event.text().equals("Наблюдение за задачей Codex восстановлено.")));
        assertEquals(ChatEvent.Kind.DONE, events.getLast().kind());
    }
}
