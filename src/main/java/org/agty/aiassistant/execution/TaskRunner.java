package org.agty.aiassistant.execution;

import org.agty.aiassistant.chat.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Execution belongs to the project; views may attach and detach without cancelling tasks. */
public final class TaskRunner implements AutoCloseable {
    public enum State { QUEUED, RUNNING, WAITING_USER, SUCCEEDED, FAILED, CANCELLED }
    public static final class Task {
        public final String id = UUID.randomUUID().toString();
        public final ChatSessions.Session session;
        public final ChatProvider.Request request;
        public final String question, provider;
        public final Path directory;
        public final long startedAt = System.nanoTime();
        public volatile State state = State.QUEUED;
        public volatile String status = "В очереди";
        public final Map<String, com.google.gson.JsonObject> pending = new LinkedHashMap<>();
        private volatile boolean cancelled;
        private Task(ChatSessions.Session session, ChatProvider.Request request, String question, String provider, Path directory) {
            this.session = session; this.request = request; this.question = question; this.provider = provider; this.directory = directory;
        }
        public boolean active() { return state == State.QUEUED || state == State.RUNNING || state == State.WAITING_USER; }
    }
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<Path, Semaphore> writers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Task>> listeners = new CopyOnWriteArrayList<>();
    private final Consumer<Runnable> dispatch;
    private final Consumer<Task> beforeWrite;
    private final java.util.function.UnaryOperator<ChatEvent> eventProcessor;
    private final java.util.function.Function<ChatSessions.Message, String> fullText;
    private volatile boolean closed;
    public TaskRunner(Consumer<Runnable> dispatch) { this(dispatch, task -> {}); }
    public TaskRunner(Consumer<Runnable> dispatch, Consumer<Task> beforeWrite) { this(dispatch, beforeWrite, message -> message.markdown); }
    public TaskRunner(Consumer<Runnable> dispatch, Consumer<Task> beforeWrite, java.util.function.Function<ChatSessions.Message, String> fullText) {
        this(dispatch, beforeWrite, fullText, java.util.function.UnaryOperator.identity());
    }
    public TaskRunner(Consumer<Runnable> dispatch, Consumer<Task> beforeWrite, java.util.function.Function<ChatSessions.Message, String> fullText,
                      java.util.function.UnaryOperator<ChatEvent> eventProcessor) {
        this.dispatch = dispatch; this.beforeWrite = beforeWrite; this.fullText = fullText; this.eventProcessor = eventProcessor;
    }
    public AutoCloseable subscribe(Consumer<Task> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
    public Task task(String sessionId) { return tasks.get(sessionId); }
    public synchronized Task start(ChatSessions.Session session, ChatProvider.Request request, Path directory,
                                   String prompt, String question, String provider, boolean write) throws Exception {
        if (closed) throw new IllegalStateException("Проект закрыт.");
        Task previous = tasks.get(session.id);
        if (previous != null && previous.active()) throw new IllegalStateException("В этом чате уже выполняется задача.");
        Path canonical = directory.toRealPath();
        Task task = new Task(session, request, question, provider, canonical);
        tasks.put(session.id, task);
        append(session, "Вы", question);
        session.draft = "";
        publish(task);
        workers.submit(() -> execute(task, canonical, prompt, write));
        return task;
    }
    private void execute(Task task, Path directory, String prompt, boolean write) {
        Semaphore gate = write ? writers.computeIfAbsent(directory, key -> new Semaphore(1, true)) : null;
        boolean acquired = false;
        try {
            if (gate != null) {
                while (!task.cancelled && !(acquired = gate.tryAcquire(100, TimeUnit.MILLISECONDS))) { }
            }
            if (task.cancelled) throw new CancellationException();
            dispatch.accept(() -> { if (!task.cancelled) { task.state = State.RUNNING; task.status = "Выполняется"; publish(task); } });
            if (write) {
                var prepared = new CompletableFuture<Void>();
                dispatch.accept(() -> {
                    try {
                        if (task.cancelled) throw new CancellationException();
                        beforeWrite.accept(task); prepared.complete(null);
                    } catch (Exception e) { prepared.completeExceptionally(e); }
                });
                prepared.get();
            }
            if (task.cancelled) throw new CancellationException();
            task.request.run(directory, prompt, event -> {
                var prepared = eventProcessor.apply(event);
                dispatch.accept(() -> reduce(task, prepared));
            });
            dispatch.accept(() -> finish(task, task.cancelled ? State.CANCELLED : State.SUCCEEDED, task.cancelled ? "Остановлено" : "Готово"));
        } catch (Exception e) {
            dispatch.accept(() -> finish(task, task.cancelled || e instanceof CancellationException ? State.CANCELLED : State.FAILED,
                    task.cancelled ? "Остановлено" : "Ошибка: " + e.getMessage()));
        } finally { if (acquired) gate.release(); }
    }
    private void reduce(Task task, ChatEvent event) {
        if (!task.active()) return;
        var session = task.session;
        switch (event.kind()) {
            case SESSION -> { if (!event.text().isBlank()) { session.nativeSessionId = event.text(); session.history = ""; } }
            case MODEL -> { session.effectiveModel = event.text(); session.confirmedThreadId = session.remoteId(); }
            case USAGE -> session.tokenUsage = event.text();
            case STATUS -> task.status = event.text();
            case SERVER_REQUEST -> {
                task.state = State.WAITING_USER;
                task.pending.put(event.id(), com.google.gson.JsonParser.parseString(event.text()).getAsJsonObject());
                task.status = "Требуется ответ (" + task.pending.size() + ")";
            }
            case SERVER_RESOLVED -> {
                task.pending.remove(event.id());
                task.state = task.pending.isEmpty() ? State.RUNNING : State.WAITING_USER;
                task.status = task.pending.isEmpty() ? "Выполняется" : "Требуется ответ (" + task.pending.size() + ")";
            }
            case ERROR -> { append(session, "Ошибка", event.text()); task.status = event.text(); }
            case MESSAGE, ACTIVITY -> {
                boolean activity = event.kind() == ChatEvent.Kind.ACTIVITY;
                String id = task.id + ":" + (event.id().isEmpty() ? UUID.randomUUID() : event.id());
                var message = session.messages.stream().filter(m -> m.id.equals(id)).findFirst().orElse(null);
                if (activity && event.title().startsWith("Пояснение") && event.text().isBlank()) {
                    if (message != null) session.messages.remove(message);
                    break;
                }
                if (message == null) { message = new ChatSessions.Message(); message.id = id; session.messages.add(message); }
                message.title = activity ? event.title() : task.provider;
                message.markdown = event.text(); message.artifact = event.artifact(); message.activity = activity; message.complete = event.complete();
            }
            default -> { }
        }
        publish(task);
    }
    private void finish(Task task, State state, String status) {
        task.pending.clear();
        task.state = state; task.status = status;
        task.session.messages.stream().filter(m -> m.id.startsWith(task.id + ":")).forEach(m -> m.complete = true);
        if (state == State.FAILED || state == State.CANCELLED) append(task.session, "Запрос не завершён", status);
        if (state == State.SUCCEEDED && task.session.remoteId().isEmpty()) {
            String answer = String.join("\n", task.session.messages.stream()
                    .filter(m -> m.id.startsWith(task.id + ":") && !m.activity).map(fullText).toList());
            task.session.history += "USER:\n" + task.question + "\nASSISTANT:\n" + answer + "\n";
        }
        publish(task);
    }
    private static void append(ChatSessions.Session session, String title, String text) {
        var message = new ChatSessions.Message(); message.id = "local-" + UUID.randomUUID();
        message.title = title; message.markdown = text; session.messages.add(message);
    }
    private void publish(Task task) { listeners.forEach(listener -> listener.accept(task)); }
    public void respond(String sessionId, String requestId, com.google.gson.JsonObject answer) {
        Task task = tasks.get(sessionId);
        if (task == null || !task.active() || !task.pending.containsKey(requestId))
            throw new IllegalStateException("Запрос больше не ожидает ответа.");
        // Remove before the asynchronous write so repeated clicks cannot send a second answer.
        task.pending.remove(requestId);
        task.state = task.pending.isEmpty() ? State.RUNNING : State.WAITING_USER; publish(task);
        workers.submit(() -> {
            try {
                task.request.respond(requestId, answer);
                dispatch.accept(() -> { task.status = "Ответ отправлен"; publish(task); });
            } catch (Exception e) {
                dispatch.accept(() -> { task.status = "Ответ не доставлен: " + e.getMessage(); publish(task); });
            }
        });
    }
    public void cancel(String sessionId) {
        Task task = tasks.get(sessionId);
        if (task == null || !task.active()) return;
        task.cancelled = true;
        workers.submit(task.request::cancel);
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        tasks.values().stream().filter(Task::active).forEach(task -> { task.cancelled = true; workers.submit(task.request::cancel); });
        listeners.clear();
        workers.shutdown();
    }
}
