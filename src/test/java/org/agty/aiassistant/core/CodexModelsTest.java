package org.agty.aiassistant.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CodexModelsTest {
    @TempDir Path directory;
    @Test void readsAllPagesAndSelectsCanonicalModelRatherThanCatalogId() throws Exception {
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("fixture.classes"), RpcFixture.class.getName(), "catalog");
        try (var client = new CodexSessionClient(command, directory, () -> false)) {
            var models = CodexModels.list(client);
            assertEquals(List.of("engine-a", "engine-b"), models.stream().map(CodexModels.Model::model).toList());
            assertEquals("engine-b", CodexModels.resolve(models, "b").model());
            assertEquals("high", CodexModels.resolve(models, "b").defaultEffort());
            assertEquals(List.of("high", "future-level"), CodexModels.resolve(models, "b").efforts().stream().map(CodexModels.Effort::value).toList());
            assertTrue(CodexModels.resolve(models, "a").efforts().isEmpty());
            assertEquals("engine-a", CodexModels.resolve(models, "engine-a").model());
            assertThrows(IllegalArgumentException.class, () -> CodexModels.resolve(models, "missing"));
        }
    }
}
