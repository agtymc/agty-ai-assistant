# Матрица реализации Codex 0.169.0

Источник очередности: CODEX_PLUGIN_DEVELOPMENT_PLAN.md. Классификация обозначает целевой способ реализации, а не готовность команды. Незавершённые обработчики — работа плагина, а не ограничение Codex.

## Возможности → API → UI → справка → тест

| Возможность | API / контракт | UI | Справка | Проверки / этап |
| --- | --- | --- | --- | --- |
| RPC | initialize, initialized, ID → future | без UI | диагностика P2 | перемежающиеся ответы, EOF, close: CodexSessionClientTest |
| Сессии | thread/list, read, start, resume, fork | текущие меню; фон P1 | P2/P5 | CodexSessionClientTest, CodexStreamingTest |
| Выполнение | turn/start, interrupt; thread/resume при обрыве | сервис проекта и подписки P1 | P2 | CodexStreamingTest; фоновые чаты ещё не покрыты |
| Права/вопросы | ServerRequest, requestUserInput, requestApproval | карточки P3 | P2/P5 | полный цикл P3; сейчас отклонение |
| Элементы/история | ServerNotification, ThreadItem | типизированные карточки P3 | P2 | CodexStream; полный replay P3 |
| Изменения | turn/diff/updated, fileChange | diff/откат P4 | P4 | ChangeSetTest + сохранность P4 |
| План/ревью | collaborationMode, review/start | P5 | P5 | P5 |
| Интеграции | skills/*, mcpServer/*, app/*, plugin/*, config/* | P5 | P5 | fixture P5 |
| Worktree/агенты | Git + thread; collaboration items | P5 | P5 | изоляция/очередь P1/P5 |
| Авторизация | account/* | P5 | P5 | OAuth/без секретов P5 |
| Справочник | общий CommandDescriptor/CommandHelp | вкладка P2 | offline + Markdown P2/P5 | aliases/примеры/черновик P2/P5 |

## Каждая команда эталонного CLI

[Зафиксированный каталог](https://github.com/openai/codex/blob/rust-v0.169.0/codex-rs/tui/src/slash_command.rs). Методы и experimental-поля сверять с bundles в protocol/0.169.0; наличие схемы не гарантирует доступность аккаунту. Точные runtime gates ещё предстоит реализовать.

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
| /title | — | Действие UI IDEA (P2/P5) |
| /statusline | — | Действие UI IDEA (P2/P5) |
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

Дополнения плагина: `/help` (P2), `/interrupt` (P1/P2). Текущие обработчики: `/status`, `/help`, `/model`, `/new`, `/resume`, `/permissions`, `/stop`. Семантика `/stop` пока старая (interrupt turn); миграция на остановку фоновых терминалов и alias `/clean` ожидает P2/P5. `/review` и `/diff` пока не являются реализованными slash-командами. Справочник docs/CODEX_COMMANDS.md появится на P2.
