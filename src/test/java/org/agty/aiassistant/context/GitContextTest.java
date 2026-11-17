package org.agty.aiassistant.context;

import org.agty.aiassistant.security.ProjectAccessPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GitContextTest {
    @TempDir Path root;
    private void git(String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(args));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
    private void initialize() throws Exception {
        git("init"); git("config", "user.name", "Fixture"); git("config", "user.email", "fixture@example.invalid");
        git("config", "core.autocrlf", "false");
    }
    @Test void unicodePathsAndLargeOutputAreReadWithoutPipeDeadlock() throws Exception {
        initialize();
        Path file = root.resolve("данные.txt"); Files.writeString(file, "old\n"); git("add", "."); git("commit", "-m", "base");
        Files.writeString(file, "new\n".repeat(20_000));
        var context = ProjectContextCollector.gitDiff(root, new ProjectAccessPolicy(root));
        assertEquals(1, context.items().size(), context.notes().toString());
        assertEquals("данные.txt", context.items().getFirst().path());
        assertTrue(context.items().getFirst().text().contains("+new"));
        Files.writeString(root.resolve(".aiignore"), "/данные.txt\n");
        assertTrue(ProjectContextCollector.validated(root, new ProjectAccessPolicy(root), context.items()).isEmpty());
    }
    @Test void diffDoesNotExecuteConfiguredExternalDiffOrTextConversion() throws Exception {
        initialize(); Files.writeString(root.resolve("data.txt"), "old\n");
        Files.writeString(root.resolve(".gitattributes"), "data.txt diff=hostile\n");
        git("add", "."); git("commit", "-m", "base"); Files.writeString(root.resolve("data.txt"), "new\n");
        git("config", "diff.external", "this-command-must-never-be-executed-agty");
        git("config", "diff.hostile.textconv", "this-command-must-never-be-executed-agty");
        var context = ProjectContextCollector.gitDiff(root, new ProjectAccessPolicy(root));
        assertEquals(1, context.items().size(), context.notes().toString());
        assertTrue(context.items().getFirst().text().contains("+new"));
    }
    @Test void oldAggregateDiffCannotBypassCurrentExclusions() {
        var old = new ContextItem("Git diff", ".git-diff.patch", 1, 1, "private data");
        assertTrue(ProjectContextCollector.validated(root, new ProjectAccessPolicy(root), List.of(old)).isEmpty());
    }
    @Test void nestedCwdUsesItsOwnRelativeNamesAndExcludesSiblingFiles() throws Exception {
        initialize(); Path nested = Files.createDirectory(root.resolve("nested"));
        Files.writeString(root.resolve("secret.txt"), "private old\n"); Files.writeString(nested.resolve("source.txt"), "old\n");
        git("add", "."); git("commit", "-m", "base");
        Files.writeString(root.resolve("secret.txt"), "private new\n"); Files.writeString(nested.resolve("source.txt"), "new\n");
        var context = ProjectContextCollector.gitDiff(nested, new ProjectAccessPolicy(nested));
        assertEquals(1, context.items().size(), context.notes().toString());
        assertEquals("source.txt", context.items().getFirst().path());
        assertFalse(context.promptBlock().contains("private"));
    }
}
