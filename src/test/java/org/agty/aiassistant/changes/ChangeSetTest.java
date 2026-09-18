package org.agty.aiassistant.changes;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChangeSetTest {
    @Test void parsesUnifiedDiffAndDetectsConflictsByOriginalHash() {
        String diff = """
                diff --git a/src/A.java b/src/A.java
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1 +1 @@
                -class A {}
                +class A { int x; }
                """;

        ChangeSet set = ChangeSet.fromUnifiedDiff(diff, Map.of("src/A.java", "class A {}\n"));

        assertEquals(1, set.files().size());
        assertEquals("src/A.java", set.files().getFirst().path());
        assertTrue(set.preview().contains("class A { int x; }"));
        assertEquals("class A { int x; }\n", set.files().getFirst().afterText());
        assertTrue(set.conflicts(Map.of("src/A.java", "class A { int y; }\n")).contains("src/A.java"));
        assertTrue(set.conflicts(Map.of("src/A.java", "class A {}\n")).isEmpty());
    }
}
