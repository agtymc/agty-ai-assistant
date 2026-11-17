package org.agty.aiassistant.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Standalone fake app-server: no IDE, auth, network, or model dependencies. */
public final class RpcFixture {
    private static final String THREAD = "019e309f-5fe7-7a93-90d0-ce794686cdfd";
    private static final String FORK = "019e309f-5fe7-7a93-90d0-ce794686cdfa";
    private static String currentThread = THREAD;
    private static String fixtureMode = "";
    private static String heldId;
    private static String secondId;
    private static int threadSequence;
    private static final java.util.Set<String> interrupted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static void emit(String value) {
        if (value.contains("\"model\":") && value.contains("\"thread\":{") && !value.contains("\"sandbox\":")) {
            String cwd = Path.of(".").toAbsolutePath().normalize().toString().replace("\\", "\\\\");
            String type = fixtureMode.equals("review-wide") ? "dangerFullAccess" : "readOnly";
            value = value.replace("\"result\":{", "\"result\":{\"cwd\":\"" + cwd + "\",\"sandbox\":{\"type\":\"" + type
                    + "\",\"networkAccess\":" + fixtureMode.equals("review-network") + "},");
        }
        System.out.println(value); System.out.flush();
    }
    private static void event(String method, String fields) {
        emit("{\"method\":\"" + method + "\",\"params\":{\"threadId\":\"" + currentThread + "\"," + fields + "}}");
    }
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        String mode = args[0]; fixtureMode = mode;
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                var match = Pattern.compile("\"id\":(\\d+)").matcher(line);
                if (!match.find()) continue;
                String id = match.group(1);
                if (line.contains("\"initialize\"")) emit("{\"id\":" + id + ",\"result\":{}}");
                else if (line.contains("\"review/start\"")) {
                    if (!line.contains("\"target\":{\"type\":\"uncommittedChanges\"}")) System.exit(24);
                    emit("{\"id\":" + id + ",\"result\":{\"turn\":{\"id\":\"review-1\"},\"reviewThreadId\":\"" + currentThread + "\"}}");
                    event("item/completed", "\"turnId\":\"review-1\",\"item\":{\"id\":\"review\",\"type\":\"agentMessage\",\"text\":\"Review OK\"}");
                    event("turn/completed", "\"turn\":{\"id\":\"review-1\",\"status\":\"completed\"}");
                }
                else if (line.contains("\"thread/compact/start\"")) {
                    emit("{\"id\":" + id + ",\"result\":{}}");
                    if (mode.equals("compact-hang")) java.nio.file.Files.writeString(Path.of("compact-started"), "started");
                    else event("thread/compacted", "\"turnId\":\"compact-1\"");
                }
                else if (line.contains("\"thread/turns/list\"")) {
                    emit("{\"id\":" + id + ",\"result\":{\"data\":[{\"id\":\"observed-turn\",\"status\":\"completed\",\"items\":[{\"id\":\"observed-item\",\"type\":\"agentMessage\",\"text\":\"Observed without turn/start\"}]}]}}");
                }
                else if (line.contains("\"thread/items/list\"")) {
                    boolean second = line.contains("\"cursor\":\"older\"");
                    emit("{\"id\":" + id + ",\"result\":{\"data\":[{\"turnId\":\"" + (second ? "old" : "new")
                            + "\",\"item\":{\"id\":\"same\",\"type\":\"agentMessage\",\"text\":\"" + (second ? "Earlier" : "Latest") + "\"}}],\"nextCursor\":"
                            + (second ? "null" : "\"older\"") + "}}");
                }
                else if (mode.startsWith("multiplex") && line.contains("\"thread/start\"")) {
                    String thread = threadSequence++ == 0 ? THREAD : FORK;
                    emit("{\"id\":" + id + ",\"result\":{\"model\":\"fixture\",\"thread\":{\"id\":\"" + thread + "\"}}}");
                } else if (mode.startsWith("multiplex") && (line.contains("\"turn/start\"") || line.contains("\"turn/interrupt\""))) {
                    var threadMatch = Pattern.compile("\"threadId\":\"([^\"]+)\"").matcher(line);
                    if (!threadMatch.find()) System.exit(21);
                    String thread = threadMatch.group(1), turn = "turn-" + thread;
                    if (line.contains("turn/interrupt")) {
                        interrupted.add(thread);
                        if (mode.equals("multiplex-reject")) emit("{\"id\":" + id + ",\"error\":{\"message\":\"Turn already finishing\"}}");
                        else emit("{\"id\":" + id + ",\"result\":{}}");
                        Thread.startVirtualThread(() -> {
                            try {
                                Thread.sleep(200);
                                java.nio.file.Files.writeString(Path.of("interrupt-finished-" + thread), "stopped");
                                emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"" + thread
                                        + "\",\"turn\":{\"id\":\"" + turn + "\",\"status\":\"interrupted\"}}}");
                            } catch (Exception e) { throw new RuntimeException(e); }
                        });
                    } else {
                        emit("{\"id\":" + id + ",\"result\":{\"turn\":{\"id\":\"" + turn + "\"}}}");
                        Thread.startVirtualThread(() -> {
                            emit("{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"" + thread
                                    + "\",\"turnId\":\"" + turn + "\",\"itemId\":\"same-id\",\"delta\":\"" + thread + "\"}}");
                            try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                            if (!interrupted.contains(thread)) {
                                emit("{\"method\":\"item/completed\",\"params\":{\"threadId\":\"" + thread
                                        + "\",\"turnId\":\"" + turn + "\",\"item\":{\"id\":\"same-id\",\"type\":\"agentMessage\",\"text\":\"" + thread + "\"}}}");
                                emit("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"" + thread
                                        + "\",\"turn\":{\"id\":\"" + turn + "\",\"status\":\"completed\"}}}");
                            }
                        });
                    }
                }
                else if ((mode.equals("interactive") || mode.equals("write-e2e") || mode.equals("reconnect-approval")) && id.equals("900")) {
                    if (!line.contains("\"decision\":\"accept\"")) System.exit(20);
                    event("serverRequest/resolved", "\"requestId\":900");
                    if (mode.equals("write-e2e")) {
                        java.nio.file.Files.writeString(Path.of("task.txt"), "agent result\n");
                        event("item/completed", "\"turnId\":\"turn-1\",\"item\":{\"id\":\"change\",\"type\":\"fileChange\",\"status\":\"completed\",\"changes\":[{\"path\":\"task.txt\",\"kind\":{\"type\":\"update\"},\"diff\":\"@@ -1 +1 @@\\n-user draft\\n+agent result\\n\"}]}");
                    }
                    event("item/completed", "\"turnId\":\"turn-1\",\"item\":{\"id\":\"answer\",\"type\":\"agentMessage\",\"text\":\"Approved\"}");
                    event("turn/completed", "\"turn\":{\"id\":\"turn-1\",\"status\":\"completed\"}");
                }
                else if (line.contains("\"fixture/pid\"")) {
                    emit("{\"id\":" + id + ",\"result\":{\"pid\":" + ProcessHandle.current().pid() + "}}");
                }
                else if (line.contains("\"fixture/first\"") || line.contains("\"fixture/second\"")) {
                    if (line.contains("fixture/first")) heldId = id; else secondId = id;
                    if (heldId != null && secondId != null) {
                        emit("{\"id\":" + secondId + ",\"result\":{\"value\":2}}");
                        emit("{\"method\":\"fixture/event\",\"params\":{}}");
                        emit("{\"id\":" + heldId + ",\"result\":{\"value\":1}}");
                    }
                }
                else if (line.contains("\"fixture/burst-pending\"")) {
                    emit("{\"id\":" + id + ",\"result\":{}}");
                    for (int requestId = 1000; requestId < 1140; requestId++) emit("{\"id\":" + requestId + ",\"method\":\"item/commandExecution/requestApproval\",\"params\":{\"threadId\":\"" + THREAD + "\",\"turnId\":\"turn\",\"itemId\":\"command\",\"startedAtMs\":0}}");
                }
                else if (line.contains("\"fixture/pending\"")) {
                    for (int requestId : new int[]{910, 911}) emit("{\"id\":" + requestId + ",\"method\":\"item/commandExecution/requestApproval\",\"params\":{\"threadId\":\"" + THREAD + "\",\"turnId\":\"turn\",\"itemId\":\"command\",\"startedAtMs\":0}}");
                    emit("{\"id\":" + id + ",\"result\":{}}");
                }
                else if (line.contains("\"fixture/reuse-resolved\"")) {
                    emit("{\"id\":910,\"method\":\"item/commandExecution/requestApproval\",\"params\":{\"threadId\":\"" + FORK + "\",\"turnId\":\"other-turn\",\"itemId\":\"other-command\",\"startedAtMs\":0}}");
                    emit("{\"id\":" + id + ",\"result\":{}}");
                }
                else if (mode.equals("reconnect-approval") && line.contains("\"turn/steer\"")) {
                    java.nio.file.Files.writeString(Path.of(".rpc-reconnect-started"), "started");
                    emit("{\"id\":" + id + ",\"result\":{}}"); System.exit(7);
                }
                else if (line.contains("\"fixture/resolve\"")) {
                    event("serverRequest/resolved", "\"requestId\":910"); emit("{\"id\":" + id + ",\"result\":{}}");
                }
                else if (line.contains("\"fixture/crash\"")) { System.exit(7); }
                else if (line.contains("\"model/list\"")) {
                    if (line.contains("\"cursor\":\"page2\""))
                        emit("{\"id\":" + id + ",\"result\":{\"data\":[{\"id\":\"b\",\"model\":\"engine-b\",\"displayName\":\"Model B\",\"defaultReasoningEffort\":\"high\",\"supportedReasoningEfforts\":[{\"reasoningEffort\":\"high\",\"description\":\"Thorough\"},{\"reasoningEffort\":\"future-level\",\"description\":\"Future\"}]}],\"nextCursor\":null}}");
                    else emit("{\"id\":" + id + ",\"result\":{\"data\":[{\"id\":\"a\",\"model\":\"engine-a\",\"displayName\":\"Model A\"},{\"id\":\"hidden\",\"model\":\"hidden\",\"hidden\":true}],\"nextCursor\":\"page2\"}}");
                }
                else if (line.contains("\"thread/read\"")) {
                    String cwd = Path.of(".").toRealPath().toString().replace("\\", "\\\\");
                    emit("{\"id\":" + id + ",\"result\":{\"thread\":{\"id\":\"" + THREAD + "\",\"cwd\":\"" + cwd + "\",\"turns\":[]}}}");
                } else if (line.contains("\"thread/start\"") || line.contains("\"thread/resume\"") || line.contains("\"thread/fork\"")) {
                    if (mode.equals("model") && !line.contains("\"model\":\"engine-b\"")) System.exit(10);
                    if (mode.equals("fork") && (!line.contains("\"thread/fork\"") || !line.contains("\"model\":\"engine-b\""))) System.exit(13);
                    if (mode.equals("observe")) {
                        if (line.contains("\"sandbox\"") || line.contains("\"approvalPolicy\"")) System.exit(26);
                        emit("{\"id\":" + id + ",\"result\":{\"model\":\"fixture\",\"thread\":{\"id\":\"" + currentThread + "\"}}}"); continue;
                    }
                    String expected = mode.equals("full") ? "danger-full-access" : (mode.equals("write") || mode.equals("write-e2e")) ? "workspace-write" : "read-only";
                    if (!line.contains("\"sandbox\":\"" + expected + "\"") || !line.contains("\"approvalPolicy\":\"" + ((mode.equals("interactive") || mode.equals("write-e2e")) ? "on-request" : "never") + "\"")) System.exit(8);
                    if (mode.equals("image-swap")) java.nio.file.Files.writeString(Path.of(".aiignore"), "sample.png\n");
                    String responseModel = mode.equals("mismatch") ? "engine-a" : mode.equals("no-model") ? "" : mode.equals("model") || mode.equals("rerouted") || mode.equals("fork") || mode.equals("image-swap") ? "engine-b" : "fixture";
                    currentThread = mode.equals("fork") ? FORK : THREAD;
                    if (mode.startsWith("reconnect") && line.contains("\"thread/resume\"")) {
                        String snapshot = mode.equals("reconnect-active")
                                ? "\"status\":{\"type\":\"active\",\"activeFlags\":[]},\"turns\":[]"
                                : "\"turns\":[{\"id\":\"turn-1\",\"status\":\"inProgress\",\"items\":[{\"id\":\"answer\",\"type\":\"agentMessage\",\"text\":\"Привет\"}]}]";
                        emit("{\"id\":" + id + ",\"result\":{\"model\":\"fixture\",\"thread\":{\"id\":\"" + currentThread
                                + "\"," + snapshot + "}}}");
                        if (mode.equals("reconnect-approval")) {
                            emit("{\"id\":900,\"method\":\"item/commandExecution/requestApproval\",\"params\":{\"threadId\":\"" + currentThread
                                    + "\",\"turnId\":\"turn-1\",\"itemId\":\"new-command\",\"command\":\"new operation\",\"startedAtMs\":0}}");
                            continue;
                        }
                        event("item/completed", "\"turnId\":\"turn-1\",\"item\":{\"id\":\"answer\",\"type\":\"agentMessage\",\"text\":\"Привет после восстановления\"}");
                        event("turn/completed", "\"turn\":{\"id\":\"turn-1\",\"status\":\"completed\"}");
                    } else {
                        emit("{\"id\":" + id + ",\"result\":{\"model\":\"" + responseModel + "\",\"thread\":{\"id\":\"" + currentThread + "\"}}}");
                    }
                } else if (line.contains("\"turn/start\"")) {
                    if (mode.equals("lost-start-ack")) {
                        java.nio.file.Files.writeString(Path.of("writer-pid"), Long.toString(ProcessHandle.current().pid()));
                        System.out.close(); Thread.sleep(60_000); return;
                    }
                    if (mode.equals("review") || mode.equals("compact") || mode.equals("observe")) System.exit(25);
                    Path reconnectMarker = Path.of(".rpc-reconnect-started");
                    if (mode.startsWith("reconnect") && java.nio.file.Files.exists(reconnectMarker)) System.exit(15);
                    if (mode.equals("fork") && !line.contains("\"threadId\":\"" + FORK + "\"")) System.exit(14);
                    if (mode.equals("model") && !line.contains("\"effort\":\"high\"")) System.exit(12);
                    if (mode.equals("model") && !line.contains("\"model\":\"engine-b\"")) System.exit(11);
                    String expected = mode.equals("full") ? "dangerFullAccess" : (mode.equals("write") || mode.equals("write-e2e")) ? "workspaceWrite" : "readOnly";
                    if (!line.contains("\"type\":\"" + expected + "\"")
                            || mode.equals("full") == line.contains("\"networkAccess\":false")) System.exit(9);
                    emit("{\"id\":" + id + ",\"result\":{\"turn\":{\"id\":\"turn-1\"}}}");
                    if (mode.equals("interactive") || mode.equals("write-e2e") || mode.equals("reconnect-approval")) {
                        emit("{\"id\":900,\"method\":\"item/commandExecution/requestApproval\",\"params\":{\"threadId\":\"" + currentThread
                                + "\",\"turnId\":\"turn-1\",\"itemId\":\"cmd\",\"command\":\"echo test\",\"startedAtMs\":0}}");
                        continue;
                    }
                    if (mode.equals("rerouted")) event("model/rerouted", "\"turnId\":\"turn-1\",\"fromModel\":\"engine-b\",\"toModel\":\"engine-a\",\"reason\":\"highRiskCyberActivity\"");
                    event("item/agentMessage/delta", "\"turnId\":\"turn-1\",\"itemId\":\"answer\",\"delta\":\"Привет\"");
                    if (mode.startsWith("reconnect")) {
                        java.nio.file.Files.writeString(reconnectMarker, "started");
                        System.exit(7);
                    }
                    if (mode.equals("hang")) { Thread.sleep(60_000); return; }
                    if (mode.equals("crash")) System.exit(7);
                    Thread.sleep(150);
                    event("item/agentMessage/delta", "\"turnId\":\"turn-1\",\"itemId\":\"answer\",\"delta\":\", мир\"");
                    event("item/completed", "\"turnId\":\"turn-1\",\"item\":{\"id\":\"answer\",\"type\":\"agentMessage\",\"text\":\"Привет, мир!\"}");
                    event("thread/tokenUsage/updated", "\"turnId\":\"turn-1\",\"tokenUsage\":{\"last\":{\"totalTokens\":12},\"total\":{\"totalTokens\":20},\"modelContextWindow\":1000}");
                    event("turn/completed", "\"turn\":{\"id\":\"turn-1\",\"status\":\"" + (mode.equals("fail") ? "failed" : "completed") + "\"}");
                }
            }
        }
    }
}
