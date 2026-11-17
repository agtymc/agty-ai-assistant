package org.agty.aiassistant.core;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GuardianRetryTest {
    @Test void approvalPayloadBindsOriginalDeniedActionAndNeverStartsAnotherTurn() {
        var event = JsonParser.parseString("""
            {"id":"review","turn_id":"original-turn","status":"denied","action":{"type":"command","command":"echo test","cwd":"/project","source":"shell"}}
            """).getAsJsonObject();
        var params = GuardianRetry.approvalParams("019e309f-5fe7-7a93-90d0-ce794686cdfd", event);
        assertEquals(java.util.Set.of("threadId", "event"), params.keySet());
        event.getAsJsonObject("action").addProperty("command", "different");
        assertEquals("echo test", params.getAsJsonObject("event").getAsJsonObject("action").get("command").getAsString());
        assertThrows(IllegalArgumentException.class, () -> GuardianRetry.approvalParams("", event));
        event.remove("turn_id");
        assertThrows(IllegalArgumentException.class, () -> GuardianRetry.approvalParams("019e309f-5fe7-7a93-90d0-ce794686cdfd", event));
    }
    @Test void nativeAdapterChangesEnvelopeAndActionKeysButNotPermissionObjects() {
        var notification = JsonParser.parseString("""
            {"reviewId":"review","turnId":"turn","targetItemId":"item","startedAtMs":1,
             "review":{"status":"denied","riskLevel":"high","userAuthorization":"unknown","rationale":"reason"},
             "action":{"type":"requestPermissions","permissions":{"fileSystem":{"write":["/project"]}}}}
            """).getAsJsonObject();
        var adapted = GuardianRetry.fromNotification(notification);
        assertEquals("review", adapted.get("id").getAsString()); assertEquals("item", adapted.get("target_item_id").getAsString());
        assertEquals("request_permissions", adapted.getAsJsonObject("action").get("type").getAsString());
        assertEquals(notification.getAsJsonObject("action").get("permissions"), adapted.getAsJsonObject("action").get("permissions"));
        notification.getAsJsonObject("review").addProperty("status", "approved");
        assertNull(GuardianRetry.fromNotification(notification));
    }
}
