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
    private static void emit(String value) { System.out.println(value); System.out.flush(); }
    private static void event(String method, String fields) {
        emit("{\"method\":\"" + method + "\",\"params\":{\"threadId\":\"" + currentThread + "\"," + fields + "}}");
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                var match = Pattern.compile("\"id\":(\\d+)").matcher(line);
                if (!match.find()) continue;
                String id = match.group(1);
                if (line.contains("\"initialize\"")) emit("{\"id\":" + id + ",\"result\":{}}");
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
                    String expected = mode.equals("write") ? "workspace-write" : "read-only";
                    if (!line.contains("\"sandbox\":\"" + expected + "\"") || !line.contains("\"approvalPolicy\":\"never\"")) System.exit(8);
                    String responseModel = mode.equals("mismatch") ? "engine-a" : mode.equals("no-model") ? "" : mode.equals("model") || mode.equals("rerouted") || mode.equals("fork") ? "engine-b" : "fixture";
                    currentThread = mode.equals("fork") ? FORK : THREAD;
                    emit("{\"id\":" + id + ",\"result\":{\"model\":\"" + responseModel + "\",\"thread\":{\"id\":\"" + currentThread + "\"}}}");
                } else if (line.contains("\"turn/start\"")) {
                    if (mode.equals("fork") && !line.contains("\"threadId\":\"" + FORK + "\"")) System.exit(14);
                    if (mode.equals("model") && !line.contains("\"effort\":\"high\"")) System.exit(12);
                    if (mode.equals("model") && !line.contains("\"model\":\"engine-b\"")) System.exit(11);
                    String expected = mode.equals("write") ? "workspaceWrite" : "readOnly";
                    if (!line.contains("\"type\":\"" + expected + "\"") || !line.contains("\"networkAccess\":false")) System.exit(9);
                    emit("{\"id\":" + id + ",\"result\":{\"turn\":{\"id\":\"turn-1\"}}}");
                    if (mode.equals("rerouted")) event("model/rerouted", "\"turnId\":\"turn-1\",\"fromModel\":\"engine-b\",\"toModel\":\"engine-a\",\"reason\":\"highRiskCyberActivity\"");
                    event("item/agentMessage/delta", "\"turnId\":\"turn-1\",\"itemId\":\"answer\",\"delta\":\"Привет\"");
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
