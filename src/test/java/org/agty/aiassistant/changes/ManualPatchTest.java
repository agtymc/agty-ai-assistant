package org.agty.aiassistant.changes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManualPatchTest {
    @TempDir Path root;
    private static String patch(String path, String before, String after) {
        return "diff --git a/" + path + " b/" + path + "\n--- a/" + path + "\n+++ b/" + path
                + "\n@@ -1 +1 @@\n-" + before + "\n+" + after + "\n";
    }
    @Test void wrongPatchContextNeverDeletesUnrelatedUserLines() throws Exception {
        Files.writeString(root.resolve("file"), "user data\n");
        var set = ChangeSet.fromUnifiedDiff(patch("file", "other", "agent"), Map.of("file", "user data\n"));
        assertThrows(IOException.class, () -> ManualPatch.apply(root, set, Set.of()));
        assertEquals("user data\n", Files.readString(root.resolve("file")));
    }
    @Test void allConflictsAreCheckedBeforeAnyFileIsWritten() throws Exception {
        Files.writeString(root.resolve("first"), "before\n"); Files.writeString(root.resolve("second"), "user later\n");
        var set = ChangeSet.fromUnifiedDiff(patch("first", "before", "after") + patch("second", "before", "after"),
                Map.of("first", "before\n", "second", "before\n"));
        assertThrows(IOException.class, () -> ManualPatch.apply(root, set, Set.of()));
        assertEquals("before\n", Files.readString(root.resolve("first")));
        assertEquals("user later\n", Files.readString(root.resolve("second")));
    }
    @Test void undoIsBoundToOriginalRootAndRefusesLaterEdits() throws Exception {
        Files.writeString(root.resolve("file"), "before\n");
        var set = ChangeSet.fromUnifiedDiff(patch("file", "before", "after"), Map.of("file", "before\n"));
        var applied = ManualPatch.apply(root, set, Set.of());
        assertEquals(root.toRealPath(), applied.root());
        Files.writeString(root.resolve("file"), "later user\n");
        assertThrows(IOException.class, applied::undo);
        assertEquals("later user\n", Files.readString(root.resolve("file")));
        Files.writeString(root.resolve("file"), "after\n"); applied.undo();
        assertEquals("before\n", Files.readString(root.resolve("file")));
        assertThrows(IOException.class, applied::undo);
    }
    @Test void undoCreationDeletesFileAndNeverConfusesExistingEmptyFile() throws Exception {
        String diff = "diff --git a/new b/new\nnew file mode 100644\n--- /dev/null\n+++ b/new\n@@ -0,0 +1 @@\n+created\n";
        var set = ChangeSet.fromUnifiedDiff(diff, Map.of());
        var applied = ManualPatch.apply(root, set, Set.of());
        assertEquals("created\n", Files.readString(root.resolve("new"))); applied.undo();
        assertFalse(Files.exists(root.resolve("new")));
        Files.writeString(root.resolve("new"), "");
        assertThrows(IOException.class, () -> ManualPatch.apply(root, set, Set.of()));
        assertTrue(Files.exists(root.resolve("new")));
    }
    @Test void rejectsDeleteRenameAndMetadataOperationsInsteadOfWritingWrongFile() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ChangeSet.fromUnifiedDiff(
                "diff --git a/old b/new\nrename from old\nrename to new\n", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ChangeSet.fromUnifiedDiff(
                "diff --git a/file b/file\ndeleted file mode 100644\n--- a/file\n+++ /dev/null\n@@ -1 +0,0 @@\n-x\n", Map.of()));
        Files.createDirectories(root.resolve(".git")); Files.writeString(root.resolve(".git/config"), "before\n");
        var set = ChangeSet.fromUnifiedDiff(patch(".git/config", "before", "after"), Map.of(".git/config", "before\n"));
        assertThrows(IOException.class, () -> ManualPatch.apply(root, set, Set.of()));
        assertEquals("before\n", Files.readString(root.resolve(".git/config")));
    }
    @Test void undoCannotFollowReplacementRootOrParentSymlink() throws Exception {
        Path directory = Files.createDirectory(root.resolve("work"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.writeString(directory.resolve("file"), "before\n");
        Files.writeString(outside.resolve("file"), "after\n");
        var set = ChangeSet.fromUnifiedDiff(patch("file", "before", "after"), Map.of("file", "before\n"));
        var applied = ManualPatch.apply(directory, set, Set.of());
        Files.move(directory, root.resolve("moved"));
        try { Files.createSymbolicLink(directory, outside); }
        catch (UnsupportedOperationException | IOException e) { org.junit.jupiter.api.Assumptions.abort("Symlinks unavailable"); }
        assertThrows(IOException.class, applied::undo);
        assertEquals("after\n", Files.readString(outside.resolve("file")));
        assertEquals("after\n", Files.readString(root.resolve("moved/file")));
    }
    @Test void selectedHunksHaveUniqueIdsEvenWhenFileNamesHaveEqualHashes() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        var set = ChangeSet.fromUnifiedDiff(patch("Aa", "before", "after") + patch("BB", "before", "after"),
                Map.of("Aa", "before\n", "BB", "before\n"));
        assertNotEquals(set.hunks().get(0).id(), set.hunks().get(1).id());
        assertEquals("after\n", set.files().get(0).afterText(Set.of(set.hunks().get(0).id())));
    }
}
