package org.agty.aiassistant.core;

import java.nio.charset.StandardCharsets;

public class ProcessFixture {
    public static void main(String[] args) throws Exception {
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
