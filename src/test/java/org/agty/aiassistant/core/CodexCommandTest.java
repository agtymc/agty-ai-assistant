package org.agty.aiassistant.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CodexCommandTest {
    @TempDir Path directory;

    @Test void preservesExecutablePathWithSpacesAndShellMetacharacters() throws Exception {
        Path binary = Files.createFile(directory.resolve("codex binary $test"));
        assertTrue(binary.toFile().setExecutable(true));
        var command = CodexCommand.chat(binary.toString());
        assertEquals(binary.toString(), command.getFirst());
        assertEquals("-", command.getLast());
        assertTrue(command.contains("read-only"));
        assertFalse(command.contains("--dangerously-bypass-approvals-and-sandbox"));
    }

    @Test void rejectsMissingBinaryAndEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> CodexCommand.executable(" "));
        assertThrows(IllegalArgumentException.class, () -> CodexCommand.executable(directory.resolve("missing").toString()));
        assertEquals("codex", CodexCommand.executable(" codex "));
    }
}
