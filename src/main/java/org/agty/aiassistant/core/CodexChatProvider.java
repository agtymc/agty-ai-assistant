package org.agty.aiassistant.core;

import com.google.gson.*;
import org.agty.aiassistant.chat.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

public final class CodexChatProvider implements ChatProvider {
    private final List<String> serverCommand;
    public CodexChatProvider(String binary) { this(List.of(CodexCommand.executable(binary), "app-server")); }
    CodexChatProvider(List<String> command) { serverCommand = List.copyOf(command); }
    @Override public String name() { return "Codex"; }
    @Override public Request newRequest() { return newRequest("", false); }
    @Override public Request newRequest(String sessionId) { return newRequest(sessionId, false); }
    @Override public Request newRequest(String sessionId, boolean writeAccess) {
        return newRequest(sessionId, writeAccess, "");
    }
    @Override public Request newRequest(String sessionId, boolean writeAccess, String model) {
        return newRequest(sessionId, writeAccess, model, "");
    }
    @Override public Request newRequest(String sessionId, boolean writeAccess, String model, String effort) {
        return newRequest(sessionId, writeAccess, model, effort, false);
    }
    @Override public Request newRequest(String sessionId, boolean writeAccess, String model, String effort, boolean forkForModel) {
        return new Request() {
            private volatile boolean cancelled;
            private volatile CodexSessionClient client;
            @Override public void cancel() {
                cancelled = true;
                var connection = client;
                if (connection != null) connection.close();
            }
            @Override public void run(Path directory, String prompt, Consumer<ChatEvent> events) throws Exception {
                if (cancelled) throw new CancellationException();
                try (var connection = new CodexSessionClient(serverCommand, directory, () -> cancelled)) {
                    client = connection;
                    if (cancelled) throw new CancellationException();
                    if (!model.isBlank()) {
                        var available = CodexModels.resolve(CodexModels.list(connection), model);
                        if (!available.model().equals(model))
                            throw new IllegalStateException("Выбрана неизвестная модель Codex: " + model);
                        if (!effort.isBlank() && available.efforts().stream().noneMatch(option -> option.value().equals(effort)))
                            throw new IllegalStateException("Модель " + model + " не поддерживает уровень рассуждения " + effort + ".");
                    }
                    JsonObject params = new JsonObject();
                    params.addProperty("cwd", directory.toRealPath().toString());
                    params.addProperty("approvalPolicy", "never");
                    params.addProperty("sandbox", writeAccess ? "workspace-write" : "read-only");
                    if (!model.isBlank()) params.addProperty("model", model);
                    if (!sessionId.isBlank()) {
                        UUID.fromString(sessionId);
                        connection.readThread(sessionId, directory, false); // Verify project without fetching all messages.
                        params.addProperty("threadId", sessionId);
                        params.addProperty("excludeTurns", true);
                    }
                    if (forkForModel && sessionId.isBlank()) throw new IllegalStateException("Нельзя ответвить сессию без UID.");
                    var thread = connection.call(sessionId.isBlank() ? "thread/start" : forkForModel ? "thread/fork" : "thread/resume", params);
                    String actualModel = CodexStream.string(thread, "model");
                    if (!model.isBlank() && !model.equals(actualModel))
                        throw new IllegalStateException("Codex не переключил модель: выбрана " + model + ", сервер вернул "
                                + (actualModel.isBlank() ? "неизвестную модель" : actualModel) + ". Запрос не отправлен.");
                    String id = thread.getAsJsonObject("thread").get("id").getAsString();
                    events.accept(ChatEvent.simple(ChatEvent.Kind.SESSION, id));
                    events.accept(ChatEvent.simple(ChatEvent.Kind.MODEL, actualModel));
                    JsonObject turn = turnParams(directory, id, prompt, writeAccess);
                    if (!model.isBlank()) turn.addProperty("model", model);
                    if (!effort.isBlank()) turn.addProperty("effort", effort);
                    var started = connection.call("turn/start", turn);
                    String turnId = started.getAsJsonObject("turn").get("id").getAsString();
                    long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
                    var stream = new CodexStream();
                    while (System.nanoTime() < deadline) {
                        if (cancelled) throw new CancellationException();
                        var notification = connection.nextNotification();
                        if (notification == null) continue;
                        var p = notification.getAsJsonObject("params");
                        if (p == null || !id.equals(CodexStream.string(p, "threadId"))) continue;
                        String eventTurn = CodexStream.string(p, "turnId");
                        if (!eventTurn.isEmpty() && !eventTurn.equals(turnId)) continue;
                        if (p.has("turn") && !turnId.equals(CodexStream.string(p.getAsJsonObject("turn"), "id"))) continue;
                        if (CodexStream.string(notification, "method").equals("model/rerouted")) {
                            String routedModel = CodexStream.string(p, "toModel");
                            if (!model.isBlank() && !model.equals(routedModel)) {
                                events.accept(ChatEvent.simple(ChatEvent.Kind.MODEL, routedModel));
                                throw new IllegalStateException("Codex переключил модель на " + routedModel + " вместо " + model
                                        + ". Запрос остановлен.");
                            }
                        }
                        ChatEvent event = stream.parse(notification);
                        events.accept(event);
                        if (event.kind() == ChatEvent.Kind.ERROR) throw new IllegalStateException(event.text());
                        if (event.kind() == ChatEvent.Kind.DONE) return;
                    }
                    throw new IllegalStateException("Codex не завершил запрос за 10 минут.");
                } finally { client = null; }
            }
        };
    }
    static JsonObject turnParams(Path directory, String id, String prompt, boolean writeAccess) {
        JsonObject turn = new JsonObject(); turn.addProperty("threadId", id);
        turn.addProperty("cwd", directory.toAbsolutePath().normalize().toString());
        turn.addProperty("approvalPolicy", "never");
        JsonObject sandbox = new JsonObject(); sandbox.addProperty("type", writeAccess ? "workspaceWrite" : "readOnly");
        sandbox.addProperty("networkAccess", false);
        if (writeAccess) {
            JsonArray roots = new JsonArray(); roots.add(directory.toAbsolutePath().normalize().toString());
            sandbox.add("writableRoots", roots);
            sandbox.addProperty("excludeSlashTmp", true); sandbox.addProperty("excludeTmpdirEnvVar", true);
        }
        turn.add("sandboxPolicy", sandbox);
        JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", prompt);
        JsonArray input = new JsonArray(); input.add(text); turn.add("input", input);
        return turn;
    }
}
