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
                options.interactiveApprovals(), options.mode(), options.reviewTarget(), options.compact(), false, options.serviceTier(), options.cyberAccessProgram(), options.attachments()), options);
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
        return createRequest(sessionId, writeAccess, model, effort, forkForModel, unrestrictedAccess, false, RequestOptions.Mode.WORK, null, false, false, "", "", List.of());
    }
    public Request observeRequest(String sessionId) {
        if (sessionId.isBlank()) throw new IllegalArgumentException("Нужен thread ID.");
        return createRequest(sessionId, false, "", "", false, false, false, RequestOptions.Mode.WORK, null, false, true, "", "", List.of());
    }
    private Request createRequest(String sessionId, boolean writeAccess, String model, String effort,
                                  boolean forkForModel, boolean unrestrictedAccess, boolean interactive, RequestOptions.Mode mode, JsonObject reviewTarget, boolean compact, boolean observe, String serviceTier, String cyberAccessProgram, List<RequestOptions.Attachment> attachments) {
        if (unrestrictedAccess) writeAccess = true;
        boolean canWrite = writeAccess;
        boolean fullAccess = unrestrictedAccess;
        return new Request() {
            private volatile boolean cancelled;
            private volatile CodexSessionClient client;
            private volatile String activeThreadId = "";
            private volatile String activeTurnId = "";
            private final Set<String> serverHandles = java.util.concurrent.ConcurrentHashMap.newKeySet();
            private void invalidateServerRequests(Consumer<ChatEvent> events) {
                for (String handle : List.copyOf(serverHandles)) if (serverHandles.remove(handle))
                    events.accept(new ChatEvent(ChatEvent.Kind.SERVER_RESOLVED, handle, "", "", true));
            }
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
                if (!serverHandles.contains(id)) throw new java.io.IOException("Исходный запрос больше не ожидает ответа.");
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
                boolean turnSubmitted = false;
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
                    // review/start and compact/start inherit native settings and do not carry turn sandboxPolicy.
                    if (reviewTarget != null || compact) verifyReadOnlyScope(thread, directory);
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
                        Path source = Path.of(attachment.value());
                        new ProjectAccessPolicy(directory).image(source).requireAllowed();
                        Path verified = source.toRealPath();
                        new ProjectAccessPolicy(directory.toRealPath()).image(verified).requireAllowed();
                        if (!java.nio.file.Files.isRegularFile(verified) || java.nio.file.Files.size(verified) > 20L * 1024 * 1024)
                            throw new IllegalArgumentException("Изображение недоступно или превышает 20 MiB.");
                        JsonObject image = new JsonObject(); image.addProperty("type", attachment.kind()); image.addProperty("path", verified.toString());
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
                    new ProjectAccessPolicy(directory).request(canWrite).requireAllowed();
                    var stream = new CodexStream();
                    JsonObject observedTurn = null;
                    JsonObject started;
                    if (observe) {
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
                        turnSubmitted = true;
                        connection.call("thread/compact/start", paramsCompact); started = null;
                    } else if (reviewTarget == null) { turnSubmitted = true; started = connection.call("turn/start", turn); }
                    else {
                        JsonObject review = new JsonObject(); review.addProperty("threadId", id); review.addProperty("delivery", "inline"); review.add("target", reviewTarget.deepCopy());
                        turnSubmitted = true; started = connection.call("review/start", review);
                    }
                    String turnId = compact ? "" : started.getAsJsonObject("turn").get("id").getAsString();
                    activeTurnId = turnId;
                    if (cancelled && !turnId.isBlank()) { stopAndAwait(connection, subscription, directory, id, turnId, stream, events); turnSettled = true; throw new CancellationException(); }
                    long unidentifiedCancellationDeadline = 0;
                    while (true) {
                        if (cancelled && turnId.isBlank()) {
                            if (unidentifiedCancellationDeadline == 0) unidentifiedCancellationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                            if (System.nanoTime() >= unidentifiedCancellationDeadline) {
                                connection.close(); turnSettled = true; throw new CancellationException("Codex не сообщил ID остановленной задачи.");
                            }
                        }
                        if (cancelled && !turnId.isBlank()) { stopAndAwait(connection, subscription, directory, id, turnId, stream, events); turnSettled = true; throw new CancellationException(); }
                        JsonObject notification;
                        try {
                            notification = subscription.next();
                        } catch (java.io.IOException disconnected) {
                            client = null;
                            invalidateServerRequests(events);
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
                            String handle = CodexSessionClient.serverRequestHandle(notification);
                            if (handle.isBlank()) throw new java.io.IOException("Запрос Codex не имеет привязки к соединению.");
                            serverHandles.add(handle);
                            events.accept(new ChatEvent(ChatEvent.Kind.SERVER_REQUEST, handle,
                                    CodexStream.string(notification, "method"), notification.toString(), false));
                            continue;
                        }
                        if (CodexStream.string(notification, "method").equals("serverRequest/resolved")) {
                            String handle = CodexSessionClient.serverRequestHandle(notification);
                            if (serverHandles.remove(handle)) events.accept(new ChatEvent(ChatEvent.Kind.SERVER_RESOLVED, handle, "", "", true));
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
                    if (pool != null && !turnSettled) {
                        if (!activeTurnId.isBlank()) stopAndAwait(connection, subscription, directory, activeThreadId, activeTurnId, new CodexStream(), events);
                        else if (turnSubmitted) {
                            // A lost start ACK is ambiguous: do not release the writer lease while our
                            // process may still be applying the unacknowledged task, and never resend it.
                            connection.close();
                            events.accept(ChatEvent.simple(ChatEvent.Kind.STATUS, "Codex не подтвердил идентификатор задачи; принадлежащее плагину соединение остановлено для сохранности файлов."));
                        }
                    }
                    client = null;
                    invalidateServerRequests(events);
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
    static void verifyReadOnlyScope(JsonObject response, Path directory) throws java.io.IOException {
        String cwd = CodexStream.string(response, "cwd");
        if (cwd.isBlank() || !Path.of(cwd).toRealPath().equals(directory.toRealPath()))
            throw new java.io.IOException("Codex не подтвердил каталог операции. Запрос не отправлен.");
        var sandbox = response.has("sandbox") && response.get("sandbox").isJsonObject() ? response.getAsJsonObject("sandbox") : null;
        if (sandbox == null || !CodexStream.string(sandbox, "type").equals("readOnly")
                || sandbox.has("networkAccess") && !sandbox.get("networkAccess").isJsonNull() && sandbox.get("networkAccess").getAsBoolean())
            throw new java.io.IOException("Codex не подтвердил режим только чтения без сети. Операция не запущена; дождитесь завершения нативной задачи и проверьте права.");
    }
    /** Confirm terminal state, including interruption races; never release an unconfirmed writer. */
    private void stopAndAwait(CodexSessionClient connection, CodexSessionClient.Subscription subscription, Path directory,
                              String threadId, String turnId, CodexStream stream, Consumer<ChatEvent> events) {
        if (threadId.isBlank() || turnId.isBlank()) return;
        if (pool == null) { connection.interrupt(threadId, turnId); connection.close(); return; }
        events.accept(ChatEvent.simple(ChatEvent.Kind.STATUS, "Остановка задачи Codex…"));
        JsonObject params = new JsonObject(); params.addProperty("threadId", threadId); params.addProperty("turnId", turnId);
        try {
            try { connection.call("turn/interrupt", params); }
            catch (java.io.IOException ignored) { /* A just-completed turn may reject interruption. Verify it below. */ }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && connection.alive()) {
                var notification = subscription.next();
                if (notification == null || notification.has("id")) continue;
                var p = notification.getAsJsonObject("params");
                if (p == null || !threadId.equals(CodexStream.string(p, "threadId"))) continue;
                if (CodexStream.string(notification, "method").equals("turn/completed")
                        && p.has("turn") && turnId.equals(CodexStream.string(p.getAsJsonObject("turn"), "id"))) return;
                if (turnId.equals(CodexStream.string(p, "turnId"))) events.accept(stream.parse(notification));
            }
            if (connection.alive()) {
                var original = findTurn(connection.readThread(threadId, directory, true), turnId);
                if (original != null && Set.of("completed", "failed", "interrupted").contains(CodexStream.string(original, "status"))) return;
            }
        } catch (Exception ignored) { /* Fail closed: an unconfirmed writer must not overlap the next task. */ }
        connection.close();
        events.accept(ChatEvent.simple(ChatEvent.Kind.STATUS,
                "Не удалось подтвердить остановку turn; процесс этого проекта завершён. Другие чаты восстановят наблюдение без повторной отправки задач."));
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
