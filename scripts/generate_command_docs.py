"""Generate the readable help from the packaged command catalog."""
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
commands = json.loads((root / 'src/main/resources/commands/catalog.json').read_text())
lines = ['# Команды Codex — руководство и примеры', '', 'Сгенерировано scripts/generate_command_docs.py из ресурсов плагина. Статус реализации и ограничения каждой команды указаны явно.', '']
workflow = json.loads((root / 'src/main/resources/commands/workflow.json').read_text())
lines.extend(['## Рабочая последовательность', '', 'Вставка каждого шага дополняет черновик и не выполняет его автоматически. Plan и права доступа независимы.', ''])
for step in workflow:
    lines.extend([step['title'], '', '```text', step['text'], '```', ''])
for command in commands:
    lines.extend(['## ' + command['name'], '', command['purpose'], '', 'Статус: ' + ('реализована' if command['implemented'] else 'отложена') + '. Aliases: ' + (', '.join(command['aliases']) or '—'), '', 'Синтаксис: `' + command['syntax'] + '`.', '', command['scenario'], '', 'UI: ' + command['ui'], '', 'Примеры:', ''])
    for context, example in zip(command['exampleContexts'], command['examples']):
        lines.extend([context, '', '```text', example, '```', ''])
    for label, field in [('Результат', 'result'), ('Ограничения', 'constraints'), ('Ошибки', 'errors')]:
        lines.extend([label + ': ' + command[field], ''])
    lines.extend(['Связанные команды: ' + ', '.join(command['related']), ''])
(root / 'docs/CODEX_COMMANDS.md').write_text('\n'.join(lines))
