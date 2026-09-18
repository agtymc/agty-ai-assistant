package org.agty.aiassistant.changes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public record ChangeSet(List<FileChange> files) {
    public record FileChange(String path, String beforeSha256, String beforeText, String unifiedDiff) {
        public boolean conflictsWith(String currentText) {
            return beforeSha256 != null && !beforeSha256.isBlank() && !sha256(currentText).equals(beforeSha256);
        }

        public String afterText() {
            return applyUnifiedDiff(beforeText, unifiedDiff);
        }
    }

    public static ChangeSet fromUnifiedDiff(String diff, Map<String, String> originalTexts) {
        List<FileChange> files = new ArrayList<>();
        String currentPath = "";
        StringBuilder currentDiff = new StringBuilder();
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                flush(files, currentPath, currentDiff, originalTexts);
                currentPath = pathFromDiffHeader(line);
                currentDiff.setLength(0);
            }
            if (!currentPath.isBlank()) currentDiff.append(line).append('\n');
        }
        flush(files, currentPath, currentDiff, originalTexts);
        return new ChangeSet(List.copyOf(files));
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public String preview() {
        StringBuilder out = new StringBuilder();
        for (FileChange file : files) {
            out.append("FILE: ").append(file.path()).append('\n');
            out.append(file.unifiedDiff());
            if (!file.unifiedDiff().endsWith("\n")) out.append('\n');
            out.append('\n');
        }
        return out.toString().stripTrailing();
    }

    public List<String> conflicts(Map<String, String> currentTexts) {
        List<String> result = new ArrayList<>();
        for (FileChange file : files) {
            String current = currentTexts.get(file.path());
            if (current != null && file.conflictsWith(current)) result.add(file.path());
        }
        return List.copyOf(result);
    }

    private static void flush(List<FileChange> files, String path, StringBuilder diff, Map<String, String> originalTexts) {
        if (path == null || path.isBlank() || diff.isEmpty()) return;
        String original = originalTexts.getOrDefault(path, "");
        files.add(new FileChange(path, sha256(original), original, diff.toString()));
    }

    private static String pathFromDiffHeader(String line) {
        String[] parts = line.split(" ");
        if (parts.length < 4) return "";
        String path = parts[3];
        if (path.startsWith("b/")) path = path.substring(2);
        return path;
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static String applyUnifiedDiff(String original, String diff) {
        List<String> originalLines = lines(original);
        List<String> result = new ArrayList<>();
        int originalIndex = 0;
        String[] diffLines = diff.split("\n", -1);
        for (int i = 0; i < diffLines.length; i++) {
            String line = diffLines[i];
            if (!line.startsWith("@@ ")) continue;
            Hunk hunk = Hunk.parse(line);
            while (originalIndex < hunk.oldStart - 1 && originalIndex < originalLines.size())
                result.add(originalLines.get(originalIndex++));
            i++;
            while (i < diffLines.length && !diffLines[i].startsWith("@@ ") && !diffLines[i].startsWith("diff --git ")) {
                String hunkLine = diffLines[i];
                if (hunkLine.startsWith(" ")) {
                    if (originalIndex < originalLines.size()) result.add(originalLines.get(originalIndex));
                    originalIndex++;
                } else if (hunkLine.startsWith("-")) {
                    originalIndex++;
                } else if (hunkLine.startsWith("+")) {
                    result.add(hunkLine.substring(1));
                }
                i++;
            }
            i--;
        }
        while (originalIndex < originalLines.size()) result.add(originalLines.get(originalIndex++));
        return joinLines(result, original.endsWith("\n"));
    }

    private record Hunk(int oldStart) {
        static Hunk parse(String header) {
            int minus = header.indexOf('-');
            int comma = header.indexOf(',', minus);
            int space = header.indexOf(' ', minus);
            int end = comma > 0 && comma < space ? comma : space;
            if (minus < 0 || end <= minus) throw new IllegalArgumentException("Некорректный hunk header: " + header);
            return new Hunk(Integer.parseInt(header.substring(minus + 1, end)));
        }
    }

    private static List<String> lines(String text) {
        String[] split = text.split("\n", -1);
        int count = split.length;
        if (text.endsWith("\n")) count--;
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(split[i]);
        return result;
    }

    private static String joinLines(List<String> lines, boolean trailingNewline) {
        String joined = String.join("\n", lines);
        return trailingNewline ? joined + "\n" : joined;
    }
}
