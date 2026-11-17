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
    @Test void subdirectoryCwdReadsTheActualRepositoryFile() throws Exception {
        git("init"); Path nested = Files.createDirectory(root.resolve("nested"));
        Files.writeString(root.resolve("file"), "root user data\n");
        Files.writeString(nested.resolve("file"), "nested user data\n");
        var entries = WorkingTreeChanges.list(nested);
        for (var entry : entries) {
            var pair = WorkingTreeChanges.contents(nested, entry);
            assertArrayEquals(Files.readAllBytes(root.resolve(entry.path())), pair.after());
        }
        assertEquals(2, entries.size());
    }
    @Test void readOnlyGitViewsDoNotExecuteConfiguredMonitorOrFilters() throws Exception {
        git("init");
        Files.writeString(root.resolve(".gitattributes"), "*.txt filter=audit\n");
        git("config", "filter.audit.clean", "printf called > filter-marker; cat");
        git("config", "filter.audit.required", "true");
        Files.writeString(root.resolve("file.txt"), "initial\n"); git("add", "file.txt");
        assertTrue(Files.exists(root.resolve("filter-marker")), "Fixture must prove the configured clean filter actually executes");
        Files.delete(root.resolve("filter-marker"));
        git("config", "filter.audit.process", "printf called > process-marker");
        git("config", "core.fsmonitor", "printf called > monitor-marker");
        Files.writeString(root.resolve("file.txt"), "changed contents\n");
        var entries = WorkingTreeChanges.list(root);
        for (var entry : entries) WorkingTreeChanges.contents(root, entry);
        assertFalse(Files.exists(root.resolve("filter-marker")));
        assertFalse(Files.exists(root.resolve("process-marker")));
        assertFalse(Files.exists(root.resolve("monitor-marker")));
    }
}
