package org.agty.aiassistant.core;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CodexCompatibilityTest {
    @Test void extractsExactCliVersionWithoutTreatingNewerVersionsAsCompatible() {
        assertEquals("0.169.0", CodexCompatibility.parse("codex-cli 0.169.0\n"));
        assertEquals("0.170.0", CodexCompatibility.parse("wrapper message\ncodex-cli 0.170.0\n"));
        assertEquals("", CodexCompatibility.parse("0.169.0-beta"));
    }
}
