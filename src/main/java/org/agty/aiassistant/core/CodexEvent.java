package org.agty.aiassistant.core;
import com.google.gson.*;
import org.agty.aiassistant.chat.ChatEvent;
import static org.agty.aiassistant.chat.ChatEvent.Kind.*;

/** Maps exec JSONL to presentation events; never invents reasoning. */
public final class CodexEvent {
    private CodexEvent() {}
    public static ChatEvent parse(String line) {
        try {
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
            String type = string(event, "type");
            if (type.equals("thread.started")) return ChatEvent.simple(SESSION, string(event, "thread_id"));
            if (type.equals("turn.completed")) return ChatEvent.simple(DONE, "Готово");
            if (type.equals("turn.started")) return ChatEvent.simple(STATUS, "Обрабатывает запрос");
            if (type.equals("turn.failed")) {
                JsonObject error = event.has("error") && event.get("error").isJsonObject()
                        ? event.getAsJsonObject("error") : event;
                return ChatEvent.simple(ERROR, string(error, "message"));
            }
            if (type.equals("error")) return ChatEvent.simple(ERROR, string(event, "message"));
            if (!type.startsWith("item.") || !event.has("item")) return ChatEvent.simple(IGNORE, "");
            JsonObject item = event.getAsJsonObject("item");
            String id = string(item, "id");
            boolean complete = type.equals("item.completed");
            String text = string(item, "text");
            return switch (string(item, "type")) {
                case "agent_message" -> new ChatEvent(MESSAGE, id, "", text, complete);
                case "reasoning" -> new ChatEvent(ACTIVITY, id, "Пояснение модели", text, complete);
                case "command_execution" -> new ChatEvent(ACTIVITY, id, "Команда · " + string(item, "status"),
                        "```shell\n" + string(item, "command") + "\n```\n"
                        + bounded(string(item, "aggregated_output"))
                        + (item.has("exit_code") ? "\n\nКод завершения: " + item.get("exit_code") : ""), complete);
                case "web_search" -> new ChatEvent(ACTIVITY, id, "Поиск", string(item, "query"), complete);
                case "mcp_tool_call" -> new ChatEvent(ACTIVITY, id, "Инструмент",
                        string(item, "server") + " / " + string(item, "tool") + " · " + string(item, "status"), complete);
                case "plan" -> new ChatEvent(ACTIVITY, id, "План", plan(item), complete);
                case "file_change" -> new ChatEvent(ACTIVITY, id, "Изменения файлов", bounded(item.toString()), complete);
                case "error" -> ChatEvent.simple(ERROR, string(item, "message"));
                default -> ChatEvent.simple(IGNORE, "");
            };
        } catch (RuntimeException e) {
            return ChatEvent.simple(ERROR, "Некорректный JSON от Codex. Проверьте совместимость CLI.");
        }
    }
    private static String plan(JsonObject item) {
        StringBuilder text = new StringBuilder();
        if (item.has("items") && item.get("items").isJsonArray()) {
            for (JsonElement element : item.getAsJsonArray("items")) {
                JsonObject step = element.getAsJsonObject();
                text.append("- ").append(string(step, "text")).append(" · ").append(string(step, "status")).append('\n');
            }
        }
        return text.toString();
    }
    private static String bounded(String text) {
        return text.length() <= 6000 ? text : text.substring(0, 6000) + "\n… вывод сокращён";
    }
    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
}
