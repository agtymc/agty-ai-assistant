package org.agty.aiassistant.chat;

public final class CommandAvailability {
    public record Context(String provider, boolean project, boolean thread, boolean running, boolean experimental,
                          Boolean signedIn, String cliVersion) { }
    public record Result(boolean available, String reason) { }
    public static Result evaluate(SlashCommands.Command command, Context context) {
        String reason = "";
        if (!command.implemented()) reason = "Эта команда ещё не реализована в плагине.";
        else if (command.codexOnly() && !context.provider().equals("codex")) reason = "Нужен провайдер Codex.";
        else if (command.requiresProject() && !context.project()) reason = "Откройте локальный проект.";
        else if (!command.supportedOs().isEmpty() && command.supportedOs().stream().noneMatch(os -> System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains(os))) reason = "Команда доступна только на ОС: " + String.join(", ", command.supportedOs());
        else if (command.requiresThread() && !context.thread()) reason = "Сначала создайте сессию Codex сообщением.";
        else if (!command.duringTask() && context.running()) reason = "Завершите задачу выбранного чата.";
        else if (command.experimental() && !context.experimental()) reason = "Включите экспериментальный контракт: /experimental on.";
        else if (command.requiresAuth() && Boolean.FALSE.equals(context.signedIn())) reason = "Требуется вход в Codex: /login.";
        else if (command.codexOnly() && !context.cliVersion().isBlank() && !context.cliVersion().equals("0.169.0")) reason = "Версия CLI не соответствует проверенному контракту 0.169.0.";
        return new Result(reason.isEmpty(), reason.isEmpty() ? "Доступна" : reason);
    }
    private CommandAvailability() { }
}
