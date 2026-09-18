package org.agty.aiassistant.context;

import java.util.List;

public record CollectedContext(List<ContextItem> items, List<String> notes) {
    public static CollectedContext empty(String note) {
        return new CollectedContext(List.of(), note == null || note.isBlank() ? List.of() : List.of(note));
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public String promptBlock() {
        if (items.isEmpty()) return "";
        StringBuilder out = new StringBuilder("PROJECT CONTEXT SELECTED IN IDE:\n");
        for (ContextItem item : items) {
            out.append("\nSOURCE: ").append(item.location()).append("\n");
            out.append("```text\n").append(fenceSafe(item.text())).append("\n```\n");
        }
        out.append("\nUse this context only if it is relevant to the user's request.\n\n");
        return out.toString();
    }

    public String preview() {
        StringBuilder out = new StringBuilder();
        if (items.isEmpty()) out.append("Контекст не выбран.\n");
        for (ContextItem item : items) {
            out.append(item.location()).append("\n\n");
            out.append(item.text()).append("\n");
            if (!item.text().endsWith("\n")) out.append("\n");
            out.append("\n");
        }
        if (!notes.isEmpty()) {
            out.append("Примечания:\n");
            for (String note : notes) out.append("- ").append(note).append("\n");
        }
        return out.toString().stripTrailing();
    }

    private static String fenceSafe(String text) {
        return text.replace("```", "`\u200b``");
    }
}
