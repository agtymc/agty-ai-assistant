package org.agty.aiassistant.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import org.agty.aiassistant.changes.ChangeSet;
import org.agty.aiassistant.core.*;
import org.agty.aiassistant.chat.*;
import org.agty.aiassistant.context.CollectedContext;
import org.agty.aiassistant.context.ContextItem;
import org.agty.aiassistant.context.ProjectContextCollector;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.agty.aiassistant.settings.CodexSettings;
import org.agty.aiassistant.settings.ChatAppearance;
import org.agty.aiassistant.security.ProjectAccessPolicy;
import com.intellij.openapi.ui.Messages;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

public final class AiAssistantChatPanel extends JPanel implements Disposable {
    private final Project project;
    private final ChatTranscript transcript;
    private final java.util.List<ChatProviderRegistry.Choice> providers;
    private final javax.swing.Timer elapsedTimer;
    private long startedAt;
    private String stage = "";
    private final JBTextArea input = new JBTextArea(2, 20);
    private final JLabel status = new JLabel("Готово · Codex CLI · только чтение");
    private final JButton send = new JButton("Отправить");
    private final JButton stop = new JButton("Остановить");
    private final JButton statusButton = new JButton("Status");
    private final JCheckBox includeContext = new JCheckBox("Контекст", true);
    private final JButton addContextButton = new JButton("+");
    private final JButton contextButton = new JButton("Просмотр");
    private final JButton clearContextButton = new JButton("×");
    private final JButton reviewButton = new JButton("Review");
    private final JButton clear = new JButton("Новая сессия");
    private final com.intellij.openapi.ui.ComboBox<ChatProviderRegistry.Choice> providerPicker = new com.intellij.openapi.ui.ComboBox<>() {
        @Override public int getMinimumPopupWidth() {
            int width = Math.max(getWidth(), JBUI.scale(320));
            var metrics = getFontMetrics(getFont());
            for (int i = 0; i < getItemCount(); i++)
                width = Math.max(width, metrics.stringWidth(getItemAt(i).label()) + JBUI.scale(64));
            return width;
        }
    };
    private final JButton sessionMenuButton = new JButton("Чаты ▾");
    private final JComboBox<CodexModels.Effort> effortPicker = new com.intellij.openapi.ui.ComboBox<>();
    private boolean updatingEffort;
    private final ChatSessions.ProjectSessions sessions;
    private ChatSessions.Session session;
    private boolean loadingSession;
    private boolean readingSession;
    private final boolean nativeSessions;
    private final JLabel sessionLabel = new JLabel();
    private final JToggleButton access = new JToggleButton();
    private int historyPosition = -1;
    private String historyDraft = "";
    private boolean navigatingHistory;
    private final JPopupMenu commandSuggestions = new JPopupMenu();
    private boolean statusLoading;
    private java.util.List<CodexModels.Model> models = java.util.List.of();
    private boolean modelsLoading;
    private String modelCatalogBinary = "";
    private final JButton refresh = new JButton("↻"), openUid = new JButton("UID"), rename = new JButton("Переименовать");
    private long sessionLoad;
    private final Runnable appearanceListener = this::applyAppearance;
    private final JProgressBar progress = new JProgressBar();
    private final StringBuilder history = new StringBuilder();
    private final java.util.List<ContextItem> pinnedContext = new java.util.ArrayList<>();
    private ChatProvider.Request active;
    private String activeRequestId = "";
    private String pendingQuestion = "";
    private boolean disposed;
    private static final int MAX_HISTORY = 100_000;
    private static final int MAX_DISPLAY = 500_000;

    public AiAssistantChatPanel(Project project) {
        this(project, ChatProviderRegistry.getInstance().choices(), true);
    }

