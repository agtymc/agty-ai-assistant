package org.agty.aiassistant.chat;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable request scope; changing it never writes global Codex configuration. */
public record RequestOptions(Path directory, String sessionId, String model, String effort,
                             Access access, Mode mode, List<Attachment> attachments, boolean fork, boolean interactiveApprovals) {
    public RequestOptions(Path directory, String sessionId, String model, String effort, Access access, Mode mode,
                          List<Attachment> attachments, boolean fork) {
        this(directory, sessionId, model, effort, access, mode, attachments, fork, false);
    }
    public enum Access { READ_ONLY, WORKSPACE_WRITE, FULL }
    public enum Mode { WORK, PLAN }
    public record Attachment(String kind, String value) {
        public Attachment { Objects.requireNonNull(kind); Objects.requireNonNull(value); }
    }
    public RequestOptions {
        directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        Objects.requireNonNull(sessionId); Objects.requireNonNull(model); Objects.requireNonNull(effort);
        Objects.requireNonNull(access); Objects.requireNonNull(mode); attachments = List.copyOf(attachments);
    }
}
