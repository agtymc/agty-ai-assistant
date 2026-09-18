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
                (com.intellij.openapi.util.Computable<CollectedContext>) () -> collect(project, root, policy));
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

    private static CollectedContext collect(Project project, Path root, ProjectAccessPolicy policy) {
        List<ContextItem> items = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
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
        String relative = root.toAbsolutePath().normalize().relativize(filePath.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
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
        try { text = Files.readString(filePath); }
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
        List<String> names = git(root, staged ? List.of("diff", "--cached", "--name-only") : List.of("diff", "--name-only"), notes);
        if (names.isEmpty()) return;
        List<String> allowed = new ArrayList<>();
        for (String name : names) {
            if (name.isBlank()) continue;
            ProjectAccessPolicy.Verdict verdict = policy.path(root.resolve(name), false);
            if (verdict.allowed()) allowed.add(name);
            else notes.add(verdict.message());
        }
        if (allowed.isEmpty()) return;
        List<String> command = new ArrayList<>();
        command.add("diff");
        if (staged) command.add("--cached");
        command.add("--");
        command.addAll(allowed);
        String diff = String.join("\n", git(root, command, notes));
        if (diff.isBlank()) return;
        if (diff.length() > MAX_CONTEXT_CHARS) {
            notes.add((staged ? "Staged" : "Unstaged") + " Git diff больше 256 КиБ и не был добавлен.");
            return;
        }
        items.add(new ContextItem(staged ? "Git diff staged" : "Git diff unstaged",
                staged ? ".git-diff-staged.patch" : ".git-diff.patch", 1, diff.split("\n", -1).length, diff));
    }

    private static List<String> git(Path root, List<String> args, List<String> notes) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(args);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                notes.add("Git diff не был добавлен: команда превысила 10 секунд.");
                return List.of();
            }
            if (process.exitValue() != 0) {
                notes.add("Git diff недоступен: " + output.strip());
                return List.of();
            }
            return output.isBlank() ? List.of() : output.lines().toList();
        } catch (Exception e) {
            notes.add("Git diff недоступен: " + e.getMessage());
            return List.of();
        }
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
        return root.toAbsolutePath().normalize().relativize(filePath.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private ProjectContextCollector() {}
}
