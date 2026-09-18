package org.agty.aiassistant.ui;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {
    @Test void userTextPreservesLineBreaksTabsAndIndentation() {
        String message = "строка 1\n\t  строка 2 <tag>";
        assertEquals("строка&#160;1<br>&#160;&#160;&#160;&#160;&#160;&#160;строка&#160;2&#160;&lt;tag&gt;",
                MarkdownRenderer.userText(message, false));
        assertEquals("строка 1\n\t  строка 2 &lt;tag&gt;", MarkdownRenderer.userText(message, true));
    }
    private MarkdownRenderer renderer() { return new MarkdownRenderer((lang, text) -> MarkdownRenderer.escape(text)); }
    @Test void rendersMarkdownAndKeepsCopyTextUnchanged() {
        var r = renderer();
        String code = "public void run() { a < b; }\n";
        String html = r.render("## Title\n\n**Bold** and `File.java:42`\n\n```java\n" + code + "```\n\n|A|B|\n|-|-|\n|1|2|");
        assertTrue(html.contains("<h2>Title</h2>"));
        assertTrue(html.contains("<strong>Bold</strong>"));
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("a&#160;&lt;&#160;b"));
        assertTrue(r.actions().values().contains(new MarkdownRenderer.Action("copy", code)));
        assertTrue(r.actions().values().contains(new MarkdownRenderer.Action("open", "File.java:42")));
    }
    @Test void disablesHtmlAndDoesNotLoadImages() {
        var r = renderer();
        String html = r.render("<script>alert(1)</script>\n\n![tracking](https://example.org/pixel)\n\n[x](javascript:alert(1))");
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<img"));
        assertFalse(html.contains("href=\"javascript:"));
        assertTrue(html.contains("&lt;script&gt;"));
    }
    @Test void supportsIncompleteFencesAndSourceLinks() {
        var r = renderer();
        assertTrue(r.render("```java\nclass A {").contains("class=\"codeblock\""));
        r.render("[method](src/A.java#L42)");
        assertTrue(r.actions().values().contains(new MarkdownRenderer.Action("open", "src/A.java#L42")));
        r.reset();
        assertTrue(r.actions().isEmpty());
    }
}
