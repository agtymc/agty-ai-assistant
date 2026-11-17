package org.agty.aiassistant.ui;

import com.intellij.diff.*;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.openapi.application.*;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.*;
import com.intellij.openapi.vfs.*;
import com.intellij.ui.components.*;
import org.agty.aiassistant.changes.*;
import org.agty.aiassistant.execution.TaskRunner;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.List;

/** Task changes and Git changes are distinct views. Already applied changes are never reapplied. */
final class ChangesDialog extends DialogWrapper {
    private final Project project;
    private final TaskRunner runner;
    private final TaskRunner.Task task;
    private final Path root;
    private final DefaultListModel<WorkspaceChanges.Change> taskFiles = new DefaultListModel<>();
    private final DefaultListModel<WorkingTreeChanges.Entry> gitFiles = new DefaultListModel<>();
    private final JList<WorkspaceChanges.Change> taskList = new JBList<>(taskFiles);
    private final JList<WorkingTreeChanges.Entry> gitList = new JBList<>(gitFiles);
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel status = new JLabel(" ");
    ChangesDialog(Project project, Path root, TaskRunner runner, TaskRunner.Task task) {
        super(project, false); this.project = project; this.root = root; this.runner = runner; this.task = task;
        setTitle("Изменения кода"); setModal(false);
        tabs.addTab("Изменения задачи", new JBScrollPane(taskList)); tabs.addTab("Рабочее дерево Git", new JBScrollPane(gitList));
        init(); reloadTask(); loadGit();
    }
    @Override protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4)); panel.add(tabs, BorderLayout.CENTER); panel.add(status, BorderLayout.SOUTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        button(buttons, "Открыть сравнение", this::compare);
        button(buttons, "Откатить файл", () -> rollback(false, false));
        button(buttons, "Откатить hunk", () -> rollback(false, true));
        button(buttons, "Откатить задачу", () -> rollback(true, false));
        button(buttons, "Обновить Git", this::loadGit); panel.add(buttons, BorderLayout.NORTH); panel.setPreferredSize(new Dimension(840, 420));
        return panel;
    }
    @Override protected Action[] createActions() { return new Action[]{getCancelAction()}; }
    private static void button(JPanel panel, String title, Runnable action) { JButton button = new JButton(title); button.addActionListener(e -> action.run()); panel.add(button); }
    private void reloadTask() {
        taskFiles.clear();
        if (task != null && task.workspace != null) task.workspace.changes().forEach(taskFiles::addElement);
        if (!taskFiles.isEmpty()) taskList.setSelectedIndex(0);
        status.setText(task == null ? "Нет локальной базы задачи; история Codex не предоставляет право на автоматический откат."
                : task.active() ? "Задача ещё выполняется. Откат доступен после завершения." : task.changeWarning.isBlank() ? "Откат требует совпадения итоговой версии и отсутствия пользовательских правок." : task.changeWarning);
    }
    private void loadGit() {
        background(() -> {
            List<WorkingTreeChanges.Entry> entries = WorkingTreeChanges.list(root);
            ApplicationManager.getApplication().invokeLater(() -> { if (!isDisposed()) { gitFiles.clear(); entries.forEach(gitFiles::addElement); if (!gitFiles.isEmpty()) gitList.setSelectedIndex(0); } });
        });
    }
    private void compare() {
        if (tabs.getSelectedIndex() == 0) {
            var change = taskList.getSelectedValue(); if (change == null) return;
            background(() -> showDiff(change.source() + " → " + change.target(), WorkspaceChanges.bytes(change.before()), WorkspaceChanges.bytes(change.after()), change.target()));
        } else {
            var entry = gitList.getSelectedValue(); if (entry == null) return;
            background(() -> { var pair = WorkingTreeChanges.contents(root, entry); showDiff(entry.toString(), pair.before(), pair.after(), entry.path()); });
        }
    }
    private void showDiff(String title, byte[] before, byte[] after, String path) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) return;
            WriteIntentReadAction.run((Runnable) () -> {
                try {
                    var factory = DiffContentFactory.getInstance(); var type = FileTypeManager.getInstance().getFileTypeByFileName(path);
                    DiffManager.getInstance().showDiff(project, new SimpleDiffRequest(title,
                            factory.createFromBytes(project, before, type, path), factory.createFromBytes(project, after, type, path), "До", "После"));
                } catch (Exception e) { Messages.showErrorDialog(project, e.getMessage(), "Сравнение"); }
            });
        });
    }
    private void rollback(boolean wholeTask, boolean hunk) {
        if (tabs.getSelectedIndex() != 0 || task == null || task.workspace == null || task.active()) { status.setText("Откат доступен только для завершённой локальной задачи."); return; }
        List<WorkspaceChanges.Change> selected = wholeTask ? task.workspace.changes() : taskList.getSelectedValuesList();
        if (selected.isEmpty()) return;
        if (selected.stream().anyMatch(change -> !change.proven())) { status.setText("Нет достоверной базы; откройте сравнение."); compare(); return; }
        if (Messages.showYesNoDialog(project, "Откатить " + (hunk ? "выбранный фрагмент" : wholeTask ? "изменения задачи" : "выбранный файл")
                        + "? Созданные файлы будут удалены, удалённые восстановлены, переименования обращены. История диалога сохранится.",
                "Откат файлов", Messages.getQuestionIcon()) != Messages.YES) return;
        background(() -> {
            int hunkIndex = -1;
            if (hunk) {
                if (selected.size() != 1) throw new IllegalStateException("Выберите один файл.");
                var hunks = StrictPatch.hunks(task.workspace.diff(selected.getFirst()));
                if (hunks.isEmpty()) throw new IllegalStateException("Для этого файла нет текстовых hunks.");
                var chosen = new java.util.concurrent.atomic.AtomicInteger(-1);
                ApplicationManager.getApplication().invokeAndWait(() -> {
                    JList<String> options = new JList<>(hunks.toArray(String[]::new)); options.setSelectedIndex(0);
                    if (JOptionPane.showConfirmDialog(getContentPane(), new JBScrollPane(options), "Выберите hunk для отката", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) chosen.set(options.getSelectedIndex());
                });
                hunkIndex = chosen.get(); if (hunkIndex < 0) return;
            }
            int index = hunkIndex;
            runner.withWorkspaceLock(root, () -> {
                var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
                ApplicationManager.getApplication().invokeAndWait(() -> WriteAction.run(() -> {
                    try {
                        var documents = FileDocumentManager.getInstance();
                        var paths = new java.util.HashSet<Path>();
                        for (var change : selected) for (String name : List.of(change.source(), change.target())) paths.add(task.workspace.root().resolve(name).normalize());
                        for (var document : documents.getUnsavedDocuments()) {
                            var file = documents.getFile(document);
                            if (file != null && file.isInLocalFileSystem() && paths.contains(WorkspaceChanges.canonical(file.toNioPath())))
                                throw new IllegalStateException("Есть несохранённые документы. Откройте сравнение; автоматический откат запрещён.");
                        }
                        // Keep document edits excluded through validation, writes and VFS refresh.
                        if (hunk) task.workspace.rollbackHunk(selected.getFirst(), index); else task.workspace.rollback(selected);
                        for (var change : selected) for (String name : List.of(change.source(), change.target()))
                            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve(name));
                    } catch (Exception e) { failure.set(e); }
                }));
                if (failure.get() != null) throw failure.get();
            });
            ApplicationManager.getApplication().invokeLater(() -> { if (!isDisposed()) { reloadTask(); loadGit(); VirtualFileManager.getInstance().asyncRefresh(null); } });
        });
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private void background(Operation operation) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { operation.run(); }
            catch (Exception e) { ApplicationManager.getApplication().invokeLater(() -> { if (!isDisposed()) status.setText(e.getMessage()); }); }
        });
    }
}
