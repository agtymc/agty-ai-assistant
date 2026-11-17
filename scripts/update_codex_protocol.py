"""Regenerate checked-in bundles with the exact reference CLI; no account required."""
import pathlib
import subprocess
import tempfile
import shutil

root = pathlib.Path(__file__).resolve().parents[1]
version = subprocess.check_output(['codex', '--version'], text=True).strip()
if version != 'codex-cli 0.169.0':
    raise SystemExit(f'Expected codex-cli 0.169.0, found {version}')
for mode in ('stable', 'experimental'):
    with tempfile.TemporaryDirectory() as temp:
        command = ['codex', 'app-server', 'generate-json-schema', '--out', temp]
        if mode == 'experimental':
            command.append('--experimental')
        subprocess.run(command, check=True)
        target = root / 'docs/protocol/0.169.0' / mode
        target.mkdir(parents=True, exist_ok=True)
        for name in ('codex_app_server_protocol.schemas.json', 'codex_app_server_protocol.v2.schemas.json'):
            shutil.copyfile(pathlib.Path(temp) / name, target / name)
