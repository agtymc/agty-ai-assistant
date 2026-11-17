package org.agty.aiassistant.ui;

import java.util.regex.Pattern;

final class DiffMarkup {
    private static final Pattern HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    static String render(String patch) {
        StringBuilder html = new StringBuilder("<table class=\"diff\">"); int oldLine = 0, newLine = 0, oldRemaining = 0, newRemaining = 0; boolean inHunk = false;
        for (String line : patch.split("\n", -1)) {
            if (line.isEmpty()) continue;
            var header = HEADER.matcher(line.stripTrailing());
            if (header.matches()) {
                inHunk = true; oldLine = number(header.group(1)); newLine = number(header.group(3));
                oldRemaining = header.group(2) == null ? 1 : number(header.group(2));
                newRemaining = header.group(4) == null ? 1 : number(header.group(4));
            }
            if (line.startsWith("diff --git ")) inHunk = false;
            boolean add = inHunk && line.startsWith("+"), remove = inHunk && line.startsWith("-");
            boolean context = inHunk && line.startsWith(" ");
            String left = remove || context ? Integer.toString(oldLine++) : "";
            String right = add || context ? Integer.toString(newLine++) : "";
            if (remove || context) oldRemaining--;
            if (add || context) newRemaining--;
            if (oldRemaining == 0 && newRemaining == 0) inHunk = false;
            html.append("<tr class=\"").append(add ? "addition" : remove ? "deletion" : "context").append("\"><td class=\"gutter\">")
                    .append(left).append("</td><td class=\"gutter\">").append(right).append("</td><td><code>")
                    .append(MarkdownRenderer.codeWhitespace(MarkdownRenderer.escape(line))).append("</code></td></tr>");
        }
        return html.append("</table>").toString();
    }
    private static int number(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 0; } }
    private DiffMarkup() { }
}
