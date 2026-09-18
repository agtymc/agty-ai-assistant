package org.agty.aiassistant.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CodexCommand {
    private CodexCommand() {}

    public static String executable(String value) {
        String path = value.trim();
        if (path.isEmpty()) throw new IllegalArgumentException("Укажите путь к Codex или имя codex из PATH.");
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            path = Path.of(System.getProperty("user.home"), path.substring(2)).toString();
        }
        if (path.contains("/") || path.contains("\\")) {
            Path file = Path.of(path);
            if (!file.isAbsolute() || !Files.isRegularFile(file) || !Files.isExecutable(file)) {
                throw new IllegalArgumentException("Нужен абсолютный путь к исполняемому файлу Codex.");
            }
        }
        if (path.endsWith(".cmd") || path.endsWith(".bat")) {
            throw new IllegalArgumentException("Выберите codex.exe, а не .cmd/.bat оболочку.");
        }
        return path;
    }

    public static List<String> chat(String binary) { return chat(binary, ""); }
    public static List<String> chat(String binary, String sessionId) {
        var command = new java.util.ArrayList<>(List.of(executable(binary), "exec", "--json", "--color", "never",
                "--sandbox", "read-only", "-c", "approval_policy=\"never\"", "--skip-git-repo-check"));
        if (!sessionId.isBlank()) {
            java.util.UUID.fromString(sessionId);
            command.add("resume"); command.add(sessionId);
        }
        command.add("-");
        return List.copyOf(command);
    }
}
