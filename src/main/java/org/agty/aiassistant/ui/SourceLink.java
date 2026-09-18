package org.agty.aiassistant.ui;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

public record SourceLink(Path path, int line, int column, String symbol) {
    private static final Pattern POSITION = Pattern.compile("^(.*?)(?::(\\d+)(?::(\\d+))?|#L(\\d+)(?:C(\\d+))?(?:-L?\\d+)?)$");
    public static SourceLink parse(Path root, String target) {
        if (target.matches("(?i)^(https?|javascript|data|command|vscode|mailto):.*"))
            throw new IllegalArgumentException("Эта ссылка не указывает на файл проекта.");
        String value = target;
        if (value.startsWith("file:")) {
            URI uri = URI.create(value);
            value = Path.of(URI.create(value.split("#", 2)[0])).toString()
                    + (uri.getFragment() == null ? "" : "#" + uri.getFragment());
        } else value = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        int line = 0, column = 0;
        String symbol = "";
        var match = POSITION.matcher(value);
        if (match.matches()) {
            value = match.group(1);
            line = Math.max(0, Integer.parseInt(match.group(2) != null ? match.group(2) : match.group(4)) - 1);
            String col = match.group(3) != null ? match.group(3) : match.group(5);
            if (col != null) column = Math.max(0, Integer.parseInt(col) - 1);
        } else if (value.contains("#")) {
            int hash = value.lastIndexOf('#');
            symbol = value.substring(hash + 1).replaceFirst("\\(.*\\)$", "");
            value = value.substring(0, hash);
        }
        Path base = root.toAbsolutePath().normalize();
        Path path = base.resolve(value).normalize();
        if (!path.startsWith(base)) throw new IllegalArgumentException("Ссылка ведёт за пределы проекта.");
        return new SourceLink(path, line, column, symbol);
    }
}
