package org.agty.aiassistant.ui;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerRequestDialogTest {
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
