package org.agty.aiassistant.chat;

import java.util.List;

public final class SlashCommands {
    public record Command(String name, String description, boolean codexOnly) {}
    public record Invocation(String name, String argument) {}
    public static final List<Command> ALL = List.of(
            new Command("/status", "Статус сессии, конфигурация Codex и доступные лимиты", false),
            new Command("/help", "Поддерживаемые команды", false),
            new Command("/model", "Выбор модели в списке LLM или /model ID", true),
            new Command("/new", "Создать новый чат", false),
            new Command("/resume", "Открыть сессию Codex: /resume UUID", true),
            new Command("/permissions", "Режим: read-only или workspace-write", false),
            new Command("/stop", "Остановить текущий запрос", false));
    public static Invocation parse(String input) {
        String value = input.strip();
        if (!value.startsWith("/") || value.startsWith("//")) return null;
        String[] parts = value.split("\\s+", 2);
        return new Invocation(parts[0], parts.length > 1 ? parts[1].strip() : "");
    }
    public static List<Command> available(String provider) {
        return ALL.stream().filter(c -> !c.codexOnly() || provider.equals("codex")).toList();
    }
    public static String help(String provider) {
        return "Команды обрабатываются плагином, без обращения к модели.\n\n"
                + String.join("\n", available(provider).stream().map(c -> "- `" + c.name() + "` — " + c.description()).toList())
                + "\n\nЭто поддерживаемый набор, а не терминал Codex. Для буквального сообщения с `/` в начале используйте `//`.";
    }
    private SlashCommands() {}
}
