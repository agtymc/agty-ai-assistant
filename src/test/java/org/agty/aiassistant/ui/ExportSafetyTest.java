package org.agty.aiassistant.ui;

import com.intellij.openapi.application.*;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ExportSafetyTest {
    @TempDir Path root;
    @Test void unsavedDestinationBlocksExportWithoutSavingOrDiscardingUserText() throws Exception {
        TestApplicationManager.getInstance(); Path path = root.resolve("export.md"); Files.writeString(path, "disk");
        EdtTestUtil.runInEdtAndWait(() -> {
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path); assertNotNull(file);
            var documents = FileDocumentManager.getInstance();
            var document = ReadAction.compute(() -> documents.getDocument(file)); assertNotNull(document);
            try {
                WriteCommandAction.runWriteCommandAction(ProjectManager.getInstance().getDefaultProject(), () -> document.setText("unsaved user data"));
                WriteAction.run(() -> assertThrows(java.io.IOException.class,
                        () -> SessionCommandActions.requireSavedExportDestination(path.toRealPath())));
                assertEquals("unsaved user data", document.getText()); assertTrue(documents.isDocumentUnsaved(document));
                assertDoesNotThrow(() -> assertEquals("disk", Files.readString(path)));
            } finally { WriteAction.run(() -> documents.reloadFromDisk(document)); }
        });
    }
}
