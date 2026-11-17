package org.agty.aiassistant.changes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Checked, reversible import of a manually reviewed text patch. The original cwd stays bound to the undo. */
public final class ManualPatch {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private record State(boolean exists, byte[] bytes, FileMetadata.Access access) {
        boolean same(State other) { return exists == other.exists && Arrays.equals(bytes, other.bytes) && Objects.equals(access == null ? null : access.fingerprint(), other.access == null ? null : other.access.fingerprint()); }
    }
    private record Edit(String name, State before, State after) { }
    public static final class Applied {
        private final Path root;
        private final List<Edit> edits;
        private boolean undone;
        private Applied(Path root, List<Edit> edits) { this.root = root; this.edits = List.copyOf(edits); }
        public Path root() { return root; }
        public List<Path> paths() { return edits.stream().map(edit -> root.resolve(edit.name)).toList(); }
        public int size() { return edits.size(); }
        public synchronized void undo() throws IOException {
            if (undone) throw new IOException("Ручной patch уже отменён.");
            commit(root, edits.stream().map(edit -> new Edit(edit.name, edit.after, edit.before)).toList());
            undone = true;
        }
    }
    public static Applied apply(Path directory, ChangeSet changeSet, Set<Integer> selected) throws IOException {
        Path root = directory.toRealPath();
        List<Edit> edits = new ArrayList<>();
        long totalBytes = 0;
        for (var file : changeSet.files()) {
            if (!selected.isEmpty() && file.hunks().stream().noneMatch(hunk -> selected.contains(hunk.id()))) continue;
            Path path = safe(root, file.path());
            State before = read(path);
            boolean create = file.unifiedDiff().lines().takeWhile(line -> !line.startsWith("@@ ")).anyMatch(line -> line.equals("--- /dev/null"));
            if (create == before.exists || !Arrays.equals(before.bytes, file.beforeText().getBytes(StandardCharsets.UTF_8)))
                throw new IOException("Файл изменился после review или не соответствует операции patch: " + file.path());
            byte[] after;
            try { after = file.afterText(selected).getBytes(StandardCharsets.UTF_8); }
            catch (RuntimeException e) { throw new IOException("Patch не соответствует исходному файлу: " + file.path(), e); }
            if (after.length > MAX_BYTES) throw new IOException("Ручной patch превышает 16 MiB: " + file.path());
            totalBytes += (long) before.bytes.length + after.length;
            if (totalBytes > 64L * 1024 * 1024) throw new IOException("Ручной patch превышает общий лимит 64 MiB.");
            FileMetadata.Access access = before.access;
            if (create) {
                Files.createDirectories(path.getParent());
                Path template = Files.createTempFile(path.getParent(), ".agty-access-", ".tmp");
                try { access = FileMetadata.capture(template); } finally { Files.deleteIfExists(template); }
            }
            edits.add(new Edit(file.path(), before, new State(true, after, access)));
        }
        commit(root, edits);
        return new Applied(root, edits);
    }
    private static void commit(Path root, List<Edit> edits) throws IOException {
        for (Edit edit : edits) verify(root, edit.name, edit.before);
        List<Edit> written = new ArrayList<>();
        try {
            for (Edit edit : edits) {
                verify(root, edit.name, edit.before);
                write(root, edit.name, edit.after, edit.before);
                written.add(edit);
            }
        } catch (IOException e) {
            for (Edit edit : written.reversed()) {
                try {
                    State current = read(safe(root, edit.name));
                    if (!current.same(edit.after))
                        throw new IOException("Файл изменился во время восстановления: " + edit.name);
                    write(root, edit.name, edit.before, edit.after);
                } catch (IOException recovery) { e.addSuppressed(recovery); }
            }
            throw e;
        }
    }
    private static void verify(Path root, String name, State expected) throws IOException {
        if (!read(safe(root, name)).same(expected)) throw new IOException("Файл изменён после review; автоматическая запись запрещена: " + name);
    }
    public static String readOriginal(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return "";
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("Файл превышает 16 MiB: " + path);
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private static State read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new State(false, new byte[0], null);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Ожидается обычный файл: " + path);
        try { if (((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() > 1) throw new IOException("Ручной patch для hardlink запрещён: " + path); }
        catch (UnsupportedOperationException ignored) { }
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("Файл превышает 16 MiB: " + path);
        return new State(true, bytes, FileMetadata.capture(path));
    }

    private static Path safe(Path root, String name) throws IOException {
        if (Files.isSymbolicLink(root) || !root.toRealPath().equals(root)) throw new IOException("Рабочий каталог заменён ссылкой; автоматическая запись запрещена.");
        Path path = root.resolve(name).toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("Путь patch выходит за пределы проекта.");
        for (Path part : root.relativize(path)) if (part.toString().equalsIgnoreCase(".git")) throw new IOException("Ручной patch метаданных Git запрещён.");
        for (Path parent = path; !parent.equals(root); parent = parent.getParent()) {
            if (Files.isSymbolicLink(parent) || Files.exists(parent, LinkOption.NOFOLLOW_LINKS) && !parent.toRealPath().startsWith(root))
                throw new IOException("Ручной patch через ссылку запрещён.");
        }
        return path;
    }
    private static void write(Path root, String name, State state, State expected) throws IOException {
        Path path = safe(root, name);
        if (!state.exists) { verify(root, name, expected); Files.delete(path); return; }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".agty-patch-", ".tmp");
        try {
            Files.write(temporary, state.bytes);
            FileMetadata.apply(state.access, temporary);
            verify(root, name, expected);
            if (!expected.exists) Files.createLink(path, temporary); // atomic no-overwrite publication
            else Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private ManualPatch() { }
}
