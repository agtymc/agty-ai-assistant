package org.agty.aiassistant.core;

import com.google.gson.*;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Whitelist public status fields; never render raw configuration or authentication responses. */
public final class CodexStatus {
    public static String read(String binary, Path root, String threadId, String selectedModel, String selectedEffort, boolean writeAccess) throws Exception {
        try (var client = new CodexSessionClient(binary, root)) {
            JsonObject thread = null;
            TurnModel last = null;
            if (!threadId.isBlank()) {
                try {
                    thread = client.readThread(threadId, root, false);
                    String path = CodexStream.string(thread, "path");
                    if (!path.isBlank()) last = lastTurn(Path.of(path));
                } catch (Exception ignored) { }
            }
            JsonObject config = null;
            try {
                JsonObject params = new JsonObject(); params.addProperty("cwd", root.toString()); params.addProperty("includeLayers", false);
                config = client.call("config/read", params).getAsJsonObject("config");
            } catch (Exception ignored) { }
            String version = thread == null ? "неизвестна" : CodexStream.string(thread, "cliVersion");
            String provider = thread == null ? "" : CodexStream.string(thread, "modelProvider");
            if (provider.isBlank() && config != null) provider = CodexStream.string(config, "model_provider");
            String summary = config == null ? "" : CodexStream.string(config, "model_reasoning_summary");
            StringBuilder result = new StringBuilder("### >_ OpenAI Codex");
            if (!version.isBlank() && !version.equals("неизвестна")) result.append(" (v").append(version).append(')');
            result.append("\n\n[Лимиты и кредиты в Codex](https://chatgpt.com/codex/settings/usage)\n\n");
            result.append("- **Модель для следующего запроса:** `").append(clean(selectedModel)).append("` (reasoning `")
                    .append(clean(selectedEffort)).append("`, summaries `").append(clean(summary)).append("`)\n");
            result.append("- **Последний записанный ход:** ");
            if (last == null) result.append("данные недоступны\n");
            else result.append('`').append(clean(last.model())).append("` (reasoning `").append(clean(last.effort())).append("`)\n");
            result.append("- **Model provider:** `").append(clean(provider)).append("`\n")
                    .append("- **Directory:** `").append(clean(root.toString())).append("`\n")
                    .append("- **Permissions:** ").append(writeAccess ? "редактирование проекта" : "только чтение")
                    .append("; approval `never`\n")
                    .append("- **Agents.md:** ").append(Files.isRegularFile(root.resolve("AGENTS.md")) ? "`AGENTS.md`" : "не найден")
                    .append("\n- **Collaboration mode:** не предоставлен API плагина\n")
                    .append("- **Session:** `").append(clean(threadId)).append("`\n");
            try {
                var response = client.call("account/read", new JsonObject());
                if (response.has("account") && response.get("account").isJsonObject()) {
                    var account = response.getAsJsonObject("account");
                    result.append("- **Account:** ").append(field(account, "email")).append(" (")
                            .append(field(account, "planType")).append(", ").append(field(account, "type")).append(")\n");
                } else result.append("- **Account:** API не вернул данные\n");
            } catch (Exception e) { result.append("- **Account:** недоступен через API\n"); }
            try { result.append("\n").append(limits(client.call("account/rateLimits/read", new JsonObject()))); }
            catch (Exception e) { result.append("\n- Лимиты: недоступны для этого подключения или API.\n"); }
            return result.toString();
        }
    }
    private static String clean(String value) {
        return value == null || value.isBlank() ? "не предоставлено" : value.replace('`', ' ').replace('\n', ' ').replace('\r', ' ');
    }
    record TurnModel(String model, String effort) {}
    static TurnModel lastTurn(Path path) throws Exception {
        TurnModel last = null;
        try (BufferedReader lines = Files.newBufferedReader(path)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (!line.contains("\"turn_context\"")) continue;
                try {
                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                    if (!"turn_context".equals(CodexStream.string(event, "type"))) continue;
                    JsonObject payload = event.getAsJsonObject("payload");
                    if (payload == null) continue;
                    String model = CodexStream.string(payload, "model");
                    String effort = CodexStream.string(payload, "effort");
                    if (!model.isBlank()) last = new TurnModel(model, effort.isBlank() ? "не указан" : effort);
                } catch (RuntimeException ignored) { }
            }
        }
        return last;
    }
    static String limits(JsonObject response) {
        StringBuilder text = new StringBuilder();
        var limits = response.getAsJsonObject("rateLimits");
        if (limits == null) return "- Лимиты: API не вернул данные.\n";
        for (String key : new String[]{"primary", "secondary"}) {
            if (!limits.has(key) || !limits.get(key).isJsonObject()) continue;
            var window = limits.getAsJsonObject(key);
            String duration = CodexStream.string(window, "windowDurationMins");
            String label = duration.equals("300") ? "5h limit" : duration.equals("10080") ? "Weekly limit" : "Лимит " + key;
            text.append("- **").append(label).append(":** ");
            try {
                int left = Math.max(0, Math.min(100, 100 - (int) Math.round(Double.parseDouble(CodexStream.string(window, "usedPercent")))));
                int filled = (int) Math.round(left / 5.0);
                text.append('[').append("█".repeat(filled)).append("░".repeat(20 - filled)).append("] ").append(left).append("% осталось");
            } catch (RuntimeException e) { text.append("использовано ").append(field(window, "usedPercent")).append("%"); }
            text.append(" (окно ").append(field(window, "windowDurationMins")).append(" мин)");
            if (window.has("resetsAt") && !window.get("resetsAt").isJsonNull()) {
                try { text.append(" · сброс ").append(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
                        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(window.get("resetsAt").getAsLong()))); }
                catch (RuntimeException ignored) { }
            }
            text.append('\n');
        }
        return text.isEmpty() ? "- Лимиты: API не вернул окна использования.\n" : text.toString();
    }
    public static String usage(String json) {
        if (json.isBlank()) return "Данные о токенах этой сессии ещё не получены.";
        try {
            var usage = JsonParser.parseString(json).getAsJsonObject();
            var last = usage.getAsJsonObject("last"); var total = usage.getAsJsonObject("total");
            return "Последние полученные данные Codex:\n\n- Токены последнего хода: " + field(last, "totalTokens")
                    + "\n- Токены всего: " + field(total, "totalTokens")
                    + "\n- Окно контекста: " + field(usage, "modelContextWindow");
        } catch (RuntimeException e) { return "Данные о токенах недоступны."; }
    }
    private static String field(JsonObject object, String key) {
        if (object == null) return "не предоставлено";
        String value = CodexStream.string(object, key);
        return value.isEmpty() ? "не предоставлено" : "`" + value.replace("`", "").replace('\n', ' ') + "`";
    }
    private CodexStatus() {}
}
