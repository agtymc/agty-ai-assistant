package org.agty.aiassistant.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SafeExportTest {
    @TempDir Path root;
    @Test void changedDestinationAndNewCollisionAreNeverOverwritten() throws Exception {
        Path file = root.resolve("export.md");
        try (var export = new SafeExport(file)) {
            export.stage("chat"); Files.writeString(file, "user");
            assertThrows(IOException.class, export::commit); assertEquals("user", Files.readString(file));
        }
        try (var export = new SafeExport(file)) {
            export.stage("chat"); Files.writeString(file, "later");
            assertThrows(IOException.class, export::commit); assertEquals("later", Files.readString(file));
        }
    }
    @Test void stagesWithoutTruncatingAndCommitsCompleteText() throws Exception {
        Path file = root.resolve("export.md"); Files.writeString(file, "original");
        try (var export = new SafeExport(file)) {
            export.stage("new\nданные"); assertEquals("original", Files.readString(file));
            export.commit(); assertEquals("new\nданные", Files.readString(file));
        }
        Path created = root.resolve("new.md");
        try (var export = new SafeExport(created)) { export.stage("new"); export.commit(); }
        assertEquals("new", Files.readString(created));
    }
    @Test void symbolicDestinationIsRejected() throws Exception {
        Path file = root.resolve("private"); Files.writeString(file, "user");
        Path link = root.resolve("export");
        try { Files.createSymbolicLink(link, file); }
        catch (IOException | UnsupportedOperationException e) { org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable"); }
        assertThrows(IOException.class, () -> new SafeExport(link)); assertEquals("user", Files.readString(file));
    }
    @Test void partialExportFailurePreservesOriginalAndCleansStagingFile() throws Exception {
        Path file = root.resolve("export.md"); Files.writeString(file, "original");
        try (var export = new SafeExport(file)) {
            assertThrows(IOException.class, () -> export.stage(writer -> { writer.write("partial"); throw new IOException("disk full"); }));
            assertThrows(IOException.class, export::commit);
            assertEquals("original", Files.readString(file));
        }
        try (var paths = Files.list(root)) { assertEquals(List.of(file), paths.toList()); }
    }
}
