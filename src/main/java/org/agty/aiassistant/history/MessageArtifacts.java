package org.agty.aiassistant.history;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import org.agty.aiassistant.chat.ChatSessions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.HexFormat;

/** Full local results are immutable blobs; only previews enter session XML and the DOM. */
@Service(Service.Level.APP)
public final class MessageArtifacts {
    private static final int MAX_TEXT_BYTES = 256 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 21 * 1024 * 1024;
    private final Path directory;
    private final Path privateRoot;
    public MessageArtifacts() { this(PathManager.getSystemDir().resolve("agty-ai-assistant/results"), PathManager.getSystemDir().resolve("agty-ai-assistant")); }
    public MessageArtifacts(Path directory) { this(directory, directory); }
    MessageArtifacts(Path directory, Path privateRoot) {
        this.directory = directory.toAbsolutePath().normalize(); this.privateRoot = privateRoot.toAbsolutePath().normalize();
        if (!this.directory.startsWith(this.privateRoot)) throw new IllegalArgumentException("Хранилище должно находиться внутри частного каталога.");
    }
    public static MessageArtifacts getInstance() { return ApplicationManager.getApplication().getService(MessageArtifacts.class); }
    public void offload(ChatSessions.Message message) throws IOException {
        if (message.complete && !message.imageData.isEmpty()) { message.imageArtifact = storeImage(message.imageData); message.imageData = ""; }
        if (!message.complete || !message.artifact.isEmpty() || message.markdown.length() <= 16_000) return;
        String original = message.markdown;
        byte[] encoded = original.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_TEXT_BYTES) throw new IOException("Полный результат превышает лимит локального хранилища.");
        String id;
        try { id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        ensureDirectory();
        Path target = resolve(id);
        boolean replace = !Files.exists(target);
        if (!replace) { try { read(id); } catch (IOException e) { replace = true; } }
        if (replace) {
            Path temporary = Files.createTempFile(directory, "result-", ".tmp");
            try {
                Files.write(temporary, encoded);
                replace(temporary, target);
            } finally { Files.deleteIfExists(temporary); }
        }
        message.artifact = id; message.markdown = original.substring(0, 8_000) + "\n\n… Полный результат доступен отдельно.";
    }
    public String storeImage(String encoded) throws IOException {
        if (encoded.startsWith("data:image/")) { int separator = encoded.indexOf(','); if (separator < 0) throw new IOException("Некорректное изображение."); encoded = encoded.substring(separator + 1); }
        if (encoded.length() > 28_000_000) throw new IOException("Изображение превышает лимит локального предпросмотра.");
        byte[] bytes;
        try { bytes = java.util.Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException e) { throw new IOException("Codex не предоставил корректные inline-данные изображения.", e); }
        String extension;
        if (bytes.length >= 8 && bytes[0] == (byte) 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71) extension = "png";
        else if (bytes.length >= 3 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255) extension = "jpg";
        else if (bytes.length >= 6 && new String(bytes, 0, 3, StandardCharsets.US_ASCII).equals("GIF")) extension = "gif";
        else if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) extension = "webp";
        else throw new IOException("Формат inline-изображения не поддерживается.");
        String id;
        try { id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) + "." + extension; }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        ensureDirectory(); Path path = directory.resolve(id);
        boolean replace = true;
        try { imagePath(id); replace = false; } catch (IOException ignored) { }
        if (replace) {
            Path temporary = Files.createTempFile(directory, "image-", ".tmp");
            try { Files.write(temporary, bytes); replace(temporary, path); } finally { Files.deleteIfExists(temporary); }
        }
        return id;
    }
    public Path imagePath(String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}\\.(png|jpg|gif|webp)")) throw new IOException("Некорректный ID изображения.");
        Path path = directory.resolve(id); byte[] bytes = readBounded(path, MAX_IMAGE_BYTES);
        try { if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(id.substring(0, 64))) throw new IOException("Изображение повреждено."); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return path;
    }
    private Path resolve(String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Некорректный ID результата.");
        return directory.resolve(id + ".txt");
    }
    public String read(String id) throws IOException {
        byte[] bytes = readBounded(resolve(id), MAX_TEXT_BYTES);
        try { if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(id)) throw new IOException("Полный результат повреждён."); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    private void ensureDirectory() throws IOException {
        Files.createDirectories(privateRoot);
        secureDirectory(privateRoot);
        Path parent = privateRoot;
        for (Path part : privateRoot.relativize(directory)) {
            if (part.toString().isEmpty()) continue;
            parent = parent.resolve(part);
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(parent); } catch (FileAlreadyExistsException concurrent) { }
            }
            secureDirectory(parent);
        }
    }
    private static void secureDirectory(Path path) throws IOException {
        requireDirectory(path);
        var attributes = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes != null) attributes.setPermissions(PosixFilePermissions.fromString("rwx------"));
    }
    private void requireDirectory() throws IOException {
        requireDirectory(privateRoot);
        Path parent = privateRoot;
        for (Path part : privateRoot.relativize(directory)) { parent = parent.resolve(part); requireDirectory(parent); }
    }
    private static void requireDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Хранилище результатов не должно быть символической ссылкой.");
    }
    private byte[] readBounded(Path path, int limit) throws IOException {
        requireDirectory();
        var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > limit) throw new IOException("Недопустимый файл или размер локального результата.");
        // Do not follow a substituted link, and keep the bound even if the file grows after stat.
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Локальный результат превышает допустимый размер.");
            return bytes;
        }
    }
    private static void replace(Path temporary, Path target) throws IOException {
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    public String text(ChatSessions.Message message) {
        if (message.artifact.isEmpty()) return message.markdown;
        try { return read(message.artifact); }
        catch (IOException e) { throw new IllegalStateException("Полный результат отсутствует в локальном хранилище.", e); }
    }
}
