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
        if (method.equals("item/commandExecution/outputDelta")) {
            String value = text.getOrDefault(id, "") + string(p, "delta"); store(id, value);
            return new ChatEvent(ACTIVITY, id, "Вывод команды", "```text\n" + value + "\n```", false);
        }
        if (method.equals("turn/diff/updated"))
            return new ChatEvent(ACTIVITY, "turn-diff", "Изменения задачи", "```diff\n" + string(p, "diff") + "\n```", true);
        if (method.equals("item/fileChange/patchUpdated"))
            return new ChatEvent(ACTIVITY, id, "Изменения файлов", "```diff\n" + string(p, "patch") + "\n```", false);
        if (method.equals("turn/plan/updated")) {
            StringBuilder plan = new StringBuilder(string(p, "explanation"));
            if (p.has("plan")) for (var step : p.getAsJsonArray("plan")) {
                var entry = step.getAsJsonObject();
                plan.append("\n- ").append(string(entry, "status")).append(": ").append(string(entry, "step"));
            }
            return new ChatEvent(ACTIVITY, "turn-plan", "План", plan.toString(), false);
        }
        if (method.equals("item/autoApprovalReview/completed")) {
            var retry = GuardianRetry.fromNotification(p);
            String status = p.has("review") ? string(p.getAsJsonObject("review"), "status") : "";
            String rationale = p.has("review") ? string(p.getAsJsonObject("review"), "rationale") : "";
            if (retry != null) retry.addProperty("eventType", "guardianDenial");
            return new ChatEvent(ACTIVITY, string(p, "reviewId"), "Автоматическая проверка · " + status,
                    rationale + (retry == null ? "" : "\n\nДля одного явного повтора после завершения turn используйте /approve."), true, "", retry);
        }
        if (method.equals("thread/tokenUsage/updated")) return ChatEvent.simple(USAGE, p.get("tokenUsage").toString());
        if (method.equals("turn/completed")) {
            var turn = p.getAsJsonObject("turn");
            if (string(turn, "status").equals("completed")) return ChatEvent.simple(DONE, "Готово");
            String error = turn.has("error") && turn.get("error").isJsonObject() ? string(turn.getAsJsonObject("error"), "message") : string(turn, "status");
            return ChatEvent.simple(ERROR, "Запрос не завершён: " + error);
        }
        if (method.equals("error")) return ChatEvent.simple(STATUS, p.has("error") && p.get("error").isJsonObject() ? string(p.getAsJsonObject("error"), "message") : "Codex сообщил об ошибке");
        if (!method.equals("item/started") && !method.equals("item/completed")) {
            // Raw reasoning and token-level internal events must never reach public history.
            if (method.contains("reasoning") || method.startsWith("thread/") || method.equals("turn/started"))
                return ChatEvent.simple(IGNORE, "");
            return new ChatEvent(ACTIVITY, id.isEmpty() ? method : id, "Событие Codex · " + method, p.toString(), true);
        }
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
            case "commandExecution" -> {
                title = "Команда · " + string(item, "status");
                String output = string(item, "aggregatedOutput");
                if (output.isEmpty()) output = text.getOrDefault(id, "");
                value = "```shell\n" + string(item, "command") + "\n```\ncwd: " + string(item, "cwd")
                        + "\n\n```text\n" + output + "\n```\nExit: " + string(item, "exitCode")
                        + " · duration: " + string(item, "durationMs") + " ms";
            }
            case "fileChange" -> { title = "Изменения файлов · " + string(item, "status"); StringBuilder changes = new StringBuilder();
                if (item.has("changes")) for (var change : item.getAsJsonArray("changes")) {
                    var entry = change.getAsJsonObject();
                    String operation = entry.has("kind") ? string(entry.getAsJsonObject("kind"), "type") : "";
                    String moved = entry.has("kind") ? string(entry.getAsJsonObject("kind"), "move_path") : "";
                    changes.append("\n").append(operation).append(" · ").append(string(entry, "path"))
                            .append(moved.isBlank() ? "" : " → " + moved).append(" · ")
                            .append(org.agty.aiassistant.changes.PatchSummary.of(string(entry, "diff"))).append("\n\n```diff\n")
                            .append(string(entry, "diff")).append("\n```\n");
                }
                value = changes.toString(); }
            case "webSearch" -> { title = "Поиск"; value = string(item, "query"); }
            case "mcpToolCall" -> { title = "Инструмент · " + string(item, "server") + " / " + string(item, "tool"); value = item.toString(); }
            case "plan" -> { title = "План"; value = string(item, "text"); }
            case "userMessage" -> { return ChatEvent.simple(IGNORE, ""); }
            case "collabAgentToolCall" -> {
                title = "Агенты · " + string(item, "tool") + " · " + string(item, "status");
                StringBuilder agents = new StringBuilder();
                if (item.has("agentsStates")) for (var state : item.getAsJsonObject("agentsStates").entrySet())
                    agents.append("- Агент `").append(state.getKey()).append("`: ").append(state.getValue()).append('\n');
                value = agents + "\nОткрыть связанные чаты: /agents";
            }
            case "subAgentActivity" -> { title = "Подагент · " + string(item, "kind"); value = "Thread: `" + string(item, "agentThreadId") + "`\n\n/agents — открыть чат и наблюдение."; }
            case "imageView" -> { title = "Просмотр изображения"; value = "Локальный файл: `" + string(item, "path").replace("`", "") + "`"; }
            case "imageGeneration" -> {
                title = "Изображение · " + string(item, "status");
                String path = string(item, "savedPath");
                value = path.isBlank() ? "Сгенерированное изображение" : "Файл: `" + path.replace("`", "") + "`";
                if (item.has("fileId")) value += "\nID вложения: " + string(item, "fileId");
                if (item.has("failure") && !item.get("failure").isJsonNull()) value += "\n" + item.get("failure");
            }
            case "sleep" -> { title = "Ожидание"; value = string(item, "durationMs") + " ms; ожидание прерывается вместе с задачей."; }
            case "hookPrompt", "functionCallOutput", "dynamicToolCall", "enteredReviewMode", "exitedReviewMode", "contextCompaction" -> {
                title = "Codex · " + type; value = item.toString();
            }
            default -> { title = "Неизвестный элемент · " + type; value = item.toString(); }
        }
        return new ChatEvent(ACTIVITY, id, title, value, complete, "", item.deepCopy());
    }
    List<ChatEvent> replay(JsonObject turn) {
        List<ChatEvent> events = new ArrayList<>();
        if (turn.has("items") && turn.get("items").isJsonArray()) {
            for (JsonElement element : turn.getAsJsonArray("items")) {
                if (!element.isJsonObject()) continue;
                JsonObject notification = new JsonObject();
                notification.addProperty("method", "item/completed");
                JsonObject params = new JsonObject();
                params.add("item", element.getAsJsonObject());
                notification.add("params", params);
                ChatEvent event = parse(notification);
                if (event.kind() != IGNORE) events.add(event);
            }
        }
        String status = string(turn, "status");
        if (status.equals("completed")) events.add(ChatEvent.simple(DONE, "Готово"));
        else if (status.equals("failed") || status.equals("interrupted")) {
            String message = status;
            if (turn.has("error") && turn.get("error").isJsonObject())
                message = string(turn.getAsJsonObject("error"), "message");
            events.add(ChatEvent.simple(ERROR, "Запрос не завершён: " + message));
        }
        return events;
    }
    private void store(String id, String value) {
        if (value.length() > 16_000_000) throw new IllegalStateException("Ответ превысил 16 000 000 символов.");
        text.put(id, value);
        if (text.size() > 1000 || text.values().stream().mapToInt(String::length).sum() > 64_000_000)
            throw new IllegalStateException("Превышен лимит потокового ответа.");
    }
    static String string(JsonObject o, String key) { return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : ""; }
}
