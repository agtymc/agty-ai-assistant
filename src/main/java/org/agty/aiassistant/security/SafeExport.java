package org.agty.aiassistant.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stages an export before touching its destination and refuses stale overwrite consent. */
public final class SafeExport implements AutoCloseable {
    private final Path target;
    private final String expected;
    private Path temporary;
    private boolean prepared;

    public SafeExport(Path chosen) throws IOException {
        Path absolute = chosen.toAbsolutePath().normalize();
        target = absolute.getParent().toRealPath().resolve(absolute.getFileName());
        expected = fingerprint(target);
    }

    public Path target() { return target; }

    @FunctionalInterface public interface ContentWriter { void write(java.io.Writer writer) throws IOException; }

    public void stage(String content) throws IOException { stage(writer -> writer.write(content)); }

    public void stage(ContentWriter content) throws IOException {
        if (temporary != null) throw new IOException("Экспорт уже подготовлен.");
        temporary = Files.createTempFile(target.getParent(), ".agty-export-", ".tmp");
        try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) { content.write(writer); }
        prepared = true;
    }

    public void commit() throws IOException {
        if (temporary == null || !prepared) throw new IOException("Экспорт не подготовлен.");
        if (!target.getParent().toRealPath().equals(target.getParent()) || !expected.equals(fingerprint(target)))
            throw new IOException("Файл экспорта изменился после выбора. Выберите его заново.");
        if (expected.equals("absent")) {
            // createLink is an atomic no-replace operation; ATOMIC_MOVE alone can
            // overwrite a concurrently created target on Unix even without REPLACE_EXISTING.
            Files.createLink(target, temporary);
            Files.delete(temporary);
        } else {
            org.agty.aiassistant.changes.FileMetadata.copyAccess(target, temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        temporary = null;
    }

    private static String fingerprint(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return "absent";
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Экспорт требует обычный файл, без символической ссылки: " + path);
        var access = org.agty.aiassistant.changes.FileMetadata.capture(path);
        final long maximum = 64L * 1024 * 1024;
        if (Files.size(path) > maximum) throw new IOException("Существующий файл больше 64 MiB. Выберите новый файл для экспорта.");
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192]; int size; long total = 0;
                while ((size = input.read(buffer)) >= 0) {
                    if ((total += size) > maximum) throw new IOException("Файл назначения вырос при проверке. Экспорт отменён.");
                    digest.update(buffer, 0, size);
                }
            }
            if (!access.fingerprint().equals(org.agty.aiassistant.changes.FileMetadata.fingerprint(path)))
                throw new IOException("Права файла изменились во время проверки. Выберите файл заново.");
            return HexFormat.of().formatHex(digest.digest()) + ":" + access.fingerprint();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override public void close() throws IOException { if (temporary != null) Files.deleteIfExists(temporary); }
}
