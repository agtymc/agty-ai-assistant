package org.agty.aiassistant.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Count and aggregate size limits apply before retaining untrusted protocol messages. */
final class ProtocolBuffer implements AutoCloseable {
    record Entry(JsonObject value, int characters) { }
    static final class Budget {
        private final long maximum;
        private final AtomicLong retained = new AtomicLong();
        Budget(long maximum) { this.maximum = maximum; }
        void retain(int characters) throws IOException {
            if (characters < 0 || retained.addAndGet(characters) > maximum) {
                if (characters >= 0) retained.addAndGet(-characters);
                throw new IOException("Превышен общий лимит буфера событий Codex.");
            }
        }
        void release(int characters) { retained.addAndGet(-characters); }
        long retained() { return retained.get(); }
    }
    private final LinkedBlockingQueue<Entry> queue;
    private final Budget budget;
    private boolean closed;
    ProtocolBuffer(int capacity, Budget budget) { this.queue = new LinkedBlockingQueue<>(capacity); this.budget = budget; }
    synchronized boolean offer(JsonObject value, int characters) throws IOException {
        if (closed) return true;
        budget.retain(characters);
        if (queue.offer(new Entry(value, characters))) return true;
        budget.release(characters); return false;
    }
    JsonObject poll(long milliseconds) throws InterruptedException {
        var entry = queue.poll(milliseconds, TimeUnit.MILLISECONDS);
        if (entry == null) return null;
        budget.release(entry.characters()); return entry.value();
    }
    synchronized void clear() {
        Entry entry; while ((entry = queue.poll()) != null) budget.release(entry.characters());
    }
    @Override public synchronized void close() { closed = true; clear(); }
}
