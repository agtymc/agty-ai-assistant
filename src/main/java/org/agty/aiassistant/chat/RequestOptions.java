package org.agty.aiassistant.chat;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Immutable request scope; changing it never writes global Codex configuration. */
public record RequestOptions(Path directory, String sessionId, String model, String effort,
                             Access access, Mode mode, List<Attachment> attachments, boolean fork, boolean interactiveApprovals, com.google.gson.JsonObject reviewTarget, boolean compact, String serviceTier, String cyberAccessProgram) {
    public RequestOptions(Path directory, String sessionId, String model, String effort, Access access, Mode mode,
                          List<Attachment> attachments, boolean fork, boolean interactiveApprovals, com.google.gson.JsonObject reviewTarget, boolean compact) {
        this(directory, sessionId, model, effort, access, mode, attachments, fork, interactiveApprovals, reviewTarget, compact, "", "");
    }
    public RequestOptions withExtras(String tier, String cyber) { return new RequestOptions(directory, sessionId, model, effort, access, mode, attachments, fork, interactiveApprovals, reviewTarget, compact, tier, cyber); }
    public RequestOptions(Path directory, String sessionId, String model, String effort, Access access, Mode mode,
                          List<Attachment> attachments, boolean fork, boolean interactiveApprovals, com.google.gson.JsonObject reviewTarget) {
        this(directory, sessionId, model, effort, access, mode, attachments, fork, interactiveApprovals, reviewTarget, false);
    }
    public RequestOptions(Path directory, String sessionId, String model, String effort, Access access, Mode mode,
                          List<Attachment> attachments, boolean fork, boolean interactiveApprovals) {
        this(directory, sessionId, model, effort, access, mode, attachments, fork, interactiveApprovals, null);
    }
    public RequestOptions withSession(String id) { return new RequestOptions(directory, id, model, effort, access, mode, attachments, fork, interactiveApprovals, reviewTarget, compact, serviceTier, cyberAccessProgram); }
    public RequestOptions(Path directory, String sessionId, String model, String effort, Access access, Mode mode,
                          List<Attachment> attachments, boolean fork) {
        this(directory, sessionId, model, effort, access, mode, attachments, fork, false);
    }
    @Override public com.google.gson.JsonObject reviewTarget() { return reviewTarget == null ? null : reviewTarget.deepCopy(); }
    public enum Access { READ_ONLY, WORKSPACE_WRITE, FULL }
    public enum Mode { WORK, PLAN }
    public record Attachment(String kind, String value) {
        public Attachment { Objects.requireNonNull(kind); Objects.requireNonNull(value); }
    }
    public RequestOptions {
        directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        Objects.requireNonNull(sessionId); Objects.requireNonNull(model); Objects.requireNonNull(effort);
        Objects.requireNonNull(access); Objects.requireNonNull(mode); attachments = List.copyOf(attachments);
        Objects.requireNonNull(serviceTier); Objects.requireNonNull(cyberAccessProgram);
        if (reviewTarget != null) reviewTarget = reviewTarget.deepCopy();
        if (compact && reviewTarget != null) throw new IllegalArgumentException("Compaction и review — отдельные операции.");
    }
}
