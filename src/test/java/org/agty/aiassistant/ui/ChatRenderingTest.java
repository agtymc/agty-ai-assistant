package org.agty.aiassistant.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestApplicationManager;
import org.agty.aiassistant.chat.ChatEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ChatRenderingTest {
    @Test void statusButtonRunsCommandAndStatusIsBelowChat() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var store = com.intellij.openapi.application.ApplicationManager.getApplication().getService(org.agty.aiassistant.chat.ChatSessions.class);
            var previous = store.getState().projects.remove("default-project");
            AiAssistantChatPanel panel = null;
            try {
                panel = new AiAssistantChatPanel(project, () -> { throw new AssertionError("No request expected"); });
                JButton button = (JButton) named(panel, "status-command");
                assertEquals("Status", button.getText());
                button.doClick();
                assertTrue(store.project("default-project").current().messages.stream().anyMatch(m -> m.title.startsWith("/status")));
                assertNotNull(named(panel, "chat-status-bar"));
                assertSame(named(panel, "chat-status-bar"), named(panel, "chat-status").getParent());
            } catch (Throwable e) { failure.set(e); }
            finally {
                if (panel != null) panel.dispose();
                store.getState().projects.remove("default-project");
                if (previous != null) store.getState().projects.put("default-project", previous);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    @Test void historyKeysLeaveNonemptyDraftToCaretNavigation() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var store = com.intellij.openapi.application.ApplicationManager.getApplication().getService(org.agty.aiassistant.chat.ChatSessions.class);
            var previous = store.getState().projects.remove("default-project");
            AiAssistantChatPanel panel = null;
            try {
                var session = store.project("default-project").current();
                session.promptHistory.add("предыдущий запрос");
                panel = new AiAssistantChatPanel(project, () -> { throw new AssertionError("No request expected"); });
                JTextArea input = component(panel, JTextArea.class);
                KeyStroke up = KeyStroke.getKeyStroke("UP");
                input.setText("текущий\nтекст");
                assertNotEquals("previous-prompt", input.getInputMap().get(up));
                input.setText("");
                assertEquals("previous-prompt", input.getInputMap().get(up));
                input.getActionMap().get("previous-prompt").actionPerformed(new java.awt.event.ActionEvent(input, 0, ""));
                assertEquals("предыдущий запрос", input.getText());
                input.setText("изменённый запрос");
                assertNotEquals("previous-prompt", input.getInputMap().get(up));
            } catch (Throwable e) { failure.set(e); }
            finally {
                if (panel != null) panel.dispose();
                store.getState().projects.remove("default-project");
                if (previous != null) store.getState().projects.put("default-project", previous);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    @Test void elapsedTimeUsesMinutesAndHours() {
        assertEquals("59 с", AiAssistantChatPanel.elapsed(59));
        assertEquals("1 мин 1 с", AiAssistantChatPanel.elapsed(61));
        assertEquals("1 ч 1 мин 1 с", AiAssistantChatPanel.elapsed(3661));
    }
    private static Project project;
    @BeforeAll static void setup() throws Exception {
        TestApplicationManager.getInstance();
        SwingUtilities.invokeAndWait(() -> project = ProjectManager.getInstance().getDefaultProject());
        PlatformTestUtil.waitForAllBackgroundActivityToCalmDown();
    }
    @Test void emptyReasoningDoesNotCreateOrRestoreCard() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ChatTranscript chat = new ChatTranscript(project, s -> {});
            try {
                chat.event("request", "Codex", new ChatEvent(ChatEvent.Kind.ACTIVITY, "reason-1", "Пояснение модели", "", false));
                assertTrue(chat.snapshot().isEmpty());
                var old = new org.agty.aiassistant.chat.ChatSessions.Message();
                old.id = "old"; old.title = "Пояснение модели"; old.activity = true;
                chat.restore(java.util.List.of(old));
                assertTrue(chat.snapshot().isEmpty());
            } catch (Throwable e) { failure.set(e); }
            finally { chat.dispose(); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    @Test void rendersUpdatedMarkdownInRealSwingPane() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ChatTranscript chat = new ChatTranscript(project, s -> {});
            try {
                chat.event("request", "Model", new ChatEvent(ChatEvent.Kind.MESSAGE, "same", "", "old text", false));
                chat.event("request", "Model", new ChatEvent(ChatEvent.Kind.MESSAGE, "same", "", "## Final heading\n\n```java\npublic class Demo {}\n```", true));
                chat.event("cancelled", "Model", new ChatEvent(ChatEvent.Kind.ACTIVITY, "tool", "Search", "Looking for files", false));
                chat.finishRequest("cancelled", false);
                JEditorPane view = find(chat);
                PlatformTestUtil.waitWithEventsDispatching("Markdown not rendered", () -> view.getText().contains("Final heading"), 5);
                assertFalse(view.getText().contains("old text"));
                assertTrue(view.getText().contains("<h2>"));
                assertTrue(view.getText().contains("<font color="));
                assertTrue(view.getDocument().getText(0, view.getDocument().getLength()).contains("Копировать код"));
                assertTrue(view.getDocument().getText(0, view.getDocument().getLength()).contains("прервано"));
                assertFalse(view.getDocument().getText(0, view.getDocument().getLength()).contains("выполняется"));
            } catch (Throwable e) { failure.set(e); }
            finally { chat.dispose(); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    @Test void codePreservesLinesIndentationAndWrapsLongTokens() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ChatTranscript chat = new ChatTranscript(project, s -> {});
            try {
                String longToken = "z".repeat(220);
                chat.message("Model", "```java\nclass Demo {\n\tvoid method() {}\n\n    " + longToken + "\n}\n```");
                JEditorPane view = find(chat);
                PlatformTestUtil.waitWithEventsDispatching("Code not rendered", () -> view.getText().contains("method"), 5);
                view.setSize(300, 10000);
                var image = new java.awt.image.BufferedImage(300, 1000, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { view.paint(graphics); } finally { graphics.dispose(); }
                String text = view.getDocument().getText(0, view.getDocument().getLength());
                int first = text.indexOf("class"), method = text.indexOf("void"), token = text.indexOf(longToken);
                assertTrue(first >= 0 && method >= 0 && token >= 0, text);
                assertEquals("\u00a0".repeat(4), text.substring(method - 4, method));
                var firstBox = view.modelToView2D(first);
                var methodBox = view.modelToView2D(method);
                assertTrue(methodBox.getY() > firstBox.getY(), "Explicit newline collapsed");
                assertTrue(methodBox.getX() > firstBox.getX(), "Indentation collapsed");
                assertTrue(view.modelToView2D(token).getY() > methodBox.getY() + methodBox.getHeight(), "Blank line collapsed");
                assertTrue(view.modelToView2D(token + 219).getY() > view.modelToView2D(token).getY(), "Long token did not wrap");
                for (int offset = token; offset < token + 220; offset++)
                    assertTrue(view.modelToView2D(offset).getMaxX() <= 300, "Code exceeds viewport");
            } catch (Throwable e) { failure.set(e); }
            finally { chat.dispose(); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    @Test void switchesSessionsAndRestoresLastSelectionAndDraftOnReopen() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var store = com.intellij.openapi.application.ApplicationManager.getApplication()
                    .getService(org.agty.aiassistant.chat.ChatSessions.class);
            var previous = store.getState().projects.remove("default-project");
            AiAssistantChatPanel panel = null;
            try {
                panel = new AiAssistantChatPanel(project, () -> { throw new AssertionError("No model needed"); });
                JTextArea input = component(panel, JTextArea.class);

                input.setText("first draft");
                assertTrue(store.project("default-project").current().title.startsWith("first draft"));
                input.append(" updated");
                assertTrue(store.project("default-project").current().title.startsWith("first draft updated"));
                menuItem(panel.sessionMenu(), "+ Новый чат").doClick();
                assertEquals(2, store.project("default-project").sessions.size());
                assertEquals("", input.getText());
                input.setText("second draft");
                selectMenuSession(panel, store.project("default-project").sessions.get(0).id);
                assertEquals("first draft updated", input.getText());
                selectMenuSession(panel, store.project("default-project").sessions.get(1).id);
                assertEquals("second draft", input.getText());
                panel.dispose();
                panel = new AiAssistantChatPanel(project, () -> { throw new AssertionError("No model needed"); });
                assertEquals(store.project("default-project").sessions.get(1).id, store.project("default-project").selected);
                assertEquals("second draft", component(panel, JTextArea.class).getText());
            } catch (Throwable e) { failure.set(e); }
            finally {
                if (panel != null) panel.dispose();
                store.getState().projects.remove("default-project");
                if (previous != null) store.getState().projects.put("default-project", previous);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layoutTree(nested);
    }
    @Test void slashCommandsBypassModelAndPersistProjectPermissions() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var store = com.intellij.openapi.application.ApplicationManager.getApplication().getService(org.agty.aiassistant.chat.ChatSessions.class);
            var previous = store.getState().projects.remove("default-project");
            AiAssistantChatPanel panel = null;
            try {
                panel = new AiAssistantChatPanel(project, () -> { throw new AssertionError("A slash command must not instantiate a model"); });
                var input = component(panel, JTextArea.class);
                for (String command : java.util.List.of("/status", "/help", "/unknown", "/permissions workspace-write")) {
                    input.setText(command);
                    input.getActionMap().get("send-message").actionPerformed(new java.awt.event.ActionEvent(input, 0, ""));
                }
                var state = store.project("default-project");
                assertTrue(state.writeAccess);
                assertEquals("Новая сессия", state.current().title);
                assertTrue(state.current().history.isEmpty());
                assertTrue(state.current().messages.stream().anyMatch(m -> m.markdown.contains("не поддерживается")));
                assertTrue(state.current().messages.stream().anyMatch(m -> m.title.startsWith("/status")));
                panel.dispose(); panel = new AiAssistantChatPanel(project, () -> { throw new AssertionError(); });
                assertTrue(((JToggleButton) named(panel, "access-mode")).isSelected());
                assertEquals("Редактирование", ((JToggleButton) named(panel, "access-mode")).getText());
                panel.executeCommand(new org.agty.aiassistant.chat.SlashCommands.Invocation("/permissions", "read-only"));
                assertFalse(state.writeAccess);
                var access = (JToggleButton) named(panel, "access-mode");
                assertEquals("Только чтение", access.getText());
                access.doClick(); assertTrue(state.writeAccess);
                access.doClick(); assertFalse(state.writeAccess);
            } catch (Throwable e) { failure.set(e); }
            finally {
                if (panel != null) panel.dispose();
                store.getState().projects.remove("default-project");
                if (previous != null) store.getState().projects.put("default-project", previous);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static JMenuItem menuItem(JPopupMenu menu, String title) {
        for (var component : menu.getComponents()) if (component instanceof JMenuItem item && item.getText().equals(title)) return item;
        throw new AssertionError("Missing menu item: " + title);
    }
    @Test void promptHistorySessionCompletionAndInputHeight() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var store = com.intellij.openapi.application.ApplicationManager.getApplication().getService(org.agty.aiassistant.chat.ChatSessions.class);
            var previous = store.getState().projects.remove("default-project");
            var appearance = org.agty.aiassistant.settings.ChatAppearance.getInstance();
            int oldRows = appearance.getState().inputRows;
            AiAssistantChatPanel panel = null;
            try {
                panel = new AiAssistantChatPanel(project, () -> { throw new AssertionError("No request expected"); });
                var input = component(panel, JTextArea.class);
                var initial = store.project("default-project").current();
                input.setText("/help");
                input.getActionMap().get("send-message").actionPerformed(new java.awt.event.ActionEvent(input, 0, ""));
                input.setText("/permissions workspace-write");
                input.getActionMap().get("send-message").actionPerformed(new java.awt.event.ActionEvent(input, 0, ""));
                input.setText("unfinished draft");
                assertNotEquals("previous-prompt", input.getInputMap().get(KeyStroke.getKeyStroke("UP")));
                input.setText("");
                input.getActionMap().get("previous-prompt").actionPerformed(new java.awt.event.ActionEvent(input, 0, ""));
                assertEquals("/permissions workspace-write", input.getText());
                input.getActionMap().get("previous-prompt").actionPerformed(new java.awt.event.ActionEvent(input, 0, ""));
                assertEquals("/help", input.getText());
                input.getActionMap().get("next-prompt").actionPerformed(new java.awt.event.ActionEvent(input, 0, ""));
                assertEquals("/permissions workspace-write", input.getText());
                input.getActionMap().get("next-prompt").actionPerformed(new java.awt.event.ActionEvent(input, 0, ""));
                assertEquals("", input.getText());
                input.setText("unfinished draft");
                panel.resizeInputRows(5); assertEquals(5, input.getRows());
                assertEquals(5, appearance.getState().inputRows);
                assertNotNull(named(panel, "editor-resize-handle"));
                menuItem(panel.sessionMenu(), "Завершить текущую сессию").doClick();
                assertTrue(initial.closed);
                assertNotEquals(initial.id, store.project("default-project").selected);
                assertEquals(2, store.project("default-project").sessions.size());
                selectMenuSession(panel, initial.id);
                assertFalse(initial.closed);
                assertEquals("unfinished draft", input.getText());
                assertEquals(2, initial.promptHistory.size());
            } catch (Throwable e) { failure.set(e); }
            finally {
                if (panel != null) panel.dispose();
                appearance.getState().inputRows = oldRows; appearance.update(appearance.getState());
                store.getState().projects.remove("default-project");
                if (previous != null) store.getState().projects.put("default-project", previous);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static void selectMenuSession(AiAssistantChatPanel panel, String id) {
        for (var component : panel.sessionMenu().getComponents()) if (component instanceof JRadioButtonMenuItem item && item.getActionCommand().equals(id)) {
            item.doClick(); return;
        }
        throw new AssertionError("Missing session: " + id);
    }
    @Test void changingLlmKeepsSeparateSessionsAndRestoresProviderOnReopen() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var store = com.intellij.openapi.application.ApplicationManager.getApplication().getService(org.agty.aiassistant.chat.ChatSessions.class);
            var previous = store.getState().projects.remove("default-project");
            AiAssistantChatPanel panel = null;
            var choices = java.util.List.of(
                    new org.agty.aiassistant.chat.ChatProviderRegistry.Choice("codex", "Codex", () -> { throw new AssertionError("No request expected"); }),
                    new org.agty.aiassistant.chat.ChatProviderRegistry.Choice("test-llm", "Test LLM", () -> { throw new AssertionError("No request expected"); }));
            try {
                panel = new AiAssistantChatPanel(project, choices, false);
                JTextArea input = component(panel, JTextArea.class);
                JComboBox<?> picker = (JComboBox<?>) named(panel, "llm-picker");
                input.setText("Codex draft");
                var codex = store.project("default-project").current();
                codex.codexId = "019e309f-5fe7-7a93-90d0-ce794686cdfd";
                picker.setSelectedIndex(1);
                var other = store.project("default-project").current();
                assertEquals("test-llm", other.providerId);
                assertEquals("", other.remoteId());
                assertEquals("", input.getText());
                input.setText("Other draft");
                picker.setSelectedIndex(0);
                assertEquals(codex.id, store.project("default-project").selected);
                assertEquals("Codex draft", input.getText());
                picker.setSelectedIndex(1);
                assertEquals("Other draft", input.getText());
                panel.dispose(); panel = new AiAssistantChatPanel(project, choices, false);
                assertEquals("test-llm", ((org.agty.aiassistant.chat.ChatProviderRegistry.Choice) ((JComboBox<?>) named(panel, "llm-picker")).getSelectedItem()).id());
                assertEquals("Other draft", component(panel, JTextArea.class).getText());
                assertEquals(2, component(panel, JTextArea.class).getRows());
                panel.setSize(380, 640);
                layoutTree(panel);
                var image = new java.awt.image.BufferedImage(380, 640, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { panel.paint(graphics); } finally { graphics.dispose(); }
                var output = java.nio.file.Path.of("build/reports/composer-0.5.png");
                java.nio.file.Files.createDirectories(output.getParent());
                javax.imageio.ImageIO.write(image, "png", output.toFile());
            } catch (Throwable e) { failure.set(e); }
            finally {
                if (panel != null) panel.dispose();
                store.getState().projects.remove("default-project");
                if (previous != null) store.getState().projects.put("default-project", previous);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    @Test void modelPickerPreservesSessionAndRestoresSelection() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            var store = com.intellij.openapi.application.ApplicationManager.getApplication().getService(org.agty.aiassistant.chat.ChatSessions.class);
            var previous = store.getState().projects.remove("default-project");
            AiAssistantChatPanel panel = null;
            var choices = java.util.List.of(new org.agty.aiassistant.chat.ChatProviderRegistry.Choice("codex", "Codex", () -> { throw new AssertionError("No request expected"); }));
            var catalog = java.util.List.of(new org.agty.aiassistant.core.CodexModels.Model("a", "engine-a", "Model 10 extended context"), new org.agty.aiassistant.core.CodexModels.Model("b", "engine-b", "Model 2 extended context",
                    java.util.List.of(new org.agty.aiassistant.core.CodexModels.Effort("low", "Fast"), new org.agty.aiassistant.core.CodexModels.Effort("high", "Thorough")), "low"));
            try {
                var session = store.project("default-project").current();
                session.history = "Existing conversation";
                session.codexId = "019e309f-5fe7-7a93-90d0-ce794686cdfd";
                panel = new AiAssistantChatPanel(project, choices, false);
                panel.updateModels(catalog);
                component(panel, JTextArea.class).setText("Unsent draft");
                var picker = (com.intellij.openapi.ui.ComboBox<?>) named(panel, "llm-picker");
                assertEquals("engine-b", ((org.agty.aiassistant.chat.ChatProviderRegistry.Choice) picker.getItemAt(1)).model());
                assertEquals("engine-a", ((org.agty.aiassistant.chat.ChatProviderRegistry.Choice) picker.getItemAt(2)).model());
                assertTrue(picker.getMinimumPopupWidth() > picker.getFontMetrics(picker.getFont()).stringWidth("Codex · Model 10 extended context"));
                picker.setSelectedIndex(1);
                assertSame(session, store.project("default-project").current());
                assertEquals("engine-b", session.selectedModel);
                assertEquals("low", session.reasoningEffort);
                var effort = (JComboBox<?>) named(panel, "reasoning-effort");
                assertTrue(effort.isVisible());
                effort.setSelectedIndex(1);
                assertEquals("high", session.reasoningEffort);
                assertEquals("Existing conversation", session.history);
                assertEquals("019e309f-5fe7-7a93-90d0-ce794686cdfd", session.remoteId());
                assertEquals("Unsent draft", session.draft);
                panel.dispose(); panel = new AiAssistantChatPanel(project, choices, false);
                panel.updateModels(catalog);
                assertEquals("engine-b", ((org.agty.aiassistant.chat.ChatProviderRegistry.Choice) ((JComboBox<?>) named(panel, "llm-picker")).getSelectedItem()).model());
                assertEquals("Unsent draft", component(panel, JTextArea.class).getText());
                assertEquals("high", ((org.agty.aiassistant.core.CodexModels.Effort) ((JComboBox<?>) named(panel, "reasoning-effort")).getSelectedItem()).value());
                ((JComboBox<?>) named(panel, "llm-picker")).setSelectedIndex(1);
                assertEquals("engine-a", session.selectedModel);
                assertEquals("", session.reasoningEffort);
                assertFalse(named(panel, "reasoning-effort").isVisible());
            } catch (Throwable e) { failure.set(e); }
            finally {
                if (panel != null) panel.dispose();
                store.getState().projects.remove("default-project");
                if (previous != null) store.getState().projects.put("default-project", previous);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static JButton button(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton b && text.equals(b.getText())) return b;
            if (child instanceof Container nested) { var found = button(nested, text); if (found != null) return found; }
        }
        return null;
    }
    private static <T> T component(Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            T found = component(child, type); if (found != null) return found;
        }
        return null;
    }
    private static Component named(Component root, String name) {
        if (name.equals(root.getName())) return root;
        if (root instanceof Container container) for (Component child : container.getComponents()) {
            Component found = named(child, name); if (found != null) return found;
        }
        return null;
    }
    @Test void browserMarkdownPreservesWhitespaceAndAppearance() throws Exception {
        var d = new org.agty.aiassistant.settings.ChatAppearance.Data();
        d.ideColors = false;
        var renderer = new MarkdownRenderer((lang, code) -> CodeHighlighting.html(project, lang, code));
        renderer.browser(true);
        String code = "public class Greeting {\n    public String hello(String name) {\n        return \"Привет, \" + name;\n    }\n}\n";
        String content = renderer.render("### Пример для проекта\n\nМетод возвращает приветствие. Откройте [Greeting.java](src/Greeting.java#L2).\n\n```java\n" + code + "```\n\n- Код сохраняет отступы.\n- Сессия продолжается по UID.");
        assertTrue(content.contains("\n"));
        assertFalse(content.contains("&#160;"));
        assertTrue(renderer.actions().values().contains(new MarkdownRenderer.Action("copy", code)));
        String css = ChatStyles.css(d, true);
        assertTrue(css.contains("pre-wrap"));
        d.wrapCode = false; assertTrue(ChatStyles.css(d, true).contains("white-space:pre;"));
        String page = "<!doctype html><html><head><meta charset='UTF-8'><style>" + css + "</style></head><body><main id='chat'>"
                + "<div class='card user'><div class='title'>Вы</div>Покажи пример метода приветствия на Java.</div>"
                + "<div class='card activity'><div class='title'>▸ Просмотр исходников · завершено</div></div>"
                + "<div class='card assistant'><div class='title'>✦ Codex <a class='copy'>Копировать</a></div>" + content + "</div></main></body></html>";
        var output = java.nio.file.Path.of("build/reports/chat-preview.html");
        java.nio.file.Files.createDirectories(output.getParent()); java.nio.file.Files.writeString(output, page);
    }
    @Test void javaCodeUsesMoreThanOneSyntaxColor() {
        String html = CodeHighlighting.html(project, "java", "public class Demo { String text = \"hello\"; // comment\n}");
        var matcher = java.util.regex.Pattern.compile("color=\"(#[a-f0-9]+)\"").matcher(html);
        var colors = new java.util.HashSet<String>();
        while (matcher.find()) colors.add(matcher.group(1));
        assertTrue(colors.size() > 1, "Expected real syntax highlighting: " + html);
    }
    private JEditorPane find(Component component) {
        if (component instanceof JEditorPane pane) return pane;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JEditorPane result = find(child);
                if (result != null) return result;
            }
        }
        return null;
    }
}
