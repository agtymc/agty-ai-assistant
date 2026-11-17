package org.agty.aiassistant.ui;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.TestApplicationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManualPatchSafetyTest {
    @TempDir Path root;
    @Test void unsavedEditorContentBlocksManualApplyAndUndoWithoutBeingSavedOrDiscarded() throws Exception {
        TestApplicationManager.getInstance();
        Path path = root.resolve("file.txt"); Files.writeString(path, "disk before\n");
        EdtTestUtil.runInEdtAndWait(() -> {
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path); assertNotNull(file);
            var documents = FileDocumentManager.getInstance();
            var document = ReadAction.compute(() -> documents.getDocument(file)); assertNotNull(document);
            try {
                WriteCommandAction.runWriteCommandAction(ProjectManager.getInstance().getDefaultProject(), () -> document.setText("unsaved user data\n"));
                WriteAction.run(() -> assertThrows(IllegalStateException.class,
                        () -> AiAssistantChatPanel.requireSavedPatchDocuments(List.of(path))));
                assertEquals("unsaved user data\n", document.getText());
                assertTrue(documents.isDocumentUnsaved(document));
                assertDoesNotThrow(() -> assertEquals("disk before\n", Files.readString(path)));
            } finally { WriteAction.run(() -> documents.reloadFromDisk(document)); }
        });
    }
}
