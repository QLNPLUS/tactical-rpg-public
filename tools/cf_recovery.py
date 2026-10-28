"""Recovery publish for one already-built release: no rebuild, one CurseForge POST, no retry.

The first tag run (`v0.23.1-neoforge-1.21.1`, commit `c6ecbe3`) built and verified a JAR, then its
CurseForge upload was rejected with `HTTP 400` and the response body was not recorded, so the cause
is still **unknown**. This module is the independent control code for the single permitted recovery
attempt. It never rebuilds anything: the product is the verified artifact of run `40661691067`,
pinned by commit and JAR SHA-256.

Design rules (all enforced here, not in prose):

* **Fixed identity.** Tag, source run, commit, JAR SHA-256 and intent artifact name are constants;
  there are no workflow inputs, so a dispatch cannot point at a different product.
* **One POST.** `upload_once` sends exactly one request and never retries. Any failure — HTTP error,
  timeout, transport error, unparsable body, or a success body without a positive integer file id —
  raises `Blocked` with a *whitelisted* classification. A failed attempt means the CurseForge state
  is **unknown**, never "nothing was created".
* **No untrusted output.** Only fixed `key=value` lines are printed: booleans, counts, a numeric
  file id, the tag/commit, and one classification token. The token, request bytes, response bodies
  and exception texts are never printed.
* **Fail closed.** A recovery intent artifact must not already exist, no GitHub release may exist for
  the tag, and the catalog must still contain the four version names before the POST happens.
* **Intent before POST.** `record-intent` writes a token-free JSON record that the workflow uploads
  as an artifact *before* the upload step, so any later run refuses instead of double-posting.

Usage (each step is independent so the workflow can keep them separate):

    python tools/cf_recovery.py verify-product
    python tools/cf_recovery.py check-intent
    python tools/cf_recovery.py check-release
    python tools/cf_recovery.py preflight
    python tools/cf_recovery.py record-intent
    python tools/cf_recovery.py upload
"""
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

import release_pipeline as release

ROOT = Path(__file__).resolve().parent.parent

TAG = 'v0.23.1-neoforge-1.21.1'
SOURCE_RUN_ID = '40661691067'
EXPECTED_COMMIT = 'c6ecbe3bead2309baeed2ad2e58728d40e3c0dd8'
EXPECTED_JAR_SHA256 = 'df18bfae641aa20db61e2ac558c847a2bead45557df2c3acd2fb157e92bac141'
INTENT_NAME = 'cf-recovery-intent-v0.23.1-neoforge-1.21.1'
RELEASE_TYPE = 'beta'
BUNDLE = Path('build/release')
INTENT_FILE = 'cf-recovery-intent.json'

# Every failure is reported with one of these tokens and nothing else.
ERROR_CLASSES = (
    'product_missing', 'product_extra', 'product_sha_mismatch', 'product_manifest_mismatch',
    'intent_present', 'intent_query_failed', 'release_present', 'release_query_failed',
    'catalog_missing_name', 'catalog_error', 'notes_missing',
    'http_400', 'http_401', 'http_403', 'http_404', 'http_409', 'http_429', 'http_5xx', 'http_other',
    'timeout', 'network_error', 'invalid_response', 'no_file_id', 'unexpected',
)

VERSION_RE = re.compile(r'^v(?P<version>[0-9A-Za-z][0-9A-Za-z.+-]*)-neoforge-1\.21\.1$')


class Blocked(Exception):
    """A fail-closed stop; `error_class` is always one of ERROR_CLASSES."""

    def __init__(self, error_class, detail=None):
        assert error_class in ERROR_CLASSES, error_class
        super().__init__(error_class)
        self.error_class = error_class
        # `detail` is only ever a name we control (an artifact name, a missing version name).
        self.detail = detail


def version_of(tag=TAG):
    match = VERSION_RE.match(tag)
    if match is None:
        raise Blocked('product_manifest_mismatch')
    return match.group('version')


