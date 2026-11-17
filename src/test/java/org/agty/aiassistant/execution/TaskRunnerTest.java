package org.agty.aiassistant.execution;

import org.agty.aiassistant.chat.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TaskRunnerTest {
    @TempDir Path directory;
    static final class Request implements ChatProvider.Request {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger cancellations = new AtomicInteger();
        @Override public void run(Path path, String prompt, java.util.function.Consumer<ChatEvent> events) throws Exception {
            entered.countDown(); release.await();
            events.accept(new ChatEvent(ChatEvent.Kind.MESSAGE, "answer", "", prompt, true));
        }
        @Override public void cancel() { cancellations.incrementAndGet(); release.countDown(); }
    }
    @Test void queuedMessageStartsAfterCompletionUsingUpdatedNativeSession() throws Exception {
        try (var runner = new TaskRunner(Runnable::run)) {
            var session = new ChatSessions.Session(); var first = new Request();
            var finished = new CountDownLatch(1);
            runner.start(session, first, directory, "first", "first", "Fixture", false);
            assertTrue(first.entered.await(2, TimeUnit.SECONDS));
            runner.enqueue(session, () -> {
                assertEquals("native-latest", session.nativeSessionId);
                return new ChatProvider.Request() {
                    public void run(Path path, String prompt, java.util.function.Consumer<ChatEvent> events) {
                        events.accept(new ChatEvent(ChatEvent.Kind.MESSAGE, "queued", "", prompt, true)); finished.countDown();
                    }
                    public void cancel() { }
                };
            }, directory, "queued", "queued question", "Fixture", false);
            session.nativeSessionId = "native-latest"; session.draft = "new unsent draft"; first.release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS)); assertTrue(session.queuedMessages.isEmpty());
            assertEquals("new unsent draft", session.draft);
        }
    }
    @Test void writePreparationHappensBeforeProviderStartsAndCanAbortSafely() throws Exception {
        var request = new Request();
        var failed = new CountDownLatch(1);
        try (var runner = new TaskRunner(Runnable::run, task -> { throw new IllegalStateException("unsaved file"); })) {
            runner.subscribe(task -> { if (task.state == TaskRunner.State.FAILED) failed.countDown(); });
            runner.start(new ChatSessions.Session(), request, directory, "", "", "Fixture", true);
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            assertEquals(1, request.entered.getCount());
        }
    }
    @Test void detachedViewDoesNotCancelAndNewViewReceivesOriginalSession() throws Exception {
        try (var runner = new TaskRunner(Runnable::run)) {
            var session = new ChatSessions.Session(); var request = new Request();
            var subscription = runner.subscribe(task -> {});
            runner.start(session, request, directory, "answer", "question", "Fixture", false);
            assertTrue(request.entered.await(2, TimeUnit.SECONDS));
            subscription.close();
            assertEquals(0, request.cancellations.get());
            var done = new CountDownLatch(1);
            runner.subscribe(task -> { if (!task.active()) done.countDown(); });
            request.release.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals("answer", session.messages.getLast().markdown);
            assertEquals(TaskRunner.State.SUCCEEDED, runner.task(session.id).state);
        }
    }
    @Test void writersQueueAndCancellationTargetsOnlySelectedTask() throws Exception {
        try (var runner = new TaskRunner(Runnable::run)) {
            var a = new ChatSessions.Session(); var b = new ChatSessions.Session();
            var first = new Request(); var second = new Request();
            runner.start(a, first, directory, "a", "a", "Fixture", true);
            assertTrue(first.entered.await(2, TimeUnit.SECONDS));
            runner.start(b, second, directory, "b", "b", "Fixture", true);
            assertFalse(second.entered.await(200, TimeUnit.MILLISECONDS));
            runner.cancel(a.id);
            assertTrue(second.entered.await(2, TimeUnit.SECONDS));
            assertEquals(0, second.cancellations.get());
            second.release.countDown();
        }
    }
    @Test void independentDirectoriesRunInParallelAndCloseCancelsOwnedTasks() throws Exception {
        var runner = new TaskRunner(Runnable::run);
        var first = new Request(); var second = new Request();
        runner.start(new ChatSessions.Session(), first, directory, "a", "a", "Fixture", true);
        runner.start(new ChatSessions.Session(), second, Files.createDirectory(directory.resolve("worktree")), "b", "b", "Fixture", true);
        assertTrue(first.entered.await(2, TimeUnit.SECONDS)); assertTrue(second.entered.await(2, TimeUnit.SECONDS));
        runner.close();
        assertTrue(first.release.await(2, TimeUnit.SECONDS)); assertTrue(second.release.await(2, TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class, () -> runner.start(new ChatSessions.Session(), new Request(), directory, "", "", "Fixture", false));
    }
}
