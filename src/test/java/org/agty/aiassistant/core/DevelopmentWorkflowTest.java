package org.agty.aiassistant.core;

import org.agty.aiassistant.chat.*;
import org.agty.aiassistant.execution.TaskRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DevelopmentWorkflowTest {
    @TempDir Path directory;
    @Test void approvalNativeFileChangeAndVerifiedRollbackPreserveOriginalUserDraft() throws Exception {
        Path file = directory.resolve("task.txt"); Files.writeString(file, "user draft\n");
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("fixture.classes"), RpcFixture.class.getName(), "write-e2e");
        var completed = new CountDownLatch(1); var preflight = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = new CodexConnectionPool(); var runner = new TaskRunner(Runnable::run, task -> preflight.incrementAndGet())) {
            var session = new ChatSessions.Session();
            var subscription = runner.subscribe(task -> {
                if (task.state == TaskRunner.State.WAITING_USER) {
                    var response = new com.google.gson.JsonObject(); response.addProperty("decision", "accept");
                    runner.respond(session.id, task.pending.keySet().iterator().next(), response);
                }
                if (!task.active()) completed.countDown();
            });
            try {
                var options = new RequestOptions(directory, "", "", "", RequestOptions.Access.WORKSPACE_WRITE,
                        RequestOptions.Mode.WORK, List.of(), false, true);
                var task = runner.start(session, new CodexChatProvider(command, pool).newRequest(options), directory, "edit", "edit", "Codex", true);
                assertTrue(completed.await(8, TimeUnit.SECONDS)); assertEquals(TaskRunner.State.SUCCEEDED, task.state, task.status);
                assertEquals(1, preflight.get()); assertTrue(task.pending.isEmpty());
                assertTrue(session.messages.stream().anyMatch(message -> message.title.equals("Запрос обработан сервером") && message.complete));
                assertEquals("agent result\n", Files.readString(file)); assertFalse(session.remoteId().isBlank());
                assertEquals(1, task.workspace.changes().size()); assertTrue(task.workspace.changes().getFirst().proven());
                assertTrue(session.messages.stream().anyMatch(message -> message.title.startsWith("Изменения файлов")));
                runner.withWorkspaceLock(directory, () -> task.workspace.rollback(task.workspace.changes()));
                assertEquals("user draft\n", Files.readString(file));
            } finally { subscription.close(); }
        }
    }
}
