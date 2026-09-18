package org.agty.aiassistant.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CodexProcessTest {
    @TempDir Path directory;

    private List<String> command(String mode) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = System.getProperty("fixture.classes");
        return List.of(java, "-cp", classes, ProcessFixture.class.getName(), mode);
    }

    @Test void passesPromptViaStdinAndSeparatesDiagnostics() throws Exception {
        var output = new ArrayList<String>();
        var errors = new ArrayList<String>();
        int exit = new CodexProcess().run(command("echo"), directory, "Привет ' $() ;", Duration.ofSeconds(5), output::add, errors::add);
        assertEquals(0, exit);
        assertEquals(List.of("Привет ' $() ;"), output);
        assertEquals(List.of("diagnostic"), errors);
    }

    @Test void returnsNonzeroExitAndDiagnostics() throws Exception {
        var errors = new ArrayList<String>();
        assertEquals(7, new CodexProcess().run(command("fail"), directory, "", Duration.ofSeconds(5), s -> {}, errors::add));
        assertEquals(List.of("login required"), errors);
    }

    @Test void timesOutHungProcess() throws Exception {
        var command = command("wait");
        assertThrows(TimeoutException.class, () -> new CodexProcess().run(command, directory, "", Duration.ofMillis(200), s -> {}, s -> {}));
    }

    @Test void cancellationStopsRunningProcess() throws Exception {
        var process = new CodexProcess();
        var ready = new CountDownLatch(1);
        var command = command("wait");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> task = executor.submit(() -> {
                assertThrows(CancellationException.class, () -> process.run(command, directory, "", Duration.ofSeconds(30), s -> ready.countDown(), s -> {}));
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            process.cancel();
            task.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void rejectsOversizedEvents() throws Exception {
        var command = command("large");
        assertThrows(ExecutionException.class, () -> new CodexProcess().run(command, directory, "", Duration.ofSeconds(5), s -> {}, s -> {}));
    }

    @Test void cancelledInvocationDoesNotStart() throws Exception {
        var process = new CodexProcess();
        process.cancel();
        assertThrows(CancellationException.class, () -> process.run(List.of("does-not-exist"), directory, "", Duration.ofSeconds(1), s -> {}, s -> {}));
    }
}
