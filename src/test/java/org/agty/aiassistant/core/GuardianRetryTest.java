package org.agty.aiassistant.core;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GuardianRetryTest {
    @Test void nativeAdapterChangesEnvelopeAndActionKeysButNotPermissionObjects() {
        var notification = JsonParser.parseString("""
            {"reviewId":"review","turnId":"turn","targetItemId":"item","startedAtMs":1,
             "review":{"status":"denied","riskLevel":"high","userAuthorization":"unknown","rationale":"reason"},
             "action":{"type":"requestPermissions","additionalPermissions":{"fileSystem":{"write":["/project"]}}}}
            """).getAsJsonObject();
        var adapted = GuardianRetry.fromNotification(notification);
        assertEquals("review", adapted.get("id").getAsString()); assertEquals("item", adapted.get("target_item_id").getAsString());
        assertEquals("request_permissions", adapted.getAsJsonObject("action").get("type").getAsString());
        assertEquals(notification.getAsJsonObject("action").get("additionalPermissions"), adapted.getAsJsonObject("action").get("additional_permissions"));
        notification.getAsJsonObject("review").addProperty("status", "approved");
        assertNull(GuardianRetry.fromNotification(notification));
    }
}
