package org.agty.aiassistant.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.agty.aiassistant.security.ProjectAccessPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CollectedContextTest {
    @TempDir Path root;

    @Test void formatsPromptBlockWithSourceLocationsAndEscapedFences() {
        var context = new CollectedContext(List.of(new ContextItem("Selection", "src/A.java", 2, 4, "```java\nx\n```")),
                List.of("note"));

        String prompt = context.promptBlock();

        assertTrue(prompt.contains("SOURCE: src/A.java#L2-L4"));
        assertTrue(prompt.contains("`\u200b``java"));
        assertFalse(prompt.contains("note"));
    }

    @Test void previewShowsItemsAndNotes() {
        var context = new CollectedContext(List.of(new ContextItem("File", "src/A.java", 1, 1, "class A {}")),
                List.of("skipped"));

        String preview = context.preview();

        assertTrue(preview.contains("src/A.java#L1"));
        assertTrue(preview.contains("class A {}"));
        assertTrue(preview.contains("skipped"));
    }

    @Test void validatesPinnedContextAgainstCurrentPolicy() throws Exception {
        Files.writeString(root.resolve(".aiignore"), "secret.txt\n");
        Files.writeString(root.resolve("visible.txt"), "ok");
        Files.writeString(root.resolve("secret.txt"), "hidden");

        var context = ProjectContextCollector.validated(root, new ProjectAccessPolicy(root), List.of(
                new ContextItem("visible", "visible.txt", 1, 1, "ok"),
                new ContextItem("secret", "secret.txt", 1, 1, "hidden")));

        assertEquals(1, context.items().size());
        assertEquals("visible.txt", context.items().getFirst().path());
        assertTrue(context.preview().contains(".aiignore"));
    }
}
