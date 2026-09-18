package org.agty.aiassistant.chat;

/** Provider-independent snapshots: the same id updates an existing entry. */
public record ChatEvent(Kind kind, String id, String title, String text, boolean complete) {
    public enum Kind { SESSION, MODEL, USAGE, MESSAGE, ACTIVITY, STATUS, ERROR, DONE, IGNORE }
    public static ChatEvent simple(Kind kind, String text) {
        return new ChatEvent(kind, "", "", text, true);
    }
}
