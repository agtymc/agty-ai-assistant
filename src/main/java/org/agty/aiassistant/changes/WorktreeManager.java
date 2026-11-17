package org.agty.aiassistant.changes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Git worktrees without force, checkout replacement, resets or branch deletion. */
public final class WorktreeManager {
    public record Worktree(Path directory, String branch) { @Override public String toString() { return branch + " · " + directory; } }
    private static String run(Path root, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        var output = CompletableFuture.supplyAsync(() -> { try { return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); } catch (IOException e) { throw new CompletionException(e); } });
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Git worktree превысил 30 секунд."); }
            String text = output.get(5, TimeUnit.SECONDS); if (process.exitValue() != 0) throw new IOException(text); return text;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); throw new IOException(e); }
        catch (ExecutionException | TimeoutException e) { process.destroyForcibly(); throw new IOException(e); }
    }
    public static List<Worktree> list(Path root) throws IOException {
        String value = run(root, "worktree", "list", "--porcelain", "-z");
        List<Worktree> result = new ArrayList<>(); String directory = "", branch = "detached";
        for (String field : value.split("\u0000", -1)) {
            if (field.startsWith("worktree ")) { if (!directory.isBlank()) result.add(new Worktree(Path.of(directory), branch)); directory = field.substring(9); branch = "detached"; }
            else if (field.startsWith("branch ")) branch = field.substring(7).replaceFirst("^refs/heads/", "");
        }
        if (!directory.isBlank()) result.add(new Worktree(Path.of(directory), branch)); return List.copyOf(result);
    }
    public static Path create(Path root, Path directory, String branch) throws IOException {
        if (branch == null || branch.isBlank() || branch.startsWith("-")) throw new IOException("Укажите имя новой ветки.");
        run(root, "check-ref-format", "--branch", branch);
        Path target = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(target)) throw new IOException("Каталог worktree не может быть symlink.");
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) throw new IOException("Путь занят файлом.");
            try (var entries = Files.list(target)) { if (entries.findAny().isPresent()) throw new IOException("Каталог worktree должен быть пустым."); }
        }
        run(root, "worktree", "add", "-b", branch, "--", target.toString(), "HEAD"); return target.toRealPath();
    }
    private WorktreeManager() { }
}
