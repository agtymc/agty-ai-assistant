# Проверки основы P0 и первого изменения P1

17 ноября 2026 года, Linux. Рабочее дерево плагина 0.13.0, Codex CLI 0.169.0, IDEA 2025.3 (253).

## Воспроизведение

JDK 21 (Gradle также запускается на JDK 25), Python 3 для помощников. Из корня:

```bash
./gradlew test buildPlugin verifyPluginStructure --console=plain
```

В среде с HTTP proxy:

```bash
python3 scripts/gradle_with_proxy.py test buildPlugin verifyPluginStructure --console=plain
python3 scripts/update_codex_protocol.py
```

Прямой запуск здесь завершился таймаутами загрузки java-compiler и asm из JetBrains. Через proxy загрузка и инструментация восстановлены. Параллельные Gradle-прогоны используют одни каталоги отчётов и sandbox: запускать проверки последовательно.

## Результаты

- `test buildPlugin verifyPluginStructure`: BUILD SUCCESSFUL (1m 32s), ZIP `build/distributions/agty-ai-assistant-0.13.0.zip`.
- Тесты: 70, ошибок/падений/пропусков 0 (свежие XML из build/test-results/test).
- RPC: ответы в обратном порядке, независимое уведомление, EOF при ожидающем вызове и вызов после close; фиктивный App Server, без аккаунта или модели.
- UI-тесты исправлены: обращаются к стабильному ProjectIdentity вместо устаревшего ключа default-project.
- Регенерация stable/experimental bundles повторена; SHA-256 всех четырёх файлов совпали.
- `git diff --check` проходит.

## Границы проверки

CI на Windows/macOS подготовлен, но удалённые runners здесь не запускались. Plugin Verifier, авторизация, реальная задача Codex и ручная приёмка в IDEA остаются для последующих этапов. P1 пока реализован только на уровне маршрутизации RPC: панель продолжает владеть выполнением; сервис проекта, общий процесс, подписки, фоновые чаты и очередь по cwd ещё предстоит реализовать. UI-тесты не подтверждают эти будущие возможности.

Предшествующие изменения рабочего дерева сохранены. AGENTS.md обновлён локально, но этот файл исключён существующим .gitignore.
