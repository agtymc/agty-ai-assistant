package org.agty.aiassistant.core;

import org.agty.aiassistant.chat.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in account/network smoke in an isolated directory. No repository files are supplied. */
@EnabledIfSystemProperty(named = "real.codex.smoke", matches = "true")
@Timeout(180)
class RealCodexSmokeTest {
    @TempDir Path root;
    @Test void realBaselineCompletesReadOnlyTurnWithoutChangingFiles() throws Exception {
        Path sentinel = root.resolve("sentinel.txt"); Files.writeString(sentinel, "Preserve this file.\n");
        var events = new ArrayList<ChatEvent>();
        try (var pool = new CodexConnectionPool()) {
            var provider = new CodexChatProvider("codex", pool);
            var options = new RequestOptions(root, "", "", "", RequestOptions.Access.READ_ONLY,
                    RequestOptions.Mode.WORK, List.of(), false, true);
            provider.newRequest(options).run(root, "Reply exactly AGTY_SMOKE_OK. Do not use tools. Do not read or modify files.", event -> {
                events.add(event);
                if (event.kind() == ChatEvent.Kind.SERVER_REQUEST) fail("Unexpected tool permission request in read-only smoke");
            });
            assertTrue(events.stream().anyMatch(event -> event.kind() == ChatEvent.Kind.MESSAGE && event.text().contains("AGTY_SMOKE_OK")), "Expected public response");
            assertFalse(events.stream().anyMatch(event -> event.kind() == ChatEvent.Kind.ERROR), "Native turn failed");
            assertEquals(ChatEvent.Kind.DONE, events.getLast().kind());
        }
        assertEquals("Preserve this file.\n", Files.readString(sentinel));
        try (var files = Files.list(root)) { assertEquals(List.of(sentinel), files.toList()); }
    }
}
