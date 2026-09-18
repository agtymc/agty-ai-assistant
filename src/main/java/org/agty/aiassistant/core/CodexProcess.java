package org.agty.aiassistant.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One cancellable invocation; stdout and stderr are drained independently. */
public final class CodexProcess {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Process process;
    private static final int MAX_LINE = 1_048_576;

    public int run(List<String> command, Path directory, String input, Duration timeout,
                   Consumer<String> stdout, Consumer<String> stderr) throws Exception {
        if (cancelled.get()) throw new CancellationException();
        Process child = new ProcessBuilder(command).directory(directory.toFile()).start();
        process = child;
        try (ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> out = workers.submit(() -> drain(child.getInputStream(), stdout));
            Future<?> err = workers.submit(() -> drain(child.getErrorStream(), stderr));
            Future<?> writer = workers.submit(() -> {
                try (OutputStream stream = child.getOutputStream()) {
                    stream.write(input.getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) { throw new UncheckedIOException(e); }
            });
            try {
                if (cancelled.get()) throw new CancellationException();
                long deadline = System.nanoTime() + timeout.toNanos();
                while (!child.waitFor(100, TimeUnit.MILLISECONDS)) {
                    if (cancelled.get()) throw new CancellationException();
                    if (System.nanoTime() >= deadline) throw new TimeoutException("Превышено время ожидания Codex.");
                    if (out.isDone()) out.get();
                    if (err.isDone()) err.get();
                }
                if (cancelled.get()) throw new CancellationException();
                out.get(2, TimeUnit.SECONDS);
                err.get(2, TimeUnit.SECONDS);
                if (child.exitValue() == 0) writer.get(2, TimeUnit.SECONDS);
                return child.exitValue();
            } finally {
                kill(child);
                child.getInputStream().close();
                child.getErrorStream().close();
                child.getOutputStream().close();
                out.cancel(true);
                err.cancel(true);
                writer.cancel(true);
            }
        } finally {
            process = null;
        }
    }

    public void cancel() {
        cancelled.set(true);
        Process child = process;
        if (child != null) kill(child);
    }

    private static void kill(Process child) {
        child.descendants().forEach(p -> { if (p.isAlive()) p.destroyForcibly(); });
        if (child.isAlive()) child.destroyForcibly();
    }

    private void drain(InputStream input, Consumer<String> receiver) {
        try (Reader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = reader.read()) != -1) {
                if (c == '\n') {
                    if (!cancelled.get()) receiver.accept(line.toString());
                    line.setLength(0);
                } else if (c != '\r') {
                    if (line.length() >= MAX_LINE) throw new IOException("Слишком длинное событие Codex.");
                    line.append((char) c);
                }
            }
            if (!line.isEmpty() && !cancelled.get()) receiver.accept(line.toString());
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}