def verify_product(bundle=BUNDLE, jar_sha=EXPECTED_JAR_SHA256, commit=EXPECTED_COMMIT, tag=TAG):
    """Check the downloaded artifact is exactly the verified product of the pinned tag commit."""
    bundle = Path(bundle)
    version = version_of(tag)
    jar = bundle / release.jar_name(version)
    if not jar.is_file():
        raise Blocked('product_missing')
    names = {item.name for item in bundle.iterdir()}
    expected_names = {jar.name, 'SHA256SUMS', 'release-manifest.json'}
    if names != expected_names:
        raise Blocked('product_extra')
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if digest != jar_sha:
        raise Blocked('product_sha_mismatch')
    try:
        manifest = json.loads((bundle / 'release-manifest.json').read_text(encoding='utf-8'))
        sums = (bundle / 'SHA256SUMS').read_text(encoding='utf-8')
    except (OSError, json.JSONDecodeError):
        raise Blocked('product_manifest_mismatch') from None
    if (manifest.get('version'), manifest.get('tag'), manifest.get('sha256'),
            manifest.get('file')) != (version, tag, digest, jar.name):
        raise Blocked('product_manifest_mismatch')
    # The artifact this recovery ships was built before `commit` was added to the manifest, so the
    # field is optional here. When present it must still match the pinned commit.
    if manifest.get('commit') is not None and manifest.get('commit') != commit:
        raise Blocked('product_manifest_mismatch')
    if sums != f'{digest}  {jar.name}\n':
        raise Blocked('product_manifest_mismatch')
    return jar


def active_intent_names(payload, intent=INTENT_NAME):
    """Names of live (non-expired) artifacts in a GitHub artifacts API payload."""
    artifacts = payload.get('artifacts') if isinstance(payload, dict) else None
    if not isinstance(artifacts, list):
        raise Blocked('intent_query_failed')
    return [item.get('name') for item in artifacts
            if isinstance(item, dict) and item.get('name') == intent and item.get('expired') is False]


def check_no_intent(names, intent=INTENT_NAME):
    if names:
        raise Blocked('intent_present', intent)


def release_tags(payload):
    if not isinstance(payload, list) or not all(isinstance(item, dict) for item in payload):
        raise Blocked('release_query_failed')
    return [item.get('tag_name') for item in payload]


def check_no_release(tags, tag=TAG):
    if tag in tags:
        raise Blocked('release_present')


def _opener(opener):
    """Resolve the opener at call time so tests can patch `release.safe_opener`."""
    return release.safe_opener if opener is None else opener


def github_json(url, token, opener=None):
    request = urllib.request.Request(url, headers={
        'Authorization': 'Bearer ' + token,
        'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
    }, method='GET')
    with _opener(opener)(request, timeout=60) as response:
        return json.load(response)


def artifacts_url(repo, intent=INTENT_NAME):
    return f'https://api.github.com/repos/{repo}/actions/artifacts?per_page=100&name={intent}'


def releases_url(repo):
    return f'https://api.github.com/repos/{repo}/releases?per_page=100'


def preflight(env, notes, report=None, opener=None):
    """Catalog + configuration pre-check. GET only; nothing is created."""
    try:
        project, token, metadata = release.cf_metadata(env, notes, RELEASE_TYPE, _opener(opener),
                                                       report if report is not None else None)
    except Blocked:
        raise
    except ValueError as error:
        # release.cf_metadata raises for a missing/ambiguous catalog name or bad configuration.
        text = str(error)
        if 'version names absent' in text:
            raise Blocked('catalog_missing_name', text.split(':', 1)[-1].strip()) from None
        raise Blocked('catalog_error') from None
    return project, token, metadata


def release_notes(text, tag=TAG):
    version = version_of(tag)
    try:
        return release.release_notes(text, version)
    except ValueError:
        raise Blocked('notes_missing') from None


def upload_once(metadata, jar, project, token, opener=None):
    """Exactly one CurseForge POST. On any failure the remote state stays *unknown*."""
    boundary, body = release.multipart(metadata, jar)
    request = urllib.request.Request(
        f'{release.API}/projects/{project}/upload-file',
        data=body,
        headers={'X-Api-Token': token,
                 'Content-Type': f'multipart/form-data; boundary={boundary}'},
        method='POST',
    )
    try:
        with _opener(opener)(request, timeout=120) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        code = int(getattr(error, 'code', 0) or 0)
        if code in (400, 401, 403, 404, 409, 429):
            raise Blocked('http_%d' % code) from None
        if 500 <= code < 600:
            raise Blocked('http_5xx') from None
        raise Blocked('http_other') from None
    except (TimeoutError, urllib.error.URLError):
        raise Blocked('network_error') from None
    except json.JSONDecodeError:
        raise Blocked('invalid_response') from None
    except OSError:
        raise Blocked('network_error') from None
    except Exception:
        raise Blocked('unexpected') from None
    file_id = payload.get('id') if isinstance(payload, dict) else None
    if type(file_id) is not int or file_id <= 0:
        raise Blocked('no_file_id')
    return file_id


