package org.agty.aiassistant.changes;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkspaceChangesTest {
    @TempDir Path root;
    private void report(WorkspaceChanges changes, String source, String kind, String target, String patch) {
        var object = new com.google.gson.JsonObject(); object.addProperty("type", "fileChange");
        var item = new com.google.gson.JsonObject(); item.addProperty("path", source); item.addProperty("diff", patch);
        var operation = new com.google.gson.JsonObject(); operation.addProperty("type", kind); if (target != null) operation.addProperty("move_path", target);
        item.add("kind", operation); var list = new com.google.gson.JsonArray(); list.add(item); object.add("changes", list); changes.report(source, object);
    }
    @Test void rollbackPreservesDirtyBaselineAndRejectsLaterEdits() throws Exception {
        var file = root.resolve("file.txt"); Files.writeString(file, "user draft\n");
        try (var changes = WorkspaceChanges.capture(root)) {
            Files.writeString(file, "agent\n"); report(changes, "file.txt", "update", null, "@@ -1 +1 @@\n-user draft\n+agent\n"); changes.finish();
            assertTrue(changes.changes().getFirst().proven());
            Files.writeString(file, "later user edit\n");
            assertThrows(java.io.IOException.class, () -> changes.rollback(changes.changes()));
            assertEquals("later user edit\n", Files.readString(file));
            Files.writeString(file, "agent\n"); changes.rollback(changes.changes()); assertEquals("user draft\n", Files.readString(file));
        }
    }
    @Test void createDeleteAndRenameAreRevertedAsOperations() throws Exception {
        Files.writeString(root.resolve("deleted"), "delete me\n"); Files.writeString(root.resolve("old"), "rename me\n");
        try (var changes = WorkspaceChanges.capture(root)) {
            Files.writeString(root.resolve("created"), "new\n"); Files.delete(root.resolve("deleted")); Files.move(root.resolve("old"), root.resolve("renamed"));
            report(changes, "created", "add", null, "@@ -0,0 +1 @@\n+new\n");
            report(changes, "deleted", "delete", null, "@@ -1 +0,0 @@\n-delete me\n");
            report(changes, "old", "update", "renamed", ""); changes.finish();
            assertEquals(3, changes.changes().size()); assertTrue(changes.changes().stream().allMatch(WorkspaceChanges.Change::proven));
            changes.rollback(changes.changes());
            assertFalse(Files.exists(root.resolve("created"))); assertFalse(Files.exists(root.resolve("renamed")));
            assertEquals("delete me\n", Files.readString(root.resolve("deleted"))); assertEquals("rename me\n", Files.readString(root.resolve("old")));
        }
    }
    @Test void userEditsDuringTurnAndIncorrectProvenanceDisableRollback() throws Exception {
        var file = root.resolve("file"); Files.writeString(file, "original\n");
        try (var changes = WorkspaceChanges.capture(root)) {
            changes.markEdited(file); Files.writeString(file, "agent\n"); report(changes, "file", "update", null, "@@ -1 +1 @@\n-original\n+agent\n"); changes.finish();
            assertFalse(changes.changes().getFirst().proven()); assertThrows(java.io.IOException.class, () -> changes.rollback(changes.changes()));
        }
        try (var changes = WorkspaceChanges.capture(root)) {
            Files.writeString(file, "something else\n"); report(changes, "file", "update", null, "@@ -1 +1 @@\n-agent\n+expected\n"); changes.finish();
            assertFalse(changes.changes().getFirst().proven());
        }
    }
    @Test void unreportedShellChangesAreVisibleButNeverAttributedToAgent() throws Exception {
        Files.writeString(root.resolve("modified"), "before"); Files.writeString(root.resolve("deleted"), "before");
        try (var changes = WorkspaceChanges.capture(root)) {
            Files.writeString(root.resolve("modified"), "after"); Files.delete(root.resolve("deleted")); Files.write(root.resolve("binary"), new byte[]{0, 1, 2});
            changes.finish();
            assertEquals(3, changes.changes().size()); assertTrue(changes.changes().stream().noneMatch(WorkspaceChanges.Change::proven));
            assertThrows(java.io.IOException.class, () -> changes.rollback(changes.changes()));
        }
    }
    @Test void corruptedBackupNeverOverwritesCurrentFile() throws Exception {
        var file = root.resolve("file"); Files.writeString(file, "before\n");
        try (var changes = WorkspaceChanges.capture(root)) {
            Files.writeString(file, "after\n"); report(changes, "file", "update", null, "@@ -1 +1 @@\n-before\n+after\n"); changes.finish();
            var change = changes.changes().getFirst(); Files.writeString(change.before().backup(), "corrupt");
            assertThrows(java.io.IOException.class, () -> changes.rollback(changes.changes()));
            assertEquals("after\n", Files.readString(file));
        }
    }
    @Test void renameChainRestoresOriginalPathAndExistingTarget() throws Exception {
        Files.writeString(root.resolve("original"), "content\n"); Files.writeString(root.resolve("final"), "user destination\n");
        try (var changes = WorkspaceChanges.capture(root)) {
            Files.move(root.resolve("original"), root.resolve("intermediate"));
            report(changes, "original", "update", "intermediate", "");
            Files.move(root.resolve("intermediate"), root.resolve("final"), StandardCopyOption.REPLACE_EXISTING);
            report(changes, "intermediate", "update", "final", ""); changes.finish();
            assertEquals(1, changes.changes().size()); assertTrue(changes.changes().getFirst().proven());
            changes.rollback(changes.changes());
            assertEquals("content\n", Files.readString(root.resolve("original")));
            assertEquals("user destination\n", Files.readString(root.resolve("final"))); assertFalse(Files.exists(root.resolve("intermediate")));
        }
    }
    @Test void invalidReportedPathCannotEscapeSnapshotRoot() throws Exception {
        try (var changes = WorkspaceChanges.capture(root)) {
            report(changes, "../outside", "add", null, "@@ -0,0 +1 @@\n+unsafe\n"); changes.finish();
            assertTrue(changes.changes().isEmpty());
        }
    }
    @Test void renameChainWithOverwrittenIntermediateCannotClaimCompleteUndo() throws Exception {
        Files.writeString(root.resolve("original"), "agent source\n");
        Files.writeString(root.resolve("intermediate"), "user intermediate\n");
        try (var changes = WorkspaceChanges.capture(root)) {
            Files.move(root.resolve("original"), root.resolve("intermediate"), StandardCopyOption.REPLACE_EXISTING);
            report(changes, "original", "update", "intermediate", "");
            Files.move(root.resolve("intermediate"), root.resolve("final"));
            report(changes, "intermediate", "update", "final", ""); changes.finish();
            assertFalse(changes.changes().getFirst().proven());
            assertThrows(java.io.IOException.class, () -> changes.rollback(changes.changes()));
            assertEquals("agent source\n", Files.readString(root.resolve("final")));
        }
    }
    @Test void allRestoreAndRecoveryBackupsAreVerifiedBeforeAnyWrite() throws Exception {
        Files.writeString(root.resolve("first"), "before\n"); Files.writeString(root.resolve("second"), "before\n");
        try (var changes = WorkspaceChanges.capture(root)) {
            for (String name : java.util.List.of("first", "second")) {
                Files.writeString(root.resolve(name), "after\n");
                report(changes, name, "update", null, "@@ -1 +1 @@\n-before\n+after\n");
            }
            changes.finish();
            Files.writeString(changes.changes().get(0).after().backup(), "broken recovery");
            Files.writeString(changes.changes().get(1).before().backup(), "broken restore");
            assertThrows(java.io.IOException.class, () -> changes.rollback(changes.changes()));
            assertEquals("after\n", Files.readString(root.resolve("first")));
            assertEquals("after\n", Files.readString(root.resolve("second")));
        }
    }
    @Test void hardlinksCannotBeSilentlySplitByAutomaticRollback() throws Exception {
        Files.writeString(root.resolve("original"), "before\n");
        try { Files.createLink(root.resolve("alias"), root.resolve("original")); }
        catch (UnsupportedOperationException | java.io.IOException e) { Assumptions.abort("Hardlinks unavailable"); }
        Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("unix"));
        try (var changes = WorkspaceChanges.capture(root)) {
            Files.writeString(root.resolve("original"), "after\n");
            report(changes, "original", "update", null, "@@ -1 +1 @@\n-before\n+after\n"); changes.finish();
            assertTrue(changes.changes().stream().noneMatch(WorkspaceChanges.Change::proven));
            assertThrows(java.io.IOException.class, () -> changes.rollback(changes.changes()));
            assertTrue(Files.isSameFile(root.resolve("original"), root.resolve("alias")));
        }
    }
    @Test void hunkRollbackAndFullRollbackKeepCrLfAndNoFinalNewline() throws Exception {
        assertEquals("new\r\n", StrictPatch.apply("old\r\n", "@@ -1 +1 @@\n-old\n+new\n"));
        String patch = "@@ -1 +1 @@\n-old\n\\ No newline at end of file\n+new\n\\ No newline at end of file\n";
        assertEquals("new", StrictPatch.apply("old", patch)); assertEquals("old", StrictPatch.apply("new", StrictPatch.reverse(patch)));
        assertThrows(IllegalStateException.class, () -> StrictPatch.apply("user", "@@ -1 +1 @@\n-other\n+new\n"));
        Files.writeString(root.resolve("file"), "old\n");
        try (var changes = WorkspaceChanges.capture(root)) {
            Files.writeString(root.resolve("file"), "new\n"); report(changes, "file", "update", null, "@@ -1 +1 @@\n-old\n+new\n"); changes.finish();
            changes.rollbackHunk(changes.changes().getFirst(), 0); assertEquals("old\n", Files.readString(root.resolve("file"))); assertTrue(changes.changes().isEmpty());
        }
    }
}
