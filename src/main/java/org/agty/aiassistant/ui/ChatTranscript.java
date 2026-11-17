package org.agty.aiassistant.ui;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.*;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.agty.aiassistant.chat.ChatEvent;
import org.agty.aiassistant.settings.ChatAppearance;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.beans.PropertyChangeListener;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public final class ChatTranscript extends JPanel implements Disposable {
    private record Entry(String title, String markdown, boolean activity, boolean complete, String artifact, String imageArtifact) {
        Entry(String title, String markdown, boolean activity, boolean complete, String artifact) { this(title, markdown, activity, complete, artifact, ""); }
        Entry(String title, String markdown, boolean activity, boolean complete) { this(title, markdown, activity, complete, "", ""); }
    }
    private final Project project;
    private final java.util.function.Supplier<Path> directory;
    private BrowserTranscript browser;
    private final Runnable appearanceListener = this::schedule;
    private final Consumer<String> status;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final Set<String> expanded = new HashSet<>();
    private final JEditorPane view = new JEditorPane() {
        @Override public boolean getScrollableTracksViewportWidth() { return ChatAppearance.getInstance().getState().wrapCode; }
    };
    private final JBScrollPane scroll = new JBScrollPane(view);
    private final MarkdownRenderer markdown;
    private final javax.swing.Timer repaintTimer;
    private final PropertyChangeListener themeListener;
    private boolean disposed;
    private int serial;
    private Runnable onChange = () -> {};
    public void onChange(Runnable callback) { onChange = callback; }
    public java.util.List<org.agty.aiassistant.chat.ChatSessions.Message> snapshot() {
        var result = new java.util.ArrayList<org.agty.aiassistant.chat.ChatSessions.Message>();
        entries.forEach((id, entry) -> {
            var message = new org.agty.aiassistant.chat.ChatSessions.Message();
            message.id = id; message.title = entry.title(); message.markdown = entry.markdown();
            message.activity = entry.activity(); message.complete = entry.complete(); message.artifact = entry.artifact(); message.imageArtifact = entry.imageArtifact(); result.add(message);
        });
        return result;
    }
    public void synchronize(java.util.List<org.agty.aiassistant.chat.ChatSessions.Message> messages) {
        entries.clear();
        for (var message : messages) entries.put(message.id,
                new Entry(message.title, message.markdown, message.activity, message.complete, message.artifact, message.imageArtifact));
        schedule();
    }
    public void restore(java.util.List<org.agty.aiassistant.chat.ChatSessions.Message> messages) {
        entries.clear(); expanded.clear();
        if (browser != null) browser.resetScroll();
        for (var message : messages) {
            if (message.activity && message.title.startsWith("Пояснение") && message.markdown.isBlank()) continue;
            entries.put(message.id, new Entry(
                    message.title + (message.complete ? "" : " · прервано"), message.markdown, message.activity, true, message.artifact, message.imageArtifact));
        }
        schedule();
    }

    public ChatTranscript(Project project, Consumer<String> status) { this(project, status, () -> Path.of(project.getBasePath())); }
    public ChatTranscript(Project project, Consumer<String> status, java.util.function.Supplier<Path> directory) {
        super(new BorderLayout());
        this.project = project; this.directory = directory;
        this.status = status;
        markdown = new MarkdownRenderer((language, code) -> CodeHighlighting.html(project, language, code));
        view.setEditorKit(new WrappingHtmlKit());
        view.setEditable(false);
        view.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        ((DefaultCaret) view.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        view.getAccessibleContext().setAccessibleName("Сообщения и ход работы ассистента");
        view.addHyperlinkListener(e -> { if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) activate(e.getDescription()); });
        if (!ApplicationManager.getApplication().isHeadlessEnvironment() && com.intellij.ui.jcef.JBCefApp.isSupported()) {
            try { browser = new BrowserTranscript(this::activate); }
            catch (RuntimeException e) { status.accept("Браузер чата недоступен; используется Swing."); }
        }
        markdown.browser(browser != null);
        scroll.setBorder(null);
        add(browser == null ? scroll : browser.component(), BorderLayout.CENTER);
        repaintTimer = new javax.swing.Timer(120, e -> render());
        repaintTimer.setRepeats(false);
        themeListener = e -> { if ("lookAndFeel".equals(e.getPropertyName())) schedule(); };
        UIManager.addPropertyChangeListener(themeListener);
        ChatAppearance.getInstance().addListener(appearanceListener);
    }
    public void clear() { entries.clear(); expanded.clear(); schedule(); }
    public void message(String title, String text) { put("local-" + UUID.randomUUID(), new Entry(title, text, false, true)); }
    public void event(String requestId, String provider, ChatEvent event) {
        String id = requestId + ":" + (event.id().isEmpty() ? "event-" + serial++ : event.id());
        boolean activity = event.kind() == ChatEvent.Kind.ACTIVITY;
        if (activity && event.title().startsWith("Пояснение") && event.text().isBlank()) {
            if (entries.remove(id) != null) schedule();
            return;
        }
        put(id, new Entry(activity ? event.title() : provider, event.text(), activity, event.complete()));
    }
    public void finishRequest(String requestId, boolean success) {
        entries.replaceAll((id, entry) -> id.startsWith(requestId + ":") && !entry.complete()
                ? new Entry(entry.title() + (success ? " · завершено" : " · прервано"), entry.markdown(), entry.activity(), true)
                : entry);
        schedule();
    }
    private void put(String id, Entry entry) {
        if (entry.equals(entries.get(id))) return;
        entries.put(id, entry);
        schedule();
    }
    private void schedule() { onChange.run(); if (!disposed && !repaintTimer.isRunning()) repaintTimer.start(); }
    private void render() {
        if (disposed) return;
        var bar = scroll.getVerticalScrollBar();
        boolean follow = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 40;
        int oldPosition = bar.getValue();
        markdown.reset();
        var appearance = ChatAppearance.getInstance().getState();
        StringBuilder html = new StringBuilder("<html><head><meta charset=\"UTF-8\"><style>")
                .append(ChatStyles.css(appearance, browser != null)).append("</style></head><body>");
        for (var pair : entries.entrySet()) {
            Entry entry = pair.getValue();
            boolean serverRequest = pair.getKey().contains(":server:");
            if (entry.activity() && !appearance.showActivity && !(serverRequest && !entry.complete())) continue;
            String style = entry.activity() ? "activity" : entry.title().equals("Вы") ? "user" : "assistant";
            html.append("<div data-entry-id=\"").append(MarkdownRenderer.escape(pair.getKey())).append("\" class=\"card ").append(style).append("\"><div class=\"title\">");
            if (entry.activity()) html.append("<a href=\"").append(markdown.action("toggle", pair.getKey())).append("\">")
                    .append(expanded.contains(pair.getKey()) ? "▾ " : "▸ ");
            html.append(MarkdownRenderer.escape(entry.title())).append(entry.complete() ? "" : " · выполняется…");
            if (entry.activity()) html.append("</a>");
            else html.append(" <a class=\"copy\" href=\"").append(markdown.action(entry.artifact().isEmpty() ? "copy" : "copy-artifact", entry.artifact().isEmpty() ? entry.markdown() : entry.artifact())).append("\">Копировать</a>");
            if (entry.activity() && entry.title().startsWith("Изменения"))
                html.append(" <a href=\"").append(markdown.action("changes", pair.getKey().split(":", 2)[0])).append("\">Сравнение и откат</a>");
            if (serverRequest && !entry.complete()) html.append(" <a href=\"").append(markdown.action("server-request", pair.getKey())).append("\">Ответить</a>");
            if (!entry.imageArtifact().isEmpty()) html.append(" <a href=\"").append(markdown.action("image-artifact", entry.imageArtifact())).append("\">Открыть изображение</a>");
            if (!entry.artifact().isEmpty()) html.append(" <a href=\"").append(markdown.action("artifact", entry.artifact())).append("\">Полный результат</a>");
            html.append("</div>");
            if (style.equals("user")) html.append("<div class=\"usertext\">")
                    .append(MarkdownRenderer.userText(entry.markdown(), browser != null))
                    .append("</div>");
            else if (!entry.activity() || expanded.contains(pair.getKey())) html.append(markdown.render(entry.markdown()));
            else if (!entry.markdown().isBlank()) html.append("<p>").append(MarkdownRenderer.escape(preview(entry.markdown()))).append("</p>");
            html.append("</div>");
        }
        html.append("</body></html>");
        if (browser != null) { browser.update(html.toString(), appearance.autoScroll); return; }
        view.setText(html.toString());
        SwingUtilities.invokeLater(() -> { if (!disposed) bar.setValue(appearance.autoScroll && follow ? bar.getMaximum() : oldPosition); });
    }
    private void activate(String href) {
        var action = markdown.actions().get(href);
        if (action == null) return;
        switch (action.type()) {
            case "copy" -> { CopyPasteManager.getInstance().setContents(new StringSelection(action.value())); status.accept("Скопировано"); }
            case "toggle" -> { if (!expanded.add(action.value())) expanded.remove(action.value()); render(); }
            case "open" -> open(action.value());
            case "changes" -> {
                var runner = project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).runner();
                var task = runner.taskByRequest(action.value());
                if (task != null) new ChangesDialog(project, task.directory, runner, task).show();
                else if (project.getBasePath() != null) new ChangesDialog(project, Path.of(project.getBasePath()), runner, null).show();
            }
            case "server-request" -> {
                int separator = action.value().indexOf(":server:");
                var runner = project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).runner();
                var task = runner.taskByRequest(action.value().substring(0, separator));
                String id = action.value().substring(separator + ":server:".length());
                if (task == null || !task.active() || !task.pending.containsKey(id)) { status.accept("Запрос больше не ожидает ответа."); return; }
                var response = ServerRequestDialog.answer(project, task.pending.get(id));
                if (response != null) {
                    try { runner.respond(task.session.id, id, response); }
                    catch (IllegalStateException e) { status.accept(e.getMessage()); }
                }
            }
            case "image-artifact" -> ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    Path path = org.agty.aiassistant.history.MessageArtifacts.getInstance().imagePath(action.value());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (disposed || project.isDisposed()) return;
                        var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                        if (file != null) com.intellij.openapi.application.WriteIntentReadAction.run((Runnable) () -> com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true));
                    });
                } catch (Exception e) { ApplicationManager.getApplication().invokeLater(() -> status.accept(e.getMessage())); }
            });
            case "artifact", "copy-artifact" -> ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    String full = org.agty.aiassistant.history.MessageArtifacts.getInstance().read(action.value());
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (disposed || project.isDisposed()) return;
                        if (action.type().equals("copy-artifact")) CopyPasteManager.getInstance().setContents(new StringSelection(full));
                        else {
                            var file = new com.intellij.testFramework.LightVirtualFile("Результат Codex.txt", full); file.setWritable(false);
                            com.intellij.openapi.application.WriteIntentReadAction.run((Runnable) () -> com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true));
                        }
                    });
                } catch (Exception e) { ApplicationManager.getApplication().invokeLater(() -> status.accept(e.getMessage())); }
            });
            default -> { }
        }
    }
    private static String preview(String text) {
        String plain = text.replaceAll("(?m)^```[^\\n]*", "").replace('\n', ' ').strip();
        return plain.length() > 180 ? plain.substring(0, 180) + "…" : plain;
    }
    private void open(String destination) {
        if (destination.matches("(?i)^https?://.*")) { BrowserUtil.browse(destination); return; }
        try {
            if (project.getBasePath() == null) throw new IllegalArgumentException("Нет каталога проекта.");
            Path root = directory.get();
            SourceLink target = SourceLink.parse(root, destination);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    if (!target.path().toRealPath().startsWith(root.toRealPath())) throw new IllegalArgumentException("Ссылка ведёт за пределы проекта.");
                    var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.path());
                    if (file == null || file.isDirectory()) throw new IllegalArgumentException("Файл не найден: " + target.path());
                    ReadAction.nonBlocking(() -> {
                        if (target.symbol().isEmpty()) return -1;
                        PsiFile psi = PsiManager.getInstance(project).findFile(file);
                        if (psi == null) return -1;
                        var found = PsiTreeUtil.collectElements(psi, element -> element instanceof PsiNamedElement named && target.symbol().equals(named.getName()));
                        return found.length == 0 ? -1 : found[0].getTextOffset();
                    }).inSmartMode(project).expireWith(project).finishOnUiThread(ModalityState.nonModal(), offset -> {
                        if (disposed || project.isDisposed()) return;
                        if (offset >= 0) new OpenFileDescriptor(project, file, offset).navigate(true);
                        else {
                            new OpenFileDescriptor(project, file, target.line(), target.column()).navigate(true);
                            if (!target.symbol().isEmpty()) status.accept("Символ не найден; открыт файл. Для перегрузок используйте ссылку со строкой.");
                        }
                    }).submit(AppExecutorUtil.getAppExecutorService());
                } catch (Exception ex) {
                    ApplicationManager.getApplication().invokeLater(() -> { if (!disposed) status.accept("Не удалось открыть ссылку: " + ex.getMessage()); });
                }
            });
        } catch (RuntimeException ex) { status.accept("Не удалось открыть ссылку: " + ex.getMessage()); }
    }
    @Override public void dispose() { disposed = true; repaintTimer.stop(); ChatAppearance.getInstance().removeListener(appearanceListener); if (browser != null) browser.dispose(); UIManager.removePropertyChangeListener(themeListener); entries.clear(); markdown.reset(); }
}
