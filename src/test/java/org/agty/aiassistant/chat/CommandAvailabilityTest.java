package org.agty.aiassistant.chat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CommandAvailabilityTest {
    @Test void usesRealStateAndPreservesOfflineHelp() {
        var state = new CommandAvailability.Context("codex", true, false, true, false, false, "0.169.0");
        assertFalse(CommandAvailability.evaluate(SlashCommands.find("fork"), state).available());
        assertFalse(CommandAvailability.evaluate(SlashCommands.find("review"), state).available());
        assertTrue(CommandAvailability.evaluate(SlashCommands.find("help"), state).available());
        assertEquals("/stop", SlashCommands.parse("/clean").name());
        assertEquals("/pwd", SlashCommands.parse("/cwd").name());
        assertEquals("/side", SlashCommands.parse("/btw question").name());
    }
}
