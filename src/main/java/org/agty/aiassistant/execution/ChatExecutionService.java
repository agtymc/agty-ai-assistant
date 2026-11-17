package org.agty.aiassistant.execution;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;

@Service(Service.Level.PROJECT)
public final class ChatExecutionService implements Disposable {
    private final TaskRunner runner;
    private volatile Boolean signedIn;
    public Boolean signedIn() { return signedIn; }
    public void refreshAccount(String binary, java.nio.file.Path root) {
        try {
            var result = connections.call(binary, root, false, "account/read", new com.google.gson.JsonObject());
            signedIn = result.has("account") && !result.get("account").isJsonNull();
        } catch (Exception ignored) { signedIn = null; }
    }
    private final java.util.Set<String> remoteOperations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public void experimental(boolean enabled) {
        if (runner.anyActive() || !remoteOperations.isEmpty()) throw new IllegalStateException("Измените экспериментальный контракт после завершения задач и операций всех чатов проекта.");
        connections.setExperimental(enabled);
    }
    public boolean reserve(String sessionId) { return remoteOperations.add(sessionId); }
    public void release(String sessionId) { remoteOperations.remove(sessionId); }
    public boolean reserved(String sessionId) { return remoteOperations.contains(sessionId); }
    private final org.agty.aiassistant.core.CodexConnectionPool connections = new org.agty.aiassistant.core.CodexConnectionPool();
    public org.agty.aiassistant.core.CodexConnectionPool connections() { return connections; }
    public ChatExecutionService(com.intellij.openapi.project.Project project) {
        runner = new TaskRunner(action -> ApplicationManager.getApplication().invokeLater(action), task -> {
            if (project.isDisposed()) throw new IllegalStateException("Проект закрыт.");
            com.intellij.openapi.application.WriteIntentReadAction.run((Runnable) () -> {
                var documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
                for (var document : documents.getUnsavedDocuments()) {
                    var file = documents.getFile(document);
                    if (file != null && file.isInLocalFileSystem() && file.toNioPath().toAbsolutePath().normalize().startsWith(task.directory))
                        documents.saveDocument(document);
                }
                com.intellij.history.LocalHistory.getInstance().putSystemLabel(project, "Codex task " + task.id);
            });
        }, org.agty.aiassistant.history.MessageArtifacts.getInstance()::text, event -> {
            if (event.kind() != org.agty.aiassistant.chat.ChatEvent.Kind.MESSAGE && event.kind() != org.agty.aiassistant.chat.ChatEvent.Kind.ACTIVITY) return event;
            var message = new org.agty.aiassistant.chat.ChatSessions.Message(); message.markdown = event.text(); message.complete = event.complete();
            if (event.details() != null && event.details().has("type") && event.details().get("type").getAsString().equals("imageGeneration")
                    && event.details().has("result") && !event.details().has("fileId")) message.imageData = event.details().get("result").getAsString();
            try { org.agty.aiassistant.history.MessageArtifacts.getInstance().offload(message); }
            catch (java.io.IOException e) { throw new IllegalStateException("Не удалось сохранить полный результат.", e); }
            var details = event.details() == null ? null : event.details().deepCopy();
            if (details != null && !message.imageArtifact.isEmpty()) { details.addProperty("imageArtifact", message.imageArtifact); details.remove("result"); }
            return new org.agty.aiassistant.chat.ChatEvent(event.kind(), event.id(), event.title(), message.markdown, event.complete(), message.artifact, details);
        });
        com.intellij.openapi.editor.EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new com.intellij.openapi.editor.event.DocumentListener() {
            @Override public void documentChanged(com.intellij.openapi.editor.event.DocumentEvent event) {
                var file = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(event.getDocument());
                if (file != null && file.isInLocalFileSystem()) runner.markEdited(file.toNioPath());
            }
        }, this);
        var notified = new java.util.HashSet<String>();
        runner.subscribe(task -> {
            for (String id : task.pending.keySet()) {
                if (!notified.add(task.id + ":" + id)) continue;
                var notification = com.intellij.notification.NotificationGroupManager.getInstance()
                        .getNotificationGroup("AGTY Codex requests")
                        .createNotification("Codex ожидает ответа", "В одном из чатов требуется подтверждение или ответ на вопрос.",
                                com.intellij.notification.NotificationType.INFORMATION);
                notification.addAction(com.intellij.notification.NotificationAction.createSimpleExpiring("Открыть чат", () -> {
                    var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("AGTY AI Assistant");
                    if (window != null) window.activate(() -> {
                        for (var content : window.getContentManager().getContents())
                            if (content.getComponent() instanceof org.agty.aiassistant.ui.AiAssistantChatPanel panel) panel.selectSessionById(task.session.id);
                    });
                }));
                notification.notify(project);
            }
        });
    }
    public TaskRunner runner() { return runner; }
    @Override public void dispose() { runner.close(); connections.close(); }
}
