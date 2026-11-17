package org.agty.aiassistant.changes;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkingTreeChangesTest {
    @TempDir Path root;
    private void git(String... arguments) throws Exception {
        var args = new ArrayList<>(List.of("git")); args.addAll(List.of(arguments));
        var process = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()); assertEquals(0, process.waitFor(), output);
    }
    @Test void distinguishesIndexWorktreeAndUntrackedInUnbornRepository() throws Exception {
        git("init"); Files.writeString(root.resolve("name with spaces.txt"), "staged\n"); git("add", "name with spaces.txt");
        Files.writeString(root.resolve("name with spaces.txt"), "worktree\n"); Files.writeString(root.resolve("untracked"), "new\n");
        var entries = WorkingTreeChanges.list(root); assertEquals(3, entries.size());
        for (var entry : entries) {
            var contents = WorkingTreeChanges.contents(root, entry);
            if (entry.stage() == WorkingTreeChanges.Stage.STAGED) {
                assertEquals(0, contents.before().length); assertEquals("staged\n", new String(contents.after(), java.nio.charset.StandardCharsets.UTF_8));
            } else if (entry.stage() == WorkingTreeChanges.Stage.UNSTAGED) {
                assertEquals("staged\n", new String(contents.before(), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals("worktree\n", new String(contents.after(), java.nio.charset.StandardCharsets.UTF_8));
            } else assertEquals("new\n", new String(contents.after(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals("worktree\n", Files.readString(root.resolve("name with spaces.txt")));
    }
}
