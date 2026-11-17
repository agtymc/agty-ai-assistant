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
    private final Path rulesRoot;
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
        Path repository = realRoot;
        for (Path candidate = realRoot; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) { repository = candidate; break; }
        }
        rulesRoot = repository;
        rules = loadRules(rulesRoot);
    }

    public Verdict request(boolean writeAccess) {
        for (Path directory = realRoot; directory != null && directory.startsWith(rulesRoot); directory = directory.getParent())
            if (Files.exists(directory.resolve(".noai"), LinkOption.NOFOLLOW_LINKS))
                return Verdict.deny("AI отключён файлом .noai.");
        return Verdict.allow();
    }

    public Verdict path(Path path, boolean writeAccess) { return path(path, writeAccess, true); }
    public Verdict image(Path path) { return path(path, false, false); }
    private Verdict path(Path path, boolean writeAccess, boolean textOnly) {
        Verdict enabled = request(writeAccess);
        if (!enabled.allowed()) return enabled;
        Path normalized = path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
        if (!normalized.startsWith(root)) return Verdict.deny("Путь выходит за пределы проекта.");
        Path checked = existingPath(normalized);
        Path physical;
        try {
            Path real = checked.toRealPath();
            if (!real.startsWith(realRoot)) return Verdict.deny("Путь ведёт за пределы проекта через символическую ссылку.");
            physical = real.resolve(checked.relativize(normalized));
        } catch (IOException e) {
            return Verdict.deny("Путь проекта недоступен: " + checked);
        }
        Path relative = root.relativize(normalized);
        for (Path candidate : List.of(relative, realRoot.relativize(physical))) {
            if (isDefaultExcluded(candidate)) return Verdict.deny("Путь исключён из AI-контекста: " + printable(candidate));
            Rule matched = matchedRule(candidate);
            if (matched != null && matched.exclude)
                return Verdict.deny("Путь исключён правилом " + matched.source + ": " + printable(candidate));
        }
        if (writeAccess && Files.isDirectory(normalized)) return Verdict.allow();
        if (textOnly && looksBinary(normalized)) return Verdict.deny("Бинарные файлы не передаются в AI-контекст.");
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
        relative = rulesRoot.relativize(realRoot.resolve(relative));
        List<List<Rule>> layers = new ArrayList<>(); layers.add(rules);
        for (int depth = 1; depth <= relative.getNameCount(); depth++) {
            Rule result = null;
            for (int layer = 0; layer < layers.size(); layer++) {
                for (Rule rule : layers.get(layer)) {
                    if (rule.pattern == null) return rule; // Unreadable rules can never be negated.
                    try { if (matches(rule.pattern, printable(relative.subpath(layer, depth)), rule.exclude && java.io.File.separatorChar == '\\')) result = rule; }
                    catch (java.util.regex.PatternSyntaxException e) { return new Rule(rule.source, null, true); }
                }
            }
            // Git ignores directory contents before considering child negations.
            if (result != null && result.exclude) return result;
            if (depth < relative.getNameCount()) layers.add(loadRules(rulesRoot.resolve(relative.subpath(0, depth))));
        }
        return null;
    }

    private static boolean matches(String pattern, String rel, boolean ignoreCase) {
        String clean = pattern.endsWith("/") ? pattern.substring(0, pattern.length() - 1) : pattern;
        if (clean.isEmpty()) return false;
        boolean anchored = clean.startsWith("/");
        if (anchored) clean = clean.substring(1);
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        return glob(clean, anchored || clean.contains("/") ? rel : name, ignoreCase);
    }

    private static boolean glob(String pattern, String value, boolean ignoreCase) {
        // Dynamic programming avoids exponential regex backtracking on repository-
        // supplied ignore patterns such as *a*a*a*... against a long file name.
        boolean[] reachable = new boolean[value.length() + 1]; reachable[0] = true;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            boolean[] next = new boolean[value.length() + 1];
            if (c == '*') {
                boolean recursive = i + 1 < pattern.length() && pattern.charAt(i + 1) == '*';
                if (recursive) {
                    i++;
                    if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                        i++; boolean prefix = false;
                        for (int j = 0; j <= value.length(); j++) {
                            next[j] = reachable[j] || prefix && j > 0 && value.charAt(j - 1) == '/'; prefix |= reachable[j];
                        }
                        reachable = next; continue;
                    }
                }
                next[0] = reachable[0];
                for (int j = 1; j <= value.length(); j++) next[j] = reachable[j] || next[j - 1] && (recursive || value.charAt(j - 1) != '/');
            }
            else if (c == '[' && classEnd(pattern, i) > i + 1) {
                int end = classEnd(pattern, i);
                String group = pattern.substring(i + 1, end);
                var character = characterClass(group, ignoreCase); i = end;
                for (int j = 1; j <= value.length(); j++) next[j] = reachable[j - 1] && value.charAt(j - 1) != '/' && character.matcher(value.substring(j - 1, j)).matches();
            } else {
                boolean question = c == '?';
                if (c == '\\' && i + 1 < pattern.length()) c = pattern.charAt(++i);
                for (int j = 1; j <= value.length(); j++) next[j] = reachable[j - 1] && (question ? value.charAt(j - 1) != '/'
                        : value.charAt(j - 1) == c || ignoreCase && Character.toLowerCase(value.charAt(j - 1)) == Character.toLowerCase(c));
            }
            reachable = next;
        }
        return reachable[value.length()];
    }

    private static int classEnd(String pattern, int start) {
        int i = start + 1;
        if (i < pattern.length() && (pattern.charAt(i) == '!' || pattern.charAt(i) == '^')) i++;
        if (i < pattern.length() && pattern.charAt(i) == ']') i++;
        for (; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '\\') { i++; continue; }
            if (pattern.startsWith("[:", i)) {
                int end = pattern.indexOf(":]", i + 2);
                if (end < 0) throw new java.util.regex.PatternSyntaxException("Invalid POSIX class", pattern, i);
                i = end + 1; continue;
            }
            if (pattern.charAt(i) == ']') return i;
        }
        return -1;
    }

    private static java.util.regex.Pattern characterClass(String group, boolean ignoreCase) {
        StringBuilder expression = new StringBuilder("["); int start = 0;
        if (group.startsWith("!") || group.startsWith("^")) { expression.append('^'); start = 1; }
        for (int i = start; i < group.length(); i++) {
            if (group.startsWith("[:", i)) {
                int end = group.indexOf(":]", i + 2);
                String name = group.substring(i + 2, end);
                expression.append(switch (name) {
                    case "alnum" -> "A-Za-z0-9"; case "alpha" -> "A-Za-z";
                    case "blank" -> "\\x20\\t"; case "cntrl" -> "\\x00-\\x1f\\x7f";
                    case "digit" -> "0-9"; case "graph" -> "\\x21-\\x7e";
                    case "lower" -> "a-z"; case "print" -> "\\x20-\\x7e";
                    case "punct" -> "\\p{Punct}"; case "space" -> "\\x20\\t\\r\\n\\x0b\\f";
                    case "upper" -> "A-Z"; case "xdigit" -> "A-Fa-f0-9";
                    default -> throw new java.util.regex.PatternSyntaxException("Unsupported POSIX class", group, i);
                }); i = end + 1;
            } else {
                char c = group.charAt(i);
                if (c == '\\' && i + 1 < group.length()) expression.append(java.util.regex.Pattern.quote(String.valueOf(group.charAt(++i))));
                else { if ("[]^&\\".indexOf(c) >= 0) expression.append('\\'); expression.append(c); }
            }
        }
        return java.util.regex.Pattern.compile(expression.append(']').toString(), ignoreCase ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0);
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
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            rules.add(new Rule(source, null, true)); return;
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IOException("Файл правил слишком велик");
            String content = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            for (String line : content.split("\n", -1)) {
                if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                String trimmed = trimIgnoreLine(line);
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                boolean exclude = true;
                if (trimmed.startsWith("!")) {
                    exclude = false;
                    trimmed = trimmed.substring(1);
                }
                if (!trimmed.isEmpty()) rules.add(new Rule(source, trimmed, exclude));
            }
        } catch (IOException ignored) {
            rules.add(new Rule(source, null, true));
        }
    }

    private static String trimIgnoreLine(String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            int backslashes = 0;
            for (int i = end - 2; i >= 0 && line.charAt(i) == '\\'; i--) backslashes++;
            if (backslashes % 2 != 0) break;
            end--;
        }
        return line.substring(0, end);
    }

    private static String printable(Path relative) {
        return relative.getFileSystem().getSeparator().equals("\\") ? relative.toString().replace('\\', '/') : relative.toString();
    }
}
