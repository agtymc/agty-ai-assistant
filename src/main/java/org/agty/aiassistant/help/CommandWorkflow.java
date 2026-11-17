package org.agty.aiassistant.help;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Steps are inert examples. Inserting a step never runs a command. */
public final class CommandWorkflow {
    public record Step(String kind, String title, String text) { }
    public static final List<Step> STEPS = read();
    private static List<Step> read() {
        try (var input = Objects.requireNonNull(CommandWorkflow.class.getResourceAsStream("/commands/workflow.json"));
             var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return List.of(new Gson().fromJson(reader, Step[].class));
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private CommandWorkflow() { }
}
