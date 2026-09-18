package org.agty.aiassistant.context;

public record ContextItem(String title, String path, int startLine, int endLine, String text) {
    public String location() {
        if (path.isBlank()) return title;
        if (startLine <= 0) return path;
        if (endLine <= startLine) return path + "#L" + startLine;
        return path + "#L" + startLine + "-L" + endLine;
    }
}
