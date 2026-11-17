package org.agty.aiassistant.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CodexConnectionPoolTest {
    @TempDir Path directory;
    private List<String> command() {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("fixture.classes"), RpcFixture.class.getName(), "rpc");
    }
    @Test void connectionLivesAcrossTurnsAndDiesOnlyWithItsProject() throws Exception {
        var pool = new CodexConnectionPool();
        var client = pool.acquire(command(), directory);
        assertSame(client, pool.acquire(command(), directory));
        assertTrue(client.alive());
        pool.close();
        assertFalse(client.alive());
        assertThrows(IllegalStateException.class, () -> pool.acquire(command(), directory));
    }
    @Test void concurrentChatsShareConnectionAndCancellingOneDoesNotStopTheOther() throws Exception {
        var command = new java.util.ArrayList<>(command()); command.set(command.size() - 1, "multiplex");
        try (var pool = new CodexConnectionPool(); var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var client = pool.acquire(command, directory);
            var provider = new CodexChatProvider(command, pool);
            var first = provider.newRequest(); var second = provider.newRequest();
            var firstEvents = new java.util.concurrent.CopyOnWriteArrayList<org.agty.aiassistant.chat.ChatEvent>();
            var secondEvents = new java.util.concurrent.CopyOnWriteArrayList<org.agty.aiassistant.chat.ChatEvent>();
            var streamed = new java.util.concurrent.CountDownLatch(2);
            var a = executor.submit(() -> {
                assertThrows(java.util.concurrent.CancellationException.class, () -> first.run(directory, "a", event -> {
                    firstEvents.add(event); if (event.kind() == org.agty.aiassistant.chat.ChatEvent.Kind.MESSAGE) streamed.countDown();
                })); return null;
            });
            var b = executor.submit(() -> { second.run(directory, "b", event -> {
                secondEvents.add(event); if (event.kind() == org.agty.aiassistant.chat.ChatEvent.Kind.MESSAGE && !event.complete()) streamed.countDown();
            }); return null; });
            assertTrue(streamed.await(3, java.util.concurrent.TimeUnit.SECONDS)); first.cancel();
            a.get(); b.get();
            assertSame(client, pool.acquire(command, directory)); assertTrue(client.alive());
            assertEquals(org.agty.aiassistant.chat.ChatEvent.Kind.DONE, secondEvents.getLast().kind());
            var firstId = firstEvents.stream().filter(e -> e.kind() == org.agty.aiassistant.chat.ChatEvent.Kind.SESSION).findFirst().orElseThrow().text();
            assertTrue(secondEvents.stream().filter(e -> e.kind() == org.agty.aiassistant.chat.ChatEvent.Kind.MESSAGE).noneMatch(e -> e.text().equals(firstId)));
        }
    }
    @Test void subscribersIndependentlyReceiveNotifications() throws Exception {
        try (var pool = new CodexConnectionPool()) {
            var client = pool.acquire(command(), directory);
            try (var a = client.subscribe(); var b = client.subscribe();
                 var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> client.call("fixture/first", new com.google.gson.JsonObject()));
                client.call("fixture/second", new com.google.gson.JsonObject());
                first.get();
                assertEquals("fixture/event", a.next().get("method").getAsString());
                assertEquals("fixture/event", b.next().get("method").getAsString());
            }
        }
    }
}
