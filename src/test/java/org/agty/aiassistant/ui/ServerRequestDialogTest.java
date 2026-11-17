package org.agty.aiassistant.ui;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerRequestDialogTest {
    @Test void serverOptionLabelsRemainPlainTextAndSecretQuestionsUsePasswordFields() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            String label = "<html><img src='https://example.invalid/tracking'>Разрешить</html>";
            var question = new com.google.gson.JsonObject();
            var option = new com.google.gson.JsonObject(); option.addProperty("label", label);
            var options = new com.google.gson.JsonArray(); options.add(option); question.add("options", options);
            @SuppressWarnings("unchecked")
            var choices = (javax.swing.JComboBox<String>) ServerRequestDialog.questionField(question);
            var rendered = (javax.swing.JLabel) choices.getRenderer().getListCellRendererComponent(new javax.swing.JList<>(), label, 0, false, false);
            assertEquals(label, rendered.getText());
            assertNull(rendered.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey), "Untrusted labels must not create Swing HTML/ImageView renderers");
            for (var component : java.util.List.of(PlainText.label(label), PlainText.menuItem(label), PlainText.checkBox(label, true)))
                assertNull(component.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey));
            assertEquals(-1, choices.getSelectedIndex());
            question.addProperty("isSecret", true);
            assertInstanceOf(javax.swing.JPasswordField.class, ServerRequestDialog.questionField(question));
        });
    }
    @Test void validatesMcpPrimitiveFormsAndRejectsUnknownConstraints() {
        var schema = JsonParser.parseString("""
                {"type":"object","required":["count"],"properties":{
                "count":{"type":"integer","minimum":1,"maximum":3},
                "name":{"type":"string","enum":["a","b"]}}}
                """).getAsJsonObject();
        assertTrue(ServerRequestDialog.validForm(schema, JsonParser.parseString("{\"count\":2,\"name\":\"a\"}")));
        assertFalse(ServerRequestDialog.validForm(schema, JsonParser.parseString("{\"count\":2.5}")));
        assertFalse(ServerRequestDialog.validForm(schema, JsonParser.parseString("{\"count\":4}")));
        assertFalse(ServerRequestDialog.validForm(schema, JsonParser.parseString("{}")));
        assertFalse(ServerRequestDialog.validForm(schema, JsonParser.parseString("{\"count\":2,\"name\":\"c\"}")));
        schema.getAsJsonObject("properties").getAsJsonObject("count").addProperty("multipleOf", 2);
        assertFalse(ServerRequestDialog.supportedSchema(schema));
    }
}
