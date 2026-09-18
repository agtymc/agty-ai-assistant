package org.agty.aiassistant.chat;

import com.intellij.util.xmlb.XmlSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatSessionsTest {
    @Test void restoresSelectedSessionMessagesContextAndDraftFromXml() {
        ChatSessions store = new ChatSessions();
        var project = store.project("/projects/first");
        project.writeAccess = true;
        var first = project.current();
        first.title = "Первый вопрос";
        first.codexId = "019e309f-5fe7-7a93-90d0-ce794686cdfd";
        first.customTitle = true;
        first.selectedModel = "engine-b";
        first.reasoningEffort = "high";
        first.confirmedThreadId = first.codexId;
        first.closed = true;
        first.promptHistory.add("/status");
        first.promptHistory.add("another question");
        first.history = "USER:\nquestion\nASSISTANT:\nanswer\n";
        first.draft = "next question";
        var message = new ChatSessions.Message();
        message.id = "request:item"; message.title = "Assistant";
        message.markdown = "```java\n\tclass A {}\n```";
        first.messages.add(message);
        var second = project.create();
        second.providerId = "local-model"; second.nativeSessionId = "thread-local-123";
        project.providerSelections.put(second.providerId, second.id);
        project.selected = first.id;
        store.project("/projects/second").current().draft = "different project";
        var xml = XmlSerializer.serialize(store.getState());
        ChatSessions restored = new ChatSessions();
        restored.loadState(XmlSerializer.deserialize(xml, ChatSessions.Data.class));
        var selected = restored.project("/projects/first").current();
        assertTrue(restored.project("/projects/first").writeAccess);
        assertEquals(first.id, selected.id);
        assertEquals(first.title, selected.title);
        assertEquals(first.codexId, selected.codexId);
        assertTrue(selected.customTitle);
        assertEquals("engine-b", selected.selectedModel);
        assertEquals("high", selected.reasoningEffort);
        assertEquals(first.codexId, selected.confirmedThreadId);
        assertTrue(selected.closed);
        assertEquals(java.util.List.of("/status", "another question"), selected.promptHistory);
        assertEquals(first.codexId, selected.remoteId());
        var other = restored.project("/projects/first").sessions.get(1);
        assertEquals("local-model", other.providerId);
        assertEquals("thread-local-123", other.remoteId());
        assertEquals(other.id, restored.project("/projects/first").providerSelections.get("local-model"));
        assertEquals(first.history, selected.history);
        assertEquals(first.draft, selected.draft);
        assertEquals(message.markdown, selected.messages.getFirst().markdown);
        assertEquals(2, restored.project("/projects/first").sessions.size());
        assertEquals("different project", restored.project("/projects/second").current().draft);
    }

    @Test void migratesLegacyPathHistoryToStableProjectIdentity() {
        ChatSessions store = new ChatSessions();
        var legacy = store.project("/old/path/project");
        var session = legacy.current();
        session.title = "Old path chat";
        session.draft = "keep me";

        var migrated = store.project("project-uuid", java.util.List.of("/new/path/project", "/old/path/project"));

        assertSame(legacy, migrated);
        assertEquals("Old path chat", store.project("project-uuid").current().title);
        assertEquals("keep me", store.project("project-uuid").current().draft);
        assertFalse(store.getState().projects.containsKey("/old/path/project"));
    }
}
