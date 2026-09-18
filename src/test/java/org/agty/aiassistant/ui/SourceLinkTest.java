package org.agty.aiassistant.ui;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SourceLinkTest {
    private final Path root = Path.of(System.getProperty("java.io.tmpdir"), "project").toAbsolutePath();
    @Test void parsesLinesColumnsAndRanges() {
        var link = SourceLink.parse(root, "src/A.java:42:3");
        assertEquals(41, link.line());
        assertEquals(2, link.column());
        assertEquals(root.resolve("src/A.java"), link.path());
        assertEquals(41, SourceLink.parse(root, "src/A.java#L42-L46").line());
    }
    @Test void parsesSymbolsAndEncodedPaths() {
        assertEquals("run", SourceLink.parse(root, "src/A.java#run()").symbol());
        assertEquals(root.resolve("a b/A.java"), SourceLink.parse(root, "a%20b/A.java").path());
        assertEquals(root.resolve("A.java"), SourceLink.parse(root, root.resolve("A.java").toUri() + "#L5").path());
    }
    @Test void rejectsEscapeAndUnsafeSchemes() {
        assertThrows(IllegalArgumentException.class, () -> SourceLink.parse(root, "../../private.txt"));
        assertThrows(IllegalArgumentException.class, () -> SourceLink.parse(root, "javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> SourceLink.parse(root, "command:delete"));
    }
}
