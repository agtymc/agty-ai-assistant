package org.agty.aiassistant.core;

import com.google.gson.*;
import org.agty.aiassistant.chat.ChatEvent;
import java.util.*;
import static org.agty.aiassistant.chat.ChatEvent.Kind.*;

/** App-server deltas become provider-independent snapshots with stable item ids. */
final class CodexStream {
    private final Map<String, String> text = new HashMap<>();
    ChatEvent parse(JsonObject notification) {
        String method = string(notification, "method");
        JsonObject p = notification.has("params") ? notification.getAsJsonObject("params") : new JsonObject();
        String id = string(p, "itemId");
        if (method.equals("item/agentMessage/delta") || method.equals("item/plan/delta") || method.equals("item/reasoning/summaryTextDelta")) {
            boolean message = method.equals("item/agentMessage/delta");
            String value = text.getOrDefault(id, "") + string(p, "delta");
            store(id, value);
            return new ChatEvent(message ? MESSAGE : ACTIVITY, id, message ? "" : "Пояснение / план", value, false);
        }
        if (method.equals("thread/tokenUsage/updated")) return ChatEvent.simple(USAGE, p.get("tokenUsage").toString());
        if (method.equals("turn/completed")) {
            var turn = p.getAsJsonObject("turn");
            if (string(turn, "status").equals("completed")) return ChatEvent.simple(DONE, "Готово");
            String error = turn.has("error") && turn.get("error").isJsonObject() ? string(turn.getAsJsonObject("error"), "message") : string(turn, "status");
            return ChatEvent.simple(ERROR, "Запрос не завершён: " + error);
        }
        if (method.equals("error")) return ChatEvent.simple(STATUS, p.has("error") && p.get("error").isJsonObject() ? string(p.getAsJsonObject("error"), "message") : "Codex сообщил об ошибке");
        if (!method.equals("item/started") && !method.equals("item/completed")) return ChatEvent.simple(IGNORE, "");
        var item = p.getAsJsonObject("item"); id = string(item, "id");
        boolean complete = method.equals("item/completed");
        String type = string(item, "type");
        if (type.equals("agentMessage")) {
            String value = string(item, "text");
            if (!complete && value.isEmpty()) value = text.getOrDefault(id, "");
            store(id, value);
            return new ChatEvent(MESSAGE, id, "", value, complete);
        }
        if (type.equals("reasoning")) {
            // Only public summaries, never raw reasoning content.
            String value = "";
            if (item.has("summary")) for (var part : item.getAsJsonArray("summary")) value += part.getAsString() + "\n";
            if (value.isBlank()) value = text.getOrDefault(id, "");
            return new ChatEvent(ACTIVITY, id, "Пояснение модели", value, complete);
        }
        String title, value;
        switch (type) {
            case "commandExecution" -> { title = "Команда · " + string(item, "status"); value = "```shell\n" + string(item, "command") + "\n```\n" + bounded(string(item, "aggregatedOutput")); }
            case "fileChange" -> { title = "Изменения файлов · " + string(item, "status"); value = item.has("changes") ? bounded(item.get("changes").toString()) : ""; }
            case "webSearch" -> { title = "Поиск"; value = string(item, "query"); }
            case "mcpToolCall" -> { title = "Инструмент"; value = string(item, "server") + " / " + string(item, "tool"); }
            case "plan" -> { title = "План"; value = string(item, "text"); }
            default -> { return ChatEvent.simple(IGNORE, ""); }
        }
        return new ChatEvent(ACTIVITY, id, title, value, complete);
    }
    private void store(String id, String value) {
        if (value.length() > 500_000) throw new IllegalStateException("Ответ превысил 500 000 символов.");
        text.put(id, value);
        if (text.size() > 1000 || text.values().stream().mapToInt(String::length).sum() > 1_000_000)
            throw new IllegalStateException("Превышен лимит потокового ответа.");
    }
    static String string(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : ""; }
    private static String bounded(String value) { return value.length() > 6000 ? value.substring(0, 6000) + "\n…" : value; }
}
