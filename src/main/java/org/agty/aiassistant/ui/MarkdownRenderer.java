package org.agty.aiassistant.ui;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.*;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import java.util.*;
import java.util.function.BiFunction;

/** All navigations go through opaque, per-render action ids; raw HTML and images are inert. */
public final class MarkdownRenderer {
    public record Action(String type, String value) {}
    private final Map<String, Action> actions = new HashMap<>();
    private final BiFunction<String, String, String> highlight;
    private int sequence;
    private boolean browser;
    public void browser(boolean value) { browser = value; }
    public MarkdownRenderer(BiFunction<String, String, String> highlight) { this.highlight = highlight; }
    public Map<String, Action> actions() { return Map.copyOf(actions); }
    public void reset() { actions.clear(); }
    public String action(String type, String value) {
        String key = "https://agty.invalid/action/" + sequence++;
        actions.put(key, new Action(type, value));
        return key;
    }
    public String render(String markdown) {
        var extensions = List.of(TablesExtension.create(), StrikethroughExtension.create());
        var parser = Parser.builder().extensions(extensions).build();
        var renderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true)
                .nodeRendererFactory(context -> new NodeRenderer() {
                    @Override public Set<Class<? extends Node>> getNodeTypes() {
                        return Set.of(FencedCodeBlock.class, IndentedCodeBlock.class, Link.class, Image.class, Code.class);
                    }
                    @Override public void render(Node node) {
                        HtmlWriter out = context.getWriter();
                        if (node instanceof FencedCodeBlock code) {
                            code(out, code.getInfo().split("\\s+", 2)[0], code.getLiteral());
                        } else if (node instanceof IndentedCodeBlock code) {
                            code(out, "text", code.getLiteral());
                        } else if (node instanceof Link link) {
                            out.raw("<a href=\"" + action("open", link.getDestination()) + "\">");
                            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) context.render(child);
                            out.raw("</a>");
                        } else if (node instanceof Image image) {
                            out.raw("<em>[Изображение: " + escape(image.getDestination()) + "]</em>");
                        } else if (node instanceof Code code) {
                            String literal = code.getLiteral();
                            boolean path = literal.matches("[^\\n]*\\.[a-zA-Z0-9]{1,10}(?:(?::\\d+(?::\\d+)?)|(?:#.+))?");
                            out.raw("<code>" + (path ? "<a href=\"" + action("open", literal) + "\">" : "")
                                    + escape(literal) + (path ? "</a>" : "") + "</code>");
                        }
                    }
                    private void code(HtmlWriter out, String language, String code) {
                        out.raw("<div class=\"codehead\">" + escape(language.isBlank() ? "text" : language)
                                + " &nbsp; <a href=\"" + action("copy", code) + "\">Копировать код</a></div><div class=\"codeblock\"><code>"
                                + (browser ? highlight.apply(language, code) : codeWhitespace(highlight.apply(language, code))) + "</code></div>");
                    }
                }).build();
        return renderer.render(parser.parse(markdown));
    }
    static String codeWhitespace(String html) {
        StringBuilder result = new StringBuilder();
        boolean tag = false;
        int column = 0;
        for (int i = 0; i < html.length(); i++) {
            char c = html.charAt(i);
            if (c == '<') tag = true;
            if (tag) { result.append(c); if (c == '>') tag = false; continue; }
            if (c == '\n') { result.append("<br>"); column = 0; }
            else if (c == '\r') { /* CRLF is displayed as one break. */ }
            else if (c == ' ' || c == '\t') {
                int count = c == '\t' ? 4 - column % 4 : 1;
                result.append("&#160;".repeat(count)); column += count;
            } else {
                result.append(c);
                if (c == '&') {
                    int end = html.indexOf(';', i);
                    if (end >= 0) { result.append(html, i + 1, end + 1); i = end; }
                }
                column++;
            }
        }
        return result.toString();
    }
    static String userText(String text, boolean browser) {
        String escaped = escape(text);
        return browser ? escaped : codeWhitespace(escaped);
    }
    public static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
