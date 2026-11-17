package org.agty.aiassistant.core;

import java.io.IOException;
import java.util.concurrent.*;

/** Completion means the OS has released the process, not just published an exit code. */
final class OwnedProcessTermination {
    private final Process process;
    private final CompletableFuture<Void> exited = new CompletableFuture<>();
    private boolean stopped;

    OwnedProcessTermination(Process process) {
        this.process = process;
        // Windows' timed Process.waitFor has an exit-code fast path. Its untimed variant
        // always waits on the native process handle, including termination cleanup of cwd.
        Thread.startVirtualThread(() -> {
            try { process.waitFor(); exited.complete(null); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); exited.completeExceptionally(e); }
            catch (RuntimeException e) { exited.completeExceptionally(e); }
        });
    }

    synchronized void stop() {
        boolean[] interrupted = {Thread.interrupted()};
        try {
            if (stopped) return;
            // Reap children while their parent is still present; otherwise Unix children
            // can become zombies owned by a non-reaping container init.
            var descendants = process.descendants().toList();
            var childExits = descendants.stream().map(ProcessHandle::onExit).toList();
            descendants.forEach(ProcessHandle::destroyForcibly);
            boolean childrenStopped = true;
            long childDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (var childExit : childExits) childrenStopped &= await(childExit, childDeadline, interrupted);
            process.destroy();
            if (!await(exited, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500), interrupted)) {
                process.destroyForcibly();
                if (!await(exited, System.nanoTime() + TimeUnit.SECONDS.toNanos(2), interrupted))
                    throw new IllegalStateException("ОС не подтвердила завершение процесса Codex.");
            }
            closeStreams();
            if (!childrenStopped) throw new IllegalStateException("ОС не подтвердила завершение дочерних процессов Codex.");
            stopped = true;
        } finally { if (interrupted[0]) Thread.currentThread().interrupt(); }
    }

    private static boolean await(CompletableFuture<?> completion, long deadline, boolean[] interrupted) {
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return completion.isDone() && !completion.isCompletedExceptionally();
            try { completion.get(remaining, TimeUnit.NANOSECONDS); return true; }
            catch (InterruptedException e) { interrupted[0] = true; }
            catch (TimeoutException e) { return false; }
            // A failed child observer must not skip termination of the owned parent.
            catch (ExecutionException e) { return false; }
        }
    }

    private void closeStreams() {
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
    }
}
