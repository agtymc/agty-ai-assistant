package org.agty.aiassistant.history;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import org.agty.aiassistant.chat.ChatSessions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Full local results are immutable blobs; only previews enter session XML and the DOM. */
@Service(Service.Level.APP)
public final class MessageArtifacts {
    private final Path directory;
    public MessageArtifacts() { this(PathManager.getSystemDir().resolve("agty-ai-assistant/results")); }
    public MessageArtifacts(Path directory) { this.directory = directory; }
    public static MessageArtifacts getInstance() { return ApplicationManager.getApplication().getService(MessageArtifacts.class); }
    public void offload(ChatSessions.Message message) throws IOException {
        if (message.complete && !message.imageData.isEmpty()) { message.imageArtifact = storeImage(message.imageData); message.imageData = ""; }
        if (!message.complete || !message.artifact.isEmpty() || message.markdown.length() <= 16_000) return;
        String original = message.markdown;
        String id;
        try { id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Files.createDirectories(directory);
        Path target = resolve(id);
        boolean replace = !Files.exists(target);
        if (!replace) { try { read(id); } catch (IOException e) { replace = true; } }
        if (replace) {
            Path temporary = Files.createTempFile(directory, "result-", ".tmp");
            try {
                Files.writeString(temporary, original, StandardCharsets.UTF_8);
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
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
        Files.createDirectories(directory); Path path = directory.resolve(id);
        if (!Files.exists(path)) {
            Path temporary = Files.createTempFile(directory, "image-", ".tmp");
            try { Files.write(temporary, bytes); Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); } finally { Files.deleteIfExists(temporary); }
        }
        return id;
    }
    public Path imagePath(String id) throws IOException {
        if (!id.matches("[a-f0-9]{64}\\.(png|jpg|gif|webp)")) throw new IOException("Некорректный ID изображения.");
        Path path = directory.resolve(id); byte[] bytes = Files.readAllBytes(path);
        try { if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(id.substring(0, 64))) throw new IOException("Изображение повреждено."); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return path;
    }
    private Path resolve(String id) {
        if (id == null || !id.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Некорректный ID результата.");
        return directory.resolve(id + ".txt");
    }
    public String read(String id) throws IOException {
        byte[] bytes = Files.readAllBytes(resolve(id));
        try { if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(id)) throw new IOException("Полный результат повреждён."); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
    public String text(ChatSessions.Message message) {
        if (message.artifact.isEmpty()) return message.markdown;
        try { return read(message.artifact); }
        catch (IOException e) { throw new IllegalStateException("Полный результат отсутствует в локальном хранилище.", e); }
    }
}
