package org.agty.aiassistant.context;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.agty.aiassistant.security.ProjectAccessPolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ProjectContextCollector {
    private static final int MAX_CONTEXT_CHARS = 256 * 1024;

    public static CollectedContext currentEditor(Project project, Path root, ProjectAccessPolicy policy) {
        return ApplicationManager.getApplication().runReadAction(
                (com.intellij.openapi.util.Computable<CollectedContext>) () -> collect(project, root, policy, FileEditorManager.getInstance(project).getSelectedTextEditor()));
    }

    public static CollectedContext editor(Project project, Path root, ProjectAccessPolicy policy, Editor editor) {
        return ApplicationManager.getApplication().runReadAction(
                (com.intellij.openapi.util.Computable<CollectedContext>) () -> collect(project, root, policy, editor));
    }

    public static CollectedContext files(Path root, ProjectAccessPolicy policy, List<VirtualFile> files) {
        List<ContextItem> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (VirtualFile file : files) addFile(root, policy, file, items, notes);
        return new CollectedContext(List.copyOf(items), List.copyOf(notes));
    }

    public static CollectedContext diagnostics(Project project, Path root, ProjectAccessPolicy policy) {
        return ApplicationManager.getApplication().runReadAction(
                (com.intellij.openapi.util.Computable<CollectedContext>) () -> collectDiagnostics(project, root, policy));
    }

    public static CollectedContext gitDiff(Path root, ProjectAccessPolicy policy) {
        List<ContextItem> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        addGitDiff(root, policy, false, items, notes);
        addGitDiff(root, policy, true, items, notes);
        return new CollectedContext(List.copyOf(items), List.copyOf(notes));
    }

    public static CollectedContext validated(Path root, ProjectAccessPolicy policy, List<ContextItem> candidates) {
        List<ContextItem> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (ContextItem item : candidates) {
            if (item.path().isBlank()) {
                notes.add("Контекст без пути пропущен: " + item.title());
                continue;
            }
            // Old combined diffs have no trustworthy per-file provenance. Re-add them
            // using the per-file collector so new ignore rules can be enforced.
            if (item.path().equals(".git-diff.patch") || item.path().equals(".git-diff-staged.patch")) {
                notes.add("Повторно добавьте Git diff: прежний контекст не содержит проверяемых путей файлов.");
                continue;
            }
            ProjectAccessPolicy.Verdict verdict = policy.path(root.resolve(item.path()), false);
            if (!verdict.allowed()) {
                notes.add(verdict.message());
                continue;
            }
            if (item.text().length() > MAX_CONTEXT_CHARS) {
                notes.add("Контекст больше 256 КиБ и не был добавлен: " + item.location());
                continue;
            }
            items.add(item);
        }
        return new CollectedContext(List.copyOf(items), List.copyOf(notes));
    }

    private static CollectedContext collect(Project project, Path root, ProjectAccessPolicy policy, Editor editor) {
        List<ContextItem> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (editor == null) {
            notes.add("Нет активного текстового редактора.");
            return new CollectedContext(List.copyOf(items), List.copyOf(notes));
        }
        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) {
            notes.add("Активный документ не связан с файлом проекта.");
            return new CollectedContext(List.copyOf(items), List.copyOf(notes));
        }
        Path filePath = Path.of(file.getPath());
        ProjectAccessPolicy.Verdict verdict = policy.path(filePath, false);
        if (!verdict.allowed()) {
            notes.add(verdict.message());
            return new CollectedContext(List.copyOf(items), List.copyOf(notes));
        }
        String relative = relative(root, filePath);
        var selection = editor.getSelectionModel();
        int start = selection.hasSelection() ? selection.getSelectionStart() : 0;
        int end = selection.hasSelection() ? selection.getSelectionEnd() : document.getTextLength();
        if (start == end) {
            notes.add("Активный файл пуст или выделение пустое.");
            return new CollectedContext(List.copyOf(items), List.copyOf(notes));
        }
        if (end - start > MAX_CONTEXT_CHARS) {
            notes.add("Активный контекст больше 256 КиБ и не был добавлен: " + relative);
            return new CollectedContext(List.copyOf(items), List.copyOf(notes));
        }
        int startLine = document.getLineNumber(start) + 1;
        int endLine = document.getLineNumber(Math.max(start, end - 1)) + 1;
        boolean unsaved = FileDocumentManager.getInstance().isDocumentUnsaved(document);
        String title = selection.hasSelection() ? "Выделение в редакторе" : unsaved ? "Несохранённый активный файл" : "Активный файл";
        items.add(new ContextItem(title, relative, startLine, endLine, document.getText().substring(start, end)));
        return new CollectedContext(List.copyOf(items), List.copyOf(notes));
    }

    private static CollectedContext collectDiagnostics(Project project, Path root, ProjectAccessPolicy policy) {
        List<ContextItem> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            notes.add("Нет активного текстового редактора для диагностики.");
            return new CollectedContext(List.copyOf(items), List.copyOf(notes));
        }
        Document document = editor.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null) {
            notes.add("Активный документ не связан с файлом проекта.");
            return new CollectedContext(List.copyOf(items), List.copyOf(notes));
        }
        Path filePath = Path.of(file.getPath());
        ProjectAccessPolicy.Verdict verdict = policy.path(filePath, false);
        if (!verdict.allowed()) {
            notes.add(verdict.message());
            return new CollectedContext(List.copyOf(items), List.copyOf(notes));
        }
        String relative = relative(root, filePath);
        StringBuilder text = new StringBuilder();
        DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.WARNING, 0, document.getTextLength(), info -> {
            appendHighlight(document, info, text);
            return text.length() <= MAX_CONTEXT_CHARS;
        });
        if (text.isEmpty()) notes.add("В активном редакторе нет доступных предупреждений или ошибок.");
        else items.add(new ContextItem("Диагностики активного редактора", relative, 1, document.getLineCount(), text.toString().stripTrailing()));
        return new CollectedContext(List.copyOf(items), List.copyOf(notes));
    }

    private static void addFile(Path root, ProjectAccessPolicy policy, VirtualFile file, List<ContextItem> items, List<String> notes) {
        if (file == null || file.isDirectory()) return;
        Path filePath = Path.of(file.getPath());
        ProjectAccessPolicy.Verdict verdict = policy.path(filePath, false);
        if (!verdict.allowed()) {
            notes.add(verdict.message());
            return;
        }
        String text;
        try (var reader = Files.newBufferedReader(filePath)) {
            char[] buffer = new char[MAX_CONTEXT_CHARS + 1]; int size = 0, read;
            while (size < buffer.length && (read = reader.read(buffer, size, buffer.length - size)) >= 0) size += read;
            text = new String(buffer, 0, size);
        }
        catch (IOException e) {
            notes.add("Не удалось прочитать файл: " + relative(root, filePath));
            return;
        }
        if (text.length() > MAX_CONTEXT_CHARS) {
            notes.add("Файл больше 256 КиБ и не был добавлен: " + relative(root, filePath));
            return;
        }
        items.add(new ContextItem("Файл проекта", relative(root, filePath), 1, Math.max(1, text.split("\n", -1).length), text));
    }

    private static void addGitDiff(Path root, ProjectAccessPolicy policy, boolean staged, List<ContextItem> items, List<String> notes) {
        var listing = new ArrayList<>(List.of("diff", "--relative", "--ignore-submodules=all", "--no-ext-diff", "--no-textconv", "--no-renames", "--name-only", "-z"));
        if (staged) listing.add("--cached");
        String names = git(root, listing, notes);
        if (names.isEmpty()) return;
        for (String name : names.split("\u0000")) {
            if (name.isEmpty()) continue;
            ProjectAccessPolicy.Verdict verdict = policy.path(root.resolve(name), false);
            if (!verdict.allowed()) { notes.add(verdict.message()); continue; }
            List<String> command = new ArrayList<>(List.of("diff", "--relative", "--ignore-submodules=all", "--no-ext-diff", "--no-textconv", "--no-renames"));
            if (staged) command.add("--cached");
            command.add("--"); command.add(":(literal)" + name);
            String diff = git(root, command, notes);
            if (diff.isBlank()) continue;
            if (diff.length() > MAX_CONTEXT_CHARS) {
                notes.add("Git diff больше 256 КиБ и не был добавлен: " + name); continue;
            }
            items.add(new ContextItem(staged ? "Git diff staged" : "Git diff unstaged", name, 0, 0, diff));
        }
    }

    private static String git(Path root, List<String> args, List<String> notes) {
        Process process = null;
        try {
            var command = org.agty.aiassistant.changes.ReadOnlyGit.command(root, args.toArray(String[]::new));
            process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
            Process child = process;
            var outputTask = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    byte[] bytes = child.getInputStream().readNBytes(MAX_CONTEXT_CHARS * 4 + 1);
                    if (bytes.length > MAX_CONTEXT_CHARS * 4) { child.destroyForcibly(); throw new IOException("Вывод Git превышает лимит контекста."); }
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                } catch (IOException e) { throw new java.util.concurrent.CompletionException(e); }
            });
            boolean finished = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                notes.add("Git diff не был добавлен: команда превысила 10 секунд.");
                return "";
            }
            String output = outputTask.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                notes.add("Git diff недоступен: " + output.strip());
                return "";
            }
            return output;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            notes.add("Git diff недоступен: " + e.getMessage());
            return "";
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }

    private static void appendHighlight(Document document, HighlightInfo info, StringBuilder text) {
        if (info == null || info.getDescription() == null || info.getDescription().isBlank()) return;
        int line = document.getLineNumber(Math.max(0, Math.min(info.startOffset, document.getTextLength()))) + 1;
        text.append("- ")
                .append(info.getSeverity())
                .append(" L").append(line)
                .append(": ")
                .append(info.getDescription().replace('\n', ' '))
                .append('\n');
    }

    private static String relative(Path root, Path filePath) {
        Path relative = root.toAbsolutePath().normalize().relativize(filePath.toAbsolutePath().normalize());
        return relative.getFileSystem().getSeparator().equals("\\") ? relative.toString().replace('\\', '/') : relative.toString();
    }

    private ProjectContextCollector() {}
}
