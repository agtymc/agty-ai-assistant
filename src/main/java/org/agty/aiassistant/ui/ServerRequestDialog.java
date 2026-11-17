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
        int choice = Messages.showDialog(project, "Операция: " + method + "\n" + params,
                "Подтверждение Codex", new String[]{"Разрешить один раз", "Разрешить для сессии", "Отказать", "Отменить turn", "Ответить позже"},
                4, Messages.getQuestionIcon());
        if (choice < 0 || choice >= 4) return null;
        JsonObject result = new JsonObject(); result.addProperty("decision", List.of("accept", "acceptForSession", "decline", "cancel").get(choice));
        return result;
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
        // Accept only forms whose fields can be validated locally; other forms remain visible and can be declined.
        boolean supported = mode.equals("form") && params.has("requestedSchema") && supportedSchema(params.getAsJsonObject("requestedSchema"));
        int choice = Messages.showDialog(project, "MCP: " + text(params, "serverName") + "\n" + text(params, "message")
                        + (supported ? "\n" + params.get("requestedSchema") : "\nЭтот формат пока нельзя принять в плагине."),
                "Запрос MCP", supported ? new String[]{"Заполнить JSON", "Отказать", "Отменить", "Позже"}
                        : new String[]{"Отказать", "Отменить", "Позже"}, supported ? 3 : 2, Messages.getQuestionIcon());
        if (choice < 0 || choice == (supported ? 3 : 2)) return null;
        JsonObject result = new JsonObject();
        if (supported && choice == 0) {
            String input = Messages.showInputDialog(project, "Введите объект JSON по показанной схеме", "Ответ MCP", Messages.getQuestionIcon());
            if (input == null) return null;
            try {
                var content = JsonParser.parseString(input);
                if (!validForm(params.getAsJsonObject("requestedSchema"), content)) throw new IllegalArgumentException();
                result.addProperty("action", "accept"); result.add("content", content); return result;
            } catch (RuntimeException e) { Messages.showErrorDialog(project, "Ответ не соответствует схеме. Запрос остаётся ожидающим.", "Ответ MCP"); return null; }
        }
        result.addProperty("action", choice == (supported ? 1 : 0) ? "decline" : "cancel"); return result;
    }
    static boolean supportedSchema(JsonObject schema) {
        if (!text(schema, "type").equals("object") || !schema.has("properties")) return false;
        for (var property : schema.getAsJsonObject("properties").entrySet()) {
            if (!property.getValue().isJsonObject()) return false;
            var field = property.getValue().getAsJsonObject();
            if (!Set.of("string", "boolean", "integer", "number").contains(text(field, "type"))) return false;
            if (!Set.of("type", "title", "description", "enum", "minimum", "maximum", "minLength", "maxLength", "default")
                    .containsAll(field.keySet())) return false;
        }
        return true;
    }
    static boolean validForm(JsonObject schema, JsonElement content) {
        if (!supportedSchema(schema) || !content.isJsonObject()) return false;
        var object = content.getAsJsonObject(); var properties = schema.getAsJsonObject("properties");
        if (schema.has("required")) for (var key : schema.getAsJsonArray("required")) if (!object.has(key.getAsString())) return false;
        for (var pair : object.entrySet()) {
            if (!properties.has(pair.getKey()) || !pair.getValue().isJsonPrimitive()) return false;
            var field = properties.getAsJsonObject(pair.getKey()); var value = pair.getValue().getAsJsonPrimitive();
            String type = text(field, "type");
            if (type.equals("string") && !value.isString() || type.equals("boolean") && !value.isBoolean()) return false;
            if (type.equals("integer") || type.equals("number")) {
                if (!value.isNumber()) return false;
                var number = value.getAsBigDecimal();
                if (type.equals("integer") && number.stripTrailingZeros().scale() > 0) return false;
                if (field.has("minimum") && number.compareTo(field.get("minimum").getAsBigDecimal()) < 0) return false;
                if (field.has("maximum") && number.compareTo(field.get("maximum").getAsBigDecimal()) > 0) return false;
            }
            if (type.equals("string")) {
                int length = value.getAsString().codePointCount(0, value.getAsString().length());
                if (field.has("minLength") && length < field.get("minLength").getAsInt()) return false;
                if (field.has("maxLength") && length > field.get("maxLength").getAsInt()) return false;
            }
            if (field.has("enum")) { boolean found = false; for (var option : field.getAsJsonArray("enum")) if (option.equals(value)) found = true; if (!found) return false; }
        }
        return true;
    }
    private static String text(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : ""; }
}
