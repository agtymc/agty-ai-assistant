package org.agty.aiassistant.ui;

import com.google.gson.*;
import javax.swing.*;
import java.util.*;

/** All typed MCP 2025-11-25 primitive form variants. Unknown schema constraints fail closed. */
final class McpForm {
    private static final Set<String> KEYS = Set.of("type", "title", "description", "default", "minimum", "maximum", "minLength", "maxLength", "format", "enum", "enumNames", "oneOf", "items", "minItems", "maxItems");
    private record Choice(String value, String label) { @Override public String toString() { return label; } }
    private static boolean present(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull(); }
    private static String text(JsonObject object, String key) { return present(object, key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : ""; }
    static boolean supported(JsonObject schema) {
        try {
            if (!text(schema, "type").equals("object") || !schema.has("properties")) return false;
            if (!Set.of("$schema", "type", "properties", "required", "title", "description", "additionalProperties").containsAll(schema.keySet())) return false;
            for (var pair : schema.getAsJsonObject("properties").entrySet()) {
                if (!pair.getValue().isJsonObject()) return false;
                var field = pair.getValue().getAsJsonObject(); String type = text(field, "type");
                if (!KEYS.containsAll(field.keySet()) || !Set.of("string", "integer", "number", "boolean", "array").contains(type)) return false;
                Set<String> allowed = new HashSet<>(Set.of("type", "title", "description", "default"));
                if (type.equals("string")) allowed.addAll(Set.of("format", "minLength", "maxLength", "enum", "enumNames", "oneOf"));
                else if (type.equals("number") || type.equals("integer")) allowed.addAll(Set.of("minimum", "maximum"));
                else if (type.equals("array")) allowed.addAll(Set.of("items", "minItems", "maxItems"));
                if (!allowed.containsAll(field.keySet())) return false;
                if (present(field, "format") && !Set.of("email", "uri", "date", "date-time").contains(text(field, "format"))) return false;
                if (type.equals("array")) {
                    if (!present(field, "items")) return false;
                    var items = field.getAsJsonObject("items");
                    if (!Set.of("type", "enum", "anyOf").containsAll(items.keySet()) || choices(items).isEmpty()) return false;
                }
                if (present(field, "oneOf") || present(field, "enum")) if (choices(field).isEmpty()) return false;
            }
            return true;
        } catch (RuntimeException e) { return false; }
    }
    private static List<Choice> choices(JsonObject field) {
        List<Choice> result = new ArrayList<>();
        if (present(field, "enum")) {
            int i = 0;
            for (var value : field.getAsJsonArray("enum")) {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return List.of();
                String label = present(field, "enumNames") && i < field.getAsJsonArray("enumNames").size() ? field.getAsJsonArray("enumNames").get(i).getAsString() : value.getAsString();
                result.add(new Choice(value.getAsString(), label)); i++;
            }
        } else {
            String key = present(field, "oneOf") ? "oneOf" : "anyOf";
            if (present(field, key)) for (var option : field.getAsJsonArray(key)) {
                var value = option.getAsJsonObject(); if (!Set.of("const", "title").containsAll(value.keySet()) || !present(value, "const")) return List.of();
                if (!value.get("const").isJsonPrimitive() || !value.get("const").getAsJsonPrimitive().isString()) return List.of();
                result.add(new Choice(text(value, "const"), present(value, "title") ? text(value, "title") : text(value, "const")));
            }
        }
        return result;
    }
    static boolean valid(JsonObject schema, JsonElement content) {
        try {
            if (!supported(schema) || !content.isJsonObject()) return false;
            var object = content.getAsJsonObject(); var properties = schema.getAsJsonObject("properties");
            if (present(schema, "required")) for (var key : schema.getAsJsonArray("required")) if (!object.has(key.getAsString())) return false;
            for (var pair : object.entrySet()) {
                if (!properties.has(pair.getKey())) return false;
                var field = properties.getAsJsonObject(pair.getKey()); var value = pair.getValue(); String type = text(field, "type");
                if (type.equals("array")) {
                    if (!value.isJsonArray()) return false;
                    var array = value.getAsJsonArray();
                    if (present(field, "minItems") && array.size() < field.get("minItems").getAsInt() || present(field, "maxItems") && array.size() > field.get("maxItems").getAsInt()) return false;
                    var options = choices(field.getAsJsonObject("items")); Set<String> unique = new HashSet<>();
                    for (var item : array) if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                            || !unique.add(item.getAsString()) || options.stream().noneMatch(c -> c.value.equals(item.getAsString()))) return false;
                    continue;
                }
                if (!value.isJsonPrimitive()) return false; var primitive = value.getAsJsonPrimitive();
                if (type.equals("boolean") && !primitive.isBoolean() || type.equals("string") && !primitive.isString()) return false;
                if (type.equals("number") || type.equals("integer")) {
                    if (!primitive.isNumber()) return false; var number = primitive.getAsBigDecimal();
                    if (type.equals("integer") && number.stripTrailingZeros().scale() > 0) return false;
                    if (present(field, "minimum") && number.compareTo(field.get("minimum").getAsBigDecimal()) < 0
                            || present(field, "maximum") && number.compareTo(field.get("maximum").getAsBigDecimal()) > 0) return false;
                }
                if (type.equals("string")) {
                    String string = primitive.getAsString(); int length = string.codePointCount(0, string.length());
                    if (present(field, "minLength") && length < field.get("minLength").getAsInt() || present(field, "maxLength") && length > field.get("maxLength").getAsInt()) return false;
                    if (present(field, "format")) switch (text(field, "format")) {
                        case "email" -> { if (!string.matches("[^\\s@]+@[^\\s@]+")) return false; }
                        case "uri" -> { if (!java.net.URI.create(string).isAbsolute()) return false; }
                        case "date" -> java.time.LocalDate.parse(string);
                        case "date-time" -> java.time.OffsetDateTime.parse(string);
                        default -> { return false; }
                    }
                    if ((present(field, "enum") || present(field, "oneOf")) && choices(field).stream().noneMatch(c -> c.value.equals(string))) return false;
                }
            }
            return true;
        } catch (RuntimeException e) { return false; }
    }
    static JsonObject ask(JsonObject schema, String message) {
        if (!supported(schema)) return null;
        JPanel form = new JPanel(); form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        var introduction = new JTextArea(message); introduction.setEditable(false); introduction.setLineWrap(true); introduction.setWrapStyleWord(true); form.add(introduction);
        Map<String, JComponent> editors = new LinkedHashMap<>(); Map<String, JCheckBox> included = new LinkedHashMap<>();
        Set<String> required = new HashSet<>(); if (present(schema, "required")) for (var key : schema.getAsJsonArray("required")) required.add(key.getAsString());
        for (var pair : schema.getAsJsonObject("properties").entrySet()) {
            String key = pair.getKey(); var field = pair.getValue().getAsJsonObject(); String type = text(field, "type");
            JCheckBox include = new JCheckBox((present(field, "title") ? text(field, "title") : key) + (required.contains(key) ? " *" : ""), required.contains(key) || present(field, "default"));
            include.putClientProperty("html.disable", true); if (required.contains(key)) include.setEnabled(false); form.add(include); included.put(key, include);
            JComponent editor;
            if (type.equals("array")) {
                var options = choices(field.getAsJsonObject("items")); var list = new JList<>(options.toArray(Choice[]::new));
                list.setCellRenderer(plainRenderer()); list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION); list.setVisibleRowCount(Math.min(5, options.size()));
                editor = list; form.add(new JScrollPane(list));
                if (present(field, "default")) for (int i = 0; i < options.size(); i++) for (var value : field.getAsJsonArray("default")) if (options.get(i).value.equals(value.getAsString())) list.addSelectionInterval(i, i);
            } else if (type.equals("boolean")) { editor = new JCheckBox(); ((JCheckBox) editor).setSelected(present(field, "default") && field.get("default").getAsBoolean()); form.add(editor); }
            else if (!choices(field).isEmpty()) {
                var combo = new JComboBox<>(choices(field).toArray(Choice[]::new)); combo.setRenderer(plainRenderer()); combo.setSelectedIndex(-1);
                if (present(field, "default")) for (int i = 0; i < combo.getItemCount(); i++) if (combo.getItemAt(i).value.equals(field.get("default").getAsString())) combo.setSelectedIndex(i);
                editor = combo; form.add(editor);
            } else { editor = new JTextField(present(field, "default") ? field.get("default").getAsString() : "", 40); form.add(editor); }
            editor.setToolTipText("Описание: " + text(field, "description")); editors.put(key, editor);
        }
        while (JOptionPane.showConfirmDialog(null, new JScrollPane(form), "Данные для MCP", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            JsonObject result = new JsonObject();
            try {
                for (var pair : editors.entrySet()) {
                    if (!included.get(pair.getKey()).isSelected()) continue;
                    JComponent editor = pair.getValue(); JsonElement value;
                    if (editor instanceof JList<?> list) { JsonArray array = new JsonArray(); for (Object option : list.getSelectedValuesList()) array.add(((Choice) option).value); value = array; }
                    else if (editor instanceof JCheckBox box) value = new JsonPrimitive(box.isSelected());
                    else if (editor instanceof JComboBox<?> combo) value = new JsonPrimitive(((Choice) combo.getSelectedItem()).value);
                    else {
                        String input = ((JTextField) editor).getText(); String type = text(schema.getAsJsonObject("properties").getAsJsonObject(pair.getKey()), "type");
                        value = type.equals("string") ? new JsonPrimitive(input) : new JsonPrimitive(new java.math.BigDecimal(input));
                    }
                    result.add(pair.getKey(), value);
                }
                if (valid(schema, result)) return result;
            } catch (RuntimeException ignored) { }
            JOptionPane.showMessageDialog(null, "Проверьте обязательные поля, типы, форматы и ограничения значений.", "Ответ MCP", JOptionPane.ERROR_MESSAGE);
        }
        return null;
    }
    private static DefaultListCellRenderer plainRenderer() {
        var renderer = new DefaultListCellRenderer(); renderer.putClientProperty("html.disable", true); return renderer;
    }
    private McpForm() { }
}
