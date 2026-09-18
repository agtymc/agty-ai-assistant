package org.agty.aiassistant.security;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public final class ProjectAccessPolicy {
    private static final List<String> DEFAULT_EXCLUDED_SEGMENTS = List.of(
            ".git", ".gradle", ".idea", ".intellijPlatform", "build", "out", "target");
    private final Path root;
    private final Path realRoot;
    private final List<Rule> rules;

    public record Verdict(boolean allowed, String message) {
        public static Verdict allow() { return new Verdict(true, ""); }
        public static Verdict deny(String message) { return new Verdict(false, message); }
        public void requireAllowed() {
            if (!allowed) throw new IllegalStateException(message);
        }
    }

    private record Rule(String source, String pattern, boolean exclude) {}

    public ProjectAccessPolicy(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try { realRoot = this.root.toRealPath(); }
        catch (IOException e) { throw new IllegalArgumentException("Каталог проекта недоступен: " + this.root, e); }
        rules = loadRules(realRoot);
    }

    public Verdict request(boolean writeAccess) {
        if (Files.exists(realRoot.resolve(".noai")))
            return Verdict.deny("AI отключён файлом .noai.");
        return Verdict.allow();
    }

    public Verdict path(Path path, boolean writeAccess) {
        Path normalized = path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
        if (!normalized.startsWith(root)) return Verdict.deny("Путь выходит за пределы проекта.");
        Path checked = existingPath(normalized);
        try {
            Path real = checked.toRealPath();
            if (!real.startsWith(realRoot)) return Verdict.deny("Путь ведёт за пределы проекта через символическую ссылку.");
        } catch (IOException e) {
            return Verdict.deny("Путь проекта недоступен: " + checked);
        }
        Path relative = realRoot.relativize(normalized);
        if (isDefaultExcluded(relative)) return Verdict.deny("Путь исключён из AI-контекста: " + printable(relative));
        Rule matched = matchedRule(relative);
        if (matched != null && matched.exclude)
            return Verdict.deny("Путь исключён правилом " + matched.source + ": " + printable(relative));
        if (writeAccess && Files.isDirectory(normalized)) return Verdict.allow();
        if (looksBinary(normalized)) return Verdict.deny("Бинарные файлы не передаются в AI-контекст.");
        return Verdict.allow();
    }

    private Path existingPath(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) current = current.getParent();
        return current == null ? realRoot : current;
    }

    private boolean isDefaultExcluded(Path relative) {
        for (Path part : relative) if (DEFAULT_EXCLUDED_SEGMENTS.contains(part.toString())) return true;
        return false;
    }

    private Rule matchedRule(Path relative) {
        Rule result = null;
        String rel = printable(relative);
        String name = relative.getFileName() == null ? "" : relative.getFileName().toString();
        for (Rule rule : rules) if (matches(rule.pattern, rel, name)) result = rule;
        return result;
    }

    private static boolean matches(String pattern, String rel, String name) {
        String clean = pattern.endsWith("/") ? pattern.substring(0, pattern.length() - 1) : pattern;
        if (clean.isBlank()) return false;
        if (clean.contains("/")) return glob(clean, rel) || rel.startsWith(clean + "/");
        if (glob(clean, name)) return true;
        for (String part : rel.split("/")) if (glob(clean, part)) return true;
        return rel.equals(clean) || rel.startsWith(clean + "/");
    }

    private static boolean glob(String pattern, String value) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') regex.append(".*");
            else if (c == '?') regex.append('.');
            else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) regex.append('\\');
                regex.append(c);
            }
        }
        return value.matches(regex.toString());
    }

    private static boolean looksBinary(Path path) {
        if (!Files.isRegularFile(path)) return false;
        byte[] sample;
        try (var input = Files.newInputStream(path)) { sample = input.readNBytes(4096); }
        catch (IOException e) { return true; }
        for (byte value : sample) if (value == 0) return true;
        return false;
    }

    private static List<Rule> loadRules(Path root) {
        List<Rule> result = new ArrayList<>();
        readRules(root.resolve(".gitignore"), ".gitignore", result);
        readRules(root.resolve(".aiignore"), ".aiignore", result);
        return List.copyOf(result);
    }

    private static void readRules(Path file, String source, List<Rule> rules) {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
                boolean exclude = true;
                if (trimmed.startsWith("!")) {
                    exclude = false;
                    trimmed = trimmed.substring(1).strip();
                }
                if (!trimmed.isBlank()) rules.add(new Rule(source, trimmed, exclude));
            }
        } catch (IOException ignored) {
            rules.add(new Rule(source, "*", true));
        }
    }

    private static String printable(Path relative) {
        return relative.toString().replace('\\', '/');
    }
}
