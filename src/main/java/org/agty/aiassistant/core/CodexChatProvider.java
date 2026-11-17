package org.agty.aiassistant.core;

import com.google.gson.*;
import org.agty.aiassistant.chat.*;
import org.agty.aiassistant.security.ProjectAccessPolicy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class CodexChatProvider implements ChatProvider {
    private final List<String> serverCommand;
    private final CodexConnectionPool pool;
    private final boolean experimental;
    private boolean verifyVersion;
    public CodexChatProvider(String binary) { this(List.of(CodexCommand.executable(binary), "app-server")); verifyVersion = true; }
    CodexChatProvider(List<String> command) { this(command, null); }
    CodexChatProvider(List<String> command, CodexConnectionPool pool) { serverCommand = List.copyOf(command); this.pool = pool; this.experimental = false; }
    public CodexChatProvider(String binary, CodexConnectionPool pool) {
        this(List.of(CodexCommand.executable(binary), "app-server"), pool); verifyVersion = true;
    }
    public CodexChatProvider(String binary, CodexConnectionPool pool, boolean experimental) {
        serverCommand = List.of(CodexCommand.executable(binary), "app-server"); this.pool = pool; this.experimental = experimental; verifyVersion = true;
    }
    private CodexSessionClient connect(Path directory, java.util.function.BooleanSupplier cancelled) throws Exception {
        if (verifyVersion) CodexCompatibility.require(serverCommand.getFirst(), directory);
        return pool == null ? new CodexSessionClient(serverCommand, directory, cancelled, experimental) : pool.acquire(serverCommand, directory, experimental);
    }
    @Override public String name() { return "Codex"; }
    @Override public Request newRequest(RequestOptions options) {
        if (options.mode() == RequestOptions.Mode.PLAN && !experimental)
            throw new IllegalStateException("Этот режим или тип вложений ещё не поддерживается.");
        return ChatProvider.scoped(createRequest(options.sessionId(), options.access() != RequestOptions.Access.READ_ONLY,
                options.model(), options.effort(), options.fork(), options.access() == RequestOptions.Access.FULL,
                options.interactiveApprovals(), options.mode(), options.reviewTarget(), options.compact(), false, options.serviceTier(), options.cyberAccessProgram(), options.attachments(), null), options);
    }
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
        return newRequest(sessionId, writeAccess, model, effort, forkForModel, false);
    }
    @Override public Request newRequest(String sessionId, boolean writeAccess, String model, String effort,
                                        boolean forkForModel, boolean unrestrictedAccess) {
        return createRequest(sessionId, writeAccess, model, effort, forkForModel, unrestrictedAccess, false, RequestOptions.Mode.WORK, null, false, false, "", "", List.of(), null);
    }
    public Request observeRequest(String sessionId) {
        if (sessionId.isBlank()) throw new IllegalArgumentException("Нужен thread ID.");
        return createRequest(sessionId, false, "", "", false, false, false, RequestOptions.Mode.WORK, null, false, true, "", "", List.of(), null);
    }
    public Request retryRequest(String sessionId, JsonObject event) {
        if (!experimental) throw new IllegalStateException("Повтор требует экспериментального контракта.");
        if (!CodexStream.string(event, "status").equals("denied") || CodexStream.string(event, "id").isBlank()) throw new IllegalArgumentException("Нет исходного события отказа.");
        return createRequest(sessionId, true, "", "", false, false, false, RequestOptions.Mode.WORK, null, false, true, "", "", List.of(), event.deepCopy());
    }
    private Request createRequest(String sessionId, boolean writeAccess, String model, String effort,
                                  boolean forkForModel, boolean unrestrictedAccess, boolean interactive, RequestOptions.Mode mode, JsonObject reviewTarget, boolean compact, boolean observe, String serviceTier, String cyberAccessProgram, List<RequestOptions.Attachment> attachments, JsonObject retry) {
        if (unrestrictedAccess) writeAccess = true;
        boolean canWrite = writeAccess;
        boolean fullAccess = unrestrictedAccess;
        return new Request() {
            private volatile boolean cancelled;
            private volatile CodexSessionClient client;
            private volatile String activeThreadId = "";
            private volatile String activeTurnId = "";
            @Override public void steer(String prompt) throws Exception {
                var connection = client;
                if (connection == null || activeThreadId.isBlank() || activeTurnId.isBlank() || cancelled)
                    throw new IllegalStateException("Задача ещё не запущена или уже завершилась.");
                JsonObject params = new JsonObject(); params.addProperty("threadId", activeThreadId); params.addProperty("expectedTurnId", activeTurnId);
                JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", prompt);
                JsonArray input = new JsonArray(); input.add(text); params.add("input", input); connection.call("turn/steer", params);
            }
            @Override public void respond(String id, JsonObject response) throws Exception {
                var connection = client;
                if (connection == null || cancelled) throw new IllegalStateException("Задача больше не ожидает ответа.");
                connection.respond(id, response);
            }
            @Override public void cancel() {
                cancelled = true;
                var connection = client;
                if (connection != null) {
                    if (pool == null) {
                        connection.interrupt(activeThreadId, activeTurnId);
                        connection.close();
                    }
                }
            }
            @Override public void run(Path directory, String prompt, Consumer<ChatEvent> events) throws Exception {
                if (cancelled) throw new CancellationException();
                var accessPolicy = new ProjectAccessPolicy(directory); accessPolicy.request(canWrite).requireAllowed();
                for (var attachment : attachments) {
                    if (!attachment.kind().equals("localImage")) throw new IllegalArgumentException("Неподдерживаемый тип вложения.");
                    Path path = Path.of(attachment.value()); accessPolicy.image(path).requireAllowed();
                    if (!java.nio.file.Files.isRegularFile(path) || java.nio.file.Files.size(path) > 20L * 1024 * 1024)
                        throw new IllegalArgumentException("Изображение недоступно или превышает 20 MiB.");
                }
                CodexSessionClient connection = connect(directory, () -> cancelled);
                CodexSessionClient.Subscription subscription = connection.subscribe();
                boolean turnSettled = false;
                try {
                    client = connection;
                    if (cancelled) throw new CancellationException();
                    if (!model.isBlank()) {
                        var available = CodexModels.resolve(CodexModels.list(connection), model);
                        if (!available.model().equals(model))
                            throw new IllegalStateException("Выбрана неизвестная модель Codex: " + model);
                        if (!attachments.isEmpty() && !available.inputModalities().contains("image"))
                            throw new IllegalStateException("Эта модель не поддерживает изображения.");
                        if (!serviceTier.isBlank() && !serviceTier.equals("default") && available.serviceTiers().stream().noneMatch(t -> t.id().equals(serviceTier)))
                            throw new IllegalStateException("Скорость не предоставлена аккаунту для этой модели.");
                        if (!cyberAccessProgram.isBlank() && !cyberAccessProgram.equals("standard") && (available.cyberPrograms() == null || !available.cyberPrograms().contains(cyberAccessProgram)))
                            throw new IllegalStateException("Специальный режим не предоставлен аккаунту для этой модели.");
                        if (!effort.isBlank() && available.efforts().stream().noneMatch(option -> option.value().equals(effort)))
                            throw new IllegalStateException("Модель " + model + " не поддерживает уровень рассуждения " + effort + ".");
                    }
                    JsonObject params = new JsonObject();
                    params.addProperty("cwd", directory.toRealPath().toString());
                    params.addProperty("approvalPolicy", interactive && !fullAccess ? "on-request" : "never");
                    params.addProperty("sandbox", fullAccess ? "danger-full-access" : canWrite ? "workspace-write" : "read-only");
                    if (!model.isBlank()) params.addProperty("model", model);
                    if (!sessionId.isBlank()) {
                        UUID.fromString(sessionId);
                        connection.readThread(sessionId, directory, false); // Verify project without fetching all messages.
                        params.addProperty("threadId", sessionId);
                        params.addProperty("excludeTurns", true);
                    }
                    if (forkForModel && sessionId.isBlank()) throw new IllegalStateException("Нельзя ответвить сессию без UID.");
                    if (observe) {
                        params = new JsonObject(); params.addProperty("threadId", sessionId); params.addProperty("excludeTurns", true);
                    }
                    var thread = connection.call(sessionId.isBlank() ? "thread/start" : forkForModel ? "thread/fork" : "thread/resume", params);
                    String actualModel = CodexStream.string(thread, "model");
                    if (!model.isBlank() && !model.equals(actualModel))
                        throw new IllegalStateException("Codex не переключил модель: выбрана " + model + ", сервер вернул "
                                + (actualModel.isBlank() ? "неизвестную модель" : actualModel) + ". Запрос не отправлен.");
                    if (model.isBlank() && (!attachments.isEmpty() || !serviceTier.isBlank() || !cyberAccessProgram.isBlank())) {
                        var available = CodexModels.resolve(CodexModels.list(connection), actualModel);
                        if (!attachments.isEmpty() && !available.inputModalities().contains("image"))
                            throw new IllegalStateException("Эта модель не поддерживает изображения.");
                        if (!serviceTier.isBlank() && !serviceTier.equals("default") && available.serviceTiers().stream().noneMatch(t -> t.id().equals(serviceTier)))
                            throw new IllegalStateException("Скорость не предоставлена аккаунту для этой модели.");
                        if (!cyberAccessProgram.isBlank() && !cyberAccessProgram.equals("standard") && (available.cyberPrograms() == null || !available.cyberPrograms().contains(cyberAccessProgram)))
                            throw new IllegalStateException("Специальный режим не предоставлен аккаунту для этой модели.");
                    }
                    String id = thread.getAsJsonObject("thread").get("id").getAsString();
                    activeThreadId = id;
                    events.accept(ChatEvent.simple(ChatEvent.Kind.SESSION, id));
                    events.accept(ChatEvent.simple(ChatEvent.Kind.MODEL, actualModel));
                    JsonObject turn = turnParams(directory, id, prompt, canWrite, fullAccess);
                    turn.addProperty("approvalPolicy", interactive && !fullAccess ? "on-request" : "never");
                    for (var attachment : attachments) {
                        JsonObject image = new JsonObject(); image.addProperty("type", attachment.kind()); image.addProperty("path", Path.of(attachment.value()).toRealPath().toString());
                        turn.getAsJsonArray("input").add(image);
                    }
                    if (!model.isBlank()) turn.addProperty("model", model);
                    if (!serviceTier.isBlank()) turn.addProperty("serviceTierForTurn", serviceTier);
                    if (!cyberAccessProgram.isBlank()) {
                        if (!experimental) throw new IllegalStateException("Для специального режима требуется экспериментальный контракт.");
                        turn.addProperty("cyberAccessProgram", cyberAccessProgram);
                    }
                    if (!effort.isBlank()) turn.addProperty("effort", effort);
                    if (experimental) {
                        var collaboration = new JsonObject(); collaboration.addProperty("mode", mode == RequestOptions.Mode.PLAN ? "plan" : "default");
                        var settings = new JsonObject(); settings.addProperty("model", actualModel);
                        if (!effort.isBlank()) settings.addProperty("reasoning_effort", effort);
                        collaboration.add("settings", settings); turn.add("collaborationMode", collaboration);
                    }
                    if (cancelled) throw new CancellationException();
                    var stream = new CodexStream();
                    JsonObject observedTurn = null;
                    JsonObject started;
                    if (observe) {
                        if (retry != null) {
                            var approval = new JsonObject(); approval.addProperty("threadId", id); approval.add("event", retry.deepCopy());
                            connection.call("thread/approveGuardianDeniedAction", approval);
                        }
                        var query = new JsonObject(); query.addProperty("threadId", id); query.addProperty("limit", 1);
                        query.addProperty("itemsView", "full"); query.addProperty("sortDirection", "desc");
                        var turns = connection.call("thread/turns/list", query).getAsJsonArray("data");
                        if (turns.isEmpty()) { turnSettled = true; return; }
                        observedTurn = turns.get(0).getAsJsonObject();
                        for (var event : stream.replay(observedTurn)) events.accept(event);
                        if (!CodexStream.string(observedTurn, "status").equals("inProgress")) { turnSettled = true; return; }
                        started = new JsonObject(); started.add("turn", observedTurn);
                        events.accept(ChatEvent.simple(ChatEvent.Kind.STATUS, "Наблюдение за нативной задачей"));
                    } else if (compact) {
                        JsonObject paramsCompact = new JsonObject(); paramsCompact.addProperty("threadId", id);
                        connection.call("thread/compact/start", paramsCompact); started = null;
                    } else if (reviewTarget == null) started = connection.call("turn/start", turn);
                    else {
                        JsonObject review = new JsonObject(); review.addProperty("threadId", id); review.addProperty("delivery", "inline"); review.add("target", reviewTarget.deepCopy());
                        started = connection.call("review/start", review);
                    }
                    String turnId = compact ? "" : started.getAsJsonObject("turn").get("id").getAsString();
                    activeTurnId = turnId;
                    if (cancelled && !turnId.isBlank()) { interruptTurn(connection, id, turnId); turnSettled = true; throw new CancellationException(); }
                    while (true) {
                        if (cancelled && !turnId.isBlank()) { interruptTurn(connection, id, turnId); turnSettled = true; throw new CancellationException(); }
                        JsonObject notification;
                        try {
                            notification = subscription.next();
                        } catch (java.io.IOException disconnected) {
                            subscription.close(); connection.close();
                            ReconnectResult recovered = reconnect(directory, params, id, turnId, stream, events, disconnected);
                            if (recovered.completed()) { turnSettled = true; return; }
                            connection = recovered.connection();
                            client = connection;
                            subscription = recovered.subscription();
                            continue;
                        }
                        if (notification == null) continue;
                        var p = notification.getAsJsonObject("params");
                        if (p == null || !id.equals(CodexStream.string(p, "threadId"))) continue;
                        if (compact && CodexStream.string(notification, "method").equals("thread/compacted")) {
                            turnSettled = true;
                            events.accept(new ChatEvent(ChatEvent.Kind.ACTIVITY, "compaction", "Контекст сжат", "Нативная compaction завершена. Файлы не изменялись.", true));
                            if (cancelled) throw new CancellationException();
                            events.accept(ChatEvent.simple(ChatEvent.Kind.DONE, "Готово")); return;
                        }
                        String eventTurn = CodexStream.string(p, "turnId");
                        if (compact && turnId.isBlank() && !eventTurn.isBlank()) { turnId = eventTurn; activeTurnId = turnId; }
                        if (compact && turnId.isBlank() && p.has("turn") && p.get("turn").isJsonObject()) { turnId = CodexStream.string(p.getAsJsonObject("turn"), "id"); activeTurnId = turnId; }
                        if (!eventTurn.isEmpty() && !eventTurn.equals(turnId)) continue;
                        if (p.has("turn") && !turnId.equals(CodexStream.string(p.getAsJsonObject("turn"), "id"))) continue;
                        if (notification.has("id")) {
                            events.accept(new ChatEvent(ChatEvent.Kind.SERVER_REQUEST, notification.get("id").toString(),
                                    CodexStream.string(notification, "method"), notification.toString(), false));
                            continue;
                        }
                        if (CodexStream.string(notification, "method").equals("serverRequest/resolved")) {
                            events.accept(new ChatEvent(ChatEvent.Kind.SERVER_RESOLVED, p.get("requestId").toString(), "", "", true));
                            continue;
                        }
                        if (CodexStream.string(notification, "method").equals("model/rerouted")) {
                            String routedModel = CodexStream.string(p, "toModel");
                            events.accept(ChatEvent.simple(ChatEvent.Kind.MODEL, routedModel));
                            if (!model.isBlank() && !model.equals(routedModel)) {
                                throw new IllegalStateException("Codex переключил модель на " + routedModel + " вместо " + model
                                        + ". Запрос остановлен.");
                            }
                        }
                        if (CodexStream.string(notification, "method").equals("turn/completed")) turnSettled = true;
                        ChatEvent event = stream.parse(notification);
                        events.accept(event);
                        if (event.kind() == ChatEvent.Kind.ERROR) throw new IllegalStateException(event.text());
                        if (event.kind() == ChatEvent.Kind.DONE) return;
                    }
                } finally {
                    if (pool != null && !turnSettled && !activeTurnId.isBlank()) interruptTurn(connection, activeThreadId, activeTurnId);
                    client = null;
                    subscription.close();
                    if (pool == null) connection.close();
                    activeThreadId = "";
                    activeTurnId = "";
                }
            }

            private ReconnectResult reconnect(Path directory, JsonObject originalParams, String threadId,
                                              String turnId, CodexStream stream, Consumer<ChatEvent> events,
                                              Exception cause) throws Exception {
                Exception last = cause;
                for (int attempt = 1; attempt <= 5; attempt++) {
                    if (cancelled) throw new CancellationException();
                    events.accept(ChatEvent.simple(ChatEvent.Kind.STATUS,
                            "Связь с Codex потеряна. Восстановление наблюдения, попытка " + attempt + " из 5…"));
                    if (attempt > 1) TimeUnit.MILLISECONDS.sleep(Math.min(2_000L, 250L << (attempt - 2)));
                    CodexSessionClient replacement = null;
                    CodexSessionClient.Subscription replacementSubscription = null;
                    try {
                        replacement = connect(directory, () -> cancelled);
                        replacementSubscription = replacement.subscribe();
                        JsonObject resume = originalParams.deepCopy();
                        resume.addProperty("threadId", threadId);
                        resume.addProperty("excludeTurns", false);
                        JsonObject result = replacement.call("thread/resume", resume);
                        JsonObject thread = result.getAsJsonObject("thread");
                        JsonObject recoveredTurn = findTurn(thread, turnId);
                        boolean terminal = false;
                        if (recoveredTurn != null) {
                            for (ChatEvent event : stream.replay(recoveredTurn)) {
                                events.accept(event);
                                if (event.kind() == ChatEvent.Kind.ERROR) throw new IllegalStateException(event.text());
                                if (event.kind() == ChatEvent.Kind.DONE) terminal = true;
                            }
                        } else if (!threadStatus(thread).equals("active")) {
                            throw new java.io.IOException("Codex не вернул выполнявшийся turn " + turnId
                                    + ", статус thread: " + threadStatus(thread) + ".");
                        }
                        if (terminal) {
                            replacementSubscription.close();
                            if (pool == null) replacement.close();
                            return new ReconnectResult(null, null, true);
                        }
                        events.accept(ChatEvent.simple(ChatEvent.Kind.STATUS, "Наблюдение за задачей Codex восстановлено."));
                        return new ReconnectResult(replacement, replacementSubscription, false);
                    } catch (CancellationException e) {
                        if (replacementSubscription != null) replacementSubscription.close();
                        if (replacement != null && pool == null) replacement.close();
                        throw e;
                    } catch (IllegalStateException e) {
                        if (replacementSubscription != null) replacementSubscription.close();
                        if (replacement != null && pool == null) replacement.close();
                        throw e;
                    } catch (Exception e) {
                        if (replacementSubscription != null) replacementSubscription.close();
                        if (replacement != null && pool == null) replacement.close();
                        last = e;
                    }
                }
                throw new java.io.IOException("Не удалось восстановить наблюдение за задачей Codex. "
                        + (last.getMessage() == null ? "" : last.getMessage()), last);
            }
        };
    }
    private void interruptTurn(CodexSessionClient connection, String threadId, String turnId) {
        if (threadId.isBlank() || turnId.isBlank()) return;
        if (pool == null) { connection.interrupt(threadId, turnId); return; }
        JsonObject params = new JsonObject(); params.addProperty("threadId", threadId); params.addProperty("turnId", turnId);
        try { connection.call("turn/interrupt", params); }
        catch (Exception e) { connection.close(); }
    }
    private static JsonObject findTurn(JsonObject thread, String turnId) {
        if (thread == null || !thread.has("turns") || !thread.get("turns").isJsonArray()) return null;
        for (JsonElement element : thread.getAsJsonArray("turns")) {
            if (element.isJsonObject() && turnId.equals(CodexStream.string(element.getAsJsonObject(), "id")))
                return element.getAsJsonObject();
        }
        return null;
    }
    private static String threadStatus(JsonObject thread) {
        if (thread == null || !thread.has("status") || !thread.get("status").isJsonObject()) return "unknown";
        return CodexStream.string(thread.getAsJsonObject("status"), "type");
    }
    private record ReconnectResult(CodexSessionClient connection, CodexSessionClient.Subscription subscription, boolean completed) { }
    static JsonObject turnParams(Path directory, String id, String prompt, boolean writeAccess) {
        return turnParams(directory, id, prompt, writeAccess, false);
    }
    static JsonObject turnParams(Path directory, String id, String prompt, boolean writeAccess, boolean unrestrictedAccess) {
        JsonObject turn = new JsonObject(); turn.addProperty("threadId", id);
        turn.addProperty("cwd", directory.toAbsolutePath().normalize().toString());
        turn.addProperty("approvalPolicy", "never");
        JsonObject sandbox = new JsonObject();
        sandbox.addProperty("type", unrestrictedAccess ? "dangerFullAccess" : writeAccess ? "workspaceWrite" : "readOnly");
        if (!unrestrictedAccess) sandbox.addProperty("networkAccess", false);
        if (writeAccess && !unrestrictedAccess) {
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
