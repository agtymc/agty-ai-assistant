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
    private record Entry(String title, String markdown, boolean activity, boolean complete) {}
    private final Project project;
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
            message.activity = entry.activity(); message.complete = entry.complete(); result.add(message);
        });
        return result;
    }
    public void restore(java.util.List<org.agty.aiassistant.chat.ChatSessions.Message> messages) {
        entries.clear(); expanded.clear();
        if (browser != null) browser.resetScroll();
        for (var message : messages) {
            if (message.activity && message.title.startsWith("Пояснение") && message.markdown.isBlank()) continue;
            entries.put(message.id, new Entry(
                    message.title + (message.complete ? "" : " · прервано"), message.markdown, message.activity, true));
        }
        schedule();
    }

    public ChatTranscript(Project project, Consumer<String> status) {
        super(new BorderLayout());
        this.project = project;
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
        String text = entry.markdown();
        if (text.length() > 100_000) entry = new Entry(entry.title(), text.substring(0, 100_000) + "\n\n… отображение сокращено", entry.activity(), entry.complete());
        if (entry.equals(entries.get(id))) return;
        entries.put(id, entry);
        int length = entries.values().stream().mapToInt(e -> e.markdown().length()).sum();
        while (entries.size() > 300 || length > 500_000) {
            String first = entries.keySet().iterator().next();
            length -= entries.remove(first).markdown().length();
            expanded.remove(first);
        }
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
            if (entry.activity() && !appearance.showActivity) continue;
            String style = entry.activity() ? "activity" : entry.title().equals("Вы") ? "user" : "assistant";
            html.append("<div class=\"card ").append(style).append("\"><div class=\"title\">");
            if (entry.activity()) html.append("<a href=\"").append(markdown.action("toggle", pair.getKey())).append("\">")
                    .append(expanded.contains(pair.getKey()) ? "▾ " : "▸ ");
            html.append(MarkdownRenderer.escape(entry.title())).append(entry.complete() ? "" : " · выполняется…");
            if (entry.activity()) html.append("</a>");
            else html.append(" <a class=\"copy\" href=\"").append(markdown.action("copy", entry.markdown())).append("\">Копировать</a>");
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
            Path root = Path.of(project.getBasePath());
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
