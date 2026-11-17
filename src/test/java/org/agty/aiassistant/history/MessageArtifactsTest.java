package org.agty.aiassistant.history;
import org.agty.aiassistant.chat.ChatSessions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class MessageArtifactsTest {
    @TempDir Path directory;
    @Test void previewKeepsImmutableFullResultAndRejectsPathTraversal() throws Exception {
        var store = new MessageArtifacts(directory); var message = new ChatSessions.Message();
        String full = "Юникод\r\n".repeat(3000); message.markdown = full;
        store.offload(message);
        assertTrue(message.markdown.length() < full.length()); assertEquals(full, store.text(message));
        store.offload(message); assertEquals(full, store.read(message.artifact));
        assertThrows(IllegalArgumentException.class, () -> store.read("../../secret"));
    }
    @Test void imagesAreHashedAndCorruptionOrTraversalNeverOpensUntrustedPath() throws Exception {
        var store = new MessageArtifacts(directory);
        byte[] image = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1sAAAAASUVORK5CYII=");
        String id = store.storeImage(java.util.Base64.getEncoder().encodeToString(image));
        assertArrayEquals(image, java.nio.file.Files.readAllBytes(store.imagePath(id)));
        assertThrows(java.io.IOException.class, () -> store.imagePath("../image.png"));
        java.nio.file.Files.write(directory.resolve(id), new byte[]{1, 2, 3});
        assertThrows(java.io.IOException.class, () -> store.imagePath(id));
        var recovered = new ChatSessions.Message(); recovered.imageData = java.util.Base64.getEncoder().encodeToString(image);
        store.offload(recovered);
        assertEquals(id, recovered.imageArtifact); assertEquals("", recovered.imageData);
        assertArrayEquals(image, java.nio.file.Files.readAllBytes(store.imagePath(recovered.imageArtifact)));
    }
    @Test void rejectsOversizedCacheFilesBeforeLoadingThemAndKeepsNewPayloadOnFailure() throws Exception {
        var store = new MessageArtifacts(directory);
        String id = "a".repeat(64) + ".png";
        try (var file = new java.io.RandomAccessFile(directory.resolve(id).toFile(), "rw")) { file.setLength(22L * 1024 * 1024); }
        assertThrows(java.io.IOException.class, () -> store.imagePath(id));
        var message = new ChatSessions.Message(); message.imageData = "not-base64";
        assertThrows(java.io.IOException.class, () -> store.offload(message));
        assertEquals("not-base64", message.imageData); assertEquals("", message.imageArtifact);
    }
    @Test void cacheLinksCannotReadOrOverwriteTheirTargets() throws Exception {
        var root = java.nio.file.Files.createDirectory(directory.resolve("cache"));
        var store = new MessageArtifacts(root);
        var message = new ChatSessions.Message(); message.markdown = "private result ".repeat(2000); store.offload(message);
        var target = directory.resolve("outside.txt"); java.nio.file.Files.writeString(target, "outside original");
        var blob = root.resolve(message.artifact + ".txt"); java.nio.file.Files.delete(blob);
        try { java.nio.file.Files.createSymbolicLink(blob, target); }
        catch (UnsupportedOperationException | java.io.IOException e) { Assumptions.abort("Symbolic links unavailable: " + e.getClass().getSimpleName()); }
        assertThrows(java.io.IOException.class, () -> store.read(message.artifact));
        var repeated = new ChatSessions.Message(); repeated.markdown = "private result ".repeat(2000); store.offload(repeated);
        assertFalse(java.nio.file.Files.isSymbolicLink(blob));
        assertEquals("outside original", java.nio.file.Files.readString(target));
        assertEquals("private result ".repeat(2000), store.read(message.artifact));
        Path linkedCache = directory.resolve("linked-cache"); java.nio.file.Files.createSymbolicLink(linkedCache, root);
        var source = new ChatSessions.Message(); source.markdown = "must remain available ".repeat(1000);
        assertThrows(java.io.IOException.class, () -> new MessageArtifacts(linkedCache).offload(source));
        assertEquals("", source.artifact); assertEquals("must remain available ".repeat(1000), source.markdown);
        assertThrows(java.io.IOException.class, () -> new MessageArtifacts(linkedCache.resolve("results"), linkedCache).offload(source));
        assertFalse(java.nio.file.Files.exists(root.resolve("results")), "A substituted private parent must not receive new files");
    }
    @Test void ownsPrivateCacheDirectoriesAndBlobPermissions() throws Exception {
        Path privateRoot = directory.resolve("private"), cache = privateRoot.resolve("results");
        var store = new MessageArtifacts(cache, privateRoot);
        var message = new ChatSessions.Message(); message.markdown = "sensitive content ".repeat(2000); store.offload(message);
        if (java.nio.file.Files.getFileAttributeView(cache, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), java.nio.file.Files.getPosixFilePermissions(privateRoot));
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), java.nio.file.Files.getPosixFilePermissions(cache));
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), java.nio.file.Files.getPosixFilePermissions(cache.resolve(message.artifact + ".txt")));
        }
    }
}
