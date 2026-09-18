package org.agty.aiassistant.chat;

import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;
import java.util.*;

/** Local, non-roaming conversations, partitioned by stable project identity. */
@Service(Service.Level.APP)
@State(name = "AgtyChatSessions", storages = @Storage(value = "agty-chat-sessions.xml", roamingType = RoamingType.DISABLED))
public final class ChatSessions implements PersistentStateComponent<ChatSessions.Data> {
    public static final class Message {
        public String id = "", title = "", markdown = "";
        public boolean activity, complete = true;
    }
    public static final class Session {
        public String id = UUID.randomUUID().toString(), title = "Новая сессия", history = "", draft = "";
        public String codexId = ""; // Legacy state from 0.4.
        public String providerId = "codex", nativeSessionId = "";
        public String remoteId() { return !nativeSessionId.isEmpty() ? nativeSessionId : providerId.equals("codex") ? codexId : ""; }
        public boolean customTitle;
        public boolean closed;
        public List<String> promptHistory = new ArrayList<>();
        public String tokenUsage = "", effectiveModel = "", selectedModel = "", reasoningEffort = "", confirmedThreadId = "";
        public List<Message> messages = new ArrayList<>();
        @Override public String toString() { return title + "  ·  " + (remoteId().isEmpty() ? "локальная " + id.substring(0, 8) : remoteId()); }
    }
    public static final class ProjectSessions {
        public String selected = "";
        public boolean writeAccess;
        public Map<String, String> providerSelections = new LinkedHashMap<>();
        public List<Session> sessions = new ArrayList<>();
        public Session current() {
            for (Session session : sessions) if (session.id.equals(selected)) return session;
            if (sessions.isEmpty()) return create();
            selected = sessions.getLast().id;
            return sessions.getLast();
        }
        public Session create() {
            Session session = new Session();
            sessions.add(session);
            selected = session.id;
            return session;
        }
    }
    public static final class Data {
        public Map<String, ProjectSessions> projects = new LinkedHashMap<>();
    }
    private Data data = new Data();
    public ProjectSessions project(String directory) {
        return data.projects.computeIfAbsent(directory, key -> new ProjectSessions());
    }
    public ProjectSessions project(String identity, Collection<String> legacyKeys) {
        ProjectSessions current = data.projects.get(identity);
        if ((current == null || current.sessions.isEmpty()) && legacyKeys != null) {
            for (String legacyKey : legacyKeys) {
                if (legacyKey == null || legacyKey.isBlank() || legacyKey.equals(identity)) continue;
                ProjectSessions legacy = data.projects.get(legacyKey);
                if (legacy != null && !legacy.sessions.isEmpty()) {
                    data.projects.put(identity, legacy);
                    data.projects.remove(legacyKey);
                    return legacy;
                }
            }
        }
        return data.projects.computeIfAbsent(identity, key -> new ProjectSessions());
    }
    @Override public @NotNull Data getState() { return data; }
    @Override public void loadState(@NotNull Data state) { data = state; }
}
