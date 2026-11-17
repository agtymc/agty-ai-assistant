package org.agty.aiassistant.changes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Read-only views of index, worktree and untracked files, including unborn repositories. */
public final class WorkingTreeChanges {
    public enum Stage { STAGED, UNSTAGED, UNTRACKED }
    public record Entry(Stage stage, String path, String previous, char status) {
        @Override public String toString() { return stage + " · " + status + " · " + path; }
    }
    public record Pair(byte[] before, byte[] after) { }
    private record Result(int exit, byte[] output) { }
    private static Result git(Path root, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.quotePath=false")); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).directory(root.toFile()).start();
        var stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));
        var stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream()));
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Git превысил 20 секунд."); }
            byte[] output = stdout.get(5, TimeUnit.SECONDS), error = stderr.get(5, TimeUnit.SECONDS);
            if (process.exitValue() > 1) throw new IOException(new String(error, StandardCharsets.UTF_8));
            return new Result(process.exitValue(), output);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); throw new IOException(e); }
        catch (ExecutionException | TimeoutException e) { process.destroyForcibly(); throw new IOException(e); }
    }
    private static byte[] read(InputStream input) {
        try (input) {
            byte[] bytes = input.readNBytes(64 * 1024 * 1024 + 1);
            if (bytes.length > 64 * 1024 * 1024) throw new IOException("Результат Git превышает 64 MiB."); return bytes;
        } catch (IOException e) { throw new CompletionException(e); }
    }
    public static List<Entry> list(Path root) throws IOException {
        var result = git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all");
        String[] records = new String(result.output, StandardCharsets.UTF_8).split("\u0000");
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < records.length; i++) {
            String row = records[i]; if (row.length() < 4) continue;
            char staged = row.charAt(0), unstaged = row.charAt(1); String path = row.substring(3), previous = path;
            if (staged == 'R' || staged == 'C' || unstaged == 'R' || unstaged == 'C') {
                if (++i >= records.length) throw new IOException("Неполный rename status Git."); previous = records[i];
            }
            if (staged == '?' && unstaged == '?') entries.add(new Entry(Stage.UNTRACKED, path, previous, '?'));
            else {
                if (staged != ' ' && staged != '!') entries.add(new Entry(Stage.STAGED, path, previous, staged));
                if (unstaged != ' ' && unstaged != '!') entries.add(new Entry(Stage.UNSTAGED, path, previous, unstaged));
            }
        }
        return List.copyOf(entries);
    }
    private static byte[] blob(Path root, String revision, String path, boolean absent) throws IOException {
        if (absent) return new byte[0];
        var result = git(root, "show", revision + ":" + path);
        if (result.exit != 0) throw new IOException("Версия файла недоступна: " + revision + ":" + path); return result.output;
    }
    private static byte[] current(Path root, String name, boolean absent) throws IOException {
        if (absent) return new byte[0];
        Path path = root.resolve(name).normalize(); if (!path.startsWith(root)) throw new IOException("Путь вне проекта.");
        if (Files.isSymbolicLink(path)) return Files.readSymbolicLink(path).toString().getBytes(StandardCharsets.UTF_8);
        if (!path.toRealPath().startsWith(root.toRealPath())) throw new IOException("Файл находится вне проекта.");
        try (var input = Files.newInputStream(path)) { return read(input); }
    }
    public static Pair contents(Path root, Entry entry) throws IOException {
        root = root.toAbsolutePath().normalize();
        String previous = entry.status == 'R' ? entry.previous : entry.path;
        if (entry.stage == Stage.UNTRACKED) return new Pair(new byte[0], current(root, entry.path, false));
        if (entry.stage == Stage.STAGED) return new Pair(blob(root, "HEAD", previous, entry.status == 'A' || entry.status == 'C'),
                blob(root, "", entry.path, entry.status == 'D'));
        return new Pair(blob(root, "", previous, entry.status == 'A'), current(root, entry.path, entry.status == 'D'));
    }
    private WorkingTreeChanges() { }
}
