# Матрица реализации Codex 0.169.0

Источник очередности: CODEX_PLUGIN_DEVELOPMENT_PLAN.md. Классификация обозначает целевой способ реализации, а не готовность команды. Незавершённые обработчики — работа плагина, а не ограничение Codex.

## Возможности → API → UI → справка → тест

| Возможность | API / контракт | UI | Справка | Автоматические проверки |
| --- | --- | --- | --- | --- |
| RPC и восстановление | initialize, ID → future, EOF, thread/resume | сервис проекта | status, warnings | CodexSessionClientTest, CodexStreamingTest |
| Фоновые чаты | thread/start/resume, turn/start/steer/interrupt | состояния, очередь, адресная остановка | new/resume/fork/queue/interrupt | TaskRunnerTest, CodexConnectionPoolTest, ChatRenderingTest |
| Подтверждения | approvals, permissions, requestUserInput, MCP elicitation | ожидающие карточки, уведомления, формы | permissions/approve/mcp | ApprovalDecisionsTest, McpFormTest, CodexStreamingTest, DevelopmentWorkflowTest |
| История и результаты | thread/items/list, ThreadItem, deltas | страницы, карточки, полный просмотр | resume/copy/export | CodexSessionClientTest, MessageArtifactsTest, JcefTranscriptTest |
| Изменения | fileChange, turn diff, Git porcelain | построчный diff, native Diff Viewer, task/Git lists | diff/review | WorkspaceChangesTest, WorkingTreeChangesTest, DiffMarkupTest |
| Сохранность и откат | снимок байтов/хешей, Local History | файл/hunk/задача, запрет при недостоверной базе | diff | WorkspaceChangesTest, DevelopmentWorkflowTest, ChangeSetTest |
| План, review, goal | collaborationMode, review/start, compact/start, thread/goal/* | команды, настройки, карточки | plan/review/compact/recap/goal | CodexStreamingTest, CommandCatalogTest |
| Интеграции | skills, mcpServer, app, plugin, hooks, config | список, явные изменения конфигурации/OAuth | skills/mcp/apps/plugins/hooks/import | McpFormTest, CommandCatalogTest; реальные внешние серверы зависят от аккаунта |
| Worktree и агенты | Git worktree, thread/list, native observation | привязанные чаты и наблюдение | worktree/agents | WorktreeManagerTest, CodexStreamingTest, TaskRunnerTest |
| Авторизация и модель | account/*, model/list, точная версия | OAuth, каталог и доступность | login/logout/model/speed/daybreak | CodexModelsTest, CodexCompatibilityTest, RealCodexSmokeTest |
| Справочник | ресурсный общий каталог и workflow | офлайн editor tab, поиск, примеры | CODEX_COMMANDS.md | CommandCatalogTest, CommandAvailabilityTest, CommandHelpTest |
| Выпуск | IDEA 253, Java 21 | ZIP, JCEF/Swing | README, changelog | test/buildPlugin/verifyPluginStructure/verifyPlugin; CI 3 ОС |

## Каждая команда эталонного CLI

[Зафиксированный каталог](https://github.com/openai/codex/blob/rust-v0.169.0/codex-rs/tui/src/slash_command.rs). Методы и experimental-поля сверять с bundles в protocol/0.169.0; наличие схемы не гарантирует доступность аккаунту. Runtime gates учитывают контракт CLI, ОС, аккаунт, экспериментальные возможности и состояние задачи.

| Команда | Aliases | Классификация |
| --- | --- | --- |
| /model | — | Нативный эквивалент (P2–P5) |
| /presets | — | Нативный эквивалент (P2–P5) |
| /daybreak | — | Условно: ОС/feature/аккаунт (P5) |
| /ide | — | Нативный эквивалент (P2–P5) |
| /permissions | — | Нативный эквивалент (P2–P5) |
| /keymap | — | Действие UI IDEA (P2/P5) |
| /vim | — | Действие UI IDEA (P2/P5) |
| /setup-default-sandbox | — | Условно: ОС/feature/аккаунт (P5) |
| /experimental | — | Нативный эквивалент (P2–P5) |
| /approve | — | Условно: ОС/feature/аккаунт (P5) |
| /memories | — | Нативный эквивалент (P2–P5) |
| /skills | — | Нативный эквивалент (P2–P5) |
| /import | — | Нативный эквивалент (P2–P5) |
| /hooks | — | Нативный эквивалент (P2–P5) |
| /review | — | Нативный эквивалент (P2–P5) |
| /rename | — | Нативный эквивалент (P2–P5) |
| /new | — | Нативный эквивалент (P2–P5) |
| /archive | — | Нативный эквивалент (P2–P5) |
| /delete | — | Нативный эквивалент (P2–P5) |
| /resume | — | Нативный эквивалент (P2–P5) |
| /fork | — | Нативный эквивалент (P2–P5) |
| /worktree | — | Нативный эквивалент (P2–P5) |
| /app | — | Явно отложено |
| /init | — | Нативный эквивалент (P2–P5) |
| /compact | — | Нативный эквивалент (P2–P5) |
| /config | — | Нативный эквивалент (P2–P5) |
| /recap | — | Нативный эквивалент (P2–P5) |
| /plan | — | Нативный эквивалент (P2–P5) |
| /voice | — | Явно отложено |
| /goal | — | Нативный эквивалент (P2–P5) |
| /agents | — | Нативный эквивалент (P2–P5) |
| /side | — | Нативный эквивалент (P2–P5) |
| /btw | — | Нативный эквивалент (P2–P5) |
| /copy | — | Нативный эквивалент (P2–P5) |
| /export | — | Нативный эквивалент (P2–P5) |
| /raw | — | Явно отложено |
| /tui | — | Явно отложено |
| /diff | — | Нативный эквивалент (P2–P5) |
| /mention | — | Нативный эквивалент (P2–P5) |
| /status | — | Нативный эквивалент (P2–P5) |
| /daemon | — | Явно отложено |
| /warnings | — | Нативный эквивалент (P2–P5) |
| /cd | — | Нативный эквивалент (P2–P5) |
| /pwd | /cwd | Нативный эквивалент (P2–P5) |
| /usage | — | Нативный эквивалент (P2–P5) |
| /debug-config | — | Нативный эквивалент (P2–P5) |
| /title | — | Явно отложена терминальная функция |
| /statusline | — | Явно отложена терминальная функция |
| /theme | — | Действие UI IDEA (P2/P5) |
| /pets | /pet | Явно отложено |
| /mcp | — | Нативный эквивалент (P2–P5) |
| /apps | — | Нативный эквивалент (P2–P5) |
| /plugins | — | Нативный эквивалент (P2–P5) |
| /logout | — | Нативный эквивалент (P2–P5) |
| /quit | — | Действие UI IDEA (P2/P5) |
| /exit | — | Действие UI IDEA (P2/P5) |
| /feedback | — | Нативный эквивалент (P2–P5) |
| /rollout | — | Диагностика, скрыто |
| /ps | — | Нативный эквивалент (P2–P5) |
| /stop | /clean | Нативный эквивалент (P2–P5) |
| /clear | — | Нативный эквивалент (P2–P5) |
| /test-approval | — | Диагностика, скрыто |
| /subagents | — | Нативный эквивалент (P2–P5) |
| /debug-m-drop | — | Диагностика, скрыто |
| /debug-m-update | — | Диагностика, скрыто |

## Фактическое состояние общего каталога 0.14.0

Дополнения плагина включают `/help`, `/interrupt`, `/login`, `/queue`, `/speed`. `/stop` и alias `/clean` теперь останавливают фоновые терминалы; текущий turn прерывают `/interrupt` и ■. `/review` запускает Codex review, `/diff` открывает изменения. `/side`/`/btw` адаптированы к независимому fork, `/cd` создаёт чат нового cwd.

| Каноническая команда | Aliases | Обработчик | Статья и два примера |
| --- | --- | --- | --- |
| /status | — | реализован | есть |
| /help | — | реализован | есть |
| /model | — | реализован | есть |
| /new | — | реализован | есть |
| /resume | — | реализован | есть |
| /permissions | — | реализован | есть |
| /stop | /clean | реализован | есть |
| /interrupt | — | реализован | есть |
| /diff | — | реализован | есть |
| /clear | — | реализован | есть |
| /rename | — | реализован | есть |
| /fork | — | реализован | есть |
| /side | /btw | реализован | есть |
| /archive | — | реализован | есть |
| /delete | — | реализован | есть |
| /copy | — | реализован | есть |
| /export | — | реализован | есть |
| /pwd | /cwd | реализован | есть |
| /cd | — | реализован | есть |
| /plan | — | реализован | есть |
| /experimental | — | реализован | есть |
| /goal | — | реализован | есть |
| /recap | — | реализован | есть |
| /init | — | реализован | есть |
| /review | — | реализован | есть |
| /queue | — | реализован | есть |
| /login | — | реализован | есть |
| /logout | — | реализован | есть |
| /usage | — | реализован | есть |
| /ps | — | реализован | есть |
| /skills | — | реализован | есть |
| /mcp | — | реализован | есть |
| /apps | — | реализован | есть |
| /plugins | — | реализован | есть |
| /hooks | — | реализован | есть |
| /warnings | — | реализован | есть |
| /config | — | реализован | есть |
| /debug-config | — | реализован | есть |
| /compact | — | реализован | есть |
| /mention | — | реализован | есть |
| /ide | — | реализован | есть |
| /worktree | — | реализован | есть |
| /agents | /subagents | реализован | есть |
| /presets | — | реализован | есть |
| /memories | — | реализован | есть |
| /import | — | реализован | есть |
| /feedback | — | реализован | есть |
| /speed | — | реализован | есть |
| /daybreak | — | реализован | есть |
| /keymap | — | реализован | есть |
| /vim | — | реализован | есть |
| /theme | — | реализован | есть |
| /quit | /exit | реализован | есть |
| /setup-default-sandbox | — | реализован | есть |
| /voice | — | явно отложен | есть |
| /pets | /pet | явно отложен | есть |
| /app | — | явно отложен | есть |
| /raw | — | явно отложен | есть |
| /tui | — | явно отложен | есть |
| /daemon | — | явно отложен | есть |
| /title | — | явно отложен | есть |
| /statusline | — | явно отложен | есть |
| /approve | — | реализован | есть |

Статьи и статусы генерируются из `src/main/resources/commands/catalog.json` в [CODEX_COMMANDS.md](CODEX_COMMANDS.md). Наличие обработчика не означает проверку каждой внешней интеграции конкретного аккаунта; границы приёмки приведены в [P4_P6_VALIDATION.md](P4_P6_VALIDATION.md).
