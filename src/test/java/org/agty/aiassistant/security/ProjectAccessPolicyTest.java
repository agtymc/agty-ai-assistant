package org.agty.aiassistant.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    @Test void honorsAnchoredNestedAndDoubleStarExclusions() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "/secret.txt\nprivate/**/keys[0-9].txt\n");
        Files.createDirectories(root.resolve("src/nested"));
        Files.writeString(root.resolve("src/.aiignore"), "nested/*.pem\n");
        var policy = new ProjectAccessPolicy(root);
        assertFalse(policy.path(Path.of("secret.txt"), false).allowed());
        assertFalse(policy.path(Path.of("private/keys1.txt"), false).allowed());
        assertFalse(policy.path(Path.of("private/deep/keys2.txt"), false).allowed());
        assertFalse(policy.path(Path.of("src/nested/key.pem"), false).allowed());
        assertTrue(policy.path(Path.of("src/secret.txt"), false).allowed());
    }

    @Test void symlinksCannotExposeIgnoredTargetsOrGitMetadata() throws Exception {
        Files.createDirectories(root.resolve("private"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve("private/key.txt"), "secret");
        Files.writeString(root.resolve(".git/config"), "credential");
        Files.writeString(root.resolve(".aiignore"), "private/\n");
        try {
            Files.createSymbolicLink(root.resolve("public.txt"), Path.of("private/key.txt"));
            Files.createSymbolicLink(root.resolve("metadata.txt"), Path.of(".git/config"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable");
        }
        var policy = new ProjectAccessPolicy(root);
        assertFalse(policy.path(Path.of("public.txt"), false).allowed());
        assertFalse(policy.path(Path.of("metadata.txt"), false).allowed());
    }

    @Test void noaiAlsoDisablesContext() throws Exception {
        Files.writeString(root.resolve("visible.txt"), "data");
        Files.writeString(root.resolve(".noai"), "");
        var policy = new ProjectAccessPolicy(root);
        assertFalse(policy.path(Path.of("visible.txt"), false).allowed());
        assertFalse(policy.image(Path.of("visible.txt")).allowed());
    }

    @Test void excludedDirectoryAndUnreadableRulesCannotBeNegatedByChildFile() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "private/\n!private/key.txt\n");
        assertFalse(new ProjectAccessPolicy(root).path(Path.of("private/key.txt"), false).allowed());
        Files.delete(root.resolve(".gitignore")); Files.createDirectory(root.resolve(".gitignore"));
        Files.writeString(root.resolve(".aiignore"), "!key.txt\n");
        assertFalse(new ProjectAccessPolicy(root).path(Path.of("key.txt"), false).allowed());
    }

    @Test void ignorePatternsHaveBoundedMatchingAndMalformedNegationFailsClosed() throws Exception {
        Files.writeString(root.resolve(".aiignore"), "*a".repeat(30) + "b\n");
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
                assertTrue(new ProjectAccessPolicy(root).path(Path.of("a".repeat(100) + "c"), false).allowed()));
        Files.writeString(root.resolve(".aiignore"), "*.txt\n![z-a].txt\n");
        assertFalse(new ProjectAccessPolicy(root).path(Path.of("a.txt"), false).allowed());
    }
    @Test void leadingAndEscapedTrailingSpacesRemainPartOfIgnorePatterns() throws Exception {
        Files.writeString(root.resolve(".gitignore"), " secret.txt\nsecret.txt\\ \n #literal.txt\n\\#literal.txt\n\\!literal.txt\n");
        var policy = new ProjectAccessPolicy(root);
        for (String name : List.of(" secret.txt", "secret.txt ", " #literal.txt", "#literal.txt", "!literal.txt"))
            assertFalse(policy.path(Path.of(name), false).allowed(), name);
        assertTrue(policy.path(Path.of("secret.txt"), false).allowed());
    }
    @Test void gitPosixCharacterClassesAndUnixBackslashesDoNotBypassExclusions() throws Exception {
        Files.writeString(root.resolve(".gitignore"), "key[[:digit:]].txt\nsecret\\\\name.txt\n");
        var policy = new ProjectAccessPolicy(root);
        assertFalse(policy.path(Path.of("key1.txt"), false).allowed());
        assertTrue(policy.path(Path.of("keyx.txt"), false).allowed());
        if (root.getFileSystem().getSeparator().equals("\\")) assertFalse(policy.path(Path.of("KEY1.TXT"), false).allowed());
        if (root.getFileSystem().getSeparator().equals("/")) assertFalse(policy.path(Path.of("secret\\name.txt"), false).allowed());
    }
    @Test void nestedWorkingDirectoryCannotBypassRepositoryExclusionsOrOptOut() throws Exception {
        Files.createDirectory(root.resolve(".git")); Path nested = Files.createDirectory(root.resolve("nested"));
        Files.writeString(root.resolve(".gitignore"), "/nested/private.txt\n");
        var policy = new ProjectAccessPolicy(nested);
        assertFalse(policy.path(Path.of("private.txt"), false).allowed());
        assertTrue(policy.path(Path.of("public.txt"), false).allowed());
        Files.writeString(root.resolve(".noai"), "");
        assertFalse(policy.request(false).allowed());
    }
}
