package org.agty.aiassistant.changes;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WorktreeManagerTest {
    @TempDir Path root;
    private void git(String... arguments) throws Exception {
        var args = new ArrayList<>(List.of("git")); args.addAll(List.of(arguments));
        var process = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()); assertEquals(0, process.waitFor(), output);
    }
    @Test void createsIsolatedBranchWithoutChangingOriginalDirtyFiles() throws Exception {
        git("init"); git("config", "core.autocrlf", "false"); Files.writeString(root.resolve("file"), "base\n"); git("add", "file");
        git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "Initial");
        Files.writeString(root.resolve("file"), "user draft\n");
        Path target = root.resolve("isolated"); WorktreeManager.create(root, target, "fixture-worktree");
        assertEquals("user draft\n", Files.readString(root.resolve("file"))); assertEquals("base\n", Files.readString(target.resolve("file")));
        boolean listed = false;
        for (var item : WorktreeManager.list(root)) if (item.directory().toRealPath().equals(target.toRealPath()) && item.branch().equals("fixture-worktree")) listed = true;
        assertTrue(listed);
        assertThrows(java.io.IOException.class, () -> WorktreeManager.create(root, root, "other"));
        assertThrows(java.io.IOException.class, () -> WorktreeManager.create(root, root.resolve("bad"), "--force"));
    }
}
