package org.agty.aiassistant.ui;

import com.google.gson.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import javax.swing.*;
import java.util.*;

/** Explicit user answers only. Closing a dialog leaves the request pending. */
final class ServerRequestDialog {
    static JsonObject answer(Project project, JsonObject request) {
        String method = request.get("method").getAsString();
        JsonObject params = request.getAsJsonObject("params");
        if (method.equals("item/tool/requestUserInput")) return questions(params);
        if (method.equals("mcpServer/elicitation/request")) return elicitation(project, params);
        if (method.equals("item/permissions/requestApproval")) {
            int choice = Messages.showDialog(project, "Дополнительные права на этот turn:\n" + params.get("permissions")
                    + "\nПричина: " + text(params, "reason"), "Дополнительные права Codex",
                    new String[]{"Разрешить на этот turn", "Отказать", "Ответить позже"}, 2, Messages.getQuestionIcon());
            if (choice < 0 || choice == 2) return null;
            JsonObject result = new JsonObject(); result.addProperty("scope", "turn");
            result.add("permissions", choice == 0 ? params.get("permissions").deepCopy() : new JsonObject()); return result;
        }
        var decisions = ApprovalDecisions.choices(params, method.equals("item/commandExecution/requestApproval"));
        String[] labels = new String[decisions.size() + 1];
        for (int i = 0; i < decisions.size(); i++) labels[i] = decisions.get(i).label();
        labels[decisions.size()] = "Ответить позже";
        JTextArea detail = new JTextArea("Операция: " + method + "\n" + new GsonBuilder().setPrettyPrinting().create().toJson(params));
        detail.setEditable(false); detail.setLineWrap(true); detail.setWrapStyleWord(true); detail.setRows(12); detail.setColumns(65);
        int choice = JOptionPane.showOptionDialog(null, new JScrollPane(detail), "Подтверждение Codex",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, labels, labels[labels.length - 1]);
        return choice < 0 || choice >= decisions.size() ? null : decisions.get(choice).response();
    }

    private static JsonObject questions(JsonObject params) {
        JPanel form = new JPanel(); form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        Map<String, JComponent> fields = new LinkedHashMap<>();
        for (var element : params.getAsJsonArray("questions")) {
            var question = element.getAsJsonObject();
            var label = new JTextArea(text(question, "question")); label.setEditable(false); label.setLineWrap(true); label.setWrapStyleWord(true);
            form.add(label);
            JComponent field;
            if (question.has("isSecret") && question.get("isSecret").getAsBoolean()) field = new JPasswordField(40);
            else if (question.has("options") && question.get("options").isJsonArray()) {
                JComboBox<String> choices = new JComboBox<>();
                for (var option : question.getAsJsonArray("options")) choices.addItem(text(option.getAsJsonObject(), "label"));
                choices.setEditable(question.has("isOther") && question.get("isOther").getAsBoolean());
                choices.setSelectedIndex(-1); field = choices;
            } else field = new JTextField(40);
            form.add(field); fields.put(text(question, "id"), field);
        }
        if (JOptionPane.showConfirmDialog(null, form, "Вопросы Codex", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return null;
        JsonObject answers = new JsonObject();
        for (var pair : fields.entrySet()) {
            String value = pair.getValue() instanceof JComboBox<?> combo ? Objects.toString(combo.getSelectedItem(), "")
                    : ((JTextField) pair.getValue()).getText();
            if (value.isBlank()) return null;
            JsonArray values = new JsonArray(); values.add(value); JsonObject answer = new JsonObject(); answer.add("answers", values);
            answers.add(pair.getKey(), answer);
        }
        JsonObject result = new JsonObject(); result.add("answers", answers); return result;
    }
    private static JsonObject elicitation(Project project, JsonObject params) {
        String mode = text(params, "mode");
        if (mode.equals("url")) {
            int selected = Messages.showDialog(project, "MCP: " + text(params, "serverName") + "\n" + text(params, "message") + "\n" + text(params, "url"),
                    "Авторизация MCP", new String[]{"Открыть браузер", "Подтвердить завершение", "Отказать", "Отменить", "Позже"}, 4, Messages.getQuestionIcon());
            if (selected < 0 || selected == 4) return null;
            if (selected == 0) {
                var uri = java.net.URI.create(text(params, "url"));
                if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) {
                    Messages.showErrorDialog(project, "Недопустимый URL MCP.", "MCP"); return null;
                }
                com.intellij.ide.BrowserUtil.browse(uri.toString()); return null;
            }
            JsonObject result = new JsonObject(); result.addProperty("action", selected == 1 ? "accept" : selected == 2 ? "decline" : "cancel"); return result;
        }
        boolean supported = java.util.Set.of("form", "openai/form", "openaiForm").contains(mode) && params.has("requestedSchema") && supportedSchema(params.getAsJsonObject("requestedSchema"));
        int choice = Messages.showDialog(project, "MCP: " + text(params, "serverName") + "\n" + text(params, "message")
                        + (supported ? "\nСервер просит заполнить форму данных." : "\nЭтот формат пока нельзя принять в плагине."),
                "Запрос MCP", supported ? new String[]{"Заполнить форму", "Отказать", "Отменить", "Позже"}
                        : new String[]{"Отказать", "Отменить", "Позже"}, supported ? 3 : 2, Messages.getQuestionIcon());
        if (choice < 0 || choice == (supported ? 3 : 2)) return null;
        JsonObject result = new JsonObject();
        if (supported && choice == 0) {
            JsonObject content = McpForm.ask(params.getAsJsonObject("requestedSchema"), text(params, "message"));
            if (content == null) return null;
            result.addProperty("action", "accept"); result.add("content", content); return result;
        }
        result.addProperty("action", choice == (supported ? 1 : 0) ? "decline" : "cancel"); return result;
    }
    static boolean supportedSchema(JsonObject schema) { return McpForm.supported(schema); }
    static boolean validForm(JsonObject schema, JsonElement content) { return McpForm.valid(schema, content); }
    private static String text(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : ""; }
}
