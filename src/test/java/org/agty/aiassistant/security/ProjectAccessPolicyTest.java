package org.agty.aiassistant.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectAccessPolicyTest {
    @TempDir Path root;

    @Test void noaiDisablesProjectRequests() throws Exception {
        Files.writeString(root.resolve(".noai"), "");

        var verdict = new ProjectAccessPolicy(root).request(false);

        assertFalse(verdict.allowed());
        assertTrue(verdict.message().contains(".noai"));
    }

    @Test void rejectsPathTraversalAndSymlinkEscape() throws Exception {
        Path outside = Files.createTempDirectory("agty-outside");
        Path link = root.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }

        var policy = new ProjectAccessPolicy(root);

        assertFalse(policy.path(root.resolve("../private.txt"), false).allowed());
        assertFalse(policy.path(link.resolve("secret.txt"), false).allowed());
    }

    @Test void appliesGitignoreAiignoreAndDefaultBuildExclusions() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "*.log\n");
        Files.writeString(root.resolve(".aiignore"), "secret/\n");
        Files.createDirectories(root.resolve("secret"));
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("debug.log"), "diagnostic");
        Files.writeString(root.resolve("secret/data.txt"), "hidden");
        Files.writeString(root.resolve("build/generated.txt"), "generated");
        Files.writeString(root.resolve("src.txt"), "visible");

        var policy = new ProjectAccessPolicy(root);

        assertFalse(policy.path(root.resolve("debug.log"), false).allowed());
        assertFalse(policy.path(root.resolve("secret/data.txt"), false).allowed());
        assertFalse(policy.path(root.resolve("build/generated.txt"), false).allowed());
        assertTrue(policy.path(root.resolve("src.txt"), false).allowed());
    }

    @Test void rejectsBinaryFiles() throws Exception {
        Path binary = root.resolve("image.bin");
        Files.write(binary, new byte[] {1, 2, 0, 3});

        assertFalse(new ProjectAccessPolicy(root).path(binary, false).allowed());
    }
}