    public AiAssistantChatPanel(Project project, Supplier<ChatProvider> providers) {
        this(project, java.util.List.of(new ChatProviderRegistry.Choice("codex", "Codex · CLI", providers)), false);
    }
    AiAssistantChatPanel(Project project, java.util.List<ChatProviderRegistry.Choice> providers, boolean nativeSessions) {
        super(new BorderLayout(0, 8));
        this.nativeSessions = nativeSessions;
        this.project = project;
        this.providers = providers;
        ProjectIdentity identity = ProjectIdentity.getInstance(project);
        sessions = ApplicationManager.getApplication().getService(ChatSessions.class)
                .project(identity.stableId(project), identity.migrationCandidates(project));
        session = sessions.current();
        sessions.providerSelections.put(session.providerId, session.id);
        transcript = new ChatTranscript(project, status::setText);
        elapsedTimer = new javax.swing.Timer(1000, e -> {
            if (active != null) status.setText(stage + " · " + elapsed((System.nanoTime() - startedAt) / 1_000_000_000));
        });
        setBorder(JBUI.Borders.empty(8));
        input.setLineWrap(true);
        input.setWrapStyleWord(true);

        input.getAccessibleContext().setAccessibleName("Запрос к Codex");


        JPanel toolbar = new JPanel(new BorderLayout());
        JLabel titleLabel = new JLabel("AI Chat", IconLoader.getIcon("/icons/agty.svg", AiAssistantChatPanel.class), SwingConstants.LEADING);
        titleLabel.setIconTextGap(JBUI.scale(6));
        toolbar.add(titleLabel, BorderLayout.WEST);
        JPanel menus = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        sessionMenuButton.addActionListener(e -> sessionMenu().show(sessionMenuButton, 0, sessionMenuButton.getHeight()));
        JButton settings = new JButton("⚙"); settings.setToolTipText("Настройки чата");
        settings.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem connection = new JMenuItem("Подключения…");
            connection.addActionListener(a -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "AGTY AI Assistant"));
            JMenuItem appearance = new JMenuItem("Оформление чата…");
            appearance.addActionListener(a -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "Оформление чата"));
            menu.add(connection); menu.add(appearance); menu.show(settings, 0, settings.getHeight());
        });
        JButton commands = new JButton("/"); commands.setToolTipText("Команды чата");
        commands.addActionListener(e -> commandMenu("").show(commands, 0, commands.getHeight()));
        menus.add(commands); menus.add(sessionMenuButton); menus.add(settings); toolbar.add(menus, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);
        JPanel chatArea = new JPanel(new BorderLayout());
        chatArea.add(transcript, BorderLayout.CENTER);
        JPanel chatStatus = new JPanel(new BorderLayout(0, 2));
        chatStatus.setName("chat-status-bar");
        progress.setIndeterminate(true); progress.setVisible(false); progress.setPreferredSize(new Dimension(0, 2));
        chatStatus.add(progress, BorderLayout.NORTH);
        status.setFont(status.getFont().deriveFont(12f));
        status.setName("chat-status");
        status.setBorder(JBUI.Borders.empty(4, 8));
        chatStatus.add(status, BorderLayout.CENTER);
        chatArea.add(chatStatus, BorderLayout.SOUTH);
        add(chatArea, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(0, 4));
        bottom.setName("chat-composer");
        input.getAccessibleContext().setAccessibleName("Запрос к выбранной LLM");
        input.getEmptyText().setText("Спросите о проекте…");
        input.setToolTipText("Enter — отправить · Shift+Enter — новая строка");
        var inputScroll = new JBScrollPane(input);
        inputScroll.setBorder(JBUI.Borders.empty());
        JLabel resizeHandle = new JLabel("⋯", SwingConstants.CENTER);
        resizeHandle.setName("editor-resize-handle");
        resizeHandle.setToolTipText("Потяните вверх или вниз, чтобы изменить высоту поля ввода (2–12 строк)");
        resizeHandle.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
        resizeHandle.setPreferredSize(JBUI.size(30, 9));
        var drag = new java.awt.event.MouseAdapter() {
            int startY, startRows;
            @Override public void mousePressed(java.awt.event.MouseEvent event) {
                startY = event.getYOnScreen(); startRows = input.getRows();
            }
            @Override public void mouseDragged(java.awt.event.MouseEvent event) {
                int line = Math.max(1, input.getFontMetrics(input.getFont()).getHeight());
                resizeInputRows(startRows + Math.round((startY - event.getYOnScreen()) / (float) line));
            }
        };
        resizeHandle.addMouseListener(drag); resizeHandle.addMouseMotionListener(drag);
        JPanel editor = new JPanel(new BorderLayout());
        editor.add(resizeHandle, BorderLayout.NORTH); editor.add(inputScroll, BorderLayout.CENTER);
        bottom.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor") == null ? Color.GRAY : UIManager.getColor("Component.borderColor")), JBUI.Borders.empty(6)));
        sessionLabel.setName("current-session");
        sessionLabel.putClientProperty("html.disable", true);
        sessionLabel.setFont(sessionLabel.getFont().deriveFont(11f));
        sessionLabel.setMinimumSize(new Dimension(0, 16));
        JPanel context = new JPanel(new BorderLayout(4, 0));
        context.add(sessionLabel, BorderLayout.CENTER);
        access.setName("access-mode"); access.setSelected(sessions.writeAccess); updateAccessLabel();
        access.setToolTipText("Доступ выбранного провайдера с последующего запроса. Запись ограничена каталогом проекта.");
        access.setPreferredSize(new Dimension(150, 24));
        access.addActionListener(e -> { sessions.writeAccess = access.isSelected(); updateAccessLabel(); });
        statusButton.setName("status-command");
        statusButton.setToolTipText("Показать /status текущей сессии");
        statusButton.addActionListener(e -> executeCommand(new SlashCommands.Invocation("/status", "")));
        includeContext.setName("include-context");
        includeContext.setToolTipText("Добавлять активный файл или выделение к следующему запросу");
        includeContext.addActionListener(e -> updateContextLabel());
        addContextButton.setName("add-context");
        addContextButton.setToolTipText("Добавить источник в контекст");
        addContextButton.addActionListener(e -> contextSourceMenu().show(addContextButton, 0, addContextButton.getHeight()));
        contextButton.setName("context-preview");
        contextButton.setToolTipText("Показать список контекста перед отправкой");
        contextButton.addActionListener(e -> showContextPreview());
        clearContextButton.setName("clear-context");
        clearContextButton.setToolTipText("Очистить список контекста");
        clearContextButton.addActionListener(e -> clearContextItems());
        reviewButton.setName("diff-review");
        reviewButton.setToolTipText("Проверить и применить unified diff");
        reviewButton.addActionListener(e -> showDiffReviewDialog());
        JPanel contextActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        contextActions.add(includeContext); contextActions.add(addContextButton); contextActions.add(contextButton);
        contextActions.add(clearContextButton); contextActions.add(reviewButton); contextActions.add(access); contextActions.add(statusButton);
        context.add(contextActions, BorderLayout.EAST); bottom.add(context, BorderLayout.NORTH);
        bottom.add(editor, BorderLayout.CENTER);
        for (var choice : providers) providerPicker.addItem(choice);
        providerPicker.setName("llm-picker");
        providerPicker.getAccessibleContext().setAccessibleName("Выбор LLM");
        providerPicker.setToolTipText("LLM / модель. Выбор применяется к следующему сообщению этой сессии.");
        providerPicker.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                if (nativeSessions && (models.isEmpty() || !modelCatalogBinary.equals(CodexSettings.getInstance().getExecutablePath())))
                    loadModels(false, "");
            }
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });
        providerPicker.setPreferredSize(JBUI.size(280, 26));
        providerPicker.setMaximumRowCount(16);
        JPanel controls = new JPanel(new BorderLayout(4, 0));
        controls.add(providerPicker, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        effortPicker.setName("reasoning-effort");
        effortPicker.getAccessibleContext().setAccessibleName("Уровень рассуждения");
        effortPicker.setPreferredSize(JBUI.size(100, 26));
        effortPicker.setToolTipText("Уровень усилий рассуждения для следующего сообщения");
        effortPicker.addActionListener(e -> {
            if (updatingEffort || loadingSession || active != null || readingSession) return;
            var selected = (CodexModels.Effort) effortPicker.getSelectedItem();
            if (selected == null) return;
            session.reasoningEffort = selected.value();
            effortPicker.setToolTipText(selected.description());
            saveSession();
        });
        actions.add(effortPicker);
        send.setText("↑"); send.setToolTipText("Отправить (Enter)");
        send.getAccessibleContext().setAccessibleName("Отправить сообщение");
        stop.setText("■"); stop.setToolTipText("Остановить запрос");
        stop.getAccessibleContext().setAccessibleName("Остановить запрос");
        var composerControls = java.util.List.of(providerPicker, effortPicker, access, send, stop);
        int controlHeight = composerControls.stream().mapToInt(control -> control.getPreferredSize().height)
                .max().orElse(JBUI.scale(28));
        for (var control : composerControls) {
            var size = control.getPreferredSize();
            control.setPreferredSize(new Dimension(size.width, controlHeight));
        }
        actions.add(stop); actions.add(send); controls.add(actions, BorderLayout.EAST);
        JPanel footer = new JPanel(new BorderLayout(0, 2));
        footer.add(controls, BorderLayout.NORTH);
        bottom.add(footer, BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
        stop.setEnabled(false); stop.setVisible(false);
        send.addActionListener(e -> send()); stop.addActionListener(e -> cancel());
        clear.setText("+ Новый чат");
        clear.addActionListener(e -> {
            String providerId = session.providerId;
            cancel(); saveSession(); var created = sessions.create(); created.providerId = providerId;
            selectSession(created);
        });
        providerPicker.addActionListener(e -> {
            if (loadingSession) return;
            var choice = (ChatProviderRegistry.Choice) providerPicker.getSelectedItem();
            if (choice == null) return;
            if (choice.id().equals(session.providerId)) {
                if (!choice.model().isBlank() && !choice.model().equals(session.selectedModel)) chooseModel(choice.model());
                else if (choice.model().isBlank() && !session.selectedModel.isBlank()) refreshSessions();
                return;
            }
            String previousId = sessions.providerSelections.get(choice.id());
            var selected = sessions.sessions.stream().filter(item -> item.providerId.equals(choice.id()) && item.id.equals(previousId)).findFirst()
                    .orElseGet(() -> sessions.sessions.reversed().stream().filter(item -> item.providerId.equals(choice.id())).findFirst().orElse(null));
            if (selected == null) { selected = sessions.create(); selected.providerId = choice.id(); }
            selectSession(selected);
            if (!choice.model().isBlank()) chooseModel(choice.model());
        });
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { editedInput(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { editedInput(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { saveSession(); }
        });
        refresh.addActionListener(e -> refreshCodexSessions());
        openUid.addActionListener(e -> {
            String id = Messages.showInputDialog(project, "UID существующей сессии Codex этого проекта:", "Открыть сессию", null);
            if (id != null && !id.isBlank()) readCodexSession(id.trim());
        });
        rename.addActionListener(e -> {
            String title = Messages.showInputDialog(project, "Название сессии:", "Переименовать", null, session.title, null);
            if (title != null && !title.isBlank()) { session.title = title.strip(); session.customTitle = true; showUid(); saveSession(); }
        });
        transcript.onChange(this::saveSession);
        ChatInput.configure(input, this::send);
        input.getInputMap().put(KeyStroke.getKeyStroke("UP"), "previous-prompt");
        input.getInputMap().put(KeyStroke.getKeyStroke("DOWN"), "next-prompt");
        input.getActionMap().put("previous-prompt", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { navigateHistory(-1); }
        });
        input.getActionMap().put("next-prompt", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { navigateHistory(1); }
        });
        updateHistoryNavigation();
        updateContextLabel();
        refreshSessions();
        loadSession();
        ChatAppearance.getInstance().addListener(appearanceListener);
        applyAppearance();
        if (nativeSessions) loadModels(false, "");
        if (nativeSessions && session.providerId.equals("codex")) {
            refreshCodexSessions();
            if (!session.remoteId().isEmpty()) readCodexSession(session.remoteId());
        }
    }

    private void updateAccessLabel() {
        access.setText(access.isSelected() ? "Редактирование" : "Только чтение");
        access.getAccessibleContext().setAccessibleName("Режим: " + access.getText());
    }
    private void editedInput() {
        if (!navigatingHistory) historyPosition = -1;
        updateHistoryNavigation();
        saveSession(); suggestCommands();
    }
    private void updateHistoryNavigation() {
        var up = KeyStroke.getKeyStroke("UP");
        var down = KeyStroke.getKeyStroke("DOWN");
        if (input.getText().isEmpty() || historyPosition >= 0) {
            input.getInputMap().put(up, "previous-prompt");
            input.getInputMap().put(down, "next-prompt");
        } else {
            input.getInputMap().remove(up);
            input.getInputMap().remove(down);
        }
    }
    private void navigateHistory(int direction) {
        var prompts = session.promptHistory;
        if (prompts.isEmpty()) return;
        if (historyPosition < 0) {
            if (direction > 0) return;
            historyDraft = input.getText(); historyPosition = prompts.size();
        }
        int next = historyPosition + direction;
        if (next < 0 || next > prompts.size()) return;
        historyPosition = next;
        navigatingHistory = true;
        try {
            input.setText(next == prompts.size() ? historyDraft : prompts.get(next));
            input.setCaretPosition(input.getDocument().getLength());
        } finally { navigatingHistory = false; }
        if (next == prompts.size()) historyPosition = -1;
        updateHistoryNavigation();
    }
    private void rememberPrompt(String value) {
        if (value.isBlank()) return;
        var prompts = session.promptHistory;
        if (prompts.isEmpty() || !prompts.getLast().equals(value)) prompts.add(value);
        if (prompts.size() > 100) prompts.removeFirst();
        historyPosition = -1;
        updateHistoryNavigation();
    }
    void resizeInputRows(int rows) {
        int normalized = Math.clamp(rows, 2, 12);
        if (input.getRows() == normalized) return;
        var settings = ChatAppearance.getInstance();
        var data = settings.getState(); data.inputRows = normalized;
        settings.update(data);
    }

    private JPopupMenu commandMenu(String prefix) {
        JPopupMenu menu = new JPopupMenu();
        for (var command : SlashCommands.available(session.providerId)) {
            if (!command.name().startsWith(prefix)) continue;
            JMenuItem item = new JMenuItem(command.name() + " — " + command.description());
            item.addActionListener(e -> {
                if (command.name().equals("/resume") || command.name().equals("/permissions")) {
                    input.setText(command.name() + " "); input.requestFocusInWindow();
                } else executeCommand(new SlashCommands.Invocation(command.name(), ""));
            });
            menu.add(item);
        }
        return menu;
    }
    private void suggestCommands() {
        SwingUtilities.invokeLater(() -> {
            if (disposed) return;
            String prefix = input.getText().strip();
            if (!input.isShowing() || !prefix.matches("/[a-z]*")) { commandSuggestions.setVisible(false); return; }
            commandSuggestions.removeAll();
            var menu = commandMenu(prefix);
            while (menu.getComponentCount() > 0) commandSuggestions.add(menu.getComponent(0));
            if (commandSuggestions.getComponentCount() > 0) {
                commandSuggestions.setFocusable(false);
                commandSuggestions.show(input, 0, -commandSuggestions.getPreferredSize().height);
            } else commandSuggestions.setVisible(false);
        });
    }
    void executeCommand(SlashCommands.Invocation command) {
        if (disposed) return;
        if (SlashCommands.available(session.providerId).stream().noneMatch(c -> c.name().equals(command.name()))) {
            transcript.message("Команда", "Команда `" + command.name().replace("`", "") + "` не поддерживается. Введите `/help`. Она не отправлена модели."); return;
        }
        if (!command.argument().isBlank() && !command.name().equals("/resume") && !command.name().equals("/permissions") && !command.name().equals("/model")) {
            transcript.message("Команда", "У этой команды нет аргументов. Введите `/help`."); return;
        }
        switch (command.name()) {
            case "/help" -> transcript.message("Команды", SlashCommands.help(session.providerId));
            case "/new" -> clear.doClick();
            case "/stop" -> { cancel(); status.setText("Остановлено"); }
            case "/resume" -> {
                if (command.argument().isBlank()) {
                    if (sessionMenuButton.isShowing()) sessionMenu().show(sessionMenuButton, 0, sessionMenuButton.getHeight());
                    else transcript.message("Сессии", "Используйте `/resume UUID` или меню «Чаты».");
                }
                else readCodexSession(command.argument());
            }
            case "/permissions" -> {
                if (active != null || readingSession) { status.setText("Измените режим после завершения запроса."); return; }
                if (command.argument().equals("read-only")) access.setSelected(false);
                else if (command.argument().equals("workspace-write")) access.setSelected(true);
                else if (!command.argument().isBlank()) { transcript.message("Режим", "Используйте `/permissions read-only` или `/permissions workspace-write`."); return; }
                sessions.writeAccess = access.isSelected(); updateAccessLabel();
                transcript.message("Режим", sessions.writeAccess ? "Изменение файлов проекта разрешено со следующего запроса." : "Только чтение со следующего запроса.");
            }
            case "/status" -> showStatus();
            case "/model" -> {
                if (active != null || readingSession) { status.setText("Выберите модель после завершения запроса."); return; }
                loadModels(true, command.argument());
            }
            default -> { }
        }
    }
    private void loadModels(boolean choose, String requested) {
        if (disposed || project.getBasePath() == null) { if (choose) status.setText("Откройте локальный проект для списка моделей."); return; }
        if (modelsLoading) { if (choose) status.setText("Список моделей загружается. Повторите /model после загрузки."); return; }
        modelsLoading = true;
        String binary = CodexSettings.getInstance().getExecutablePath();
        Path root = Path.of(project.getBasePath());
        var target = session; long generation = sessionLoad;
        if (choose) status.setText("Загружаю модели Codex…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (var client = new CodexSessionClient(binary, root)) {
                var catalog = CodexModels.list(client);
                ApplicationManager.getApplication().invokeLater(() -> {
                    modelsLoading = false;
                    if (disposed || project.isDisposed() || !binary.equals(CodexSettings.getInstance().getExecutablePath())) return;
                    modelCatalogBinary = binary;
                    boolean popup = providerPicker.isPopupVisible();
                    if (popup) providerPicker.hidePopup();
                    updateModels(catalog);
                    if (choose && target == session && generation == sessionLoad && active == null && !readingSession) {
                        if (!requested.isBlank()) {
                            try { chooseModel(CodexModels.resolve(models, requested).model()); }
                            catch (IllegalArgumentException e) { transcript.message("/model", e.getMessage()); }
                        } else if (models.isEmpty()) status.setText("Codex не вернул доступных моделей.");
                        else if (providerPicker.isShowing()) providerPicker.showPopup();
                    } else if (popup && !models.isEmpty() && providerPicker.isShowing() && providerPicker.isEnabled()) providerPicker.showPopup();
                });
            } catch (Exception e) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    modelsLoading = false;
                    if (!disposed && (choose || session.providerId.equals("codex"))) status.setText("Не удалось загрузить модели: " + e.getMessage());
                });
            }
        });
    }
    void updateModels(java.util.List<CodexModels.Model> catalog) {
        models = java.util.List.copyOf(catalog);
        refreshSessions();
    }
    private void chooseModel(String model) {
        if (active != null) { refreshSessions(); status.setText("Выберите модель после завершения запроса."); return; }
        session.selectedModel = model;
        session.reasoningEffort = models.stream().filter(item -> item.model().equals(model))
                .map(CodexModels.Model::defaultEffort).findFirst().orElse("");
        refreshSessions(); saveSession();
        status.setText("Следующее сообщение: " + model);
    }
    private void showStatus() {
        if (!session.providerId.equals("codex") || project.getBasePath() == null) {
            transcript.message("/status", "LLM: " + session.providerId + " · Сессия: " + session.remoteId());
            return;
        }
        if (statusLoading) return;
        statusLoading = true; status.setText("Читаю статус Codex…");
        var target = session;
        String selectedModel = target.selectedModel, selectedEffort = target.reasoningEffort;
        boolean writeAccess = sessions.writeAccess;
        String binary = CodexSettings.getInstance().getExecutablePath(); Path root = Path.of(project.getBasePath());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String result;
            try { result = CodexStatus.read(binary, root, target.remoteId(), selectedModel,
                    selectedEffort, writeAccess); }
            catch (Exception e) { result = "Статус Codex недоступен: " + e.getMessage(); }
            String output = result;
            ApplicationManager.getApplication().invokeLater(() -> {
                statusLoading = false;
                if (!disposed && !project.isDisposed() && target == session) {
                    transcript.message("/status · Codex", output);
                    if (active == null) status.setText("Статус получен");
                }
            });
        });
    }

    private static String shortTitle(String text) {
        String title = text.replaceAll("\\s+", " ").strip();
        return title.length() > 70 ? title.substring(0, 70) + "…" : title;
    }
    static String elapsed(long seconds) {
        if (seconds < 60) return seconds + " с";
        if (seconds < 3600) return seconds / 60 + " мин " + seconds % 60 + " с";
        return seconds / 3600 + " ч " + seconds % 3600 / 60 + " мин " + seconds % 60 + " с";
    }
    private void showUid() {
        String uid = session.remoteId().isEmpty() ? session.id : session.remoteId();
        String title = session.title.length() > 44 ? session.title.substring(0, 44) + "…" : session.title;
        sessionLabel.setText(title + " · " + uid.substring(0, Math.min(8, uid.length())));
        sessionLabel.setToolTipText(session.title + " · " + session.providerId + " · " + uid);
    }
    private void applyAppearance() {
        var d = ChatAppearance.getInstance().getState();
        input.setFont(new Font(d.font, Font.PLAIN, d.fontSize)); input.setRows(d.inputRows);
        input.setBackground(d.ideColors ? UIManager.getColor("TextArea.background") : Color.decode(d.background));
        input.setForeground(d.ideColors ? UIManager.getColor("TextArea.foreground") : Color.decode(d.foreground));
        revalidate(); repaint();
    }
    private void refreshCodexSessions() {
        if (!session.providerId.equals("codex") || project.getBasePath() == null || disposed) return;
        refresh.setEnabled(false); status.setText("Загружаю сессии Codex…");
        String binary = CodexSettings.getInstance().getExecutablePath();
        Path root = Path.of(project.getBasePath());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (var client = new CodexSessionClient(binary, root)) {
                var available = client.list(root);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed || project.isDisposed()) return;
                    for (var found : available) {
                        boolean exists = sessions.sessions.stream().anyMatch(s -> s.providerId.equals("codex") && s.remoteId().equals(found.codexId));
                        if (!exists) sessions.sessions.add(found);
                    }
                    refreshSessions(); refresh.setEnabled(active == null);
                    if (active == null && !readingSession) status.setText("Сессий Codex: " + available.size());
                });
            } catch (Exception e) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed) { refresh.setEnabled(active == null); status.setText("Сессии: " + e.getMessage()); }
                });
            }
        });
    }
    private void readCodexSession(String id) {
        if (project.getBasePath() == null || disposed || active != null) return;
        try { UUID.fromString(id); }
        catch (IllegalArgumentException e) { status.setText("UID должен быть UUID сессии Codex."); return; }
        long generation = ++sessionLoad;
        readingSession = true; send.setEnabled(false); status.setText("Читаю историю сессии…");
        String binary = CodexSettings.getInstance().getExecutablePath();
        Path root = Path.of(project.getBasePath());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try (var client = new CodexSessionClient(binary, root)) {
                var found = client.read(id, root);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed || project.isDisposed() || generation != sessionLoad) return;
                    saveSession();
                    var selected = sessions.sessions.stream().filter(s -> s.providerId.equals("codex") && s.remoteId().equals(id)).findFirst().orElse(null);
                    if (selected == null) { selected = found; sessions.sessions.add(selected); }
                    else if (!found.messages.isEmpty()) selected.messages = found.messages;
                    session = selected; session.closed = false; sessions.selected = session.id;
                    historyPosition = -1; historyDraft = "";
                    sessions.providerSelections.put(session.providerId, session.id);
                    readingSession = false; refreshSessions(); loadSession(); busy(false);
                });
            } catch (Exception e) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!disposed && generation == sessionLoad) {
                        readingSession = false; busy(false); status.setText("Не удалось открыть сессию: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void selectSession(ChatSessions.Session selected) {
        sessionLoad++; readingSession = false; cancel(); saveSession();
        session = selected; sessions.selected = session.id;
        historyPosition = -1; historyDraft = "";
        sessions.providerSelections.put(session.providerId, session.id);
        refreshSessions(); loadSession(); busy(false);
        if (nativeSessions && session.providerId.equals("codex") && !session.remoteId().isEmpty()) readCodexSession(session.remoteId());
    }
    JPopupMenu sessionMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem newChat = new JMenuItem("+ Новый чат"); newChat.addActionListener(e -> clear.doClick()); menu.add(newChat);
        boolean codex = nativeSessions && session.providerId.equals("codex");
        JMenuItem reload = new JMenuItem("Обновить сессии Codex"); reload.setEnabled(codex && active == null && refresh.isEnabled());
        reload.addActionListener(e -> refresh.doClick()); menu.add(reload);
        JMenuItem uid = new JMenuItem("Открыть по UID…"); uid.setEnabled(codex && active == null);
        uid.addActionListener(e -> openUid.doClick()); menu.add(uid);
        JMenuItem renameItem = new JMenuItem("Переименовать…"); renameItem.addActionListener(e -> rename.doClick()); menu.add(renameItem);
        JMenuItem copy = new JMenuItem("Копировать UID");
        copy.addActionListener(e -> com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(
                new java.awt.datatransfer.StringSelection(session.remoteId().isEmpty() ? session.id : session.remoteId())));
        menu.add(copy); menu.addSeparator();
        JMenuItem close = new JMenuItem("Завершить текущую сессию");
        close.addActionListener(e -> finishSession()); menu.add(close); menu.addSeparator();
        ButtonGroup group = new ButtonGroup();
        for (var item : sessions.sessions) {
            JRadioButtonMenuItem entry = new JRadioButtonMenuItem((item.closed ? "Завершена · " : "") + item.title + " · " + item.providerId + " · "
                    + (item.remoteId().isEmpty() ? item.id : item.remoteId()), item == session);
            entry.putClientProperty("html.disable", true);
            entry.setActionCommand(item.id); entry.addActionListener(e -> { item.closed = false; selectSession(item); });
            group.add(entry); menu.add(entry);
        }
        return menu;
    }
    private void finishSession() {
        String provider = session.providerId;
        cancel(); saveSession(); session.closed = true;
        var next = sessions.sessions.stream().filter(item -> !item.closed && item.providerId.equals(provider))
                .reduce((first, last) -> last).orElse(null);
        if (next == null) { next = sessions.create(); next.providerId = provider; }
        selectSession(next);
        status.setText("Сессия завершена. Её можно снова открыть в меню «Чаты».");
    }
    private ChatProviderRegistry.Choice selectedProvider() {
        return providers.stream().filter(p -> p.id().equals(session.providerId)).findFirst().orElse(null);
    }
    private void refreshSessions() {
        loadingSession = true;
        providerPicker.removeAllItems();
        ChatProviderRegistry.Choice selected = null;
        String current = session.selectedModel.isBlank() ? session.effectiveModel : session.selectedModel;
        for (var provider : providers) {
            if (!provider.id().equals("codex") || models.isEmpty()) {
                providerPicker.addItem(provider);
                if (provider.id().equals(session.providerId) && current.isBlank()) selected = provider;
            } else for (var model : models) {
                var choice = new ChatProviderRegistry.Choice(provider.id(), "Codex · " + model.label(), provider.factory(), model.model());
                providerPicker.addItem(choice);
                if (session.providerId.equals("codex") && model.model().equals(current)) selected = choice;
            }
        }
        var base = selectedProvider();
        if (selected == null && base != null) {
            selected = current.isBlank() ? base : new ChatProviderRegistry.Choice(base.id(), "Codex · " + current, base.factory(), current);
            if (!current.isBlank() || !models.isEmpty()) providerPicker.addItem(selected);
        }
        var ordered = new java.util.ArrayList<ChatProviderRegistry.Choice>();
        for (int i = 0; i < providerPicker.getItemCount(); i++) ordered.add(providerPicker.getItemAt(i));
        ordered.sort((left, right) -> {
            int comparison = com.intellij.openapi.util.text.StringUtil.naturalCompare(left.label(), right.label());
            return comparison != 0 ? comparison : left.model().compareTo(right.model());
        });
        providerPicker.removeAllItems();
        ordered.forEach(providerPicker::addItem);
        providerPicker.setSelectedItem(selected); loadingSession = false;
        refreshEfforts();
        showUid();
    }
    private void refreshEfforts() {
        updatingEffort = true;
        effortPicker.removeAllItems();
        String model = session.selectedModel.isBlank() ? session.effectiveModel : session.selectedModel;
        var metadata = models.stream().filter(item -> item.model().equals(model)).findFirst().orElse(null);
        boolean available = session.providerId.equals("codex") && metadata != null && !metadata.efforts().isEmpty();
        if (available) {
            metadata.efforts().forEach(effortPicker::addItem);
            String wanted = session.reasoningEffort.isBlank() ? metadata.defaultEffort() : session.reasoningEffort;
            var chosen = metadata.efforts().stream().filter(item -> item.value().equals(wanted)).findFirst()
                    .orElseGet(() -> metadata.efforts().stream().filter(item -> item.value().equals(metadata.defaultEffort())).findFirst().orElse(metadata.efforts().getFirst()));
            effortPicker.setSelectedItem(chosen);
            session.reasoningEffort = chosen.value();
            effortPicker.setToolTipText(chosen.description());
        }
        effortPicker.setVisible(available);
        effortPicker.setEnabled(available && active == null && !readingSession);
        updatingEffort = false;
    }
    private void saveSession() {
        if (loadingSession || session == null) return;
        if (!session.customTitle && session.remoteId().isEmpty() && session.history.isEmpty() && !input.getText().isBlank() && !input.getText().stripLeading().startsWith("/")) {
            session.title = shortTitle(input.getText()); showUid();
        }
        session.history = history.toString();
        session.draft = input.getText();
        session.messages = transcript.snapshot();
    }
    private void loadSession() {
        loadingSession = true;
        history.setLength(0); history.append(session.history);
        transcript.restore(session.messages);
        input.setText(session.draft);
        loadingSession = false;
        if (session.messages.isEmpty()) welcome();
        saveSession();
        showUid();
        status.setText("Готово");
    }

    private void welcome() {
        transcript.message("AGTY AI Assistant", "Задайте вопрос о проекте. **Enter** — отправить, **Shift+Enter** — новая строка.\n\n"
                + "Ответы поддерживают Markdown и ссылки на исходники. Карточки хода работы можно раскрывать.\n\n"
                + "Выберите LLM под полем ввода. История и последняя выбранная сессия сохраняются для этого проекта.");
    }

    private void send() {
        if (disposed || project.isDisposed()) return;
        String question = input.getText();
        String commandText = question.trim();
        var command = SlashCommands.parse(commandText);
        if (command != null) { rememberPrompt(question); input.setText(""); commandSuggestions.setVisible(false); executeCommand(command); return; }
        if (readingSession || active != null) return;
        sendQuestion(commandText.startsWith("//") ? commandText.substring(1) : question);
    }
    private void sendQuestion(String question) {
        if (question.isBlank()) return;
        String root = project.getBasePath();
        if (root == null) { status.setText("Откройте локальный проект."); return; }
        final Path rootPath = Path.of(root);
        final ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(rootPath); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        ProjectAccessPolicy.Verdict accessVerdict = policy.request(sessions.writeAccess);
        if (!accessVerdict.allowed()) { status.setText(accessVerdict.message()); return; }
        if ((session.remoteId().isEmpty() ? history.length() : 0) + question.length() > MAX_HISTORY) {
            status.setText("Лимит диалога 100 000 символов. Начните новый диалог или сократите запрос.");
            return;
        }
        if (session.providerId.equals("codex") && session.selectedModel.isBlank()) {
            status.setText("Выберите модель Codex в списке LLM перед отправкой.");
            return;
        }
        final ChatProvider provider;
        try {
            var choice = selectedProvider();
            if (choice == null) throw new IllegalStateException("LLM этой сессии недоступна. Выберите подключение.");
            provider = choice.factory().get();
        }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        final boolean writeAccess = sessions.writeAccess;
        CollectedContext requestContext = requestContext(rootPath, policy);
        String prompt = "You are assisting inside IntelliJ IDEA. Answer in the user's language. "
                + (writeAccess ? "Current access mode permits modifying files within the project directory to complete the user request. "
                    : "Current access mode is read-only. Do not modify files. ")
                + "Propose code in Markdown fenced blocks with language tags. "
                + "Link source locations using [method or file](relative/path/File.java#L42), with real line numbers. "
                + "Give brief user-facing progress updates while working, when supported. "
                + "The following is conversation context, followed by the current user request.\n\n"
                + requestContext.promptBlock()
                + (session.remoteId().isEmpty() ? history : "") + "\nUSER:\n" + question;
        final ChatProvider.Request run;
        boolean forkForModel = session.providerId.equals("codex") && !session.remoteId().isEmpty()
                && (!session.remoteId().equals(session.confirmedThreadId) || !session.selectedModel.equals(session.effectiveModel));
        try { run = provider.newRequest(session.remoteId(), writeAccess, session.selectedModel, session.reasoningEffort, forkForModel); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        rememberPrompt(question);
        String requestId = UUID.randomUUID().toString();
        active = run;
        activeRequestId = requestId;
        pendingQuestion = question;
        startedAt = System.nanoTime();
        stage = "Ожидание " + provider.name();
        busy(true);
        elapsedTimer.start();
        status.setText(stage);
        if (session.history.isEmpty() && session.title.equals("Новая сессия")) {
            session.title = question.replaceAll("\\s+", " ");
            if (session.title.length() > 60) session.title = session.title.substring(0, 60) + "…";
            showUid();
        }
        transcript.message("Вы", question);
        input.setText("");

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            LinkedHashMap<String, String> answers = new LinkedHashMap<>();
            String outcome;
            boolean success = false;
            try {
                policy.request(writeAccess).requireAllowed();
                run.run(rootPath, prompt, event -> {
                    switch (event.kind()) {
                        case MODEL -> ui(run, () -> {
                            session.effectiveModel = event.text();
                            session.confirmedThreadId = session.selectedModel.equals(event.text()) ? session.remoteId() : "";
                            refreshSessions(); saveSession();
                        });
                        case USAGE -> ui(run, () -> session.tokenUsage = event.text());
                        case SESSION -> ui(run, () -> {
                            if (event.text().isBlank()) return;
                            if (session.providerId.equals("codex")) {
                                try { UUID.fromString(event.text()); }
                                catch (IllegalArgumentException ex) { status.setText("Codex вернул некорректный UID"); return; }
                            }
                            session.nativeSessionId = event.text(); history.setLength(0); showUid(); saveSession();
                        });
                        case MESSAGE -> {
                            if (event.complete()) {
                                answers.put(event.id().isEmpty() ? UUID.randomUUID().toString() : event.id(), event.text());
                                if (answers.values().stream().mapToInt(String::length).sum() > MAX_DISPLAY)
                                    throw new IllegalStateException("Ответ превысил лимит 500 000 символов.");
                            }
                            ui(run, () -> {
                                stage = "Получает ответ";
                                transcript.event(requestId, provider.name(), event);
                            });
                        }
                        case ACTIVITY -> ui(run, () -> {
                            stage = event.complete() ? "Ожидание следующего шага" : event.title();
                            status.setText(stage);
                            transcript.event(requestId, provider.name(), event);
                        });
                        case STATUS -> ui(run, () -> { stage = event.text(); status.setText(stage); });
                        case ERROR -> ui(run, () -> transcript.message("Ошибка", event.text()));
                        default -> { }
                    }
                });
                success = !answers.isEmpty();
                outcome = success ? "Готово" : "Провайдер завершил запрос без текстового ответа.";
            } catch (CancellationException e) { outcome = "Запрос остановлен."; }
            catch (Exception e) { outcome = "Ошибка: " + e.getMessage(); }
            String finalOutcome = outcome;
            boolean finalSuccess = success;
            ui(run, () -> {
                transcript.finishRequest(requestId, finalSuccess);
                if (finalSuccess && session.remoteId().isEmpty()) history.append("USER:\n").append(question)
                        .append("\nASSISTANT:\n").append(String.join("\n", answers.values())).append('\n');
                else if (!finalSuccess) {
                    transcript.message("Запрос не завершён", finalOutcome);
                    if (input.getText().isBlank()) input.setText(question);
                }
                elapsedTimer.stop();
                status.setText(finalSuccess ? (writeAccess ? "Готово · изменения проекта разрешены" : "Готово · только чтение") : "Запрос не завершён");
                if (writeAccess) com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh(null);
                active = null;
                pendingQuestion = "";
                busy(false);
            });
        });
    }

    private void showContextPreview() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        Path root = Path.of(project.getBasePath());
        final ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(root); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        CollectedContext context = includeContext.isSelected()
                ? previewContext(root, policy)
                : CollectedContext.empty("Контекст отключён для следующего запроса.");
        JTextArea area = new JTextArea(context.preview(), 24, 86);
        area.setEditable(false);
        area.setLineWrap(false);
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(JBUI.size(720, 420));
        JOptionPane.showMessageDialog(this, scroll, "Контекст следующего запроса", JOptionPane.PLAIN_MESSAGE);
    }

    private void addCurrentContext() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        Path root = Path.of(project.getBasePath());
        final ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(root); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        CollectedContext collected = ProjectContextCollector.currentEditor(project, root, policy);
        addCollectedContext(collected);
    }

    private void addFilesContext() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, true)
                .withTitle("Добавить файлы в AI-контекст")
                .withDescription("Выберите один или несколько текстовых файлов проекта.");
        java.util.List<com.intellij.openapi.vfs.VirtualFile> files = java.util.List.of(FileChooser.chooseFiles(descriptor, project, null));
        if (files.isEmpty()) return;
        Path root = Path.of(project.getBasePath());
        final ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(root); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        addCollectedContext(ProjectContextCollector.files(root, policy, files));
    }

    private void addDiagnosticsContext() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        Path root = Path.of(project.getBasePath());
        final ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(root); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        addCollectedContext(ProjectContextCollector.diagnostics(project, root, policy));
    }

    private void addGitDiffContext() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        Path root = Path.of(project.getBasePath());
        final ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(root); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        status.setText("Собираю Git diff…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CollectedContext collected = ProjectContextCollector.gitDiff(root, policy);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed && !project.isDisposed()) addCollectedContext(collected);
            });
        });
    }

    private JPopupMenu contextSourceMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem editor = new JMenuItem("Активный редактор");
        editor.addActionListener(e -> addCurrentContext());
        JMenuItem files = new JMenuItem("Файлы проекта…");
        files.addActionListener(e -> addFilesContext());
        JMenuItem diagnostics = new JMenuItem("Диагностики редактора");
        diagnostics.addActionListener(e -> addDiagnosticsContext());
        JMenuItem diff = new JMenuItem("Git diff");
        diff.addActionListener(e -> addGitDiffContext());
        menu.add(editor); menu.add(files); menu.add(diagnostics); menu.add(diff);
        return menu;
    }

    private void addCollectedContext(CollectedContext collected) {
        if (collected.items().isEmpty()) {
            status.setText(collected.notes().isEmpty() ? "Контекст не добавлен." : collected.notes().getFirst());
            return;
        }
        for (ContextItem item : collected.items()) {
            pinnedContext.removeIf(existing -> existing.location().equals(item.location()));
            pinnedContext.add(item);
        }
        includeContext.setSelected(true);
        updateContextLabel();
        status.setText("Контекст добавлен: " + collected.items().getFirst().location());
    }

    private void clearContextItems() {
        pinnedContext.clear();
        updateContextLabel();
        status.setText("Список контекста очищен.");
    }

    private void showDiffReviewDialog() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        JTextArea input = new JTextArea(24, 92);
        input.setLineWrap(false);
        JScrollPane inputScroll = new JScrollPane(input);
        inputScroll.setPreferredSize(JBUI.size(820, 460));
        int entered = JOptionPane.showConfirmDialog(this, inputScroll, "Unified diff для review", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (entered != JOptionPane.OK_OPTION || input.getText().isBlank()) return;
        reviewAndApplyDiff(input.getText(), Path.of(project.getBasePath()));
    }

    private void reviewAndApplyDiff(String diff, Path root) {
        final ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(root); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        ChangeSet skeleton = ChangeSet.fromUnifiedDiff(diff, java.util.Map.of());
        if (skeleton.isEmpty()) { status.setText("Unified diff не содержит файлов."); return; }
        java.util.Map<String, String> originals = new java.util.LinkedHashMap<>();
        for (ChangeSet.FileChange file : skeleton.files()) {
            Path path = root.resolve(file.path()).normalize();
            ProjectAccessPolicy.Verdict verdict = policy.path(path, true);
            if (!verdict.allowed()) { status.setText(verdict.message()); return; }
            try { originals.put(file.path(), Files.exists(path) ? Files.readString(path) : ""); }
            catch (Exception e) { status.setText("Не удалось прочитать " + file.path() + ": " + e.getMessage()); return; }
        }
        ChangeSet changeSet = ChangeSet.fromUnifiedDiff(diff, originals);
        java.util.List<String> conflicts = changeSet.conflicts(originals);
        String preview = changeSet.preview() + (conflicts.isEmpty() ? "\n\nКонфликтов не найдено."
                : "\n\nКонфликты:\n- " + String.join("\n- ", conflicts));
        JTextArea area = new JTextArea(preview, 26, 100);
        area.setEditable(false);
        area.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(JBUI.size(900, 520));
        int answer = JOptionPane.showConfirmDialog(this, scroll, "Diff review", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        if (!conflicts.isEmpty()) { status.setText("Diff не применён: есть конфликты."); return; }
        applyChangeSet(root, policy, changeSet);
    }

    private void applyChangeSet(Path root, ProjectAccessPolicy policy, ChangeSet changeSet) {
        try {
            CommandProcessor.getInstance().executeCommand(project, () -> ApplicationManager.getApplication().runWriteAction(() -> {
                for (ChangeSet.FileChange file : changeSet.files()) {
                    Path path = root.resolve(file.path()).normalize();
                    ProjectAccessPolicy.Verdict verdict = policy.path(path, true);
                    verdict.requireAllowed();
                    String current;
                    try { current = Files.exists(path) ? Files.readString(path) : ""; }
                    catch (Exception e) { throw new IllegalStateException("Не удалось прочитать " + file.path(), e); }
                    if (file.conflictsWith(current)) throw new IllegalStateException("Файл изменился после review: " + file.path());
                    writeFile(path, file.afterText());
                }
            }), "Apply AI ChangeSet", null);
            com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh(null);
            status.setText("ChangeSet применён: файлов " + changeSet.files().size());
        } catch (RuntimeException e) {
            status.setText("ChangeSet не применён: " + e.getMessage());
        }
    }

    private void writeFile(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file != null) {
                var document = FileDocumentManager.getInstance().getDocument(file);
                if (document != null) {
                    document.setText(text);
                    FileDocumentManager.getInstance().saveDocument(document);
                    return;
                }
            }
            Files.writeString(path, text);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось записать " + path.getFileName(), e);
        }
    }

    private CollectedContext requestContext(Path root, ProjectAccessPolicy policy) {
        if (!includeContext.isSelected()) return CollectedContext.empty("Контекст отключён для этого запроса.");
        if (!pinnedContext.isEmpty()) return ProjectContextCollector.validated(root, policy, pinnedContext);
        return ProjectContextCollector.currentEditor(project, root, policy);
    }

    private CollectedContext previewContext(Path root, ProjectAccessPolicy policy) {
        if (!pinnedContext.isEmpty()) return ProjectContextCollector.validated(root, policy, pinnedContext);
        CollectedContext active = ProjectContextCollector.currentEditor(project, root, policy);
        if (active.items().isEmpty()) return active;
        return new CollectedContext(active.items(), withNote(active.notes(), "Список контекста пуст; будет использован активный редактор."));
    }

    private java.util.List<String> withNote(java.util.List<String> notes, String note) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(notes);
        result.add(note);
        return java.util.List.copyOf(result);
    }

    private void updateContextLabel() {
        int count = pinnedContext.size();
        includeContext.setText(count == 0 ? "Контекст" : "Контекст (" + count + ")");
        includeContext.getAccessibleContext().setAccessibleName(includeContext.getText());
        clearContextButton.setEnabled(count > 0 && active == null && !readingSession);
    }

    private void cancel() {
        if (active != null) {
            ChatProvider.Request run = active;
            active = null;
            ApplicationManager.getApplication().executeOnPooledThread(run::cancel);
            transcript.finishRequest(activeRequestId, false);
            transcript.message("Остановлено", session.remoteId().isEmpty() ? "Частичный ответ не добавлен в контекст."
                    : "Запрос остановлен. Уже записанная история остаётся у провайдера.");
            elapsedTimer.stop();
            if (input.getText().isBlank()) input.setText(pendingQuestion);
            pendingQuestion = "";
            status.setText("Остановлено");
            busy(false);
        }
    }

    private void busy(boolean value) {
        access.setEnabled(!value && !readingSession);
        includeContext.setEnabled(!value && !readingSession);
        addContextButton.setEnabled(!value && !readingSession);
        contextButton.setEnabled(!value && !readingSession);
        clearContextButton.setEnabled(!value && !readingSession && !pinnedContext.isEmpty());
        reviewButton.setEnabled(!value && !readingSession);
        progress.setVisible(value);
        send.setEnabled(!value && !readingSession);
        refresh.setEnabled(!value);
        openUid.setEnabled(!value);
        input.setEditable(true);
        stop.setEnabled(value); stop.setVisible(value);
        providerPicker.setEnabled(!value && !readingSession);
        effortPicker.setEnabled(!value && !readingSession && effortPicker.getItemCount() > 0);
        saveSession();
    }

    private void ui(ChatProvider.Request run, Runnable task) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !project.isDisposed() && active == run) task.run();
        });
    }

    @Override public void dispose() {
        sessionLoad++;
        ChatAppearance.getInstance().removeListener(appearanceListener);
        cancel();
        saveSession();
        commandSuggestions.setVisible(false);
        disposed = true;
        elapsedTimer.stop();
        transcript.dispose();
        if (active != null) {
            ChatProvider.Request run = active;
            active = null;
            ApplicationManager.getApplication().executeOnPooledThread(run::cancel);
        }
        history.setLength(0);
    }
}
