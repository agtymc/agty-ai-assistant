"""Run Gradle through HTTP(S)_PROXY without persisting machine settings."""
import os
import pathlib
import subprocess
import sys
import urllib.parse

root = pathlib.Path(__file__).resolve().parents[1]
args = []
for scheme in ('http', 'https'):
    value = os.environ.get(scheme.upper() + '_PROXY') or os.environ.get(scheme + '_proxy')
    if not value:
        continue
    proxy = urllib.parse.urlparse(value)
    if proxy.scheme != 'http' or not proxy.hostname or proxy.username:
        raise SystemExit('This helper supports HTTP proxies without credentials only.')
    args.extend([f'-D{scheme}.proxyHost={proxy.hostname}', f'-D{scheme}.proxyPort={proxy.port or 80}'])
# Keep local fixture traffic and Maven Central direct, matching the usual NO_PROXY setup.
args.append('-Dhttp.nonProxyHosts=localhost|127.*|[::1]|repo.maven.apache.org|*.repo.maven.apache.org')
wrapper = 'gradlew.bat' if os.name == 'nt' else './gradlew'
raise SystemExit(subprocess.call([wrapper, *args, *sys.argv[1:]], cwd=root))
