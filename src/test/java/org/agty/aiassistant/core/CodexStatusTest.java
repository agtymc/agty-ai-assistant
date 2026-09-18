package org.agty.aiassistant.core;

import com.google.gson.JsonParser;
import org.agty.aiassistant.chat.SlashCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CodexStatusTest {
    @Test void readsLastExecutedModelFromRollout(@TempDir Path directory) throws Exception {
        Path rollout = directory.resolve("rollout.jsonl");
        Files.writeString(rollout, "{\"type\":\"turn_context\",\"payload\":{\"model\":\"gpt-6-astra\",\"effort\":\"medium\"}}\n"
                + "{\"type\":\"response_item\",\"payload\":{\"model\":\"ignored\"}}\n"
                + "{\"type\":\"turn_context\",\"payload\":{\"model\":\"gpt-5.6-sol\",\"effort\":\"high\"}}\n");
        assertEquals(new CodexStatus.TurnModel("gpt-5.6-sol", "high"), CodexStatus.lastTurn(rollout));
    }
    @Test void missingUsageIsNotPresentedAsZero() {
        assertTrue(CodexStatus.usage("").contains("ещё не получены"));
        assertTrue(CodexStatus.usage("{}").contains("не предоставлено"));
        var limits = JsonParser.parseString("{\"rateLimits\":{\"primary\":{\"usedPercent\":25,\"windowDurationMins\":300}}}").getAsJsonObject();
        assertTrue(CodexStatus.limits(limits).contains("75% осталось"));
        assertFalse(CodexStatus.limits(limits).contains("secondary"));
    }
    @Test void limitsShowRemainingPercentageAndFullResetDate() {
        var response = JsonParser.parseString("{\"rateLimits\":{\"primary\":{\"usedPercent\":66,\"windowDurationMins\":300,\"resetsAt\":1789738533}}}").getAsJsonObject();
        String status = CodexStatus.limits(response);
        assertTrue(status.contains("34% осталось"));
        assertTrue(status.contains("5h limit"));
        assertTrue(status.matches("(?s).*сброс 20\\d\\d-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d.*"));
    }
    @Test void escapedSlashesAreTextAndCommandsAreProviderScoped() {
        assertNull(SlashCommands.parse("//status"));
        assertEquals("uid", SlashCommands.parse(" /resume uid ").argument());
        assertTrue(SlashCommands.available("other").stream().noneMatch(c -> c.name().equals("/resume")));
    }
}
