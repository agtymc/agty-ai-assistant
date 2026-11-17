package org.agty.aiassistant.chat;

import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectIdentityTest {
    @TempDir Path directory;

    @Test void copiedWorkspaceCannotShareHistoryPermissionsOrContextWithOriginalProject() throws Exception {
        Path original = Files.createDirectory(directory.resolve("original"));
        Path copy = Files.createDirectory(directory.resolve("copy"));
        var sourceIdentity = new ProjectIdentity(); var copyIdentity = new ProjectIdentity();
        try {
            String sourceId = sourceIdentity.stableId(project(original));
            copyIdentity.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(sourceIdentity.getState()), ProjectIdentity.Data.class));
            var store = new ChatSessions();
            var source = store.project(sourceId); source.unrestrictedAccess = true;
            var privateContext = new ChatSessions.ContextSource(); privateContext.text = "private original context";
            source.current().contextSources.add(privateContext); source.current().draft = "private original draft";
            String copyId = copyIdentity.stableId(project(copy));
            assertNotEquals(sourceId, copyId);
            var isolated = store.project(copyId, copyIdentity.migrationCandidates(project(copy)));
            assertNotSame(source, isolated); assertFalse(isolated.unrestrictedAccess);
            assertEquals("", isolated.current().draft); assertTrue(isolated.current().contextSources.isEmpty());
            assertFalse(copyIdentity.migrationCandidates(project(copy)).contains(original.toRealPath().toString()));
            assertEquals(sourceId, sourceIdentity.stableId(project(original)));
        } finally { sourceIdentity.dispose(); copyIdentity.dispose(); }
    }

    @Test void copiedProjectIsIsolatedEvenWhenItOpensBeforeTheOriginal() throws Exception {
        Path original = Files.createDirectory(directory.resolve("original"));
        Path copy = Files.createDirectory(directory.resolve("copy"));
        var sourceIdentity = new ProjectIdentity();
        String sourceId = sourceIdentity.stableId(project(original));
        var saved = XmlSerializer.deserialize(XmlSerializer.serialize(sourceIdentity.getState()), ProjectIdentity.Data.class);
        sourceIdentity.dispose();
        var copyIdentity = new ProjectIdentity(); copyIdentity.loadState(saved);
        try { assertNotEquals(sourceId, copyIdentity.stableId(project(copy))); }
        finally { copyIdentity.dispose(); }
    }

    @Test void closedProjectMovePreservesIdentityAndLegacyMigration() throws Exception {
        Path original = Files.createDirectory(directory.resolve("original"));
        String originalCanonical = original.toRealPath().toString();
        var sourceIdentity = new ProjectIdentity();
        String sourceId = sourceIdentity.stableId(project(original));
        var saved = XmlSerializer.deserialize(XmlSerializer.serialize(sourceIdentity.getState()), ProjectIdentity.Data.class);
        sourceIdentity.dispose();
        Path moved = directory.resolve("moved"); Files.move(original, moved);
        var movedIdentity = new ProjectIdentity(); movedIdentity.loadState(saved);
        try {
            assertEquals(sourceId, movedIdentity.stableId(project(moved)));
            assertTrue(movedIdentity.migrationCandidates(project(moved)).contains(saved.legacyDirectories.getFirst()));
            assertEquals(originalCanonical, saved.legacyDirectories.getFirst());
        } finally { movedIdentity.dispose(); }
    }

    @Test void activeIdentityCannotBeReusedEvenIfItsOldDirectoryHasDisappeared() throws Exception {
        Path original = Files.createDirectory(directory.resolve("original"));
        var sourceIdentity = new ProjectIdentity(); var copyIdentity = new ProjectIdentity();
        try {
            String sourceId = sourceIdentity.stableId(project(original));
            copyIdentity.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(sourceIdentity.getState()), ProjectIdentity.Data.class));
            Path moved = directory.resolve("moved"); Files.move(original, moved);
            assertNotEquals(sourceId, copyIdentity.stableId(project(moved)));
        } finally { sourceIdentity.dispose(); copyIdentity.dispose(); }
    }

    private static Project project(Path directory) {
        return (Project) java.lang.reflect.Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[]{Project.class}, (proxy, method, arguments) -> {
            if (method.getName().equals("getBasePath")) return directory.toAbsolutePath().toString();
            throw new UnsupportedOperationException(method.getName());
        });
    }
}
