package org.agty.aiassistant.core;

import com.google.gson.*;
import java.util.*;
import java.io.IOException;

/** Catalog comes from the selected CLI; model ids are never hard-coded. */
public final class CodexModels {
    public record Effort(String value, String description) {
        @Override public String toString() { return value; }
    }
    public record ServiceTier(String id, String name, String description) { @Override public String toString() { return "Tier · " + name + " (" + id + ")"; } }
    public record Model(String id, String model, String label, List<Effort> efforts, String defaultEffort, List<ServiceTier> serviceTiers, List<String> cyberPrograms, List<String> inputModalities) {
        public Model(String id, String model, String label, List<Effort> efforts, String defaultEffort) { this(id, model, label, efforts, defaultEffort, List.of(), null, List.of("text", "image")); }
        public Model(String id, String model, String label) { this(id, model, label, List.of(), ""); }
    }
    public static List<Model> list(CodexSessionClient client) throws Exception {
        Map<String, Model> models = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String cursor = "";
        do {
            JsonObject params = new JsonObject(); params.addProperty("limit", 100); params.addProperty("includeHidden", false);
            if (!cursor.isBlank()) params.addProperty("cursor", cursor);
            JsonObject result = client.call("model/list", params);
            for (var element : result.getAsJsonArray("data")) {
                var value = element.getAsJsonObject();
                if (value.has("hidden") && value.get("hidden").isJsonPrimitive() && value.get("hidden").getAsBoolean()) continue;
                String model = CodexStream.string(value, "model"), id = CodexStream.string(value, "id");
                if (model.isBlank()) continue;
                String label = CodexStream.string(value, "displayName");
                List<Effort> efforts = new ArrayList<>();
                if (value.has("supportedReasoningEfforts") && value.get("supportedReasoningEfforts").isJsonArray())
                    for (var entry : value.getAsJsonArray("supportedReasoningEfforts")) {
                        var option = entry.getAsJsonObject();
                        String effort = CodexStream.string(option, "reasoningEffort");
                        if (!effort.isBlank()) efforts.add(new Effort(effort, CodexStream.string(option, "description")));
                    }
                List<ServiceTier> tiers = new ArrayList<>();
                if (value.has("serviceTiers") && value.get("serviceTiers").isJsonArray()) for (var tier : value.getAsJsonArray("serviceTiers")) {
                    var option = tier.getAsJsonObject(); tiers.add(new ServiceTier(CodexStream.string(option, "id"), CodexStream.string(option, "name"), CodexStream.string(option, "description")));
                }
                List<String> cyber = null;
                if (value.has("availableAccessPrograms") && value.get("availableAccessPrograms").isJsonObject()) {
                    cyber = new ArrayList<>(); var programs = value.getAsJsonObject("availableAccessPrograms");
                    if (programs.has("cyber")) for (var entry : programs.getAsJsonArray("cyber")) cyber.add(entry.getAsString());
                    cyber = List.copyOf(cyber);
                }
                List<String> modalities = new ArrayList<>();
                if (value.has("inputModalities")) for (var modality : value.getAsJsonArray("inputModalities")) modalities.add(modality.getAsString());
                else modalities.addAll(List.of("text", "image"));
                models.putIfAbsent(model, new Model(id, model, label.isBlank() ? model : label, List.copyOf(efforts), CodexStream.string(value, "defaultReasoningEffort"), List.copyOf(tiers), cyber, List.copyOf(modalities)));
            }
            cursor = CodexStream.string(result, "nextCursor");
            if (!cursor.isBlank() && !cursors.add(cursor)) throw new IOException("Codex повторил курсор списка моделей.");
        } while (!cursor.isBlank());
        return List.copyOf(models.values());
    }
    public static Model resolve(List<Model> models, String id) {
        return models.stream().filter(model -> model.model().equals(id) || model.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Модель отсутствует в списке Codex: " + id));
    }
    private CodexModels() {}
}
