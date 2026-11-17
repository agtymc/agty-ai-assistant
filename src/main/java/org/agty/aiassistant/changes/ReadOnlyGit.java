package org.agty.aiassistant.changes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Read-only Git inspection must not execute repository-configured fsmonitor or clean/process programs. */
public final class ReadOnlyGit {
    public static List<String> command(Path root, String... arguments) throws IOException {
        var process = new ProcessBuilder("git", "--no-pager", "-c", "core.fsmonitor=false", "config", "--null", "--name-only",
                "--get-regexp", "^filter\\..*\\.(clean|process|required)$")
                .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        var output = CompletableFuture.supplyAsync(() -> {
            try (var input = process.getInputStream()) {
                byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) throw new IOException("Список Git filters превышает 1 MiB.");
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) { throw new CompletionException(e); }
        });
        String names;
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("Чтение Git config превысило 5 секунд.");
            if (process.exitValue() > 1) throw new IOException("Невозможно безопасно проверить настройки Git filters.");
            names = output.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        catch (ExecutionException | TimeoutException e) { throw new IOException(e); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        List<String> command = new ArrayList<>(List.of("git", "--no-pager", "--no-optional-locks", "-c", "core.fsmonitor=false"));
        for (String name : names.split("\u0000")) {
            if (name.isEmpty()) continue;
            command.add("-c"); command.add(name + (name.endsWith(".required") ? "=false" : "="));
        }
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }
    private ReadOnlyGit() { }
}
