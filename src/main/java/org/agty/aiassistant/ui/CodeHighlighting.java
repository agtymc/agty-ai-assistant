package org.agty.aiassistant.ui;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import java.awt.Color;
import java.util.Map;

final class CodeHighlighting {
    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("kotlin", "kt"), Map.entry("kt", "kt"),
            Map.entry("javascript", "js"), Map.entry("typescript", "ts"), Map.entry("python", "py"),
            Map.entry("bash", "sh"), Map.entry("shell", "sh"), Map.entry("json", "json"),
            Map.entry("xml", "xml"), Map.entry("html", "html"), Map.entry("sql", "sql"));
    static String html(Project project, String language, String code) {
        if (code.length() > 30_000) return MarkdownRenderer.escape(code);
        var type = FileTypeManager.getInstance().getFileTypeByExtension(EXTENSIONS.getOrDefault(language, language));
        var highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(type, project, null);
        if (highlighter == null) return MarkdownRenderer.escape(code);
        var lexer = highlighter.getHighlightingLexer();
        var scheme = EditorColorsManager.getInstance().getGlobalScheme();
        StringBuilder html = new StringBuilder();
        lexer.start(code);
        while (lexer.getTokenType() != null) {
            Color color = scheme.getDefaultForeground();
            boolean bold = false;
            for (var key : highlighter.getTokenHighlights(lexer.getTokenType())) {
                var attributes = scheme.getAttributes(key);
                if (attributes != null) {
                    if (attributes.getForegroundColor() != null) color = attributes.getForegroundColor();
                    bold |= (attributes.getFontType() & java.awt.Font.BOLD) != 0;
                }
            }
            html.append("<font color=\"").append(hex(color)).append("\">");
            if (bold) html.append("<b>");
            html.append(MarkdownRenderer.escape(code.substring(lexer.getTokenStart(), lexer.getTokenEnd())));
            if (bold) html.append("</b>");
            html.append("</font>");
            lexer.advance();
        }
        return html.toString();
    }
    static String hex(Color color) { return String.format("#%06x", color.getRGB() & 0xffffff); }
}
