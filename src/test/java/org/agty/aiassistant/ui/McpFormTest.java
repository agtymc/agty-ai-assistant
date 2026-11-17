package org.agty.aiassistant.ui;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class McpFormTest {
    @Test void supportsTitledEnumsArraysAndTypedFormats() {
        var schema = JsonParser.parseString("""
            {"type":"object","required":["choice","many","date"],"properties":{
              "choice":{"type":"string","oneOf":[{"const":"x","title":"X"},{"const":"y","title":"Y"}]},
              "many":{"type":"array","items":{"anyOf":[{"const":"a","title":"A"},{"const":"b","title":"B"}]},"minItems":1,"maxItems":2},
              "date":{"type":"string","format":"date"}}}
            """).getAsJsonObject();
        assertTrue(McpForm.supported(schema));
        assertTrue(McpForm.valid(schema, JsonParser.parseString("{\"choice\":\"x\",\"many\":[\"a\",\"b\"],\"date\":\"2026-11-17\"}")));
        assertFalse(McpForm.valid(schema, JsonParser.parseString("{\"choice\":\"z\",\"many\":[\"a\"],\"date\":\"2026-11-17\"}")));
        assertFalse(McpForm.valid(schema, JsonParser.parseString("{\"choice\":\"x\",\"many\":[\"a\",\"a\"],\"date\":\"invalid\"}")));
    }
    @Test void inventoryOmitsCredentialsButKeepsTokenUsageAndPaths() {
        String result = SessionCommandActions.inventory(JsonParser.parseString("""
            {"accessToken":"secret-one","apiKey":"secret-two","refresh_token":"secret-three", "totalTokens":123,
            "paths":["/project/a"],"authStatus":"loggedIn"}
            """));
        assertFalse(result.contains("secret-")); assertTrue(result.contains("123")); assertTrue(result.contains("/project/a")); assertTrue(result.contains("loggedIn"));
    }
}
