"""Install a project-local, checksum-verified Android/Gradle toolchain on Windows."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'

def download(url, path, checksum, algorithm='sha256'):
    if path.exists() and hashlib.new(algorithm, path.read_bytes()).hexdigest() == checksum:
        return path
    print('Downloading', path.name, flush=True)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.download')
    with urllib.request.urlopen(url, timeout=120) as source, temporary.open('wb') as target:
        while chunk := source.read(1024 * 1024):
            target.write(chunk)
    if hashlib.new(algorithm, temporary.read_bytes()).hexdigest() != checksum:
        raise RuntimeError('Checksum mismatch: ' + path.name)
    temporary.replace(path)
    return path

def install_gradle():
    archive = download('https://services.gradle.org/distributions/gradle-8.11.1-bin.zip',
        TOOLS / 'downloads/gradle-8.11.1-bin.zip',
        'f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6')
    if not (TOOLS / 'gradle-8.11.1/bin/gradle.bat').exists():
        with zipfile.ZipFile(archive) as z:
            z.extractall(TOOLS)

def install_sdk():
    # Platform/build tools packages include source.properties and can be used by AGP
    # directly. The CLI tools provide sdkmanager for additional packages and licenses.
    with urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml', timeout=60) as response:
        repository = ET.fromstring(response.read())
    packages = {'cmdline-tools;16.0': 'cmdline-tools/16.0',
                'platforms;android-35': 'platforms/android-35',
                'build-tools;35.0.0': 'build-tools/35.0.0',
                'platform-tools': 'platform-tools'}
    sdk = TOOLS / 'android-sdk'
    for name, destination in packages.items():
        target = sdk / destination
        if (target / 'source.properties').exists():
            continue
        package = next(p for p in repository.findall('remotePackage') if p.get('path') == name)
        archive = next(a for a in package.findall('./archives/archive') if a.findtext('host-os') in ('windows', None))
        url = archive.findtext('./complete/url')
        path = download('https://dl.google.com/android/repository/' + url,
                        TOOLS / 'downloads' / url, archive.findtext('./complete/checksum'), 'sha1')
        with zipfile.ZipFile(path) as z:
            top = z.namelist()[0].split('/')[0]
            for item in z.infolist():
                relative = Path(item.filename).relative_to(top)
                if str(relative) == '.':
                    continue
                output = target / relative
                if item.is_dir():
                    output.mkdir(parents=True, exist_ok=True)
                else:
                    output.parent.mkdir(parents=True, exist_ok=True)
                    output.write_bytes(z.read(item))
    # sdkmanager displays the Android SDK license agreements before accepting them.
    manager = sdk / 'cmdline-tools/16.0/bin/sdkmanager.bat'
    subprocess.run([str(manager), '--sdk_root=' + str(sdk), '--licenses'],
                   input='y\n' * 100, text=True, check=True)
    (ROOT / 'local.properties').write_text('sdk.dir=' + sdk.as_posix() + '\n', encoding='utf-8')

if __name__ == '__main__':
    TOOLS.mkdir(exist_ok=True)
    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(install_gradle), executor.submit(install_sdk)]
        for future in futures:
            future.result()
    print('Android toolchain ready.', flush=True)
