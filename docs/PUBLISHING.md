# Подготовка публикации на GitHub

## Что готово

- Исходники, ресурсы и Gradle Wrapper находятся в репозитории.
- Установка описана в `README.md`.
- История изменений ведётся в `CHANGELOG.md`.
- ТЗ и сверка с M0 лежат в `docs/TECHNICAL_SPECIFICATION.md` и `docs/M0_GAP_ANALYSIS.md`.
- В `docs/` остаются только публичные документы; внутренние отчёты и рабочие материалы хранятся в локальном `!files/`.
- Локальные кэши, IDE-настройки и scratch-файлы исключены через `.gitignore`.
- Добавлен GitHub Actions workflow `.github/workflows/ci.yml`.
- Лицензия выбрана и добавлена: Apache-2.0.

## Перед первым push

1. Проверить, что в публикацию не попадают `!files/`, `.agents/`, `.codex/`, `.idea/`, `.gradle/`, `.intellijPlatform/` и `build/`.
2. Выполнить:

```bash
./gradlew test buildPlugin verifyPluginStructure
```

3. Если Gradle в текущей среде недоступен, зафиксировать это в релизных заметках и дождаться прохождения GitHub Actions.
4. Создать репозиторий на GitHub и отправить только исходники/документы, без локальных ZIP/JAR из `build/`.

## Рекомендуемые настройки GitHub

- Включить branch protection для `main`.
- Требовать успешный workflow `CI`.
- Запретить force push в `main`.
- Включить secret scanning и Dependabot alerts.
- Публиковать ZIP из GitHub Actions artifacts или из локального Gradle build только после прохождения проверок.

## Первый релиз

Текущий локальный ZIP: `build/distributions/agty-ai-assistant-0.10.2.zip`.

Для GitHub Release:

- tag: `v0.10.2`;
- title: `AGTY AI Assistant 0.10.2`;
- notes: взять раздел `0.10.2` из `CHANGELOG.md`;
- artifact: ZIP, собранный тем же commit, который опубликован в tag.
