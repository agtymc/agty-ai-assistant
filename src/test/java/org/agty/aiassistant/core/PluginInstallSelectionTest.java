package org.agty.aiassistant.core;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PluginInstallSelectionTest {
    @Test void localAndRemoteSourcesNeverSendMissingOrConflictingMarketplaceSelectors() {
        var catalog = JsonParser.parseString("""
            {"marketplaces":[{"name":"local","path":"/catalog/market.json","plugins":[{"name":"demo","id":"demo@local"}]},
             {"name":"remote","path":null,"plugins":[{"name":"demo","id":"demo@remote","remotePluginId":"remote-id"}]}]}
            """).getAsJsonObject();
        var choices = PluginInstallSelection.matching(catalog, "demo"); assertEquals(2, choices.size());
        assertTrue(choices.getFirst().params().has("marketplacePath")); assertFalse(choices.getFirst().params().has("remoteMarketplaceName"));
        assertEquals("remote", choices.getLast().params().get("remoteMarketplaceName").getAsString()); assertFalse(choices.getLast().params().has("marketplacePath"));
        assertEquals(1, PluginInstallSelection.matching(catalog, "remote-id").size());
        assertTrue(PluginInstallSelection.matching(catalog, "missing").isEmpty());
    }
    @Test void unavailableOrSourceLessCatalogEntryCannotBecomeInstallRequest() {
        assertThrows(IllegalStateException.class, () -> new PluginInstallSelection("demo", "id", "market", "", "DISABLED", new JsonObject()).params());
        assertThrows(IllegalStateException.class, () -> new PluginInstallSelection("demo", "id", "", "", "AVAILABLE", new JsonObject()).params());
    }
}
