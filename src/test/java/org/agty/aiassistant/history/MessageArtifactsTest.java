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
    }

}
