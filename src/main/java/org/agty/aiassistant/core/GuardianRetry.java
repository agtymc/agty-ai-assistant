package org.agty.aiassistant.core;

import com.google.gson.*;
import java.util.*;

/** Pinned 0.169.0 adapter: v2 notification -> native GuardianAssessmentEvent expected by retry RPC. */
public final class GuardianRetry {
    public static JsonObject fromNotification(JsonObject notification) {
        var review = notification.getAsJsonObject("review");
        if (review == null || !CodexStream.string(review, "status").equals("denied")) return null;
        var action = notification.getAsJsonObject("action"); if (action == null) return null; String type = CodexStream.string(action, "type");
        if (!Set.of("command", "execve", "writeStdin", "applyPatch", "networkAccess", "mcpToolCall", "requestPermissions").contains(type)) return null;
        JsonObject result = new JsonObject();
        result.add("id", notification.get("reviewId")); result.add("turn_id", notification.get("turnId")); result.addProperty("status", "denied");
        copy(notification, "targetItemId", result, "target_item_id"); copy(notification, "startedAtMs", result, "started_at_ms");
        copy(notification, "completedAtMs", result, "completed_at_ms"); copy(notification, "decisionSource", result, "decision_source");
        copy(review, "riskLevel", result, "risk_level"); copy(review, "userAuthorization", result, "user_authorization"); copy(review, "rationale", result, "rationale");
        var nativeAction = new JsonObject();
        for (var pair : action.entrySet()) nativeAction.add(snake(pair.getKey()), pair.getValue().deepCopy());
        nativeAction.addProperty("type", snake(type));
        if (nativeAction.has("source") && CodexStream.string(nativeAction, "source").equals("unifiedExec")) nativeAction.addProperty("source", "unified_exec");
        result.add("action", nativeAction); return result;
    }
    private static String snake(String value) { return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); }
    private static void copy(JsonObject source, String key, JsonObject target, String name) { if (source.has(key) && !source.get(key).isJsonNull()) target.add(name, source.get(key).deepCopy()); }
    private GuardianRetry() { }
}
