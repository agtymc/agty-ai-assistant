package org.agty.aiassistant.ui;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DiffMarkupTest {
    @Test void literalTripleSignsInsideHunkAreDataAndEachFileRestartsLineNumbers() {
        assertEquals(new org.agty.aiassistant.changes.PatchSummary(1, 1), org.agty.aiassistant.changes.PatchSummary.of("--- a/one\n+++ b/one\n@@ -1 +1 @@\n---content\n+++content\n"));
        String html = DiffMarkup.render("--- a/one\n+++ b/one\n@@ -4 +8 @@\n---content\n+++content\n--- a/two\n+++ b/two\n@@ -1 +2 @@\n-old\n+new\n");
        assertEquals(2, html.split("class=\"addition\"", -1).length - 1);
        assertEquals(2, html.split("class=\"deletion\"", -1).length - 1);
        assertTrue(html.contains(">4</td>")); assertTrue(html.contains(">8</td>"));
        assertTrue(html.contains(">1</td>")); assertTrue(html.contains(">2</td>"));
    }
}
