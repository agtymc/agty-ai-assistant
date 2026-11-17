package org.agty.aiassistant.chat;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Single packaged source for completion, dispatch metadata and offline help. */
public final class SlashCommands {
    public record Command(String name, String description, boolean codexOnly, String category, String syntax,
                          List<String> aliases, String purpose, String scenario, List<String> examples,
                          String result, String constraints, String errors, List<String> related, String ui, List<String> exampleContexts, boolean implemented, boolean experimental, boolean requiresThread, boolean requiresAuth, boolean duringTask, List<String> supportedOs, boolean requiresProject) {
        public Command {
            supportedOs = supportedOs == null ? List.of() : List.copyOf(supportedOs);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }
    public record Invocation(String name, String argument) {}
    public static final List<Command> ALL = read();
    private static List<Command> read() {
        try (var input = Objects.requireNonNull(SlashCommands.class.getResourceAsStream("/commands/catalog.json"));
             var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return List.of(new Gson().fromJson(reader, Command[].class));
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    public static Command find(String name) {
        String canonical = name.startsWith("/") ? name : "/" + name;
        return ALL.stream().filter(c -> c.name().equals(canonical) || c.aliases().contains(canonical)).findFirst().orElse(null);
    }
    public static Invocation parse(String input) {
        String value = input.strip();
        if (!value.startsWith("/") || value.startsWith("//")) return null;
        String[] parts = value.split("\\s+", 2);
        Command command = find(parts[0]);
        return new Invocation(command == null ? parts[0] : command.name(), parts.length > 1 ? parts[1].strip() : "");
    }
    /** Validate the literal argument grammar from the same syntax used by completion and help.
     * Account-specific IDs and paths are validated by their handlers/server, without executing examples. */
    public static String argumentError(Command command, String argument) {
        String grammar = command.syntax().substring(command.name().length()).strip();
        if (grammar.isEmpty()) return argument.isBlank() ? "" : "Команда не принимает аргументы: " + command.syntax();
        boolean optional = grammar.startsWith("[") && grammar.endsWith("]");
        if (argument.isBlank()) return optional ? "" : "Укажите аргументы: " + command.syntax();
        String values = optional ? grammar.substring(1, grammar.length() - 1) : grammar;
        if (values.matches("[a-z-]+(?:\\|[a-z-]+)*")) {
            if (!java.util.Arrays.asList(values.split("\\|")).contains(argument)) return "Синтаксис: " + command.syntax();
        }
        return "";
    }
    public static List<Command> available(String provider) {
        return ALL.stream().filter(c -> c.implemented() && (!c.codexOnly() || provider.equals("codex"))).toList();
    }
    public static String help(String provider) {
        return "Команды обрабатываются плагином.\n\n" + String.join("\n", available(provider).stream()
                .map(c -> "- `" + c.syntax() + "` — " + c.description()).toList())
                + "\n\nДля буквального сообщения с `/` в начале используйте `//`.";
    }
    private SlashCommands() {}
}
