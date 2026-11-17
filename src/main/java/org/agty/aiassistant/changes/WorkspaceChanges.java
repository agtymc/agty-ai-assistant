package org.agty.aiassistant.changes;

import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Captures real pre-task bytes, never Git HEAD. Unattributed changes remain visible but cannot be rolled back automatically. */
public final class WorkspaceChanges implements AutoCloseable {
    public enum Operation { CREATE, MODIFY, DELETE, RENAME }
    public record Version(boolean exists, Path backup, String sha256, boolean trustworthy, String mode) {
        public Version(boolean exists, Path backup, String sha256, boolean trustworthy) { this(exists, backup, sha256, trustworthy, ""); }
    }
    public record Change(String source, String target, Operation operation, Version before, Version after,
                         Version targetBefore, String patch, boolean proven) {
        @Override public String toString() { return operation + " · " + source + (source.equals(target) ? "" : " → " + target) + (proven ? "" : " · откат недоступен"); }
    }
    private final Path root, stash;
    private final Map<String, Version> baseline = new LinkedHashMap<>();
    private final Map<String, JsonObject> items = new LinkedHashMap<>();
    private final Set<String> edited = ConcurrentHashMap.newKeySet();
    private final List<Change> changes = new ArrayList<>();
    private boolean inventoryComplete = true;
    private long stored;
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final Set<String> EXCLUDED = Set.of(".git", ".idea", ".gradle", "build", "node_modules", ".venv", "venv", ".intellijPlatform", "out", "target", "__pycache__");
    private WorkspaceChanges(Path root, Path stash) { this.root = root; this.stash = stash; }
    public static WorkspaceChanges capture(Path directory) throws IOException {
        var tracker = new WorkspaceChanges(directory.toRealPath(), Files.createTempDirectory("agty-task-versions-"));
        try {
            Files.walkFileTree(tracker.root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(tracker.root) && EXCLUDED.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (EXCLUDED.contains(file.getFileName().toString())) return FileVisitResult.CONTINUE;
                    if (tracker.baseline.size() >= 100_000) { tracker.inventoryComplete = false; return FileVisitResult.TERMINATE; }
                    tracker.baseline.put(tracker.relative(file), tracker.version(file)); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException e) { tracker.inventoryComplete = false; return FileVisitResult.CONTINUE; }
            });
            return tracker;
        } catch (Exception e) { tracker.close(); throw e; }
    }
    public Path root() { return root; }
    /** Resolve existing aliases/junctions while retaining the suffix of a not-yet-existing file. */
    public static Path canonical(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize(), existing = absolute;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IOException("Путь недоступен: " + file);
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
    }
    public void markEdited(Path file) {
        try { edited.add(relative(safe(canonical(file).toString()))); }
        catch (IOException ignored) { }
    }
    public synchronized void report(String itemId, JsonObject item) {
        if (item != null && item.has("changes")) items.put(itemId, item.deepCopy());
    }
    private Version original(String name) {
        return baseline.getOrDefault(name, new Version(false, null, "", inventoryComplete && eligible(name)));
    }
    private boolean eligible(String name) {
        for (Path part : Path.of(name)) if (EXCLUDED.contains(part.toString())) return false;
        return true;
    }
    private String relative(Path path) { return root.relativize(path.toAbsolutePath().normalize()).toString(); }
    private Path safe(String name) throws IOException {
        Path path = root.resolve(name).toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("Путь вне рабочего каталога.");
        for (Path part : root.relativize(path)) if (part.toString().equals(".git")) throw new IOException("Автоматический откат метаданных Git запрещён.");
        Path parent = path;
        while (parent != null && !parent.equals(root)) {
            if (Files.isSymbolicLink(parent)) throw new IOException("Откат через symlink запрещён."); parent = parent.getParent();
        }
        return path;
    }
    private Version version(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Version(false, null, "", true);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return new Version(true, null, "", false);
        String hash = hash(file); long size = Files.size(file);
        if (stored + size > MAX_BYTES) return new Version(true, null, hash, false);
        Path copy = Files.createTempFile(stash, "version-", ".bin"); Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        stored += size;
        return new Version(true, copy, hash, hash.equals(hash(copy)) && hash.equals(hash(file)), mode(file));
    }
    private static String mode(Path path) throws IOException {
        try { return new java.util.TreeSet<>(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)).toString(); }
        catch (UnsupportedOperationException e) {
            try { var attributes = Files.readAttributes(path, java.nio.file.attribute.DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return "dos:" + attributes.isReadOnly() + ":" + attributes.isHidden() + ":" + attributes.isSystem();
            } catch (UnsupportedOperationException unsupported) { return ""; }
        }
    }
    private static String hash(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static byte[] bytes(Version version) throws IOException {
        if (!version.exists) return new byte[0];
        if (version.backup == null) throw new IOException("Полная исходная версия не сохранена.");
        if (!hash(version.backup).equals(version.sha256)) throw new IOException("Сохранённая версия повреждена; автоматический откат запрещён.");
        return Files.readAllBytes(version.backup);
    }
    private static String text(Version version) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes(version))).toString();
    }
    public synchronized void finish() throws IOException {
        changes.clear();
        Map<String, String> expected = new HashMap<>(); Set<String> unproven = new HashSet<>();
        Map<String, String> sourceForTarget = new LinkedHashMap<>(); Map<String, String> patches = new HashMap<>();
        for (var item : items.values()) for (var element : item.getAsJsonArray("changes")) {
            var change = element.getAsJsonObject();
            try {
                String source = relative(safe(change.get("path").getAsString()));
                var kind = change.getAsJsonObject("kind"); String type = kind.get("type").getAsString();
                String target = kind.has("move_path") && !kind.get("move_path").isJsonNull() ? relative(safe(kind.get("move_path").getAsString())) : source;
                String patch = change.get("diff").getAsString();
                String origin = sourceForTarget.getOrDefault(source, source);
                if (!source.equals(target) && sourceForTarget.containsKey(source)) {
                    sourceForTarget.remove(source);
                    String earlier = patches.remove(source); if (earlier != null) patches.put(target, earlier);
                    if (unproven.remove(source)) unproven.add(target);
                }
                sourceForTarget.put(target, origin); patches.merge(target, patch, (a, b) -> a + "\n" + b);
                try {
                    String before = expected.containsKey(source) ? expected.get(source) : text(original(source));
                    String after = StrictPatch.apply(before, patch);
                    if (type.equals("delete") && !after.isEmpty()) throw new IllegalStateException();
                    expected.put(target, after);
                    if (!source.equals(target) || type.equals("delete")) expected.put(source, "");
                } catch (Exception e) { unproven.add(target); }
            } catch (IOException | RuntimeException e) { inventoryComplete = false; }
        }
        for (var pair : sourceForTarget.entrySet()) {
            String target = pair.getKey(), source = pair.getValue();
            Version before = original(source), after = version(safe(target)), targetBefore = original(target);
            boolean rename = !source.equals(target);
            Operation operation = rename ? Operation.RENAME : !before.exists ? Operation.CREATE : !after.exists ? Operation.DELETE : Operation.MODIFY;
            boolean proven = !unproven.contains(target) && before.trustworthy && after.trustworthy && targetBefore.trustworthy
                    && !edited.contains(source) && !edited.contains(target);
            try { proven &= expected.containsKey(target) && expected.get(target).equals(text(after)); }
            catch (IOException e) { proven = false; }
            if (rename) proven &= !Files.exists(safe(source), LinkOption.NOFOLLOW_LINKS);
            changes.add(new Change(source, target, operation, before, after, targetBefore, patches.get(target), proven));
        }
        // Shell tools can change files without emitting fileChange items. Show those bytes too,
        // but never infer ownership over concurrent edits merely from a before/after difference.
        Set<String> reported = new HashSet<>();
        for (var change : changes) { reported.add(change.source); reported.add(change.target); }
        Set<String> observed = new LinkedHashSet<>(baseline.keySet());
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && EXCLUDED.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                String name = relative(path);
                if (eligible(name) && observed.size() < 100_000) observed.add(name);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path path, IOException e) { inventoryComplete = false; return FileVisitResult.CONTINUE; }
        });
        for (String name : observed) {
            if (reported.contains(name) || !eligible(name)) continue;
            Version before = original(name);
            Path path;
            try { path = safe(name); } catch (IOException e) { continue; }
            boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            if (exists == before.exists && (!exists || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && hash(path).equals(before.sha256) && mode(path).equals(before.mode))) continue;
            Version after = version(path);
            Operation operation = !before.exists ? Operation.CREATE : !after.exists ? Operation.DELETE : Operation.MODIFY;
            changes.add(new Change(name, name, operation, before, after, before, "", false));
        }
    }
    public String diff(Change change) throws IOException {
        Path empty = Files.createTempFile(stash, "empty-", ".txt");
        Path before = change.before.exists ? change.before.backup : empty;
        Path after = change.after.exists ? change.after.backup : empty;
        if (before == null || after == null) { Files.deleteIfExists(empty); throw new IOException("Для этого файла не сохранены полные версии."); }
        try {
            var process = new ProcessBuilder("git", "-c", "core.quotePath=false", "diff", "--no-index", "--no-ext-diff", "--no-textconv", "--", before.toString(), after.toString())
                    .directory(stash.toFile()).redirectErrorStream(true).start();
            var output = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
                catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
            });
            try {
                if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("Git diff превысил 20 секунд."); }
                String value = output.get(5, java.util.concurrent.TimeUnit.SECONDS);
                if (process.exitValue() > 1) throw new IOException(value); return value;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); process.destroyForcibly(); throw new IOException(e); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) { process.destroyForcibly(); throw new IOException(e); }
        } finally { Files.deleteIfExists(empty); }
    }
    public synchronized void rollbackHunk(Change change, int index) throws IOException {
        if (!changes.contains(change) || !change.proven || change.operation != Operation.MODIFY)
            throw new IOException("Откат hunk доступен только для достоверного изменения обычного файла.");
        verifyCurrent(change.target, change.after);
        var hunks = StrictPatch.hunks(diff(change));
        if (index < 0 || index >= hunks.size()) throw new IOException("Фрагмент не найден.");
        String restored = StrictPatch.apply(text(change.after), StrictPatch.reverse(hunks.get(index)));
        Path backup = Files.createTempFile(stash, "partial-", ".bin");
        Files.copy(change.after.backup, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.writeString(backup, restored, StandardCharsets.UTF_8);
        Version version = new Version(true, backup, hash(backup), true, change.after.mode);
        verifyCurrent(change.target, change.after); write(change.target, version);
        int position = changes.indexOf(change);
        if (version.sha256.equals(change.before.sha256)) changes.remove(position);
        else changes.set(position, new Change(change.source, change.target, change.operation, change.before, version, change.targetBefore, "", true));
    }
    public synchronized List<Change> changes() { return List.copyOf(changes); }
    private void verifyCurrent(String name, Version expected) throws IOException {
        Path path = safe(name);
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (exists != expected.exists || exists && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !hash(path).equals(expected.sha256) || !expected.mode.isEmpty() && !mode(path).equals(expected.mode)))
            throw new IOException("После задачи изменён файл " + name + ". Откройте сравнение; автоматический откат запрещён.");
        if (edited.contains(name)) throw new IOException("Файл редактировался пользователем: " + name);
    }
    public synchronized void rollback(List<Change> selected) throws IOException {
        if (selected.isEmpty()) return;
        Map<String, Version> restore = new LinkedHashMap<>(), current = new LinkedHashMap<>();
        for (var change : selected) {
            if (!changes.contains(change) || !change.proven) throw new IOException("Нет достоверной базы для отката " + change.source);
            verifyCurrent(change.target, change.after);
            restore.put(change.target, change.targetBefore); current.put(change.target, change.after);
            if (!change.source.equals(change.target)) {
                verifyCurrent(change.source, new Version(false, null, "", true));
                restore.put(change.source, change.before); current.put(change.source, new Version(false, null, "", true));
            }
        }
        List<String> applied = new ArrayList<>();
        try {
            for (var pair : restore.entrySet()) {
                verifyCurrent(pair.getKey(), current.get(pair.getKey())); write(pair.getKey(), pair.getValue()); applied.add(pair.getKey());
            }
        } catch (IOException e) {
            for (String name : applied.reversed()) {
                try { verifyCurrent(name, restore.get(name)); write(name, current.get(name)); } catch (IOException recovery) { e.addSuppressed(recovery); }
            }
            throw e;
        }
        changes.removeAll(selected);
    }
    private void write(String name, Version version) throws IOException {
        Path path = safe(name);
        if (!version.exists) { Files.deleteIfExists(path); return; }
        if (version.backup == null) throw new IOException("Нет сохранённой версии " + name);
        if (!hash(version.backup).equals(version.sha256)) throw new IOException("Повреждена сохранённая версия " + name);
        Files.createDirectories(path.getParent()); Path temporary = Files.createTempFile(path.getParent(), ".agty-rollback-", ".tmp");
        try {
            Files.copy(version.backup, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void close() throws IOException {
        try (var paths = Files.walk(stash)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
}