def intent_payload(tag=TAG, commit=EXPECTED_COMMIT, jar_sha=EXPECTED_JAR_SHA256, project=None,
                   run_id=None):
    import datetime
    return {
        'kind': 'recovery-upload-intent',
        'tag': tag,
        'sourceRunId': run_id if run_id is not None else os.environ.get('GITHUB_RUN_ID', ''),
        'sourceArtifactRunId': SOURCE_RUN_ID,
        'commit': commit,
        'projectId': project if project is not None else os.environ.get('CURSEFORGE_PROJECT_ID', ''),
        'jarSha256': jar_sha,
        'recordedAtUtc': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        'note': 'recovery intent only: no credential, no request or response body; at most one POST',
    }


def record_intent(destination=None, **kwargs):
    target = Path(destination) if destination else Path(os.environ['RUNNER_TEMP']) / INTENT_FILE
    target.write_text(json.dumps(intent_payload(**kwargs), indent=2) + '\n', encoding='utf-8')
    return target


def env_token():
    token = (os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN') or '').strip()
    if not token or re.fullmatch(r'[!-~]+', token) is None:
        raise Blocked('intent_query_failed')
    return token


def repo_name():
    repo = (os.environ.get('GH_REPO') or os.environ.get('GITHUB_REPOSITORY') or '').strip()
    if re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo) is None:
        raise Blocked('intent_query_failed')
    return repo


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    command = argv[0] if argv else ''
    report = []
    try:
        if command == 'verify-product':
            jar = verify_product()
            print('product_verified=yes')
            print('product_jar=' + jar.name)
            print('product_sha256=' + EXPECTED_JAR_SHA256)
            print('source_run_id=' + SOURCE_RUN_ID)
            print('source_commit=' + EXPECTED_COMMIT)
        elif command == 'check-intent':
            token, repo = env_token(), repo_name()
            names = active_intent_names(github_json(artifacts_url(repo), token))
            check_no_intent(names)
            print('recovery_intent_present=no')
            print('recovery_intent_name=' + INTENT_NAME)
        elif command == 'check-release':
            token, repo = env_token(), repo_name()
            tags = release_tags(github_json(releases_url(repo), token))
            check_no_release(tags)
            print('existing_release_present=no')
            print('release_tag=' + TAG)
        elif command == 'preflight':
            notes = release_notes((ROOT / 'CHANGELOG.md').read_text(encoding='utf-8'))
            project, _token, metadata = preflight(os.environ, notes, report)
            print('preflight=ok')
            print('project_id=' + project)
            print('version_names=' + ','.join(metadata['gameVersionNames']))
            print('release_type=' + metadata['releaseType'])
            print('relation_count=' + str(len(metadata['relations']['projects'])))
            print('release_notes_chars=' + str(len(notes)))
            for line in report:
                print(line)
        elif command == 'record-intent':
            target = record_intent()
            print('intent_recorded=yes')
            print('intent_path=' + target.name)
            print('intent_name=' + INTENT_NAME)
        elif command == 'upload':
            notes = release_notes((ROOT / 'CHANGELOG.md').read_text(encoding='utf-8'))
            jar = verify_product()
            project, token, metadata = preflight(os.environ, notes)
            file_id = upload_once(metadata, jar, project, token)
            print('result=success')
            print('curseforge_file_id=' + str(file_id))
            print('upload_attempts=1')
        else:
            print('result=blocked')
            print('error_class=unexpected')
            print('usage=verify-product|check-intent|check-release|preflight|record-intent|upload')
            return 1
    except Blocked as blocked:
        print('result=blocked')
        print('error_class=' + blocked.error_class)
        if blocked.detail:
            print('detail=' + str(blocked.detail)[:200])
        if command == 'upload':
            # The only thing a failed attempt proves is that the outcome is unknown.
            print('curseforge_outcome=unknown')
            print('retry_policy=forbidden_second_upload')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
