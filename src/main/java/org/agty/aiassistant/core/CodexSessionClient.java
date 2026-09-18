package org.agty.aiassistant.core;

import com.google.gson.*;
import org.agty.aiassistant.chat.ChatSessions;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Read-only app-server requests use the same executable/environment as chat. */
public final class CodexSessionClient implements AutoCloseable {
    private final Process process;
    private final java.util.function.BooleanSupplier cancelled;
    private final BlockingQueue<JsonObject> notifications = new LinkedBlockingQueue<>(2048);
    private final BufferedWriter writer;
    private final BlockingQueue<JsonObject> responses = new LinkedBlockingQueue<>(64);
    private int sequence;
    private volatile String failure = "";
    public CodexSessionClient(String binary, Path directory) throws Exception {
        this(List.of(CodexCommand.executable(binary), "app-server"), directory, () -> false);
    }
    CodexSessionClient(List<String> command, Path directory, java.util.function.BooleanSupplier cancelled) throws Exception {
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
                            JsonObject error = new JsonObject(); error.addProperty("code", -32601);
                            error.addProperty("message", "Interactive requests are not supported; permission was not granted.");
                            JsonObject rejection = new JsonObject(); rejection.add("id", value.get("id")); rejection.add("error", error);
                            write(rejection);
                            continue;
                        }
                        if (!responses.offer(value)) throw new IOException("Слишком много ответов app-server.");
                    } else if (value.has("method") && !notifications.offer(value)) throw new IOException("Переполнена очередь событий Codex.");
                }
            } catch (Exception e) { failure = e.getMessage(); }
        });
        Thread.startVirtualThread(() -> {
            try (var reader = process.errorReader(StandardCharsets.UTF_8)) { while (reader.readLine() != null) { } }
            catch (IOException ignored) { }
        });
        try {
            call("initialize", JsonParser.parseString("{\"clientInfo\":{\"name\":\"agty_codex\",\"version\":\"0.6.0\"}}").getAsJsonObject());
            writer.write("{\"method\":\"initialized\",\"params\":{}}\n"); writer.flush();
        } catch (Exception e) { close(); throw e; }
    }
    public JsonObject call(String method, JsonObject params) throws Exception {
        int id = ++sequence;
        JsonObject request = new JsonObject(); request.addProperty("id", id);
        request.addProperty("method", method); request.add("params", params);
        write(request);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (cancelled.getAsBoolean()) throw new CancellationException();
            JsonObject response = responses.poll(100, TimeUnit.MILLISECONDS);
            if (response != null && response.get("id").getAsInt() == id) {
                if (response.has("error")) throw new IOException(response.getAsJsonObject("error").get("message").getAsString());
                return response.getAsJsonObject("result");
            }
            if (!failure.isEmpty() || !process.isAlive()) throw new IOException("Codex app-server остановлен. " + failure);
        }
        throw new IOException("Codex app-server не ответил за 30 секунд.");
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
        return decode(readThread(id, directory, true));
    }
    JsonObject readThread(String id, Path directory, boolean includeTurns) throws Exception {
        UUID.fromString(id);
        JsonObject params = new JsonObject(); params.addProperty("threadId", id); params.addProperty("includeTurns", includeTurns);
        JsonObject thread = call("thread/read", params).getAsJsonObject("thread");
        if (!Path.of(string(thread, "cwd")).toRealPath().equals(directory.toRealPath()))
            throw new IOException("Эта сессия принадлежит другому проекту.");
        return thread;
    }
    static ChatSessions.Session summary(JsonObject thread) {
        var session = new ChatSessions.Session();
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
        for (var turn : thread.getAsJsonArray("turns")) {
            for (var value : turn.getAsJsonObject().getAsJsonArray("items")) {
                var item = value.getAsJsonObject();
                String type = string(item, "type");
                if (!type.equals("userMessage") && !type.equals("agentMessage")) continue;
                var message = new ChatSessions.Message(); message.id = "codex:" + string(item, "id");
                message.title = type.equals("userMessage") ? "Вы" : "Codex";
                if (type.equals("agentMessage")) message.markdown = string(item, "text");
                else if (item.has("content")) for (var content : item.getAsJsonArray("content")) {
                    var part = content.getAsJsonObject();
                    if (string(part, "type").equals("text")) message.markdown += string(part, "text") + "\n";
                }
                if (message.markdown.isBlank()) continue;
                if (message.markdown.length() > 100_000) message.markdown = message.markdown.substring(0, 100_000) + "\n…";
                session.messages.add(message); length += message.markdown.length();
                while (session.messages.size() > 300 || length > 500_000)
                    length -= session.messages.removeFirst().markdown.length();
            }
        }
        return session;
    }
    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
    @Override public void close() {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try { if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
        catch (InterruptedException e) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
}
