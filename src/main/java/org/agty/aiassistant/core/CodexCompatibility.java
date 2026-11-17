package org.agty.aiassistant.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Version numbers outside the checked contract never silently enable features. */
public final class CodexCompatibility {
    public static final String BASELINE = "0.169.0";
    private record Checked(String version, long expires) { }
    private static final Map<String, String> observed = new ConcurrentHashMap<>();
    public static String observed(String binary) { return observed.getOrDefault(CodexCommand.executable(binary), ""); }
    private static final Map<String, Checked> cache = new ConcurrentHashMap<>();
    public static String require(String binary, Path directory) throws Exception {
        String executable = CodexCommand.executable(binary);
        var cached = cache.get(executable);
        if (cached != null && System.nanoTime() < cached.expires) return cached.version;
        StringBuffer output = new StringBuffer();
        int exit = new CodexProcess().run(List.of(executable, "--version"), directory, "", Duration.ofSeconds(5),
                line -> output.append(line).append('\n'), line -> {});
        String version = parse(output.toString()); observed.put(executable, version);
        if (exit != 0 || !BASELINE.equals(version)) throw new IllegalStateException("Проверенный контракт требует Codex CLI " + BASELINE
                + "; обнаружено: " + (version.isBlank() ? "версия не определена" : version) + ". Проверьте путь CLI в настройках.");
        cache.put(executable, new Checked(version, System.nanoTime() + Duration.ofSeconds(60).toNanos())); return version;
    }
    static String parse(String output) {
        var matcher = Pattern.compile("(?m)^codex-cli ([0-9]+\\.[0-9]+\\.[0-9]+)(?:\\s|$)").matcher(output);
        return matcher.find() ? matcher.group(1) : "";
    }
    private CodexCompatibility() { }
}
