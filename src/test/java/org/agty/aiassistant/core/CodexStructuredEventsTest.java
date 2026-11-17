package org.agty.aiassistant.core;

import com.google.gson.*;
import org.agty.aiassistant.chat.ChatEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodexStructuredEventsTest {
    private JsonObject event(String method, String params) {
        return JsonParser.parseString("{\"method\":\"" + method + "\",\"params\":" + params + "}").getAsJsonObject();
    }
    @Test void commandOutputStreamsAndFinalSnapshotReplacesIt() {
        var stream = new CodexStream();
        assertTrue(stream.parse(event("item/commandExecution/outputDelta", "{\"itemId\":\"c\",\"delta\":\"first\"}")).text().contains("first"));
        assertTrue(stream.parse(event("item/commandExecution/outputDelta", "{\"itemId\":\"c\",\"delta\":\"second\"}")).text().contains("firstsecond"));
        var complete = stream.parse(event("item/completed", "{\"item\":{\"id\":\"c\",\"type\":\"commandExecution\",\"command\":\"echo test\",\"aggregatedOutput\":\"final\",\"exitCode\":0}}"));
        assertTrue(complete.text().contains("final")); assertFalse(complete.text().contains("firstsecond"));
    }
    @Test void fullFileDiffIsNotTruncatedToSixThousandCharacters() {
        var stream = new CodexStream(); var item = new JsonObject(); item.addProperty("id", "f"); item.addProperty("type", "fileChange");
        var change = new JsonObject(); change.addProperty("path", "Example.java"); change.addProperty("diff", "+line\n".repeat(2000));
        var changes = new JsonArray(); changes.add(change); item.add("changes", changes);
        var params = new JsonObject(); params.add("item", item); var notification = new JsonObject(); notification.addProperty("method", "item/completed"); notification.add("params", params);
        var result = stream.parse(notification); assertTrue(result.text().length() > 12000); assertTrue(result.text().contains("Example.java"));
    }
    @Test void opaqueSearchResultsAndSafeLinksSurviveLiveAndHistoryRepresentation() {
        var result = new CodexStream().parse(event("item/completed", """
            {"item":{"id":"search","type":"webSearch","query":"example","results":[
              {"title":"Result","url":"https://example.org/page","futureField":{"value":"preserved"}},
              {"title":"Unsafe","url":"javascript:alert(1)"}]}}
            """));
        assertTrue(result.text().contains("[Result](<https://example.org/page>)"));
        assertTrue(result.text().contains("futureField")); assertTrue(result.text().contains("preserved"));
        assertFalse(result.text().contains("[Unsafe]"));
    }
    @Test void unknownItemsAreVisibleAndRawReasoningIsIgnored() {
        var stream = new CodexStream();
        assertEquals(ChatEvent.Kind.ACTIVITY, stream.parse(event("item/completed", "{\"item\":{\"id\":\"future\",\"type\":\"futureItem\",\"result\":\"value\"}}" )).kind());
        assertEquals(ChatEvent.Kind.IGNORE, stream.parse(event("item/reasoning/textDelta", "{\"delta\":\"private\"}")).kind());
    }
}
