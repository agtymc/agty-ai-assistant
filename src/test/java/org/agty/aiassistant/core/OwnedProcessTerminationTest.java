package org.agty.aiassistant.core;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Timeout(10)
class OwnedProcessTerminationTest {
    @Test void anExitCodeCannotReplaceTheNativeCompletionBarrierAndInterruptIsPreserved() throws Exception {
        var nativeExit = new CountDownLatch(1); var waiterEntered = new CountDownLatch(1); var stopped = new AtomicBoolean();
        var destroyEntered = new CountDownLatch(1);
        var process = new Process() {
            public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            public InputStream getInputStream() { return InputStream.nullInputStream(); }
            public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            public Stream<ProcessHandle> descendants() { return Stream.empty(); }
            public int waitFor() throws InterruptedException { waiterEntered.countDown(); nativeExit.await(); return 0; }
            public boolean waitFor(long timeout, TimeUnit unit) { return true; } // Windows exit-code fast path.
            public int exitValue() { return 0; }
            public boolean isAlive() { return false; }
            public void destroy() { destroyEntered.countDown(); }
        };
        var termination = new OwnedProcessTermination(process);
        assertTrue(waiterEntered.await(1, TimeUnit.SECONDS));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var closing = executor.submit(() -> {
                Thread.currentThread().interrupt();
                try { termination.stop(); stopped.set(true); assertTrue(Thread.currentThread().isInterrupted()); }
                finally { Thread.interrupted(); }
            });
            assertTrue(destroyEntered.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
            assertFalse(stopped.get()); nativeExit.countDown(); closing.get(2, TimeUnit.SECONDS);
            termination.stop(); assertTrue(stopped.get());
        } finally { nativeExit.countDown(); }
    }
    @Test void missingNativeExitCannotBlockCloseIndefinitely() {
        var nativeExit = new CountDownLatch(1);
        var process = new Process() {
            public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            public InputStream getInputStream() { return InputStream.nullInputStream(); }
            public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            public Stream<ProcessHandle> descendants() { return Stream.empty(); }
            public int waitFor() throws InterruptedException { nativeExit.await(); return 0; }
            public int exitValue() { throw new IllegalThreadStateException(); }
            public void destroy() { }
        };
        var termination = new OwnedProcessTermination(process);
        try {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(4), () ->
                    assertThrows(IllegalStateException.class, termination::stop));
        } finally { nativeExit.countDown(); }
    }
}
