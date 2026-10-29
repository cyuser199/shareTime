"""Prepare Windows build tools under C:\\environment; verify official hashes."""
import hashlib
import pathlib
import subprocess
import urllib.request
import xml.dom.minidom as DOM
import xml.etree.ElementTree as ET
import zipfile

BASE = pathlib.Path('C:/environment')
DOWNLOADS = BASE / 'android-build-downloads'
DOWNLOADS.mkdir(parents=True, exist_ok=True)


def download(url, digest, algorithm):
    target = DOWNLOADS / url.rsplit('/', 1)[-1]
    if not target.exists():
        urllib.request.urlretrieve(url, target)
    if hashlib.new(algorithm, target.read_bytes()).hexdigest() != digest:
        raise RuntimeError('Archive checksum mismatch: ' + target.name)
    return target


def local_metadata(repository, package_path, destination):
    """Keep SDK Manager metadata so AGP does not install a duplicate."""
    source = DOM.parseString(repository)
    package = next(p for p in source.getElementsByTagName('remotePackage') if p.getAttribute('path') == package_path)
    result = DOM.Document()
    root = result.createElementNS('http://schemas.android.com/repository/android/common/02', 'localrepo:repository')
    for i in range(source.documentElement.attributes.length):
        attribute = source.documentElement.attributes.item(i)
        if attribute.name.startswith('xmlns'):
            root.setAttribute(attribute.name, attribute.value)
    root.setAttribute('xmlns:localrepo', 'http://schemas.android.com/repository/android/common/02')
    result.appendChild(root)
    for license_node in source.getElementsByTagName('license'):
        root.appendChild(result.importNode(license_node, True))
    local = result.createElement('localPackage')
    local.setAttribute('path', package_path)
    local.setAttribute('obsolete', 'false')
    for child in package.childNodes:
        if child.nodeType == child.ELEMENT_NODE and child.tagName in ['type-details', 'revision', 'display-name', 'uses-license', 'dependencies']:
            local.appendChild(result.importNode(child, True))
    root.appendChild(local)
    (destination / 'package.xml').write_bytes(result.toxml(encoding='utf-8'))


def main():
    repository = urllib.request.urlopen('https://dl.google.com/android/repository/repository2-3.xml', timeout=30).read()
    root = ET.fromstring(repository)
    for package_path in ['platforms;android-37.0', 'build-tools;37.0.0']:
        destination = BASE.joinpath(*package_path.split(';'))
        if (destination / 'package.xml').exists():
            continue
        package = next(e for e in root.findall('remotePackage') if e.attrib['path'] == package_path)
        if not (destination / 'source.properties').exists():
            archive = next(a for a in package.findall('./archives/archive') if a.findtext('host-os') in [None, 'windows'])
            complete = archive.find('complete')
            zip_path = download('https://dl.google.com/android/repository/' + complete.findtext('url'), complete.findtext('checksum'), 'sha1')
            with zipfile.ZipFile(zip_path) as opened:
                folder = opened.namelist()[0].split('/')[0]
                opened.extractall(destination.parent)
            extracted = destination.parent / folder
            if extracted != destination:
                if destination.exists():
                    raise RuntimeError('Destination already exists: ' + str(destination))
                extracted.rename(destination)
        local_metadata(repository, package_path, destination)
        print('Installed', package_path, flush=True)
    url = 'https://services.gradle.org/distributions/gradle-9.5.0-bin.zip'
    sha = urllib.request.urlopen(url + '.sha256', timeout=30).read().decode().strip()
    archive = download(url, sha, 'sha256')
    if not (BASE / 'gradle/gradle-9.5.0/bin/gradle.bat').exists():
        with zipfile.ZipFile(archive) as opened:
            opened.extractall(BASE / 'gradle')
    workspace = pathlib.Path(__file__).resolve().parents[1]
    (workspace / 'android/local.properties').write_text('sdk.dir=C\\:/environment\n', encoding='ascii')
    subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                    str(pathlib.Path(__file__).with_name('configure_build_env.ps1'))], check=True)
    print('Android build environment ready')


if __name__ == '__main__':
    main()
