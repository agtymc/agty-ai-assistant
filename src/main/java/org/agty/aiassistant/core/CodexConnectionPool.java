package org.agty.aiassistant.core;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Owns only this project's processes. Startup never holds the disposal lock. */
public final class CodexConnectionPool implements AutoCloseable {
    private record Key(List<String> command, Path directory, boolean experimental) { }
    private final Map<Key, CompletableFuture<CodexSessionClient>> clients = new HashMap<>();
    private boolean closed;
    private volatile boolean defaultExperimental;
    public void setExperimental(boolean enabled) {
        List<CompletableFuture<CodexSessionClient>> previous;
        synchronized (this) {
            if (defaultExperimental == enabled) return;
            defaultExperimental = enabled; previous = List.copyOf(clients.values()); clients.clear();
        }
        previous.forEach(future -> future.thenAccept(CodexSessionClient::close));
    }
    CodexSessionClient acquire(List<String> command, Path directory) throws Exception {
        return acquire(command, directory, false);
    }
    CodexSessionClient acquire(List<String> command, Path directory, boolean experimental) throws Exception {
        boolean effectiveExperimental = experimental || defaultExperimental;
        var key = new Key(List.copyOf(command), directory.toRealPath(), effectiveExperimental);
        CompletableFuture<CodexSessionClient> future;
        boolean creator;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Проект закрыт.");
            future = clients.get(key);
            if (future != null && future.isDone() && !future.isCompletedExceptionally() && !future.join().alive()) {
                future.join().close(); clients.remove(key); future = null;
            }
            creator = future == null;
            if (creator) { future = new CompletableFuture<>(); clients.put(key, future); }
        }
        if (creator) {
            try {
                var connection = new CodexSessionClient(command, directory, () -> false, effectiveExperimental);
                synchronized (this) {
                    if (closed) { connection.close(); throw new IllegalStateException("Проект закрыт."); }
                    future.complete(connection);
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
                synchronized (this) { clients.remove(key, future); }
                throw e;
            }
        }
        try { return future.get(); }
        catch (ExecutionException e) { throw new java.io.IOException("Не удалось запустить Codex.", e.getCause()); }
    }
    private CodexSessionClient local(String binary, Path directory) throws Exception {
        CodexCompatibility.require(binary, directory);
        return acquire(List.of(CodexCommand.executable(binary), "app-server"), directory, defaultExperimental);
    }
    public com.google.gson.JsonObject call(String binary, Path directory, boolean experimental, String method, com.google.gson.JsonObject params) throws Exception {
        CodexCompatibility.require(binary, directory);
        var connection = acquire(List.of(CodexCommand.executable(binary), "app-server"), directory, experimental || defaultExperimental);
        if (params != null) for (String key : List.of("threadId", "ancestorThreadId", "parentThreadId"))
            if (params.has(key) && !params.get(key).isJsonNull()) connection.readThread(params.get(key).getAsString(), directory, false);
        var result = connection.call(method, params);
        if (Set.of("thread/list", "app/list", "mcpServerStatus/list", "thread/backgroundTerminals/list").contains(method)
                && result != null && result.has("data") && result.get("data").isJsonArray()) {
            var seen = new HashSet<String>();
            var merged = result.deepCopy(); var page = result;
            while (page.has("nextCursor") && !page.get("nextCursor").isJsonNull() && !page.get("nextCursor").getAsString().isBlank()) {
                String cursor = page.get("nextCursor").getAsString();
                if (!seen.add(cursor) || seen.size() > 100) throw new java.io.IOException("Некорректная или слишком большая пагинация каталога Codex.");
                var next = params == null ? new com.google.gson.JsonObject() : params.deepCopy(); next.addProperty("cursor", cursor);
                page = connection.call(method, next); merged.getAsJsonArray("data").addAll(page.getAsJsonArray("data"));
            }
            merged.remove("nextCursor"); return merged;
        }
        return result;
    }
    public java.util.List<CodexModels.Model> models(String binary, Path directory) throws Exception {
        return CodexModels.list(local(binary, directory));
    }
    public java.util.List<org.agty.aiassistant.chat.ChatSessions.Session> sessions(String binary, Path directory) throws Exception {
        return local(binary, directory).list(directory);
    }
    public org.agty.aiassistant.chat.ChatSessions.Session read(String binary, Path directory, String id) throws Exception {
        var session = local(binary, directory).read(id, directory);
        for (var message : session.messages) org.agty.aiassistant.history.MessageArtifacts.getInstance().offload(message);
        return session;
    }
    public CodexSessionClient.HistoryPage historyPage(String binary, Path directory, String id, String cursor) throws Exception {
        local(binary, directory).readThread(id, directory, false);
        var page = local(binary, directory).historyPage(id, cursor);
        for (var message : page.messages()) org.agty.aiassistant.history.MessageArtifacts.getInstance().offload(message);
        return page;
    }
    public String status(String binary, Path directory, String id, String model, String effort, boolean write, boolean full, boolean interactive) throws Exception {
        return CodexStatus.read(local(binary, directory), directory, id, model, effort, write, full, interactive);
    }
    @Override public void close() {
        List<CompletableFuture<CodexSessionClient>> owned;
        synchronized (this) { closed = true; owned = List.copyOf(clients.values()); clients.clear(); }
        owned.forEach(future -> future.thenAccept(CodexSessionClient::close));
    }
}
