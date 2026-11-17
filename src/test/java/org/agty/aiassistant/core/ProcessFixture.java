package org.agty.aiassistant.core;

import java.nio.charset.StandardCharsets;

public class ProcessFixture {
    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        switch (args[0]) {
            case "echo" -> {
                String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
                System.err.println("diagnostic");
                System.out.println(input);
            }
            case "fail" -> { System.err.println("login required"); System.exit(7); }
            case "wait" -> { System.out.println("ready"); System.out.flush(); Thread.sleep(60_000); }
            case "large" -> System.out.println("x".repeat(1_048_577));
            default -> throw new IllegalArgumentException();
        }
    }
}
