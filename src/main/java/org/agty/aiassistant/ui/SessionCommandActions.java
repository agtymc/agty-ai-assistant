package org.agty.aiassistant.ui;

import com.google.gson.*;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ide.CopyPasteManager;
import org.agty.aiassistant.chat.*;
import org.agty.aiassistant.core.*;
import org.agty.aiassistant.execution.ChatExecutionService;
import org.agty.aiassistant.settings.CodexSettings;
import javax.swing.*;
import java.awt.datatransfer.StringSelection;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Client slash actions. Service RPC is kept separate from model prompts. */
final class SessionCommandActions {
    record Host(Project project, Supplier<ChatSessions.Session> current, Supplier<ChatSessions.ProjectSessions> sessions,
                Consumer<ChatSessions.Session> select, Runnable newChat, Runnable clearContext, Runnable refresh,
                Consumer<String> modelPrompt, Consumer<ChatSessions.Message> message, Consumer<String> status, Consumer<String> draft, BooleanSupplier live) { }
    private final Host host;
    private ChatSessions.Session responseTarget;
    private String loginId = "";
    SessionCommandActions(Host host) { this.host = host; }
    private ChatSessions.Session current() { return host.current.get(); }
    private Path directory() {
        String path = current().workingDirectory.isBlank() ? host.project.getBasePath() : current().workingDirectory;
        if (path == null) throw new IllegalStateException("Откройте локальный проект."); return Path.of(path).toAbsolutePath().normalize();
    }
    private boolean busy() { var task = service().runner().task(current().id); return task != null && task.active(); }
    private ChatExecutionService service() { return host.project.getService(ChatExecutionService.class); }
    private void idle() { if (busy()) throw new IllegalStateException("Завершите задачу выбранного чата."); }
    private void thread() { if (current().remoteId().isBlank()) throw new IllegalStateException("Сначала отправьте сообщение, чтобы создать thread Codex."); }
    private JsonObject threadParams() { thread(); JsonObject params = new JsonObject(); params.addProperty("threadId", current().remoteId()); return params; }
    private void experimental() { if (!host.sessions.get().experimentalApi) throw new IllegalStateException("Этот API 0.169.0 экспериментальный. Включите его явно: /experimental on."); }
    private static String value(JsonObject object, String key) { return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : ""; }
    private void oauth(String url) {
        var uri = java.net.URI.create(url);
        if (!Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null) throw new IllegalStateException("Codex вернул недопустимый URL авторизации.");
        BrowserUtil.browse(uri.toString());
    }
    private void message(String title, String text) {
        var target = responseTarget == null ? current() : responseTarget;
        var message = new ChatSessions.Message(); message.id = "command-" + UUID.randomUUID(); message.title = title; message.markdown = text;
        Runnable deliver = () -> { if (host.live.getAsBoolean() && current() == target) host.message.accept(message); else target.messages.add(message); };
        if (text.length() <= 16_000) deliver.run();
        else ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                org.agty.aiassistant.history.MessageArtifacts.getInstance().offload(message);
                ApplicationManager.getApplication().invokeLater(deliver);
            } catch (Exception e) { ApplicationManager.getApplication().invokeLater(() -> host.status.accept(e.getMessage())); }
        });
    }
    private void rpc(String method, JsonObject params, Consumer<JsonObject> result) {
        var target = current();
        boolean mutation = Set.of("thread/fork", "thread/delete", "thread/archive", "thread/approveGuardianDeniedAction").contains(method);
        if (mutation && !service().reserve(target.id)) throw new IllegalStateException("Операция с чатом ещё выполняется.");
        Path root = directory(); boolean experimental = host.sessions.get().experimentalApi;
        String binary = CodexSettings.getInstance().getExecutablePath(); host.status.accept("Выполняется " + method + "…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var response = service().connections().call(binary, root, experimental, method, params);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (host.project.isDisposed()) return;
                    try { responseTarget = target; result.accept(response); host.refresh.run(); }
                    catch (Exception e) { host.status.accept(e.getMessage()); }
                    finally { responseTarget = null; if (mutation) service().release(target.id); }
                });
            } catch (Exception e) {
                if (mutation) service().release(target.id);
                ApplicationManager.getApplication().invokeLater(() -> { if (!host.project.isDisposed()) {
                    responseTarget = target; message("Ошибка команды", method + ": " + e.getMessage()); responseTarget = null;
                    if (host.live.getAsBoolean() && current() == target) host.status.accept(method + ": " + e.getMessage());
                } });
            }
        });
    }
    private boolean confirm(String effect) { return Messages.showYesNoDialog(host.project, effect, "Действие Codex", Messages.getQuestionIcon()) == Messages.YES; }
    boolean execute(SlashCommands.Invocation command) {
        String argument = command.argument();
        try {
            switch (command.name()) {
                case "/clear" -> { host.clearContext.run(); host.newChat.run(); }
                case "/rename" -> {
                    idle(); String name = argument.isBlank() ? Messages.showInputDialog(host.project, "Название чата", "Переименовать", null) : argument;
                    if (name == null || name.isBlank()) return true;
                    var target = current();
                    if (target.remoteId().isBlank()) { target.title = name; target.customTitle = true; host.refresh.run(); }
                    else { var params = threadParams(); params.addProperty("name", name); rpc("thread/name/set", params, result -> { target.title = name; target.customTitle = true; }); }
                }
                case "/fork", "/side", "/btw" -> {
                    idle(); var params = threadParams(); params.addProperty("excludeTurns", true); var original = current();
                    rpc("thread/fork", params, result -> {
                        var thread = result.getAsJsonObject("thread"); var created = new ChatSessions.Session();
                        created.nativeSessionId = value(thread, "id"); UUID.fromString(created.nativeSessionId);
                        created.title = original.title + " · fork"; created.workingDirectory = original.workingDirectory;
                        created.selectedModel = original.selectedModel; created.reasoningEffort = original.reasoningEffort;
                        created.messages = new ArrayList<>(original.messages); created.draft = argument; host.sessions.get().sessions.add(created);
                        if (host.live.getAsBoolean() && current() == original) { host.select.accept(created); if (!argument.isBlank()) host.modelPrompt.accept(argument); }
                    });
                }
                case "/archive", "/delete" -> {
                    idle(); var target = current(); boolean delete = command.name().equals("/delete");
                    if (!confirm(delete ? "Безвозвратно удалить выбранную сессию Codex, её подагентов и сохранённые боковые чаты? Локальная история связанных чатов также будет удалена. Файлы проекта не откатываются." : "Архивировать выбранную сессию? Файлы проекта сохранятся.")) return true;
                    Runnable local = () -> {
                        if (delete) {
                            Set<String> removed = new HashSet<>(); removed.add(target.remoteId());
                            boolean added;
                            do { added = false; for (var candidate : host.sessions.get().sessions)
                                if (!candidate.parentThreadId.isBlank() && removed.contains(candidate.parentThreadId)) added |= removed.add(candidate.remoteId());
                            } while (added);
                            host.sessions.get().sessions.removeIf(candidate -> candidate == target || !candidate.remoteId().isBlank() && removed.contains(candidate.remoteId()));
                        } else { target.closed = true; target.archived = !target.remoteId().isBlank(); }
                        if (host.live.getAsBoolean() && current() == target) host.newChat.run();
                    };
                    if (target.remoteId().isBlank()) local.run();
                    else rpc(delete ? "thread/delete" : "thread/archive", threadParams(), result -> local.run());
                }
                case "/copy" -> {
                    var message = current().messages.reversed().stream().filter(m -> !m.activity && m.title.equals("Codex")).findFirst().orElseThrow(() -> new IllegalStateException("Нет ответа Codex для копирования."));
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        String text = org.agty.aiassistant.history.MessageArtifacts.getInstance().text(message);
                        ApplicationManager.getApplication().invokeLater(() -> CopyPasteManager.getInstance().setContents(new StringSelection(text)));
                    });
                }
                case "/export" -> {
                    JFileChooser chooser = new JFileChooser(); chooser.setSelectedFile(new java.io.File("codex-chat.md"));
                    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return true;
                    Path output = chooser.getSelectedFile().toPath();
                    var export = new org.agty.aiassistant.security.SafeExport(output);
                    requireSavedExportDestination(export.target());
                    if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) && !confirm("Перезаписать " + output + "?")) return true;
                    var messages = new ArrayList<>(current().localArchive);
                    messages.addAll(current().messages);
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try {
                            export.stage(writer -> {
                                for (var message : messages) writer.append("## ").append(message.title).append("\n\n")
                                        .append(org.agty.aiassistant.history.MessageArtifacts.getInstance().text(message)).append("\n\n");
                            });
                            ApplicationManager.getApplication().invokeLater(() -> {
                                try (export) {
                                    if (host.project.isDisposed()) return;
                                    WriteAction.run(() -> {
                                        requireSavedExportDestination(export.target()); export.commit();
                                        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(export.target());
                                    });
                                    host.status.accept("Экспортировано: " + output);
                                } catch (Exception e) { host.status.accept("Экспорт не выполнен: " + e.getMessage()); }
                            });
                        } catch (Exception e) {
                            try { export.close(); } catch (Exception cleanup) { e.addSuppressed(cleanup); }
                            ApplicationManager.getApplication().invokeLater(() -> host.status.accept("Экспорт не выполнен: " + e.getMessage()));
                        }
                    });
                }
                case "/pwd" -> message("Каталог", directory().toString());
                case "/cd" -> {
                    idle(); String path = argument.isBlank() ? Messages.showInputDialog(host.project, "Каталог для нового чата", "Рабочий каталог", null) : argument;
                    if (path == null || path.isBlank()) return true;
                    Path chosen = directory().resolve(path).normalize().toRealPath(); if (!Files.isDirectory(chosen)) throw new IllegalStateException("Это не каталог.");
                    var created = new ChatSessions.Session(); created.workingDirectory = chosen.toString(); created.title = chosen.getFileName().toString();
                    created.selectedModel = current().selectedModel; created.reasoningEffort = current().reasoningEffort;
                    host.sessions.get().sessions.add(created); host.clearContext.run(); host.select.accept(created);
                }
                case "/worktree" -> worktree();
                case "/agents" -> agents();
                case "/speed", "/daybreak" -> speedAndAccess(command.name(), argument);
                case "/plan" -> {
                    idle(); if (!argument.equals("off")) experimental();
                    if (!argument.isBlank() && !Set.of("on", "off").contains(argument)) throw new IllegalStateException("Синтаксис: /plan [on|off]");
                    var target = current();
                    if (argument.equals("off")) { target.planMode = false; host.refresh.run(); host.status.accept("Режим работы для следующего turn"); }
                    else rpc("collaborationMode/list", new JsonObject(), result -> {
                        boolean available = false; if (result.has("data")) for (var preset : result.getAsJsonArray("data"))
                            if (value(preset.getAsJsonObject(), "mode").equals("plan")) available = true;
                        if (!available) { message("Plan", "Сервер не предоставил режим Plan."); return; }
                        target.planMode = true; host.refresh.run(); host.status.accept("Plan для следующего turn; права не изменены");
                    });
                }
                case "/experimental" -> {
                    idle(); if (!Set.of("on", "off").contains(argument)) throw new IllegalStateException("Синтаксис: /experimental on|off");
                    service().experimental(argument.equals("on")); host.sessions.get().experimentalApi = argument.equals("on"); if (argument.equals("off")) current().planMode = false;
                    host.status.accept("Экспериментальный контракт 0.169.0: " + argument);
                }
                case "/goal" -> {
                    var params = threadParams(); String method = "thread/goal/get";
                    if (argument.equals("clear")) { params.addProperty("origin", "user"); method = "thread/goal/clear"; }
                    else if (!argument.isBlank()) {
                        params.addProperty("origin", "user"); method = "thread/goal/set";
                        if (argument.equals("pause")) params.addProperty("status", "paused");
                        else if (argument.equals("resume")) params.addProperty("status", "active");
                        else params.addProperty("objective", argument);
                    }
                    rpc(method, params, result -> message("Цель Codex", inventory(result)));
                }
                case "/approve" -> {
                    idle(); thread(); experimental();
                    var denials = service().runner().denials(current().id);
                    if (denials.isEmpty()) throw new IllegalStateException("В этом чате нет сохранённых отказов auto-review для одобрения.");
                    String[] names = denials.stream().map(event -> "Отказ · " + value(event, "id") + " · " + inventory(event.get("action"))).toArray(String[]::new);
                    String selected = (String) PlainText.choose(null, "Выберите действие для одобрения в контексте чата", "Одобрение auto-review", JOptionPane.QUESTION_MESSAGE, names, names[0]);
                    if (selected == null) return true; int index = java.util.Arrays.asList(names).indexOf(selected); var event = denials.get(index);
                    if (!confirm("Записать одобрение выбранного действия в нативный контекст этого чата?\n" + inventory(event.get("action"))
                            + "\nCodex 0.169.0 не выполняет действие этой командой. Одобрение будет доступно модели при следующих запросах в этом чате; оно не является одноразовым разрешением."
                            + "\nДля продолжения вы отдельно отправляете запрос. Общий режим доступа не изменяется.")) return true;
                    var target = current(); var params = GuardianRetry.approvalParams(target.remoteId(), event);
                    if (service().reserved(target.id)) throw new IllegalStateException("Операция с чатом ещё выполняется.");
                    // A lost acknowledgement must not invite resubmitting the same approval.
                    service().runner().consumeDenial(target.id, value(event, "id"));
                    rpc("thread/approveGuardianDeniedAction", params, result -> message("Одобрение передано Codex",
                            "Одобрение выбранного действия добавлено в контекст чата. Команда не запускала выполнение и не подтверждает, что файлы изменены. Для продолжения отправьте отдельный запрос; одобрение сохраняется в нативном контексте."));
                }
                case "/compact" -> {
                    idle(); thread(); Path root = directory(); var target = current();
                    var options = new RequestOptions(root, target.remoteId(), target.selectedModel, target.reasoningEffort,
                            RequestOptions.Access.READ_ONLY, RequestOptions.Mode.WORK, List.of(), false, host.sessions.get().interactiveApprovals, null, true);
                    var provider = new CodexChatProvider(CodexSettings.getInstance().getExecutablePath(), service().connections(), host.sessions.get().experimentalApi);
                    service().runner().start(target, provider.newRequest(options), root, "", "Сжатие контекста Codex", "Codex", false);
                }
                case "/recap" -> startReadOnlyPrompt("Кратко подведи итоги этого диалога: решения, внесённые изменения, проверки и оставшиеся задачи. Файлы не меняй.");
                case "/init" -> host.modelPrompt.accept("Подготовь или актуализируй AGENTS.md для этого проекта по его фактической структуре и проверенным командам. Сохрани существующие пользовательские инструкции. " + argument);
                case "/review" -> startReview(argument);
                case "/queue" -> {
                    if (current().queuedMessages.isEmpty()) message("Очередь", "Подготовленных сообщений нет.");
                    else {
                        String[] choices = current().queuedMessages.toArray(String[]::new);
                        String chosen = (String) PlainText.choose(null, "Выберите сообщение для вставки в черновик", "Очередь", JOptionPane.QUESTION_MESSAGE, choices, choices[0]);
                        if (chosen != null) host.draft.accept(chosen);
                    }
                }
                case "/login" -> {
                    if (argument.equals("cancel")) { var params = new JsonObject(); params.addProperty("loginId", loginId); rpc("account/login/cancel", params, result -> host.status.accept("Вход отменён")); }
                    else { var params = new JsonObject(); params.addProperty("type", "chatgpt"); rpc("account/login/start", params, result -> { loginId = value(result, "loginId"); oauth(value(result, "authUrl")); host.status.accept("Завершите вход в браузере; затем проверьте /status."); }); }
                }
                case "/logout" -> { if (confirm("Выйти из аккаунта Codex на этой машине? Это изменит общее хранилище авторизации Codex.")) rpc("account/logout", null, result -> host.status.accept("Выполнен выход из Codex")); }
                case "/usage" -> rpc("account/usage/read", new JsonObject(), result -> message("Использование Codex", inventory(result)));
                case "/ps", "/stop" -> {
                    experimental(); var params = threadParams();
                    rpc(command.name().equals("/ps") ? "thread/backgroundTerminals/list" : "thread/backgroundTerminals/clean", params,
                            result -> message("Фоновые терминалы", command.name().equals("/stop") ? "Фоновые терминалы выбранной сессии остановлены. Текущий turn не прерван." : inventory(result)));
                }
                case "/skills" -> skills(argument);
                case "/mcp" -> mcp(argument);
                case "/apps" -> apps(argument);
                case "/plugins" -> plugins(argument);
                case "/hooks" -> hooks();
                case "/presets" -> presets(argument);
                case "/memories" -> memories(argument);
                case "/import" -> importConfiguration();
                case "/feedback" -> {
                    String diagnostics = "AGTY AI Assistant 0.14.1\nIDEA 253\nCLI: " + CodexCompatibility.observed(CodexSettings.getInstance().getExecutablePath())
                            + "\nOS: " + System.getProperty("os.name") + "\nJava: " + System.getProperty("java.version")
                            + "\nTask: " + (service().runner().task(current().id) == null ? "none" : service().runner().task(current().id).state);
                    host.draft.accept("Диагностика для отчёта об ошибке:\n" + diagnostics + "\nШаги воспроизведения: ");
                    message("Обратная связь", "Безопасная диагностика вставлена в черновик. Допишите шаги и скопируйте текст в выбранный канал поддержки. Команда ничего не отправляет автоматически.");
                }
                case "/warnings" -> message("Предупреждения", String.join("\n\n", current().messages.stream().filter(m -> m.title.contains("Ошибка") || m.title.contains("не завершён")).map(m -> m.markdown).toList()));
                case "/keymap" -> com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(host.project, "Keymap");
                case "/vim" -> {
                    message("Vim в IDEA", "Vim-редактирование в IDEA обеспечивает IdeaVim. Откроются настройки плагинов; установка и включение выполняются явно пользователем.");
                    com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(host.project, "Plugins");
                }
                case "/theme" -> com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(host.project, "Оформление чата");
                case "/quit" -> {
                    var window = com.intellij.openapi.wm.ToolWindowManager.getInstance(host.project).getToolWindow("AGTY AI Assistant");
                    if (window != null) window.hide(null);
                }
                case "/setup-default-sandbox" -> {
                    if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) throw new IllegalStateException("Эта команда доступна только на Windows.");
                    idle();
                    if (!confirm("Настроить elevated Windows sandbox через Codex? Система может запросить административное подтверждение. Полный доступ не включается автоматически.")) return true;
                    var params = new JsonObject(); params.addProperty("mode", "elevated"); params.addProperty("cwd", directory().toString());
                    rpc("windowsSandbox/setupStart", params, result -> message("Windows sandbox", inventory(result)));
                }
                case "/config" -> com.intellij.openapi.options.ShowSettingsUtil.getInstance().showSettingsDialog(host.project, "AGTY AI Assistant");
                case "/debug-config" -> { var params = new JsonObject(); params.addProperty("cwd", directory().toString()); rpc("config/read", params, result -> {
                    var config = result.getAsJsonObject("config"); JsonObject safe = new JsonObject();
                    for (String key : List.of("model", "model_provider", "approval_policy", "sandbox_mode", "model_reasoning_effort")) if (config != null && config.has(key)) safe.add(key, config.get(key));
                    message("Конфигурация Codex", inventory(safe));
                }); }
                default -> { return false; }
            }
        } catch (Exception e) { host.status.accept(e.getMessage()); }
        return true;
    }
    void restoreArchived(ChatSessions.Session target) {
        Path root = target.workingDirectory.isBlank() ? directory() : Path.of(target.workingDirectory);
        var previous = current(); var params = new JsonObject(); params.addProperty("threadId", target.remoteId());
        String binary = CodexSettings.getInstance().getExecutablePath(); boolean experimental = host.sessions.get().experimentalApi;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                service().connections().call(binary, root, experimental, "thread/unarchive", params);
                ApplicationManager.getApplication().invokeLater(() -> { target.archived = false; target.closed = false; if (host.live.getAsBoolean() && current() == previous) host.select.accept(target); });
            } catch (Exception e) { ApplicationManager.getApplication().invokeLater(() -> host.status.accept(e.getMessage())); }
        });
    }
    private void startReadOnlyPrompt(String question) throws Exception {
        idle(); thread(); Path root = directory(); var target = current();
        var options = new RequestOptions(root, target.remoteId(), target.selectedModel, target.reasoningEffort,
                RequestOptions.Access.READ_ONLY, RequestOptions.Mode.WORK, List.of(), false, host.sessions.get().interactiveApprovals);
        var provider = new CodexChatProvider(CodexSettings.getInstance().getExecutablePath(), service().connections(), host.sessions.get().experimentalApi);
        service().runner().start(target, provider.newRequest(options), root, question, question, "Codex", false);
    }
    private void startReview(String argument) throws Exception {
        idle(); Path root = directory(); var target = current();
        var review = new JsonObject(); review.addProperty("type", argument.isBlank() ? "uncommittedChanges" : "custom");
        if (!argument.isBlank()) review.addProperty("instructions", argument);
        var options = new RequestOptions(root, target.remoteId(), target.selectedModel, target.reasoningEffort,
                RequestOptions.Access.READ_ONLY, RequestOptions.Mode.WORK, List.of(), false, host.sessions.get().interactiveApprovals, review);
        var provider = new CodexChatProvider(CodexSettings.getInstance().getExecutablePath(), service().connections(), host.sessions.get().experimentalApi);
        service().runner().start(target, provider.newRequest(options), root, "", "Ревью Codex " + argument, "Codex", false);
    }
    private record Agent(JsonObject thread) { @Override public String toString() { return "Agent · " + value(thread, "name") + " · " + value(thread, "id") + " · " + thread.get("status"); } }
    private void agents() {
        var params = new JsonObject(); params.addProperty("limit", 100);
        if (!current().remoteId().isBlank()) params.addProperty("ancestorThreadId", current().remoteId()); else params.addProperty("cwd", directory().toString());
        JsonArray sources = new JsonArray(); for (String source : List.of("subAgent", "subAgentReview", "subAgentCompact", "subAgentThreadSpawn", "subAgentOther")) sources.add(source);
        params.add("sourceKinds", sources);
        rpc("thread/list", params, result -> {
            if (!host.live.getAsBoolean() || responseTarget != current()) { message("Агенты", inventory(result)); return; }
            List<Agent> agents = new ArrayList<>(); for (var thread : result.getAsJsonArray("data")) agents.add(new Agent(thread.getAsJsonObject()));
            if (agents.isEmpty()) { message("Агенты", "Для выбранной задачи нет доступных подагентов. Их наличие зависит от запуска и конфигурации Codex."); return; }
            Agent selected = (Agent) PlainText.choose(null, "Выберите агента для открытия чата и наблюдения", "Агенты Codex", JOptionPane.QUESTION_MESSAGE, agents.toArray(), agents.getFirst());
            if (selected == null) return;
            String id = value(selected.thread, "id"); UUID.fromString(id);
            var session = host.sessions.get().sessions.stream().filter(candidate -> candidate.remoteId().equals(id)).findFirst().orElse(null);
            if (session == null) {
                session = CodexSessionClient.summary(selected.thread); session.nativeSessionId = id; session.title = "Agent · " + value(selected.thread, "name");
                session.workingDirectory = value(selected.thread, "cwd"); host.sessions.get().sessions.add(session);
            }
            var target = session; var active = service().runner().task(target.id);
            if (active == null || !active.active()) {
                String binary = CodexSettings.getInstance().getExecutablePath();
                var provider = new CodexChatProvider(binary, service().connections(), host.sessions.get().experimentalApi);
                try { service().runner().start(target, provider.observeRequest(id), Path.of(target.workingDirectory), "", "Наблюдение за агентом", "Codex", false); }
                catch (Exception e) { host.status.accept(e.getMessage()); return; }
            }
            host.select.accept(target);
        });
    }
    private void worktree() {
        idle(); Path root = directory(); var original = current();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var worktrees = org.agty.aiassistant.changes.WorktreeManager.list(root);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!host.live.getAsBoolean()) return;
                    int choice = Messages.showDialog(host.project, "Открыть существующий worktree или создать отдельный checkout и новую ветку?",
                            "Git worktree", new String[]{"Открыть", "Создать", "Отмена"}, 2, Messages.getQuestionIcon());
                    if (choice == 0 && !worktrees.isEmpty()) {
                        var selected = (org.agty.aiassistant.changes.WorktreeManager.Worktree) PlainText.choose(null, "Выберите worktree", "Git worktree",
                                JOptionPane.QUESTION_MESSAGE, worktrees.toArray(), worktrees.getFirst());
                        if (selected != null) openWorktree(original, selected.directory());
                    } else if (choice == 1) {
                        String branch = Messages.showInputDialog(host.project, "Имя новой ветки", "Git worktree", null); if (branch == null) return;
                        String path = Messages.showInputDialog(host.project, "Каталог нового worktree (пустой или отсутствующий)", "Git worktree", null); if (path == null) return;
                        Path target = root.resolve(path).normalize();
                        if (!confirm("Создать ветку " + branch + " и worktree " + target + "? Git добавит служебные данные worktree; исходный checkout сохранится.")) return;
                        ApplicationManager.getApplication().executeOnPooledThread(() -> {
                            try {
                                Path created = org.agty.aiassistant.changes.WorktreeManager.create(root, target, branch);
                                ApplicationManager.getApplication().invokeLater(() -> openWorktree(original, created));
                            } catch (Exception e) { ApplicationManager.getApplication().invokeLater(() -> host.status.accept(e.getMessage())); }
                        });
                    }
                });
            } catch (Exception e) { ApplicationManager.getApplication().invokeLater(() -> host.status.accept(e.getMessage())); }
        });
    }
    private void openWorktree(ChatSessions.Session original, Path path) {
        if (host.project.isDisposed()) return;
        var created = new ChatSessions.Session(); created.workingDirectory = path.toString(); created.title = path.getFileName().toString();
        created.selectedModel = original.selectedModel; created.reasoningEffort = original.reasoningEffort;
        host.sessions.get().sessions.add(created);
        if (host.live.getAsBoolean() && current() == original) { host.clearContext.run(); host.select.accept(created); }
    }
    private void speedAndAccess(String command, String argument) {
        idle(); if (command.equals("/daybreak")) experimental();
        var target = current(); String model = target.selectedModel;
        if (model.isBlank()) throw new IllegalStateException("Сначала выберите модель через /model.");
        Path root = directory(); String binary = CodexSettings.getInstance().getExecutablePath();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var metadata = CodexModels.resolve(service().connections().models(binary, root), model);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!host.live.getAsBoolean() || current() != target) return;
                    if (command.equals("/speed")) {
                        List<CodexModels.ServiceTier> tiers = new ArrayList<>(); tiers.add(new CodexModels.ServiceTier("default", "Стандартная скорость", "Стандартная скорость этого turn")); tiers.addAll(metadata.serviceTiers());
                        CodexModels.ServiceTier selected = argument.isBlank()
                                ? (CodexModels.ServiceTier) PlainText.choose(null, "Скорость следующего обычного turn; может влиять на стоимость", "Скорость Codex", JOptionPane.QUESTION_MESSAGE, tiers.toArray(), tiers.getFirst())
                                : tiers.stream().filter(t -> t.id().equals(argument)).findFirst().orElse(null);
                        if (selected == null) { if (!argument.isBlank()) host.status.accept("Tier не предоставлен текущему аккаунту/модели."); return; }
                        target.serviceTier = selected.id(); host.refresh.run();
                    } else {
                        if (argument.equals("off")) { target.cyberAccessProgram = "standard"; host.refresh.run(); return; }
                        var programs = metadata.cyberPrograms();
                        if (programs == null) { host.status.accept("Каталог не предоставил сведения о доступе к Daybreak."); return; }
                        String[] choices = programs.stream().filter(p -> !p.equals("standard")).toArray(String[]::new);
                        if (choices.length == 0) { host.status.accept("Daybreak не предоставлен текущему аккаунту и модели."); return; }
                        String selected = (String) PlainText.choose(null, "Выберите предоставленный аккаунту режим. Sandbox и разрешения не изменятся.", "Daybreak", JOptionPane.QUESTION_MESSAGE, choices, choices[0]);
                        if (selected != null) { target.cyberAccessProgram = selected; host.refresh.run(); }
                    }
                });
            } catch (Exception e) { ApplicationManager.getApplication().invokeLater(() -> host.status.accept(e.getMessage())); }
        });
    }
    private void configure(String key, JsonObject value, String effect) {
        if (!confirm(effect + "\nЭто отдельное изменение общей конфигурации Codex на машине.")) return;
        JsonObject params = new JsonObject(), edit = new JsonObject(); edit.addProperty("keyPath", key); edit.addProperty("mergeStrategy", "upsert"); edit.add("value", value);
        JsonArray edits = new JsonArray(); edits.add(edit); params.add("edits", edits); params.addProperty("reloadUserConfig", true);
        rpc("config/batchWrite", params, result -> host.status.accept("Общая настройка Codex сохранена"));
    }

    static void requireSavedExportDestination(Path target) throws java.io.IOException {
        target = org.agty.aiassistant.changes.WorkspaceChanges.canonical(target);
        var manager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
        for (var document : manager.getUnsavedDocuments()) {
            var file = manager.getFile(document);
            if (file != null && file.isInLocalFileSystem()
                    && org.agty.aiassistant.changes.WorkspaceChanges.canonical(file.toNioPath()).equals(target))
                throw new java.io.IOException("В файле назначения есть несохранённые правки. Сохраните их или выберите другой файл.");
        }
    }
    private void apps(String argument) {
        String[] parts = argument.split("\\s+", 2);
        if (parts.length == 2 && Set.of("enable", "disable").contains(parts[0])) {
            JsonObject value = new JsonObject(), app = new JsonObject(); app.addProperty("enabled", parts[0].equals("enable")); value.add(parts[1], app);
            configure("apps", value, "Изменить доступность приложения " + parts[1] + ": " + parts[0] + "?");
        } else rpc("app/list", new JsonObject(), result -> message("Apps", inventory(result)));
    }
    private void memories(String argument) {
        experimental();
        if (Set.of("on", "off").contains(argument)) {
            var params = threadParams(); params.addProperty("mode", argument.equals("on") ? "enabled" : "disabled");
            rpc("thread/memoryMode/set", params, result -> host.status.accept("Память выбранного thread: " + argument));
        } else if (argument.equals("read")) rpc("thread/memory/read", threadParams(), result -> message("Память сессии", inventory(result)));
        else if (argument.isBlank()) rpc("memory/status", new JsonObject(), result -> message("Состояние памяти", inventory(result)));
        else throw new IllegalStateException("Синтаксис: /memories [read|on|off]");
    }
    private void presets(String argument) {
        var presets = host.sessions.get().presets;
        if (argument.startsWith("save ")) {
            String[] parts = argument.split("\\s+", 3); if (parts.length != 3) throw new IllegalStateException("Синтаксис: /presets save имя текст");
            presets.put(parts[1], parts[2]); host.status.accept("Preset сохранён: " + parts[1]); return;
        }
        if (argument.startsWith("delete ")) { presets.remove(argument.substring(7).strip()); host.status.accept("Preset удалён"); return; }
        String name = argument;
        if (name.isBlank()) {
            if (presets.isEmpty()) { message("Presets", "Сохраните текст: /presets save имя текст. Вставка preset не выполняет команды."); return; }
            name = (String) PlainText.choose(null, "Выберите preset для вставки в черновик", "Presets", JOptionPane.QUESTION_MESSAGE, presets.keySet().toArray(), presets.keySet().iterator().next());
        }
        if (name != null) { String text = presets.get(name); if (text == null) throw new IllegalStateException("Preset не найден."); host.draft.accept(text); }
    }
    private record Migration(JsonObject item) { @Override public String toString() { return "Импорт · " + value(item, "itemType") + " · " + value(item, "description") + " · " + value(item, "cwd"); } }
    private void importConfiguration() {
        idle();
        int scope = Messages.showDialog(host.project, "Где искать настройки и историю для импорта в Codex?", "Импорт", new String[]{"Только этот проект", "Проект и домашние настройки", "Отмена"}, 2, Messages.getQuestionIcon());
        if (scope < 0 || scope == 2) return;
        var params = new JsonObject(); var dirs = new JsonArray(); dirs.add(directory().toString()); params.add("cwds", dirs); params.addProperty("includeHome", scope == 1);
        rpc("externalAgentConfig/detect", params, result -> {
            if (!host.live.getAsBoolean() || responseTarget != current()) { message("Импорт", "Обнаружение завершено. Вернитесь в исходный чат и повторите /import для выбора элементов."); return; }
            List<Migration> items = new ArrayList<>(); if (result.has("items")) for (var item : result.getAsJsonArray("items")) items.add(new Migration(item.getAsJsonObject()));
            if (items.isEmpty()) { message("Импорт", "Подходящие элементы не найдены."); return; }
            JList<Migration> choices = new JList<>(items.toArray(Migration[]::new)); choices.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            if (JOptionPane.showConfirmDialog(null, new JScrollPane(choices), "Выберите импортируемые элементы", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
            var selected = choices.getSelectedValuesList(); if (selected.isEmpty() || !confirm("Импортировать выбранные настройки/сессии в Codex? Это может изменить настройки проекта и пользователя по указанным областям.")) return;
            JsonObject request = new JsonObject(); JsonArray migrations = new JsonArray(); selected.forEach(item -> migrations.add(item.item.deepCopy())); request.add("migrationItems", migrations); request.addProperty("source", "agty-idea");
            rpc("externalAgentConfig/import", request, imported -> message("Импорт", inventory(imported)));
        });
    }
    private record Hook(JsonObject item) { @Override public String toString() { return "Hook · " + value(item, "eventName") + " · " + value(item, "key") + " · " + value(item, "trustStatus"); } }
    private void hooks() {
        var params = new JsonObject();
        if (!current().remoteId().isBlank()) params.addProperty("threadId", current().remoteId());
        else { var dirs = new JsonArray(); dirs.add(directory().toString()); params.add("cwds", dirs); }
        rpc("hooks/list", params, result -> {
            if (!host.live.getAsBoolean() || responseTarget != current()) { message("Hooks", inventory(result)); return; }
            List<Hook> hooks = new ArrayList<>();
            if (result.has("data")) for (var entry : result.getAsJsonArray("data")) if (entry.getAsJsonObject().has("hooks")) for (var hook : entry.getAsJsonObject().getAsJsonArray("hooks")) hooks.add(new Hook(hook.getAsJsonObject()));
            if (hooks.isEmpty()) { message("Hooks", inventory(result)); return; }
            Hook selected = (Hook) PlainText.choose(null, "Выберите hook для просмотра и управления", "Hooks", JOptionPane.QUESTION_MESSAGE, hooks.toArray(), hooks.getFirst());
            if (selected == null) return; var hook = selected.item;
            int action = Messages.showDialog(host.project, "Hook: " + value(hook, "eventName") + "\nИсточник: " + value(hook, "sourcePath")
                            + "\nКоманда: " + value(hook, "command") + "\nИнструмент: " + value(hook, "server") + " / " + value(hook, "tool")
                            + "\nДоверие: " + value(hook, "trustStatus") + "\nHash: " + value(hook, "currentHash"),
                    "Hook Codex", new String[]{"Открыть исходник", "Включить", "Выключить", "Доверять этой версии", "Отмена"}, 4, Messages.getQuestionIcon());
            if (action < 0 || action == 4) return;
            if (action == 0) {
                var file = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(value(hook, "sourcePath")));
                if (file != null) WriteIntentReadAction.run((Runnable) () -> com.intellij.openapi.fileEditor.FileEditorManager.getInstance(host.project).openFile(file, true)); return;
            }
            if (hook.has("isManaged") && hook.get("isManaged").getAsBoolean()) throw new IllegalStateException("Hook управляется администратором; пользовательское переопределение недоступно.");
            JsonObject state = new JsonObject(), update = new JsonObject();
            if (action == 3) update.addProperty("trusted_hash", value(hook, "currentHash")); else update.addProperty("enabled", action == 1);
            state.add(value(hook, "key"), update);
            configure("hooks.state", state, action == 3 ? "Доверять показанной версии hook? В дальнейшем он сможет запускаться при соответствующем событии без повторного согласования этой версии." : "Изменить включение выбранного hook?");
        });
    }
    private void skills(String argument) {
        String[] parts = argument.split("\\s+", 2); var params = new JsonObject();
        if (parts.length == 2 && Set.of("enable", "disable").contains(parts[0])) {
            if (!confirm("Изменить общую конфигурацию Codex для skill " + parts[1] + ": " + parts[0] + "?")) return;
            params.addProperty("name", parts[1]); params.addProperty("enabled", parts[0].equals("enable")); rpc("skills/config/write", params, result -> host.status.accept("Настройка skill сохранена"));
        } else { var dirs = new JsonArray(); dirs.add(directory().toString()); params.add("cwds", dirs); rpc("skills/list", params, result -> message("Skills", inventory(result))); }
    }
    private void mcp(String argument) {
        var params = new JsonObject();
        if (argument.startsWith("enable ") || argument.startsWith("disable ")) {
            String[] parts = argument.split("\\s+", 2); JsonObject value = new JsonObject(), server = new JsonObject();
            server.addProperty("enabled", parts[0].equals("enable")); value.add(parts[1], server);
            configure("mcp_servers", value, "Изменить включение MCP-сервера " + parts[1] + "?");
        } else if (argument.startsWith("login ")) {
            params.addProperty("name", argument.substring(6).strip()); if (!current().remoteId().isBlank()) params.addProperty("threadId", current().remoteId());
            rpc("mcpServer/oauth/login", params, result -> oauth(value(result, "authorizationUrl")));
        } else { params.addProperty("limit", 100); if (!current().remoteId().isBlank()) params.addProperty("threadId", current().remoteId()); rpc("mcpServerStatus/list", params, result -> message("MCP", inventory(result))); }
    }
    private void plugins(String argument) {
        String[] parts = argument.split("\\s+", 2); var params = new JsonObject();
        if (parts.length == 2 && parts[0].equals("install")) {
            var dirs = new JsonArray(); dirs.add(directory().toString()); params.add("cwds", dirs);
            rpc("plugin/list", params, catalog -> {
                if (!host.live.getAsBoolean() || responseTarget != current()) { message("Плагины Codex", "Каталог загружен. Вернитесь в исходный чат и повторите установку для выбора источника."); return; }
                var candidates = PluginInstallSelection.matching(catalog, parts[1]);
                if (candidates.isEmpty()) throw new IllegalStateException("Плагин не найден в предоставленных Codex каталогах. Откройте /plugins и используйте имя или ID из списка.");
                var selected = candidates.size() == 1 ? candidates.getFirst() : (PluginInstallSelection) PlainText.choose(null,
                        "Выберите источник установки", "Каталог плагинов Codex", JOptionPane.QUESTION_MESSAGE, candidates.toArray(), candidates.getFirst());
                if (selected == null) return;
                var install = selected.params();
                if (!confirm("Установить " + selected + "?\nИсточник: " + (selected.localPath().isBlank() ? selected.marketplace() : selected.localPath())
                        + "\n" + inventory(selected.details()) + "\nЭто изменит общую установку Codex и может подключить инструменты/skills/MCP плагина.")) return;
                rpc("plugin/install", install, result -> message("Установка плагина", inventory(result) + "\nПри необходимости авторизации MCP используйте /mcp login <ИМЯ_СЕРВЕРА>."));
            });
        } else if (parts.length == 2 && parts[0].equals("uninstall")) {
            if (!confirm("Удалить плагин Codex " + parts[1] + " из общей установки?")) return;
            params.addProperty("pluginId", parts[1]); rpc("plugin/uninstall", params, result -> message("Удаление плагина", inventory(result)));
        } else { var dirs = new JsonArray(); dirs.add(directory().toString()); params.add("cwds", dirs); rpc("plugin/list", params, result -> message("Плагины Codex", inventory(result))); }
    }
    /** Human-readable recursive inventory; credentials and opaque configuration are omitted. */
    static String inventory(JsonElement element) {
        StringBuilder result = new StringBuilder(); render(element, result, 0); return result.length() == 0 ? "Данные не предоставлены сервером." : result.toString();
    }
    private static void render(JsonElement element, StringBuilder output, int depth) {
        if (element == null || element.isJsonNull() || depth > 8) return;
        if (element.isJsonPrimitive()) output.append("- ").append(element.getAsString().replace('`', ' ')).append('\n');
        else if (element.isJsonArray()) { for (var child : element.getAsJsonArray()) render(child, output, depth + 1); }
        else if (element.isJsonObject()) {
            for (var pair : element.getAsJsonObject().entrySet()) {
                if (pair.getKey().equalsIgnoreCase("token") || pair.getKey().matches("(?i).*(secret|password|credential|authorization|authUrl|api.?key|access.?token|refresh.?token|id.?token|session.?token|bearer|environment|arguments|config).*")) continue;
                if (pair.getValue().isJsonPrimitive()) output.append("- ").append(pair.getKey()).append(": ").append(pair.getValue().getAsString().replace('`', ' ')).append('\n');
                else { output.append(pair.getKey()).append(":\n"); render(pair.getValue(), output, depth + 1); }
            }
            output.append('\n');
        }
    }
}
