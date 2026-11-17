package org.agty.aiassistant.core;

import com.google.gson.*;
import org.agty.aiassistant.chat.ChatSessions;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only app-server requests use the same executable/environment as chat. */
public final class CodexSessionClient implements AutoCloseable {
    private final Process process;
    private final java.util.function.BooleanSupplier cancelled;
    private final BlockingQueue<JsonObject> notifications = new LinkedBlockingQueue<>(2048);
    private final BufferedWriter writer;
    private final ConcurrentMap<Integer, CompletableFuture<JsonObject>> responses = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final ConcurrentMap<String, JsonObject> pendingServerRequests = new ConcurrentHashMap<>();
    private static final Set<String> INTERACTIVE = Set.of("item/commandExecution/requestApproval", "item/fileChange/requestApproval",
            "item/permissions/requestApproval", "item/tool/requestUserInput", "mcpServer/elicitation/request");
    public void respond(String id, JsonObject result) throws IOException {
        var pending = pendingServerRequests.remove(id);
        if (pending == null) throw new IOException("Запрос уже разрешён или соединение закрыто.");
        JsonObject response = new JsonObject(); response.add("id", pending.get("id")); response.add("result", result);
        write(response);
    }
    private final CopyOnWriteArrayList<BlockingQueue<JsonObject>> subscribers = new CopyOnWriteArrayList<>();
    public final class Subscription implements AutoCloseable {
        private final BlockingQueue<JsonObject> queue = new LinkedBlockingQueue<>(8192);
        private Subscription() { subscribers.add(queue); notifications.clear(); }
        JsonObject next() throws Exception {
            JsonObject value = queue.poll(100, TimeUnit.MILLISECONDS);
            if (value == null && !alive()) throw new IOException("Codex app-server остановлен. " + failure);
            return value;
        }
        @Override public void close() { subscribers.remove(queue); }
    }
    Subscription subscribe() { return new Subscription(); }
    boolean alive() { return !closed && failure.isEmpty() && process.isAlive(); }
    private void notification(JsonObject value) throws IOException {
        if (subscribers.isEmpty()) {
            // No task is subscribed: native history is authoritative on the next observation.
            // Retain a bounded diagnostics tail without killing a healthy idle connection.
            if (!notifications.offer(value)) { notifications.poll(); notifications.offer(value); }
        } else for (var queue : subscribers)
            if (!queue.offer(value)) throw new IOException("Переполнена очередь подписчика Codex.");
    }
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile String failure = "";
    public CodexSessionClient(String binary, Path directory) throws Exception {
        this(List.of(CodexCommand.executable(binary), "app-server"), directory, () -> false);
    }
    CodexSessionClient(List<String> command, Path directory, java.util.function.BooleanSupplier cancelled) throws Exception {
        this(command, directory, cancelled, false);
    }
    CodexSessionClient(List<String> command, Path directory, java.util.function.BooleanSupplier cancelled, boolean experimental) throws Exception {
        this.cancelled = cancelled;
        process = new ProcessBuilder(command)
                .directory(directory.toFile()).start();
        writer = process.outputWriter(StandardCharsets.UTF_8);
        Thread.startVirtualThread(() -> {
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 16_000_000) throw new IOException("История слишком велика.");
                    var value = JsonParser.parseString(line).getAsJsonObject();
                    if (value.has("id")) {
                        if (value.has("method")) {
                            if (INTERACTIVE.contains(string(value, "method")) && !subscribers.isEmpty()) {
                                pendingServerRequests.put(value.get("id").toString(), value);
                                notification(value); continue;
                            }
                            JsonObject error = new JsonObject(); error.addProperty("code", -32601);
                            error.addProperty("message", "Interactive requests are not supported; permission was not granted.");
                            JsonObject rejection = new JsonObject(); rejection.add("id", value.get("id")); rejection.add("error", error);
                            write(rejection);
                            continue;
                        }
                        var pending = responses.remove(value.get("id").getAsInt());
                        if (pending != null) pending.complete(value);
                    } else if (value.has("method")) {
                        if (string(value, "method").equals("serverRequest/resolved") && value.has("params")) {
                            var requestId = value.getAsJsonObject("params").get("requestId");
                            if (requestId != null) pendingServerRequests.remove(requestId.toString());
                        }
                        notification(value);
                    }
                }
            } catch (Exception e) { failure = String.valueOf(e.getMessage()); }
            finally {
                if (failure.isEmpty()) failure = "Поток ответов закрыт.";
                failPending(new IOException("Codex app-server остановлен. " + failure));
            }
        });
        Thread.startVirtualThread(() -> {
            try (var reader = process.errorReader(StandardCharsets.UTF_8)) { while (reader.readLine() != null) { } }
            catch (IOException ignored) { }
        });
        try {
            JsonObject initialize = JsonParser.parseString("{\"clientInfo\":{\"name\":\"agty_codex\",\"version\":\"0.14.0\"}}").getAsJsonObject();
            if (experimental) { var capabilities = new JsonObject(); capabilities.addProperty("experimentalApi", true); initialize.add("capabilities", capabilities); }
            call("initialize", initialize);
            write(JsonParser.parseString("{\"method\":\"initialized\",\"params\":{}}").getAsJsonObject());
        } catch (Exception e) { close(); throw e; }
    }
    public JsonObject call(String method, JsonObject params) throws Exception {
        int id = sequence.incrementAndGet();
        JsonObject request = new JsonObject(); request.addProperty("id", id);
        request.addProperty("method", method); request.add("params", params);
        var pending = new CompletableFuture<JsonObject>();
        responses.put(id, pending);
        try {
            if (closed || !failure.isEmpty()) throw new IOException("Соединение Codex закрыто. " + failure);
            write(request);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                try {
                    JsonObject response = pending.get(100, TimeUnit.MILLISECONDS);
                    if (response.has("error")) throw new IOException(response.getAsJsonObject("error").get("message").getAsString());
                    return response.getAsJsonObject("result");
                } catch (TimeoutException ignored) {
                    if (!failure.isEmpty() || !process.isAlive()) throw new IOException("Codex app-server остановлен. " + failure);
                } catch (ExecutionException e) {
                    throw new IOException("Ошибка соединения Codex.", e.getCause());
                }
            }
            throw new IOException("Codex app-server не ответил за 30 секунд.");
        } finally {
            responses.remove(id, pending);
        }
    }
    private void failPending(IOException cause) {
        responses.forEach((id, pending) -> pending.completeExceptionally(cause));
        responses.clear();
    }

    void interrupt(String threadId, String turnId) {
        if (threadId.isBlank() || turnId.isBlank() || !process.isAlive()) return;
        JsonObject params = new JsonObject();
        params.addProperty("threadId", threadId); params.addProperty("turnId", turnId);
        JsonObject request = new JsonObject(); request.addProperty("id", sequence.incrementAndGet());
        request.addProperty("method", "turn/interrupt"); request.add("params", params);
        try { write(request); } catch (IOException ignored) { }
    }
    private synchronized void write(JsonObject value) throws IOException { writer.write(value + "\n"); writer.flush(); }
    JsonObject nextNotification() throws Exception {
        if (cancelled.getAsBoolean()) throw new CancellationException();
        JsonObject next = notifications.poll(100, TimeUnit.MILLISECONDS);
        if (next != null) return next;
        if (!failure.isEmpty() || !process.isAlive()) throw new IOException("Codex app-server остановлен. " + failure);
        return null;
    }
    public List<ChatSessions.Session> list(Path directory) throws Exception {
        List<ChatSessions.Session> sessions = new ArrayList<>();
        String cursor = "";
        Set<String> seen = new HashSet<>();
        do {
            JsonObject params = new JsonObject(); params.addProperty("cwd", directory.toRealPath().toString());
            params.addProperty("limit", 100); params.addProperty("sortKey", "updated_at");
            params.add("sourceKinds", JsonParser.parseString("[\"cli\",\"vscode\",\"exec\",\"appServer\",\"unknown\"]"));
            if (!cursor.isEmpty()) params.addProperty("cursor", cursor);
            JsonObject result = call("thread/list", params);
            for (JsonElement element : result.getAsJsonArray("data")) sessions.add(summary(element.getAsJsonObject()));
            cursor = string(result, "nextCursor");
            if (!cursor.isEmpty() && !seen.add(cursor)) throw new IOException("Codex повторил курсор списка сессий.");
        } while (!cursor.isEmpty());
        return sessions;
    }
    public ChatSessions.Session read(String id, Path directory) throws Exception {
        var thread = readThread(id, directory, false);
        var session = summary(thread);
        var page = historyPage(id, ""); session.messages = page.messages(); session.historyCursor = page.nextCursor();
        return session;
    }
    public record HistoryPage(List<ChatSessions.Message> messages, String nextCursor) { }
    public HistoryPage historyPage(String id, String cursor) throws Exception {
        UUID.fromString(id);
        JsonObject params = new JsonObject(); params.addProperty("threadId", id);
        params.addProperty("sortDirection", "desc"); params.addProperty("limit", 100);
        if (!cursor.isBlank()) params.addProperty("cursor", cursor);
        JsonObject result = call("thread/items/list", params);
        String next = string(result, "nextCursor");
        if (!next.isBlank() && next.equals(cursor)) throw new IOException("Codex повторил курсор истории.");
        var entries = result.getAsJsonArray("data");
        var ordered = new JsonArray();
        for (int i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i).getAsJsonObject();
            var turn = new JsonObject(); turn.addProperty("id", string(entry, "turnId"));
            var items = new JsonArray(); items.add(entry.get("item")); turn.add("items", items); ordered.add(turn);
        }
        var thread = new JsonObject(); thread.addProperty("id", id); thread.add("turns", ordered);
        return new HistoryPage(decode(thread).messages, next);
    }
    JsonObject readThread(String id, Path directory, boolean includeTurns) throws Exception {
        UUID.fromString(id);
        JsonObject params = new JsonObject(); params.addProperty("threadId", id); params.addProperty("includeTurns", includeTurns);
        JsonObject thread = call("thread/read", params).getAsJsonObject("thread");
        if (!Path.of(string(thread, "cwd")).toRealPath().equals(directory.toRealPath()))
            throw new IOException("Эта сессия принадлежит другому проекту.");
        return thread;
    }
    public static ChatSessions.Session summary(JsonObject thread) {
        var session = new ChatSessions.Session();
        session.workingDirectory = string(thread, "cwd"); session.parentThreadId = string(thread, "parentThreadId");
        session.selectedModel = string(thread, "model"); session.effectiveModel = session.selectedModel; session.reasoningEffort = string(thread, "reasoningEffort");
        session.codexId = string(thread, "id"); session.title = string(thread, "name");
        if (session.title.isBlank()) session.title = string(thread, "preview");
        session.title = session.title.replaceAll("\\s+", " ").strip();
        if (session.title.isBlank()) session.title = "Codex";
        if (session.title.length() > 80) session.title = session.title.substring(0, 80) + "…";
        return session;
    }
    static ChatSessions.Session decode(JsonObject thread) {
        var session = summary(thread);
        if (!thread.has("turns")) return session;
        int length = 0;
        var stream = new CodexStream();
        for (var turn : thread.getAsJsonArray("turns")) {
            for (var value : turn.getAsJsonObject().getAsJsonArray("items")) {
                var item = value.getAsJsonObject();
                String type = string(item, "type");
                if (!type.equals("userMessage") && !type.equals("agentMessage")) {
                    JsonObject notification = new JsonObject(); notification.addProperty("method", "item/completed");
                    JsonObject params = new JsonObject(); params.add("item", item); notification.add("params", params);
                    var event = stream.parse(notification);
                    if (event.kind() == org.agty.aiassistant.chat.ChatEvent.Kind.ACTIVITY && !event.text().isBlank()) {
                        var activity = new ChatSessions.Message(); activity.id = "codex:" + string(turn.getAsJsonObject(), "id") + ":" + event.id();
                        activity.activity = true; activity.title = event.title(); activity.markdown = event.text();
                        if (type.equals("imageGeneration") && (!item.has("fileId") || item.get("fileId").isJsonNull())) activity.imageData = string(item, "result");
                        session.messages.add(activity);
                    }
                    continue;
                }
                var message = new ChatSessions.Message(); message.id = "codex:" + string(turn.getAsJsonObject(), "id") + ":" + string(item, "id");
                message.title = type.equals("userMessage") ? "Вы" : "Codex";
                if (type.equals("agentMessage")) message.markdown = string(item, "text");
                else if (item.has("content")) for (var content : item.getAsJsonArray("content")) {
                    var part = content.getAsJsonObject();
                    if (string(part, "type").equals("text")) message.markdown += string(part, "text") + "\n";
                }
                if (message.markdown.isBlank()) continue;
                session.messages.add(message); length += message.markdown.length();
            }
        }
        return session;
    }
    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
    @Override public void close() {
        closed = true;
        pendingServerRequests.clear();
        failPending(new IOException("Соединение Codex закрыто."));
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try { if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
        catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
        descendants.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
    }
}
