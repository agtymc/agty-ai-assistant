package org.agty.aiassistant.chat;

import com.google.gson.JsonObject;

/** Stable snapshots; full-result references and structured details survive presentation limits. */
public record ChatEvent(Kind kind, String id, String title, String text, boolean complete, String artifact, JsonObject details) {
    public ChatEvent(Kind kind, String id, String title, String text, boolean complete) { this(kind, id, title, text, complete, "", null); }
    public enum Kind { SESSION, MODEL, USAGE, MESSAGE, ACTIVITY, STATUS, ERROR, DONE, SERVER_REQUEST, SERVER_RESOLVED, IGNORE }
    public static ChatEvent simple(Kind kind, String text) { return new ChatEvent(kind, "", "", text, true); }
}
