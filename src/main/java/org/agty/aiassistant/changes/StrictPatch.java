package org.agty.aiassistant.changes;

import java.util.*;
import java.util.regex.Pattern;

/** Exact-context unified patch for provenance checks and conflict-safe hunk rollback. */
public final class StrictPatch {
    private record Line(String text, String ending) { }
    private static final Pattern HEADER = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    public static String apply(String original, String patch) {
        if (patch.isBlank()) return original;
        List<Line> source = lines(original), output = new ArrayList<>();
        String ending = original.contains("\r\n") ? "\r\n" : "\n";
        String[] diff = patch.split("\n", -1);
        int position = 0, hunks = 0;
        for (int i = 0; i < diff.length; i++) {
            var header = HEADER.matcher(diff[i].stripTrailing());
            if (!header.matches()) continue;
            hunks++;
            int oldCount = header.group(2) == null ? 1 : Integer.parseInt(header.group(2));
            int newCount = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));
            int target = Integer.parseInt(header.group(1)) - (oldCount == 0 ? 0 : 1);
            if (target < position || target > source.size()) throw conflict();
            while (position < target) output.add(source.get(position++));
            int consumed = 0, produced = 0;
            while (consumed < oldCount || produced < newCount) {
                if (++i >= diff.length || diff[i].isEmpty()) throw conflict();
                String value = diff[i]; char kind = value.charAt(0);
                if (kind != ' ' && kind != '-' && kind != '+') throw conflict();
                String content = value.substring(1); if (content.endsWith("\r")) content = content.substring(0, content.length() - 1);
                boolean noNewline = i + 1 < diff.length && diff[i + 1].startsWith("\\ No newline at end of file");
                if (kind != '+') {
                    if (position >= source.size() || !source.get(position).text.equals(content)) throw conflict();
                    if (noNewline && !source.get(position).ending.isEmpty()) throw conflict();
                    if (kind == ' ') output.add(new Line(content, noNewline ? "" : source.get(position).ending));
                    position++; consumed++;
                } else output.add(new Line(content, noNewline ? "" : ending));
                if (kind != '-') produced++;
                if (noNewline) i++;
                if (consumed > oldCount || produced > newCount) throw conflict();
            }
        }
        if (hunks == 0 && !original.isEmpty()) throw conflict();
        while (position < source.size()) output.add(source.get(position++));
        StringBuilder result = new StringBuilder(); for (var line : output) result.append(line.text).append(line.ending);
        return result.toString();
    }
    public static List<String> hunks(String patch) {
        List<String> result = new ArrayList<>(); StringBuilder current = null;
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("@@ ")) { if (current != null) result.add(current.toString()); current = new StringBuilder(); }
            if (current != null && !line.isEmpty()) current.append(line).append('\n');
        }
        if (current != null) result.add(current.toString()); return List.copyOf(result);
    }
    public static String reverse(String patch) {
        StringBuilder result = new StringBuilder(); boolean inHunk = false;
        for (String line : patch.split("\n", -1)) {
            var matcher = HEADER.matcher(line.stripTrailing());
            if (matcher.matches()) {
                inHunk = true;
                result.append("@@ -").append(matcher.group(3)).append(',').append(matcher.group(4) == null ? "1" : matcher.group(4))
                        .append(" +").append(matcher.group(1)).append(',').append(matcher.group(2) == null ? "1" : matcher.group(2)).append(" @@\n");
            } else if (inHunk && line.startsWith("+")) result.append('-').append(line.substring(1)).append('\n');
            else if (inHunk && line.startsWith("-")) result.append('+').append(line.substring(1)).append('\n');
            else if (!line.isEmpty()) result.append(line).append('\n');
        }
        return result.toString();
    }
    private static List<Line> lines(String text) {
        List<Line> result = new ArrayList<>(); int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) { result.add(new Line(text.substring(start), "")); break; }
            boolean crlf = end > start && text.charAt(end - 1) == '\r';
            result.add(new Line(text.substring(start, crlf ? end - 1 : end), crlf ? "\r\n" : "\n")); start = end + 1;
        }
        return result;
    }
    private static IllegalStateException conflict() { return new IllegalStateException("Patch не соответствует исходной версии; автоматический откат запрещён."); }
    private StrictPatch() { }
}
