"""Release-only validation and CurseForge upload; standard library, no implicit upload.

The upload API contract is documented by CurseForge support (see docs/releasing.md).
Never log tokens, request headers, HTTP response bodies or runtime credentials.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tomllib
import urllib.error
import urllib.request
import uuid
from zipfile import ZipFile

import expression_lib

ROOT = Path(__file__).resolve().parent.parent
API = 'https://minecraft.curseforge.com/api'
TAG_SUFFIX = '-neoforge-1.21.1'


def properties(root=ROOT):
    result = {}
    for line in (root / 'gradle.properties').read_text(encoding='utf-8').splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip()
    return result


def validate(root=ROOT, tag=None, java=False):
    props = properties(root)
    version = props.get('mod_version', '')
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', version):
        raise ValueError('Invalid mod_version')
    if props.get('minecraft_version') != '1.21.1':
        raise ValueError('Only Minecraft 1.21.1 is supported by this workflow')
    if props.get('neo_version') != '21.1.216':
        raise ValueError('Review workflow compatibility before changing NeoForge 21.1.216')
    if props.get('mod_license') != 'LGPL-3.0-only':
        raise ValueError('Review license metadata before release')
    expected = f'v{version}{TAG_SUFFIX}'
    if tag is not None and tag != expected:
        raise ValueError(f'Release tag must exactly match {expected}')
    if java:
        completed = subprocess.run(['java', '-version'], capture_output=True, text=True, check=True)
        if not re.search(r'version "21(?:\.|\")', completed.stdout + completed.stderr):
            raise ValueError('Release build requires JDK 21')
    return version, expected


def jar_name(version):
    return f'tactical-neoforge-1.21.1-{version}.jar'


def png_size(data):
    # Header-only validation, not a substitute for decoding or visual review.
    if (len(data) < 24 or not data.startswith(b'\x89PNG\r\n\x1a\n') or
            data[8:16] != b'\x00\x00\x00\rIHDR'):
        raise ValueError('Icon is not a PNG with an IHDR header')
    return int.from_bytes(data[16:20], 'big'), int.from_bytes(data[20:24], 'big')


def check_branding(root=ROOT):
    for path, expected in [('branding/tactical-rpg-1024.png', (1024, 1024)),
                           ('branding/curseforge-icon.png', (400, 400)),
                           ('neoforge-1.21.1/src/main/resources/logo.png', (256, 256))]:
        if png_size((root / path).read_bytes()) != expected:
            raise ValueError(f'Unexpected icon dimensions: {path}')


def check_jar(jar, version, root=ROOT):
    with ZipFile(jar) as archive:
        metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8'))
        mods = [item for item in metadata.get('mods', []) if item.get('modId') == 'tactical']
        if len(mods) != 1 or mods[0].get('version') != version or mods[0].get('logoFile') != 'logo.png':
            raise ValueError('JAR version/logo metadata mismatch')
        if metadata.get('license') != 'LGPL-3.0-only':
            raise ValueError('JAR license metadata mismatch')
        names = set(archive.namelist())
        required = ('logo.png', 'META-INF/LICENSE', 'META-INF/NOTICE.md',
                    'META-INF/licenses/yiran-expression-lib/YiRanExpressionLib-LICENSE.txt',
                    'META-INF/licenses/yiran-expression-lib/GPL-3.0.txt', 'META-INF/jarjar/metadata.json')
        if not set(required) <= names:
            raise ValueError('JAR missing logo, project/third-party notices or nested-library metadata')
        logo = archive.read('logo.png')
        if png_size(logo) != (256, 256):
            raise ValueError('logo.png must be 256 x 256')
        if logo != (root / 'neoforge-1.21.1/src/main/resources/logo.png').read_bytes():
            raise ValueError('Stale packaged logo.png')
        for source, target in [('LICENSE', 'META-INF/LICENSE'), ('NOTICE.md', 'META-INF/NOTICE.md'),
                               ('licenses/YiRanExpressionLib-LICENSE.txt', required[3]),
                               ('licenses/GPL-3.0.txt', required[4])]:
            if archive.read(target) != (root / source).read_bytes():
                raise ValueError(f'Stale packaged notice: {target}')
        # Exactly one nested library, official upstream bytes, pinned JarJar identity/range/path.
        expression_lib.check_local_artifact(root)
        expression_lib.check_nested(archive, root, names)


def package(root=ROOT, tag=None, output=None):
    version, expected = validate(root, tag)
    jar = root / 'neoforge-1.21.1/build/libs' / jar_name(version)
    check_branding(root)
    check_jar(jar, version, root)
    output = output or root / 'build/release'
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError('Release directory must be empty; refusing stale mixed artifacts')
    target = output / jar.name
    shutil.copyfile(jar, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (output / 'SHA256SUMS').write_text(f'{digest}  {jar.name}\n', encoding='utf-8')
    manifest = {'version': version, 'tag': expected, 'minecraft': '1.21.1', 'jdk': 21,
                'file': jar.name, 'sha256': digest}
    (output / 'release-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    return manifest


def verify(bundle, root=ROOT, tag=None):
    version, expected = validate(root, tag)
    manifest = json.loads((bundle / 'release-manifest.json').read_text(encoding='utf-8'))
    if (manifest.get('version'), manifest.get('tag'), manifest.get('minecraft'), manifest.get('jdk'),
        manifest.get('file')) != (version, expected, '1.21.1', 21, jar_name(version)):
        raise ValueError('Release manifest does not match checked-out release')
    jar = bundle / jar_name(version)
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if manifest.get('sha256') != digest:
        raise ValueError('Release JAR SHA-256 mismatch')
    if (bundle / 'SHA256SUMS').read_text(encoding='utf-8') != f'{digest}  {jar.name}\n':
        raise ValueError('SHA256SUMS mismatch')
    if {item.name for item in bundle.iterdir()} != {jar.name, 'SHA256SUMS', 'release-manifest.json'}:
        raise ValueError('Unexpected release payload (sources/logs are not publishable)')
    check_jar(jar, version, root)
    return jar


def required_env(env, name):
    value = env.get(name, '').strip()
    if not value:
        raise ValueError(f'Missing release configuration: {name}')
    return value


def positive_id(value, name):
    if not re.fullmatch(r'[1-9][0-9]*', value):
        raise ValueError(f'{name} must be a positive numeric project ID')
    return value


def cf_config(env):
    project = positive_id(required_env(env, 'CURSEFORGE_PROJECT_ID'), 'CURSEFORGE_PROJECT_ID')
    token = required_env(env, 'CURSEFORGE_API_TOKEN')
    if not re.fullmatch(r'[!-~]+', token):
        raise ValueError('CURSEFORGE_API_TOKEN must contain only visible ASCII characters')
    relations = []
    for prefix, relation in [('APRICITYUI', 'requiredDependency'), ('DUNGEONS_PERSPECTIVE', 'optionalDependency')]:
        slug = required_env(env, f'CURSEFORGE_{prefix}_SLUG')
        if not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', slug):
            raise ValueError(f'CURSEFORGE_{prefix}_SLUG must be a confirmed CurseForge slug')
        project_id = positive_id(required_env(env, f'CURSEFORGE_{prefix}_PROJECT_ID'), prefix)
        if project_id == project:
            raise ValueError('Dependency project must differ from release project')
        relations.append({'slug': slug, 'projectID': project_id, 'type': relation})
    if relations[0]['projectID'] == relations[1]['projectID']:
        raise ValueError('Dependency projects must be distinct')
    return project, token, relations


def request_json(request, opener=urllib.request.urlopen):
    # No redirects: avoid forwarding X-Api-Token or payload to another origin.
    try:
        with opener(request, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise ValueError(f'CurseForge HTTP {error.code}; inspect project/token configuration (body suppressed)') from None
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, UnicodeDecodeError):
        raise ValueError('CurseForge transport/JSON error; response suppressed; no automatic retry') from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def safe_opener(request, timeout):
    return urllib.request.build_opener(NoRedirect()).open(request, timeout=timeout)


LEVEL_TWO = re.compile(r'^##\s+')
RELEASE_HEADING = re.compile(r'^##\s+\[?(?P<version>[0-9A-Za-z][0-9A-Za-z.+\-]*)\]?(?=$|[\s—–:(-])')


def release_notes(text, version):
    """Return only the level-two section for `version` (bracketed or plain heading).

    CurseForge and the GitHub release must receive the current release notes, not the whole
    history. A missing, duplicated or empty section is an error: uploading the full changelog or an
    empty body would silently publish the wrong text.
    """
    lines = text.splitlines()
    starts = []
    for index, line in enumerate(lines):
        match = RELEASE_HEADING.match(line)
        if match and match.group('version') == version:
            starts.append(index)
    if not starts:
        raise ValueError(f'CHANGELOG.md has no level-two section for version {version}')
    if len(starts) > 1:
        raise ValueError(f'CHANGELOG.md has {len(starts)} level-two sections for version {version}')
    body_start = starts[0] + 1
    body_end = len(lines)
    for index in range(body_start, len(lines)):
        if LEVEL_TWO.match(lines[index]):
            body_end = index
            break
    body = '\n'.join(lines[body_start:body_end]).strip()
    if not body:
        raise ValueError(f'CHANGELOG.md section for version {version} is empty')
    return body + '\n'


REQUIRED_VERSION_NAMES = ('1.21.1', 'NeoForge')


def cf_metadata(env, changelog, release_type='beta', opener=safe_opener, report=None):
    project, token, relations = cf_config(env)
    if release_type not in ('alpha', 'beta', 'release'):
        raise ValueError('Invalid release type')
    # Safety pre-check against the catalog: each required name must exist with at least one usable
    # id. No id is selected from it — uploads send the officially supported `gameVersionNames`
    # field instead, so a duplicated catalog row (a real CurseForge situation for `1.21.1`) is not
    # an error and cannot make this code pick the wrong numeric id.
    versions = request_json(urllib.request.Request(f'{API}/game/versions',
                            headers={'X-Api-Token': token, 'Accept': 'application/json'}), opener)
    if not isinstance(versions, list) or not all(isinstance(item, dict) for item in versions):
        raise ValueError('Unexpected CurseForge game versions response')
    def usable_ids(name):
        return [item['id'] for item in versions
                if item.get('name') == name and type(item.get('id')) is int and item['id'] > 0]

    for name in REQUIRED_VERSION_NAMES:
        if not usable_ids(name):
            raise ValueError(f'CurseForge version name {name} has no usable positive id; refusing to upload')
    if report is not None:
        # Observation only: the catalog is probed for the environment tags some CurseForge setups
        # expect, but nothing is added to the upload unless that is verified and decided separately.
        present = [name for name in ('Client', 'Server') if usable_ids(name)]
        report.append('upload_game_version_names=' + ','.join(REQUIRED_VERSION_NAMES))
        report.append('optional_environment_tags_present=' + (','.join(present) if present else 'none'))
    return project, token, {'changelog': changelog, 'changelogType': 'markdown',
                            'gameVersionNames': list(REQUIRED_VERSION_NAMES), 'releaseType': release_type,
                            'relations': {'projects': relations}}


def multipart(metadata, jar):
    boundary = 'tactical-' + uuid.uuid4().hex
    prefix = (f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
              'Content-Type: application/json\r\n\r\n').encode()
    file_header = (f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{jar.name}"\r\n'
                   'Content-Type: application/java-archive\r\n\r\n').encode()
    body = prefix + json.dumps(metadata).encode('utf-8') + file_header + jar.read_bytes()
    return boundary, body + f'\r\n--{boundary}--\r\n'.encode()


def cf_upload(bundle, tag, changelog, release_type='beta', root=ROOT, env=None, opener=safe_opener):
    jar = verify(bundle, root, tag)
    project, token, metadata = cf_metadata(os.environ if env is None else env, changelog, release_type, opener)
    boundary, body = multipart(metadata, jar)
    response = request_json(urllib.request.Request(f'{API}/projects/{project}/upload-file', data=body,
                            headers={'X-Api-Token': token, 'Content-Type': f'multipart/form-data; boundary={boundary}'},
                            method='POST'), opener)
    if not isinstance(response, dict) or type(response.get('id')) is not int or response['id'] <= 0:
        raise ValueError('CurseForge did not acknowledge a file ID; check dashboard before retrying')
    return response['id']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('validate', 'package', 'verify', 'release-notes',
                                            'curseforge-preflight', 'curseforge-upload'))
    parser.add_argument('--tag')
    parser.add_argument('--java', action='store_true')
    parser.add_argument('--bundle', type=Path, default=ROOT / 'build/release')
    parser.add_argument('--changelog', type=Path, default=ROOT / 'CHANGELOG.md')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--release-type', choices=('alpha', 'beta', 'release'), default='beta')
    args = parser.parse_args()
    if args.command == 'validate':
        version, tag = validate(tag=args.tag, java=args.java)
        print(f'version={version}\ntag={tag}')
        if os.environ.get('GITHUB_OUTPUT'):
            with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
                output.write(f'version={version}\ntag={tag}\n')
    elif args.command == 'package':
        package(tag=args.tag, output=args.bundle)
        print('Verified release bundle created')
    elif args.command == 'verify':
        verify(args.bundle, tag=args.tag)
        print('Release bundle SHA-256, metadata, logo and licenses verified')
    elif args.command == 'release-notes':
        version, _ = validate(tag=args.tag)
        notes = release_notes(args.changelog.read_text(encoding='utf-8'), version)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(notes, encoding='utf-8')
            print(f'Release notes for {version} written to {args.output}')
        else:
            print(notes, end='')
    elif args.command == 'curseforge-preflight':
        version, _ = validate(tag=args.tag)
        report = []
        cf_metadata(os.environ, release_notes(args.changelog.read_text(encoding='utf-8'), version),
                    args.release_type, report=report)
        for line in report:
            print(line)
        print('CurseForge configuration, current release notes and game version names verified (no upload)')
    elif args.command == 'curseforge-upload':
        if not args.tag:
            raise ValueError('Uploading requires an explicit --tag')
        version, _ = validate(tag=args.tag)
        notes = release_notes(args.changelog.read_text(encoding='utf-8'), version)
        file_id = cf_upload(args.bundle, args.tag, notes, args.release_type)
        print(f'CurseForge acknowledged file ID {file_id}; publication remains subject to moderation')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        # Controlled local errors only; never dump an HTTP body or token.
        print(f'Release blocked: {error}', file=sys.stderr)
        sys.exit(1)
