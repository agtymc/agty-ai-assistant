package org.agty.aiassistant.execution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Serializes overlapping physical directories; sibling worktrees remain independent. */
final class WorkspaceLocks {
    private final List<Lease> waiting = new ArrayList<>();
    private final List<Lease> active = new ArrayList<>();

    final class Lease implements AutoCloseable {
        private final Path directory;
        private Lease(Path directory) { this.directory = directory; }
        @Override public void close() {
            synchronized (WorkspaceLocks.this) { active.remove(this); WorkspaceLocks.this.notifyAll(); }
        }
    }

    synchronized Lease acquire(Path directory, BooleanSupplier cancelled) throws InterruptedException {
        var lease = new Lease(directory);
        waiting.add(lease);
        try {
            while (true) {
                if (cancelled.getAsBoolean()) throw new CancellationException();
                boolean conflict = active.stream().anyMatch(other -> overlaps(directory, other.directory));
                if (!conflict) for (var earlier : waiting) {
                    if (earlier == lease) break;
                    if (overlaps(directory, earlier.directory)) { conflict = true; break; }
                }
                if (!conflict) { active.add(lease); return lease; }
                wait(100);
            }
        } finally { waiting.remove(lease); notifyAll(); }
    }

    private static boolean overlaps(Path a, Path b) { return a.startsWith(b) || b.startsWith(a); }
}
