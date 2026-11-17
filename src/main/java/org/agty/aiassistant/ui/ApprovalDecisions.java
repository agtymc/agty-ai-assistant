package org.agty.aiassistant.ui;

import com.google.gson.*;
import java.util.*;

/** Preserve the server's ordered decision set, including exact proposed persistent rules. */
final class ApprovalDecisions {
    record Choice(String label, JsonElement decision) {
        JsonObject response() { var result = new JsonObject(); result.add("decision", decision.deepCopy()); return result; }
    }
    static List<Choice> choices(JsonObject params, boolean command) {
        List<JsonElement> decisions = new ArrayList<>();
        if (command && params.has("availableDecisions") && !params.get("availableDecisions").isJsonNull()) {
            if (!params.get("availableDecisions").isJsonArray()) return List.of();
            params.getAsJsonArray("availableDecisions").forEach(decisions::add);
        }
        else {
            for (String value : List.of("accept", "acceptForSession", "decline", "cancel")) decisions.add(new JsonPrimitive(value));
            if (command && params.has("proposedExecpolicyAmendment") && params.get("proposedExecpolicyAmendment").isJsonArray())
                decisions.add(wrapped("acceptWithExecpolicyAmendment", "execpolicy_amendment", params.get("proposedExecpolicyAmendment")));
            if (command && params.has("proposedNetworkPolicyAmendments") && params.get("proposedNetworkPolicyAmendments").isJsonArray())
                for (var rule : params.getAsJsonArray("proposedNetworkPolicyAmendments"))
                    decisions.add(wrapped("applyNetworkPolicyAmendment", "network_policy_amendment", rule));
        }
        List<Choice> result = new ArrayList<>();
        for (var decision : decisions) {
            String label = label(decision);
            if (label != null) result.add(new Choice(label, decision.deepCopy()));
        }
        return List.copyOf(result);
    }
    private static JsonObject wrapped(String type, String key, JsonElement value) {
        var body = new JsonObject(); body.add(key, value.deepCopy()); var result = new JsonObject(); result.add(type, body); return result;
    }
    private static String label(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return switch (value.getAsString()) {
            case "accept" -> "Разрешить один раз";
            case "acceptForSession" -> "Разрешить для сессии";
            case "decline" -> "Отказать";
            case "cancel" -> "Отменить turn";
            default -> null;
        };
        if (!value.isJsonObject() || value.getAsJsonObject().size() != 1) return null;
        var object = value.getAsJsonObject();
        if (object.has("acceptWithExecpolicyAmendment"))
            return "Разрешить и сохранить правило выполнения: " + object.get("acceptWithExecpolicyAmendment");
        if (object.has("applyNetworkPolicyAmendment"))
            return "Сохранить сетевое правило: " + object.get("applyNetworkPolicyAmendment");
        return null;
    }
    private ApprovalDecisions() { }
}
