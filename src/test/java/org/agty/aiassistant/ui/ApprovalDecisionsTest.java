package org.agty.aiassistant.ui;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ApprovalDecisionsTest {
    @Test void malformedDecisionListDoesNotFallBackToPermissionGrants() {
        for (String value : java.util.List.of("true", "\"accept\"", "{}")) {
            var params = JsonParser.parseString("{\"availableDecisions\":" + value + "}").getAsJsonObject();
            assertTrue(ApprovalDecisions.choices(params, true).isEmpty());
        }
        assertEquals(4, ApprovalDecisions.choices(JsonParser.parseString("{\"availableDecisions\":null}").getAsJsonObject(), true).size());
    }
    @Test void neverOffersDecisionsExcludedByServerAndPreservesExactPersistentRule() {
        var params = JsonParser.parseString("""
            {"availableDecisions":["decline",{"applyNetworkPolicyAmendment":{"network_policy_amendment":{"host":"example.org","action":"deny"}}}],
             "proposedExecpolicyAmendment":["git","push"]}
            """).getAsJsonObject();
        var choices = ApprovalDecisions.choices(params, true);
        assertEquals(2, choices.size()); assertEquals("decline", choices.getFirst().decision().getAsString());
        assertEquals(params.getAsJsonArray("availableDecisions").get(1), choices.get(1).response().get("decision"));
        assertTrue(choices.get(1).label().contains("deny"));
    }
    @Test void emptyServerListGrantsNothingAndUnknownDecisionIsNeverGuessed() {
        var params = JsonParser.parseString("{\"availableDecisions\":[]}").getAsJsonObject();
        assertTrue(ApprovalDecisions.choices(params, true).isEmpty());
        params.getAsJsonArray("availableDecisions").add("unknownFutureAllow");
        assertTrue(ApprovalDecisions.choices(params, true).isEmpty());
        assertEquals(4, ApprovalDecisions.choices(params, false).size());
    }
}
