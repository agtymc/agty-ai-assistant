package org.agty.aiassistant.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.codeStyle.CodeStyleManager;
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
    private final JButton undoReviewButton = new JButton("Undo");
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
    private final JToggleButton unrestricted = new JToggleButton("Без ограничений");
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
    private java.util.Map<String, String> lastReviewBackup = java.util.Map.of();
    private String suggestedDiff = "";
    private final org.agty.aiassistant.execution.TaskRunner taskRunner;
    private final AutoCloseable taskSubscription;
    private final SessionCommandActions advancedCommands;
    private final JButton olderHistoryButton = new JButton("Загрузить предыдущие сообщения");
    private final JButton pendingRequestButton = new JButton("Требуется ответ");
    private final JButton requestOptionsButton = new JButton("Параметры запроса ▾");
    private ChatProvider.Request active;
    private String activeRequestId = "";
    private String pendingQuestion = "";
    private boolean disposed;
    private int commandIndex = -1;
    private String highlightedCommand = "/help";
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
        taskRunner = project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).runner();
        taskSubscription = taskRunner.subscribe(task -> {
            if (!disposed && task.session == session) showTask(task);
        });
        this.providers = providers;
        ProjectIdentity identity = ProjectIdentity.getInstance(project);
        sessions = ApplicationManager.getApplication().getService(ChatSessions.class)
                .project(identity.stableId(project), identity.migrationCandidates(project));
        session = sessions.current();
        project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).connections().setExperimental(sessions.experimentalApi);
        advancedCommands = new SessionCommandActions(new SessionCommandActions.Host(project, () -> session, () -> sessions,
                this::selectSession, () -> clear.doClick(), this::clearContextItems, () -> { if (!disposed) { refreshSessions(); showUid(); saveSession(); } },
                this::sendQuestion, this::commandMessage, status::setText,
                text -> input.setText(input.getText().isBlank() ? text : input.getText() + "\n" + text), () -> !disposed && !project.isDisposed()));
        sessions.providerSelections.put(session.providerId, session.id);
        transcript = new ChatTranscript(project, status::setText, this::workingRoot);
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
        sessionMenuButton.setName("session-menu");
        sessionMenuButton.getAccessibleContext().setAccessibleName("Выбор чата");
        sessionMenuButton.setIcon(titleLabel.getIcon());
        sessionMenuButton.setMinimumSize(new Dimension(0, 28));
        toolbar.add(sessionMenuButton, BorderLayout.CENTER);
        JPanel menus = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        sessionMenuButton.addActionListener(e -> sessionMenu().show(sessionMenuButton, 0, sessionMenuButton.getHeight()));
        JButton settings = new JButton("⋯"); settings.setToolTipText("Настройки чата");
        settings.addActionListener(e -> {
            JPopupMenu menu = new JPopupMenu();
            JMenuItem connection = new JMenuItem("Подключения…");
            connection.addActionListener(a -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "AGTY AI Assistant"));
            JMenuItem appearance = new JMenuItem("Оформление чата…");
            appearance.addActionListener(a -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "Оформление чата"));
            JMenuItem help = new JMenuItem("Справочник команд");
            help.addActionListener(a -> showCommandHelp("")); menu.add(help);
            menu.add(connection); menu.add(appearance); menu.addSeparator();
            JMenuItem diffAction = new JMenuItem("Изменения кода…"); diffAction.addActionListener(a -> showChanges()); menu.add(diffAction);
            JMenuItem statusAction = new JMenuItem("Статус"); statusAction.addActionListener(a -> statusButton.doClick()); menu.add(statusAction);
            JMenuItem reviewAction = new JMenuItem("Импортировать patch…"); reviewAction.addActionListener(a -> reviewButton.doClick()); menu.add(reviewAction);
            JMenuItem undoAction = new JMenuItem("Откатить ручной patch"); undoAction.setEnabled(undoReviewButton.isEnabled());
            undoAction.addActionListener(a -> undoReviewButton.doClick()); menu.add(undoAction);
            menu.show(settings, 0, settings.getHeight());
        });
        JButton commands = new JButton("/"); commands.setToolTipText("Команды чата");
        commands.addActionListener(e -> commandMenu("").show(commands, 0, commands.getHeight()));
        menus.add(clear); menus.add(settings); toolbar.add(menus, BorderLayout.EAST);
        add(toolbar, BorderLayout.NORTH);
        JPanel chatArea = new JPanel(new BorderLayout());
        chatArea.add(transcript, BorderLayout.CENTER);
        olderHistoryButton.setVisible(false); olderHistoryButton.addActionListener(e -> loadOlderHistory());
        chatArea.add(olderHistoryButton, BorderLayout.NORTH);
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
        context.add(contextButton, BorderLayout.CENTER);
        access.setName("access-mode"); access.setSelected(sessions.writeAccess); updateAccessLabel();
        access.setToolTipText("Доступ выбранного провайдера с последующего запроса. Запись ограничена каталогом проекта.");
        access.setPreferredSize(new Dimension(150, 24));
        access.addActionListener(e -> { sessions.writeAccess = access.isSelected(); updateAccessLabel(); });
        unrestricted.setName("unrestricted-mode"); unrestricted.setSelected(sessions.unrestrictedAccess);
        unrestricted.setToolTipText("Отключить sandbox Codex: разрешить сеть, .git и доступ к файлам вне проекта.");
        unrestricted.addActionListener(e -> {
            if (unrestricted.isSelected() && !confirmUnrestrictedAccess()) unrestricted.setSelected(false);
            sessions.unrestrictedAccess = unrestricted.isSelected();
            if (sessions.unrestrictedAccess) sessions.writeAccess = true;
            updateAccessLabel();
        });
        statusButton.setName("status-command");
        statusButton.setToolTipText("Показать /status текущей сессии");
        statusButton.addActionListener(e -> executeCommand(new SlashCommands.Invocation("/status", "")));
        includeContext.setName("include-context");
        includeContext.setToolTipText("Добавлять активный файл или выделение к следующему запросу");
        includeContext.addActionListener(e -> { updateContextLabel(); saveSession(); });
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
        undoReviewButton.setName("undo-diff-review");
        undoReviewButton.setToolTipText("Откатить последний применённый diff-review");
        undoReviewButton.setEnabled(false);
        undoReviewButton.addActionListener(e -> undoLastReview());
        JPanel contextActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        contextActions.add(includeContext); contextActions.add(clearContextButton);
        context.add(contextActions, BorderLayout.EAST);
        pendingRequestButton.setVisible(false);
        pendingRequestButton.addActionListener(e -> {
            var task = taskRunner.task(session.id);
            if (task == null || task.pending.isEmpty()) return;
            var requests = new java.util.ArrayList<>(task.pending.entrySet());
            int index = 0;
            if (requests.size() > 1) {
                String[] choices = requests.stream().map(entry -> entry.getKey() + " · " + entry.getValue().get("method").getAsString()).toArray(String[]::new);
                String chosen = (String) JOptionPane.showInputDialog(this, "Выберите ожидающий запрос", "Вопросы и подтверждения", JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
                if (chosen == null) return; index = java.util.Arrays.asList(choices).indexOf(chosen);
            }
            var pending = requests.get(index);
            var response = ServerRequestDialog.answer(project, pending.getValue());
            if (response != null) {
                try { taskRunner.respond(session.id, pending.getKey(), response); }
                catch (IllegalStateException ex) { status.setText(ex.getMessage()); }
            }
        });
        context.add(pendingRequestButton, BorderLayout.NORTH); bottom.add(context, BorderLayout.NORTH);
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
        JPanel inputActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        inputActions.add(addContextButton); inputActions.add(commands); controls.add(inputActions, BorderLayout.WEST);
        actions.add(stop); actions.add(send); controls.add(actions, BorderLayout.EAST);
        JPanel footer = new JPanel(new BorderLayout(0, 2));
        JPanel parameters = new JPanel(); parameters.setLayout(new BoxLayout(parameters, BoxLayout.Y_AXIS));
        for (JComponent component : java.util.List.of(providerPicker, effortPicker, access, unrestricted)) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            component.setMaximumSize(new Dimension(Integer.MAX_VALUE, controlHeight));
            component.setMinimumSize(new Dimension(0, controlHeight)); parameters.add(component);
        }
        parameters.setVisible(false);
        requestOptionsButton.setName("request-options");
        requestOptionsButton.addActionListener(e -> { parameters.setVisible(!parameters.isVisible()); revalidate(); });
        JPanel parameterArea = new JPanel(new BorderLayout());
        parameterArea.add(requestOptionsButton, BorderLayout.NORTH); parameterArea.add(parameters, BorderLayout.CENTER);
        footer.add(controls, BorderLayout.NORTH); footer.add(parameterArea, BorderLayout.CENTER);
        bottom.add(footer, BorderLayout.SOUTH); add(bottom, BorderLayout.SOUTH);
        stop.setEnabled(false); stop.setVisible(false);
        send.addActionListener(e -> send()); stop.addActionListener(e -> cancel());
        clear.setText("+"); clear.setToolTipText("Новый чат");
        clear.addActionListener(e -> {
            String providerId = session.providerId;
            saveSession(); var created = sessions.create(); created.providerId = providerId; created.workingDirectory = session.workingDirectory;
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
        input.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "hide-commands");
        input.getActionMap().put("hide-commands", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { hideCommands(); updateHistoryNavigation(); }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke("F1"), "command-help");
        input.getActionMap().put("command-help", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { showCommandHelp(highlightedCommand); }
        });
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
            if (active == null && !session.remoteId().isEmpty()) readCodexSession(session.remoteId());
        }
    }

    private void updateAccessLabel() {
        if (sessions.unrestrictedAccess) sessions.writeAccess = true;
        access.setSelected(sessions.writeAccess);
        unrestricted.setSelected(sessions.unrestrictedAccess);
        access.setText(sessions.writeAccess ? sessions.interactiveApprovals ? "Auto" : "Редактирование" : "Только чтение");
        access.getAccessibleContext().setAccessibleName("Режим: " + access.getText());
        unrestricted.getAccessibleContext().setAccessibleName("Режим без ограничений");
        access.setEnabled(active == null && !readingSession && !sessions.unrestrictedAccess);
    }
    private boolean confirmUnrestrictedAccess() {
        return Messages.showYesNoDialog(project,
                "Codex получит доступ к сети, .git, файлам вне проекта и сможет запускать команды без sandbox.\n\n"
                        + "Включайте этот режим только для доверенной задачи и проекта.",
                "Включить режим без ограничений?", Messages.getWarningIcon()) == Messages.YES;
    }
    private void editedInput() {
        if (!navigatingHistory) historyPosition = -1;
        updateHistoryNavigation();
        saveSession(); suggestCommands();
    }
    private void updateHistoryNavigation() {
        var up = KeyStroke.getKeyStroke("UP");
        var down = KeyStroke.getKeyStroke("DOWN");
        if (commandSuggestions.isVisible() || input.getText().isEmpty() || historyPosition >= 0) {
            input.getInputMap().put(up, "previous-prompt");
            input.getInputMap().put(down, "next-prompt");
        } else {
            input.getInputMap().remove(up);
            input.getInputMap().remove(down);
        }
    }
    private void navigateHistory(int direction) {
        if (commandSuggestions.isVisible()) {
            int count = commandSuggestions.getComponentCount();
            if (count > 0) {
                commandIndex = Math.floorMod(commandIndex + direction, count);
                if (commandSuggestions.getComponent(commandIndex) instanceof JMenuItem item) {
                    Object id = item.getClientProperty("command-id"); if (id instanceof String name) highlightedCommand = name;
                    MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{commandSuggestions, item});
                }
            }
            return;
        }
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

    private Path workingRoot() {
        String path = session.workingDirectory.isBlank() ? project.getBasePath() : session.workingDirectory;
        if (path == null) throw new IllegalStateException("Откройте локальный проект."); return Path.of(path).toAbsolutePath().normalize();
    }
    private org.agty.aiassistant.chat.CommandAvailability.Context commandContext() {
        var service = project.getService(org.agty.aiassistant.execution.ChatExecutionService.class);
        return new org.agty.aiassistant.chat.CommandAvailability.Context(session.providerId, project.getBasePath() != null,
                !session.remoteId().isBlank(), active != null, sessions.experimentalApi, service.signedIn(),
                org.agty.aiassistant.core.CodexCompatibility.observed(CodexSettings.getInstance().getExecutablePath()));
    }
    private JPopupMenu commandMenu(String prefix) {
        JPopupMenu menu = new JPopupMenu();
        for (var command : SlashCommands.available(session.providerId)) {
            String query = prefix.startsWith("/") ? prefix.substring(1) : prefix;
            if (!(command.name() + " " + command.description() + " " + command.category()).toLowerCase(java.util.Locale.ROOT)
                    .contains(query.toLowerCase(java.util.Locale.ROOT))) continue;
            var availability = org.agty.aiassistant.chat.CommandAvailability.evaluate(command, commandContext());
            JMenuItem item = new JMenuItem(command.name() + " — " + command.description()); item.putClientProperty("command-id", command.name());
            item.setEnabled(availability.available());
            item.setToolTipText(command.category() + " · " + command.syntax() + " · " + availability.reason() + " · F1: описание и примеры");
            item.addChangeListener(e -> { if (item.isArmed()) highlightedCommand = command.name(); });
            item.addActionListener(e -> {
                hideCommands();
                input.setText(command.name() + (command.syntax().equals(command.name()) ? "" : " "));
                input.requestFocusInWindow(); updateHistoryNavigation();
            });
            var documentation = new JPopupMenu(); var description = new JMenuItem("Описание и примеры");
            description.addActionListener(e -> showCommandHelp(command.name())); documentation.add(description);
            item.setComponentPopupMenu(documentation);
            menu.add(item);
        }
        return menu;
    }
    private void showCommandHelp(String command) {
        project.getService(org.agty.aiassistant.help.CommandHelpService.class).updateContext(commandContext());
        project.getService(org.agty.aiassistant.help.CommandHelpService.class).open(command, example -> {
            if (disposed) return;
            String draft = input.getText();
            input.setText(draft.isBlank() ? example : draft + "\n" + example);
            input.requestFocusInWindow();
        });
    }
    private void hideCommands() {
        commandSuggestions.setVisible(false); commandIndex = -1; updateHistoryNavigation();
    }
    private void suggestCommands() {
        SwingUtilities.invokeLater(() -> {
            if (disposed) return;
            String prefix = input.getText();
            if (!input.isShowing() || !prefix.matches("/[^\\s]*")) { hideCommands(); return; }
            commandIndex = -1; commandSuggestions.removeAll();
            var menu = commandMenu(prefix);
            while (menu.getComponentCount() > 0) commandSuggestions.add(menu.getComponent(0));
            if (commandSuggestions.getComponentCount() > 0) {
                commandSuggestions.setFocusable(false);
                commandSuggestions.show(input, 0, -commandSuggestions.getPreferredSize().height);
                updateHistoryNavigation();
            } else hideCommands();
        });
    }
    private void commandMessage(ChatSessions.Message message) {
        session.messages.add(message);
        if (!disposed) { loadingSession = true; transcript.synchronize(session.messages); loadingSession = false; }
    }
    void executeCommand(SlashCommands.Invocation command) {
        if (disposed) return;
        if (SlashCommands.available(session.providerId).stream().noneMatch(c -> c.name().equals(command.name()))) {
            transcript.message("Команда", "Команда `" + command.name().replace("`", "") + "` не поддерживается. Введите `/help`. Она не отправлена модели."); return;
        }
        if (advancedCommands.execute(command)) return;
        if (!command.argument().isBlank() && !command.name().equals("/resume") && !command.name().equals("/permissions") && !command.name().equals("/model") && !command.name().equals("/help") && !command.name().equals("/mention")) {
            transcript.message("Команда", "У этой команды нет аргументов. Введите `/help`."); return;
        }
        switch (command.name()) {
            case "/help" -> showCommandHelp(command.argument());
            case "/new" -> clear.doClick();
            case "/interrupt" -> cancel();
            case "/resume" -> {
                if (command.argument().isBlank()) {
                    if (sessionMenuButton.isShowing()) sessionMenu().show(sessionMenuButton, 0, sessionMenuButton.getHeight());
                    else transcript.message("Сессии", "Используйте `/resume UUID` или меню «Чаты».");
                }
                else readCodexSession(command.argument());
            }
            case "/permissions" -> {
                if (active != null || readingSession) { status.setText("Измените режим после завершения запроса."); return; }
                if (command.argument().equals("auto")) {
                    sessions.interactiveApprovals = true; sessions.unrestrictedAccess = false; sessions.writeAccess = true;
                } else if (command.argument().equals("read-only")) {
                    sessions.interactiveApprovals = false;
                    sessions.unrestrictedAccess = false; sessions.writeAccess = false;
                } else if (command.argument().equals("workspace-write")) {
                    sessions.interactiveApprovals = false;
                    sessions.unrestrictedAccess = false; sessions.writeAccess = true;
                } else if (command.argument().equals("danger-full-access")) {
                    if (!confirmUnrestrictedAccess()) { transcript.message("Режим", "Режим без ограничений не включён."); return; }
                    sessions.interactiveApprovals = false; sessions.unrestrictedAccess = true; sessions.writeAccess = true;
                } else if (!command.argument().isBlank()) {
                    transcript.message("Режим", "Используйте `/permissions read-only`, `/permissions workspace-write` или `/permissions danger-full-access`."); return;
                }
                updateAccessLabel();
                transcript.message("Режим", sessions.unrestrictedAccess ? "Sandbox отключён со следующего запроса: разрешены сеть и полный доступ к файлам."
                        : sessions.writeAccess ? "Изменение файлов проекта разрешено со следующего запроса." : "Только чтение со следующего запроса.");
            }
            case "/mention" -> {
                if (command.argument().isBlank()) addFilesContext();
                else {
                    var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(workingRoot().resolve(command.argument()).normalize());
                    if (file == null) status.setText("Файл не найден.");
                    else addCollectedContext(ProjectContextCollector.files(workingRoot(), new ProjectAccessPolicy(workingRoot()), java.util.List.of(file)));
                }
            }
            case "/ide" -> addCurrentContext();
            case "/diff" -> showChanges();
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
        Path root = workingRoot();
        var target = session; long generation = sessionLoad;
        if (choose) status.setText("Загружаю модели Codex…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).refreshAccount(binary, root);
                var catalog = project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).connections().models(binary, root);
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
        boolean writeAccess = sessions.writeAccess, unrestrictedAccess = sessions.unrestrictedAccess, interactive = sessions.interactiveApprovals;
        String binary = CodexSettings.getInstance().getExecutablePath(); Path root = workingRoot();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String result;
            project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).refreshAccount(binary, root);
            try { result = project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).connections()
                    .status(binary, root, target.remoteId(), selectedModel, selectedEffort, writeAccess, unrestrictedAccess, interactive); }
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
        sessionMenuButton.setText(title + " ▾"); sessionMenuButton.setToolTipText(sessionLabel.getToolTipText());
        String accessLabel = sessions.unrestrictedAccess ? "Полный доступ" : sessions.interactiveApprovals && sessions.writeAccess ? "Auto" : sessions.writeAccess ? "Запись проекта" : "Чтение";
        requestOptionsButton.setText((session.selectedModel.isBlank() ? "Модель" : session.selectedModel)
                + " · " + session.reasoningEffort + " · " + accessLabel
                + (session.planMode ? " · Plan" : "") + (session.serviceTier.isBlank() ? "" : " · " + session.serviceTier) + " ▾");
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
        Path root = workingRoot();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var available = project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).connections().sessions(binary, root);
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
        var running = sessions.sessions.stream().filter(candidate -> candidate.remoteId().equals(id)
                && taskRunner.task(candidate.id) != null && taskRunner.task(candidate.id).active()).findFirst();
        if (running.isPresent()) { selectSession(running.get()); return; }
        if (project.getBasePath() == null || disposed) return;
        try { UUID.fromString(id); }
        catch (IllegalArgumentException e) { status.setText("UID должен быть UUID сессии Codex."); return; }
        long generation = ++sessionLoad;
        readingSession = true; send.setEnabled(false); status.setText("Читаю историю сессии…");
        String binary = CodexSettings.getInstance().getExecutablePath();
        var known = sessions.sessions.stream().filter(candidate -> candidate.remoteId().equals(id)).findFirst().orElse(null);
        Path root = known != null && !known.workingDirectory.isBlank() ? Path.of(known.workingDirectory) : workingRoot();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var found = project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).connections().read(binary, root, id);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed || project.isDisposed() || generation != sessionLoad) return;
                    saveSession();
                    var selected = sessions.sessions.stream().filter(s -> s.providerId.equals("codex") && s.remoteId().equals(id)).findFirst().orElse(null);
                    if (selected == null) { selected = found; sessions.sessions.add(selected); }
                    else if (!found.messages.isEmpty() && (taskRunner.task(selected.id) == null || !taskRunner.task(selected.id).active()))
                        selected.replaceNativeMessages(found.messages);
                    selected.historyCursor = found.historyCursor;
                    active = null; elapsedTimer.stop();
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

    private void loadOlderHistory() {
        if (project.getBasePath() == null || session.historyCursor.isBlank()) return;
        var target = session; String cursor = target.historyCursor;
        olderHistoryButton.setEnabled(false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var page = project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).connections()
                        .historyPage(CodexSettings.getInstance().getExecutablePath(), (target.workingDirectory.isBlank() ? Path.of(project.getBasePath()) : Path.of(target.workingDirectory)), target.remoteId(), cursor);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed || project.isDisposed()) return;
                    var existing = target.messages.stream().map(m -> m.id).collect(java.util.stream.Collectors.toSet());
                    var merged = new java.util.ArrayList<ChatSessions.Message>();
                    for (var message : page.messages()) if (existing.add(message.id)) merged.add(message);
                    merged.addAll(target.messages); target.messages = merged; target.historyCursor = page.nextCursor();
                    olderHistoryButton.setEnabled(true);
                    if (target == session) {
                        loadingSession = true; transcript.synchronize(target.messages); loadingSession = false;
                        olderHistoryButton.setVisible(!target.historyCursor.isBlank());
                    }
                });
            } catch (Exception ex) {
                ApplicationManager.getApplication().invokeLater(() -> { if (!disposed) { olderHistoryButton.setEnabled(true); status.setText(ex.getMessage()); } });
            }
        });
    }
    public void selectSessionById(String id) {
        sessions.sessions.stream().filter(candidate -> candidate.id.equals(id)).findFirst().ifPresent(this::selectSession);
    }
    private void selectSession(ChatSessions.Session selected) {
        if (selected.archived && !selected.remoteId().isBlank()) { advancedCommands.restoreArchived(selected); return; }
        selected.closed = false;
        sessionLoad++; readingSession = false; saveSession();
        active = null; elapsedTimer.stop();
        session = selected; sessions.selected = session.id;
        historyPosition = -1; historyDraft = "";
        sessions.providerSelections.put(session.providerId, session.id);
        refreshSessions(); loadSession(); busy(false);
        var selectedTask = taskRunner.task(session.id);
        if (selectedTask != null) showTask(selectedTask);
        if (selectedTask == null && nativeSessions && session.providerId.equals("codex") && !session.remoteId().isEmpty()) readCodexSession(session.remoteId());
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
        menu.add(copy);
        JMenuItem localArchive = new JMenuItem("Сохранённая локальная история…"); localArchive.setEnabled(!session.localArchive.isEmpty());
        localArchive.addActionListener(e -> showLocalArchive()); menu.add(localArchive); menu.addSeparator();
        JMenuItem close = new JMenuItem("Завершить текущую сессию");
        close.addActionListener(e -> finishSession()); menu.add(close); menu.addSeparator();
        ButtonGroup group = new ButtonGroup();
        for (var item : sessions.sessions) {
            JRadioButtonMenuItem entry = new JRadioButtonMenuItem((taskRunner.task(item.id) != null && taskRunner.task(item.id).active() ? taskRunner.task(item.id).status + " · " : item.closed ? "Завершена · " : "") + item.title + " · " + item.providerId + " · "
                    + (item.remoteId().isEmpty() ? item.id : item.remoteId()), item == session);
            entry.putClientProperty("html.disable", true);
            entry.setActionCommand(item.id); entry.addActionListener(e -> selectSession(item));
            group.add(entry); menu.add(entry);
        }
        return menu;
    }
    private void showLocalArchive() {
        var messages = java.util.List.copyOf(session.localArchive);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                StringBuilder text = new StringBuilder("# Локальные сообщения до перечитывания Codex\n\n");
                for (var message : messages) text.append("## ").append(message.title).append("\n\n")
                        .append(org.agty.aiassistant.history.MessageArtifacts.getInstance().text(message)).append("\n\n");
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed || project.isDisposed()) return;
                    var file = new com.intellij.testFramework.LightVirtualFile("Локальная история.md", text.toString()); file.setWritable(false);
                    com.intellij.openapi.application.WriteIntentReadAction.run((Runnable) () -> com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true));
                });
            } catch (Exception e) { ApplicationManager.getApplication().invokeLater(() -> { if (!disposed) status.setText(e.getMessage()); }); }
        });
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
        project.getService(org.agty.aiassistant.help.CommandHelpService.class).updateContext(commandContext());
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
        session.contextSources = pinnedContext.stream().map(item -> {
            var source = new ChatSessions.ContextSource(); source.title = item.title(); source.path = item.path(); source.startLine = item.startLine(); source.endLine = item.endLine(); source.text = item.text(); return source;
        }).collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        session.includeContext = includeContext.isSelected();
        session.history = history.toString();
        session.draft = input.getText();
        session.messages = transcript.snapshot();
    }
    private void loadSession() {
        olderHistoryButton.setVisible(!session.historyCursor.isBlank());
        pendingRequestButton.setVisible(false);
        loadingSession = true;
        pinnedContext.clear();
        for (var source : session.contextSources) pinnedContext.add(new ContextItem(source.title, source.path, source.startLine, source.endLine, source.text));
        includeContext.setSelected(session.includeContext); updateContextLabel();
        history.setLength(0); history.append(session.history);
        transcript.restore(session.messages);
        input.setText(session.draft);
        loadingSession = false;
        if (session.messages.isEmpty()) welcome();
        saveSession();
        showUid();
        status.setText("Готово");
        var task = taskRunner.task(session.id);
        if (task != null) showTask(task);
    }

    private void welcome() {
        transcript.message("AGTY AI Assistant", "Задайте вопрос о проекте. **Enter** — отправить, **Shift+Enter** — новая строка.\n\n"
                + "Ответы поддерживают Markdown и ссылки на исходники. Карточки хода работы можно раскрывать.\n\n"
                + "Выберите LLM под полем ввода. История и последняя выбранная сессия сохраняются для этого проекта.");
    }

    private void send() {
        if (disposed || project.isDisposed()) return;
        if (commandSuggestions.isVisible() && commandIndex >= 0 && commandIndex < commandSuggestions.getComponentCount()
                && commandSuggestions.getComponent(commandIndex) instanceof JMenuItem item) {
            if (!item.isEnabled()) { status.setText(item.getToolTipText()); return; }
            item.doClick(); return;
        }
        String question = input.getText();
        String commandText = question.trim();
        var command = SlashCommands.parse(commandText);
        if (command != null) {
            if (SlashCommands.find(command.name()) == null) {
                transcript.message("Команда", "Команда не поддерживается. Введите /help; черновик сохранён.");
                status.setText("Неизвестная команда. Введите /help; черновик сохранён."); return;
            }
            var descriptor = SlashCommands.find(command.name());
            var availability = org.agty.aiassistant.chat.CommandAvailability.evaluate(descriptor, commandContext());
            if (!availability.available()) { status.setText(availability.reason()); return; }
            String argumentError = SlashCommands.argumentError(descriptor, command.argument());
            if (!argumentError.isEmpty()) { status.setText(argumentError + "; черновик сохранён."); return; }
            rememberPrompt(question); input.setText(""); hideCommands(); executeCommand(command); return;
        }
        if (readingSession) return;
        if (active != null) {
            int choice = Messages.showDialog(project, "Как передать сообщение выполняющейся задаче?", "Сообщение во время выполнения",
                    new String[]{"Уточнить текущую задачу", "После завершения", "Сохранить черновик"}, 2, Messages.getQuestionIcon());
            if (choice == 0) { taskRunner.steer(session.id, question); input.setText(""); }
            else if (choice == 1) sendQuestion(question, true);
            return;
        }
        sendQuestion(commandText.startsWith("//") ? commandText.substring(1) : question);
    }

    public void submitEditorAction(String question, CollectedContext context) {
        if (disposed || project.isDisposed()) return;
        if (context == null || context.items().isEmpty()) {
            status.setText(context == null || context.notes().isEmpty() ? "Контекст редактора не найден." : context.notes().getFirst());
            return;
        }
        pinnedContext.clear();
        pinnedContext.addAll(context.items());
        includeContext.setSelected(true);
        updateContextLabel();
        input.setText(question);
        if (readingSession || active != null) {
            status.setText("Запрос из редактора подготовлен; отправьте после завершения текущей операции.");
            return;
        }
        send();
    }
    private void sendQuestion(String question) { sendQuestion(question, false); }
    private void sendQuestion(String question, boolean queue) {
        if (disposed || project.isDisposed()) return;
        if (project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).reserved(session.id)) { status.setText("Операция с чатом ещё выполняется."); return; }
        if (question.isBlank()) return;
        String root = session.workingDirectory.isBlank() ? project.getBasePath() : session.workingDirectory;
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
            provider = nativeSessions && choice.id().equals("codex")
                    ? new org.agty.aiassistant.core.CodexChatProvider(CodexSettings.getInstance().getExecutablePath(),
                        project.getService(org.agty.aiassistant.execution.ChatExecutionService.class).connections(), sessions.experimentalApi)
                    : choice.factory().get();
        }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        final boolean writeAccess = sessions.writeAccess;
        final boolean unrestrictedAccess = sessions.unrestrictedAccess;
        CollectedContext requestContext = requestContext(rootPath, policy);
        String prompt = "You are assisting inside IntelliJ IDEA. Answer in the user's language. "
                + (unrestrictedAccess ? "Current access mode is unrestricted: network access and filesystem access outside the project are permitted. "
                    : writeAccess ? "Current access mode permits modifying files within the project directory to complete the user request. "
                    : "Current access mode is read-only. Do not modify files. ")
                + "Propose code in Markdown fenced blocks with language tags. "
                + "Link source locations using [method or file](relative/path/File.java#L42), with real line numbers. "
                + "Give brief user-facing progress updates while working, when supported. "
                + "The following is conversation context, followed by the current user request.\n\n"
                + requestContext.promptBlock()
                + (session.remoteId().isEmpty() ? history : "") + "\nUSER:\n" + question;
        final ChatProvider.Request run;
        final org.agty.aiassistant.chat.RequestOptions options = new org.agty.aiassistant.chat.RequestOptions(rootPath, session.remoteId(),
                session.selectedModel, session.reasoningEffort,
                unrestrictedAccess ? org.agty.aiassistant.chat.RequestOptions.Access.FULL
                        : writeAccess ? org.agty.aiassistant.chat.RequestOptions.Access.WORKSPACE_WRITE
                        : org.agty.aiassistant.chat.RequestOptions.Access.READ_ONLY,
                (session.planMode ? org.agty.aiassistant.chat.RequestOptions.Mode.PLAN : org.agty.aiassistant.chat.RequestOptions.Mode.WORK), includeContext.isSelected() ? session.imagePaths.stream().map(path -> new org.agty.aiassistant.chat.RequestOptions.Attachment("localImage", path)).toList() : java.util.List.of(), false, sessions.interactiveApprovals).withExtras(session.serviceTier, session.cyberAccessProgram);
        if (queue) {
            var target = session;
            try {
                saveSession(); taskRunner.enqueue(target, () -> provider.newRequest(options.withSession(target.remoteId())), rootPath, prompt, question, provider.name(), writeAccess || unrestrictedAccess);
                input.setText(""); status.setText("Сообщение добавлено в очередь");
            } catch (Exception e) { status.setText(e.getMessage()); }
            return;
        }
        try { run = provider.newRequest(options); }
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
        saveSession();
        try {
            taskRunner.start(session, run, rootPath, prompt, question, provider.name(), writeAccess || unrestrictedAccess);
            input.setText("");
        } catch (Exception e) { active = null; busy(false); status.setText(e.getMessage()); }
    }

    private void showTask(org.agty.aiassistant.execution.TaskRunner.Task task) {
        loadingSession = true;
        pendingRequestButton.setVisible(!task.pending.isEmpty());
        pendingRequestButton.setText("Требуется ответ (" + task.pending.size() + ")");
        transcript.synchronize(task.session.messages);
        history.setLength(0); history.append(task.session.history);
        active = task.active() ? task.request : null;
        activeRequestId = task.id; pendingQuestion = task.question;
        startedAt = task.startedAt; stage = task.status;
        showUid(); refreshSessions();
        loadingSession = false;
        busy(task.active()); status.setText(task.status + (taskRunner.queuedCount(session.id) > 0 ? " · очередь: " + taskRunner.queuedCount(session.id) : ""));
        if (task.active()) elapsedTimer.start();
        else {
            elapsedTimer.stop();
            suggestedDiff = extractUnifiedDiff(String.join("\n", task.session.messages.stream()
                    .filter(m -> m.id.startsWith(task.id + ":") && !m.activity).map(m -> m.markdown).toList()));
            com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh(null);
        }
    }

    private void showContextPreview() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        var model = new DefaultListModel<String>(); var sources = new JList<>(model);
        sources.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JTextArea preview = new JTextArea(24, 60); preview.setEditable(false); preview.setLineWrap(false);
        var automatic = new java.util.ArrayList<ContextItem>();
        Runnable reload = () -> {
            automatic.clear();
            if (session.autoEditorContext && pinnedContext.isEmpty()) automatic.addAll(ProjectContextCollector.currentEditor(project, workingRoot(), new ProjectAccessPolicy(workingRoot())).items());
            model.clear(); for (var item : pinnedContext) model.addElement("Текст · " + item.location());
            for (String path : session.imagePaths) model.addElement("Изображение · " + path);
            if (session.autoEditorContext && pinnedContext.isEmpty()) for (var item : automatic) model.addElement("Авто · " + item.location());
            if (!model.isEmpty()) sources.setSelectedIndex(0); else preview.setText(session.autoEditorContext ? "Явный список пуст. При включённом контексте активный редактор добавляется автоматически." : "Явный список пуст. Автоконтекст редактора отключён.");
        };
        sources.addListSelectionListener(e -> {
            int selected = sources.getSelectedIndex();
            if (selected >= 0 && selected < pinnedContext.size()) preview.setText(pinnedContext.get(selected).text());
            else if (selected >= pinnedContext.size() && selected - pinnedContext.size() < session.imagePaths.size())
                preview.setText("Локальное изображение будет передано Codex при следующем запросе:\n" + session.imagePaths.get(selected - pinnedContext.size()));
            else if (selected >= pinnedContext.size() + session.imagePaths.size() && selected - pinnedContext.size() - session.imagePaths.size() < automatic.size())
                preview.setText(automatic.get(selected - pinnedContext.size() - session.imagePaths.size()).text());
            preview.setCaretPosition(0);
        });
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(sources), new JScrollPane(preview)); split.setResizeWeight(.3); panel.add(split, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT)); var enabled = new JCheckBox("Включать контекст в следующий запрос", includeContext.isSelected());
        enabled.addActionListener(e -> { includeContext.setSelected(enabled.isSelected()); saveSession(); }); actions.add(enabled);
        JButton remove = new JButton("Удалить выбранные"); remove.addActionListener(e -> {
            int textCount = pinnedContext.size(), imageCount = session.imagePaths.size(); int[] selected = sources.getSelectedIndices();
            for (int i = selected.length - 1; i >= 0; i--) {
                if (selected[i] < textCount) pinnedContext.remove(selected[i]);
                else if (selected[i] < textCount + imageCount) session.imagePaths.remove(selected[i] - textCount);
                else session.autoEditorContext = false;
            }
            updateContextLabel(); saveSession(); reload.run();
        }); actions.add(remove);
        JButton openImage = new JButton("Открыть изображение"); openImage.addActionListener(e -> {
            int selected = sources.getSelectedIndex() - pinnedContext.size();
            if (selected < 0 || selected >= session.imagePaths.size()) return;
            Path path = Path.of(session.imagePaths.get(selected)); new ProjectAccessPolicy(workingRoot()).image(path).requireAllowed();
            var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file != null) com.intellij.openapi.application.WriteIntentReadAction.run((Runnable) () -> com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(file, true));
        }); actions.add(openImage); panel.add(actions, BorderLayout.NORTH); panel.setPreferredSize(JBUI.size(820, 450)); reload.run();
        JOptionPane.showMessageDialog(this, panel, "Контекст следующего запроса", JOptionPane.PLAIN_MESSAGE);
    }

    private void addCurrentContext() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        Path root = workingRoot();
        final ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(root); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        CollectedContext collected = ProjectContextCollector.currentEditor(project, root, policy);
        addCollectedContext(collected);
    }

    private void addImageContext() {
        var descriptor = new FileChooserDescriptor(true, false, false, false, false, true).withTitle("Добавить изображения")
                .withFileFilter(file -> file.getExtension() != null && java.util.Set.of("png", "jpg", "jpeg", "webp", "gif").contains(file.getExtension().toLowerCase(java.util.Locale.ROOT)));
        var policy = new ProjectAccessPolicy(workingRoot());
        for (var file : FileChooser.chooseFiles(descriptor, project, null)) {
            var verdict = policy.image(file.toNioPath());
            if (!verdict.allowed()) { status.setText(verdict.message()); continue; }
            if (file.getLength() > 20L * 1024 * 1024) { status.setText("Изображение превышает 20 MiB."); continue; }
            String path = file.toNioPath().toAbsolutePath().normalize().toString(); if (!session.imagePaths.contains(path)) session.imagePaths.add(path);
        }
        includeContext.setSelected(true); updateContextLabel(); saveSession();
    }
    private void addFilesContext() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        FileChooserDescriptor descriptor = new FileChooserDescriptor(true, false, false, false, false, true)
                .withTitle("Добавить файлы в AI-контекст")
                .withDescription("Выберите один или несколько текстовых файлов проекта.");
        java.util.List<com.intellij.openapi.vfs.VirtualFile> files = java.util.List.of(FileChooser.chooseFiles(descriptor, project, null));
        if (files.isEmpty()) return;
        Path root = workingRoot();
        final ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(root); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        addCollectedContext(ProjectContextCollector.files(root, policy, files));
    }

    private void addDiagnosticsContext() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        Path root = workingRoot();
        final ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(root); }
        catch (RuntimeException e) { status.setText(e.getMessage()); return; }
        addCollectedContext(ProjectContextCollector.diagnostics(project, root, policy));
    }

    private void addGitDiffContext() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        Path root = workingRoot();
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
        JMenuItem image = new JMenuItem("Изображение…"); image.addActionListener(e -> addImageContext());
        menu.add(editor); menu.add(files); menu.add(diagnostics); menu.add(diff); menu.add(image);
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
        updateContextLabel(); saveSession();
        status.setText("Контекст добавлен: " + collected.items().getFirst().location());
    }

    private void clearContextItems() {
        pinnedContext.clear(); session.imagePaths.clear();
        updateContextLabel(); saveSession();
        status.setText("Список контекста очищен.");
    }

    private void showChanges() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        new ChangesDialog(project, workingRoot(), taskRunner, taskRunner.task(session.id)).show();
    }
    private void showDiffReviewDialog() {
        if (project.getBasePath() == null) { status.setText("Откройте локальный проект."); return; }
        JTextArea input = new JTextArea(24, 92);
        input.setText(suggestedDiff);
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
        java.util.Set<Integer> selectedHunks;
        try { selectedHunks = selectHunks(changeSet); }
        catch (IllegalArgumentException e) { status.setText(e.getMessage()); return; }
        if (selectedHunks == null) return;
        String preview = changeSet.preview() + (conflicts.isEmpty() ? "\n\nКонфликтов не найдено."
                : "\n\nКонфликты:\n- " + String.join("\n- ", conflicts));
        if (!selectedHunks.isEmpty()) preview = changeSet.preview(selectedHunks) + "\n\nВыбраны отдельные фрагменты: " + selectedHunks.size();
        JTextArea area = new JTextArea(preview, 26, 100);
        area.setEditable(false);
        area.setLineWrap(false);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(JBUI.size(900, 520));
        int answer = JOptionPane.showConfirmDialog(this, scroll, "Diff review", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        if (!conflicts.isEmpty()) { status.setText("Diff не применён: есть конфликты."); return; }
        applyChangeSet(root, policy, changeSet, selectedHunks);
    }

    private java.util.Set<Integer> selectHunks(ChangeSet changeSet) {
        java.util.List<ChangeSet.Hunk> hunks = changeSet.hunks();
        if (hunks.size() <= 1) return java.util.Set.of();
        StringBuilder prompt = new StringBuilder("Фрагменты diff:\n");
        for (int i = 0; i < hunks.size(); i++) {
            ChangeSet.Hunk hunk = hunks.get(i);
            prompt.append(i + 1).append(". ").append(hunk.path()).append(" @ +").append(hunk.newStart()).append('\n');
        }
        prompt.append("\nВведите номера через запятую или оставьте пустым, чтобы принять все:");
        String value = Messages.showInputDialog(project, prompt.toString(), "Выбор фрагментов", null);
        if (value == null) return null;
        if (value.isBlank()) return java.util.Set.of();
        java.util.Set<Integer> selected = new java.util.LinkedHashSet<>();
        for (String part : value.split(",")) {
            int index;
            try { index = Integer.parseInt(part.strip()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Некорректный номер фрагмента: " + part); }
            if (index < 1 || index > hunks.size()) throw new IllegalArgumentException("Нет фрагмента #" + index);
            selected.add(hunks.get(index - 1).id());
        }
        return java.util.Set.copyOf(selected);
    }

    private void applyChangeSet(Path root, ProjectAccessPolicy policy, ChangeSet changeSet, java.util.Set<Integer> selectedHunks) {
        try {
            java.util.Map<String, String> backup = new java.util.LinkedHashMap<>();
            int[] appliedFiles = {0};
            CommandProcessor.getInstance().executeCommand(project, () -> ApplicationManager.getApplication().runWriteAction(() -> {
                for (ChangeSet.FileChange file : changeSet.files()) {
                    if (!selectedHunks.isEmpty() && file.hunks().stream().noneMatch(hunk -> selectedHunks.contains(hunk.id()))) continue;
                    Path path = root.resolve(file.path()).normalize();
                    ProjectAccessPolicy.Verdict verdict = policy.path(path, true);
                    verdict.requireAllowed();
                    String current;
                    try { current = Files.exists(path) ? Files.readString(path) : ""; }
                    catch (Exception e) { throw new IllegalStateException("Не удалось прочитать " + file.path(), e); }
                    if (file.conflictsWith(current)) throw new IllegalStateException("Файл изменился после review: " + file.path());
                    backup.put(file.path(), current);
                    Document document = writeFile(path, file.afterText(selectedHunks));
                    if (document != null) {
                        formatChangedRanges(document, file, selectedHunks);
                        FileDocumentManager.getInstance().saveDocument(document);
                    }
                    appliedFiles[0]++;
                }
            }), "Apply AI ChangeSet", null);
            lastReviewBackup = java.util.Map.copyOf(backup);
            undoReviewButton.setEnabled(!lastReviewBackup.isEmpty());
            suggestedDiff = "";
            com.intellij.openapi.vfs.VirtualFileManager.getInstance().asyncRefresh(null);
            status.setText("ChangeSet применён: файлов " + appliedFiles[0]);
        } catch (RuntimeException e) {
            status.setText("ChangeSet не применён: " + e.getMessage());
        }
    }

    private Document writeFile(Path path, String text) {
        try {
            Files.createDirectories(path.getParent());
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            if (file != null) {
                var document = FileDocumentManager.getInstance().getDocument(file);
                if (document != null) {
                    document.setText(text);
                    FileDocumentManager.getInstance().saveDocument(document);
                    return document;
                }
            }
            Files.writeString(path, text);
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось записать " + path.getFileName(), e);
        }
    }

    private void formatChangedRanges(Document document, ChangeSet.FileChange file, java.util.Set<Integer> selectedHunks) {
        if (document.getLineCount() == 0) return;
        PsiDocumentManager.getInstance(project).commitDocument(document);
        var psi = PsiDocumentManager.getInstance(project).getPsiFile(document);
        if (psi == null) return;
        java.util.List<TextRange> ranges = new java.util.ArrayList<>();
        for (ChangeSet.Hunk hunk : file.hunks()) {
            if (selectedHunks != null && !selectedHunks.isEmpty() && !selectedHunks.contains(hunk.id())) continue;
            int startLine = Math.max(0, hunk.newStart() - 1);
            int endLine = Math.min(document.getLineCount() - 1, startLine + Math.max(0, hunk.newCount() - 1));
            int start = document.getLineStartOffset(Math.min(startLine, document.getLineCount() - 1));
            int end = document.getLineEndOffset(Math.max(startLine, endLine));
            ranges.add(new TextRange(start, end));
        }
        if (!ranges.isEmpty()) CodeStyleManager.getInstance(project).reformatText(psi, ranges);
    }

    private void undoLastReview() {
        if (project.getBasePath() == null || lastReviewBackup.isEmpty()) return;
        Path root = workingRoot();
        java.util.Map<String, String> backup = lastReviewBackup;
        CommandProcessor.getInstance().executeCommand(project, () -> ApplicationManager.getApplication().runWriteAction(() -> {
            for (var entry : backup.entrySet()) writeFile(root.resolve(entry.getKey()).normalize(), entry.getValue());
        }), "Undo AI ChangeSet", null);
        lastReviewBackup = java.util.Map.of();
        undoReviewButton.setEnabled(false);
        status.setText("Последний ChangeSet откачен.");
    }

    private static String extractUnifiedDiff(String text) {
        int start = text.indexOf("diff --git ");
        if (start < 0) return "";
        return text.substring(start).replace("```", "").strip();
    }

    private CollectedContext requestContext(Path root, ProjectAccessPolicy policy) {
        if (!includeContext.isSelected()) return CollectedContext.empty("Контекст отключён для этого запроса.");
        if (!pinnedContext.isEmpty()) return ProjectContextCollector.validated(root, policy, pinnedContext);
        return session.autoEditorContext ? ProjectContextCollector.currentEditor(project, root, policy) : CollectedContext.empty("Автоконтекст редактора отключён.");
    }

    private CollectedContext previewContext(Path root, ProjectAccessPolicy policy) {
        if (!pinnedContext.isEmpty()) return ProjectContextCollector.validated(root, policy, pinnedContext);
        CollectedContext active = session.autoEditorContext ? ProjectContextCollector.currentEditor(project, root, policy) : CollectedContext.empty("Автоконтекст редактора отключён.");
        if (active.items().isEmpty()) return active;
        return new CollectedContext(active.items(), withNote(active.notes(), "Список контекста пуст; будет использован активный редактор."));
    }

    private java.util.List<String> withNote(java.util.List<String> notes, String note) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>(notes);
        result.add(note);
        return java.util.List.copyOf(result);
    }

    private void updateContextLabel() {
        int count = pinnedContext.size() + session.imagePaths.size();
        includeContext.setText(count == 0 ? "Контекст" : "Контекст (" + count + ")");
        includeContext.getAccessibleContext().setAccessibleName(includeContext.getText());
        clearContextButton.setEnabled(count > 0 && active == null && !readingSession);
    }

    private void cancel() {
        taskRunner.cancel(session.id);
        status.setText("Остановка…");
    }

    private void busy(boolean value) {
        access.setEnabled(!value && !readingSession && !sessions.unrestrictedAccess);
        unrestricted.setEnabled(!value && !readingSession);
        includeContext.setEnabled(!value && !readingSession);
        addContextButton.setEnabled(!value && !readingSession);
        contextButton.setEnabled(!value && !readingSession);
        clearContextButton.setEnabled(!value && !readingSession && !pinnedContext.isEmpty());
        reviewButton.setEnabled(!value && !readingSession);
        undoReviewButton.setEnabled(!value && !readingSession && !lastReviewBackup.isEmpty());
        progress.setVisible(value);
        send.setEnabled(!readingSession); send.setVisible(!value);
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
        try { taskSubscription.close(); } catch (Exception ignored) { }
        saveSession();
        hideCommands();
        disposed = true;
        elapsedTimer.stop();
        transcript.dispose();
        history.setLength(0);
    }
}
