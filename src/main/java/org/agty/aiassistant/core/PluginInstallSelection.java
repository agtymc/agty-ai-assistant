package org.agty.aiassistant.core;

import com.google.gson.*;
import java.util.*;

/** plugin/install requires exactly one marketplace selector, even though both schema fields are optional. */
public record PluginInstallSelection(String name, String id, String marketplace, String localPath, String availability, JsonObject details) {
    public static List<PluginInstallSelection> matching(JsonObject catalog, String requested) {
        List<PluginInstallSelection> result = new ArrayList<>();
        if (!catalog.has("marketplaces")) return List.of();
        for (var element : catalog.getAsJsonArray("marketplaces")) {
            var market = element.getAsJsonObject();
            if (!market.has("plugins")) continue;
            for (var entry : market.getAsJsonArray("plugins")) {
                var plugin = entry.getAsJsonObject();
                if (!List.of(value(plugin, "name"), value(plugin, "id"), value(plugin, "remotePluginId")).contains(requested)) continue;
                String availability = value(plugin, "availability");
                result.add(new PluginInstallSelection(value(plugin, "name"), value(plugin, "id"), value(market, "name"), value(market, "path"),
                        availability.isBlank() ? "AVAILABLE" : availability, plugin.deepCopy()));
            }
        }
        return List.copyOf(result);
    }
    public JsonObject params() {
        if (!availability.equals("AVAILABLE")) throw new IllegalStateException("Установка недоступна: " + availability);
        if (name.isBlank() || localPath.isBlank() && marketplace.isBlank()) throw new IllegalStateException("Каталог не предоставил источник плагина.");
        var params = new JsonObject(); params.addProperty("pluginName", name);
        if (!localPath.isBlank()) params.addProperty("marketplacePath", localPath);
        else params.addProperty("remoteMarketplaceName", marketplace);
        return params;
    }
    private static String value(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : ""; }
    @Override public String toString() { return "Плагин · " + name + " · " + marketplace + " · " + availability; }
}
