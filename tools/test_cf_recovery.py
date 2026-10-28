"""Offline tests for tools/cf_recovery.py (no network, no token, no upload).

The recovery path is the last chance to publish 0.23.1, so every guard is tested here rather than
being trusted: exact product pinning, the "no second attempt" rules, and the output discipline that
keeps the CurseForge token and response bodies out of the logs.

Run from this directory: python -m unittest test_cf_recovery
"""
import hashlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import cf_recovery as recovery
import release_pipeline as release

TOKEN = 'fake-token-value-DO-NOT-PRINT'
CATALOG = [{'id': 11779, 'name': '1.21.1'}, {'id': 10150, 'name': 'NeoForge'},
           {'id': 3, 'name': 'Client'}, {'id': 4, 'name': 'Server'}]
ENV = {
    'CURSEFORGE_PROJECT_ID': '1766445',
    'CURSEFORGE_API_TOKEN': TOKEN,
    'CURSEFORGE_APRICITYUI_SLUG': 'apricityui',
    'CURSEFORGE_APRICITYUI_PROJECT_ID': '1470115',
    'CURSEFORGE_DUNGEONS_PERSPECTIVE_SLUG': 'dungeons-perspective',
    'CURSEFORGE_DUNGEONS_PERSPECTIVE_PROJECT_ID': '1258200',
}
NOTES = '## 0.23.1 — current\nwarning\n## 0.23.0 — old\nhistory\n'


class FakeResponse:
    def __init__(self, payload):
        self._body = payload if isinstance(payload, bytes) else json.dumps(payload).encode('utf-8')

    def read(self, amount=None):
        return self._body if amount is None else self._body[:amount]

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class Recorder:
    """Opener stub: serves the catalog GET and one canned POST outcome."""

    def __init__(self, catalog=None, post=None):
        self.catalog = CATALOG if catalog is None else catalog
        self.post = post
        self.requests = []

    def __call__(self, request, timeout=None):
        self.requests.append(request)
        if request.get_method() == 'POST':
            outcome = self.post
            if isinstance(outcome, Exception):
                raise outcome
            return FakeResponse(outcome if outcome is not None else {'id': 987654})
        return FakeResponse(self.catalog)


def make_bundle(directory, sha=None, commit=recovery.EXPECTED_COMMIT, extra=None):
    bundle = Path(directory)
    bundle.mkdir(parents=True, exist_ok=True)
    jar = bundle / release.jar_name('0.23.1')
    jar.write_bytes(b'PK\x03\x04 not a real jar, only hashed here')
    digest = hashlib.sha256(jar.read_bytes()).hexdigest() if sha is None else sha
    (bundle / 'SHA256SUMS').write_text(f'{digest}  {jar.name}\n', encoding='utf-8')
    (bundle / 'release-manifest.json').write_text(json.dumps({
        'version': '0.23.1', 'tag': recovery.TAG, 'commit': commit,
        'minecraft': '1.21.1', 'jdk': 21, 'file': jar.name, 'sha256': digest,
    }), encoding='utf-8')
    for name in extra or ():
        (bundle / name).write_text('x', encoding='utf-8')
    return bundle, jar, digest


class ProductTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def test_accepts_the_pinned_artifact(self):
        bundle, jar, digest = make_bundle(self.tmp.name)
        self.assertEqual(recovery.verify_product(bundle, jar_sha=digest), jar)

    def test_rejects_a_different_jar_hash(self):
        bundle, _jar, _digest = make_bundle(self.tmp.name)
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.verify_product(bundle, jar_sha='0' * 64)
        self.assertEqual(caught.exception.error_class, 'product_sha_mismatch')

    def test_rejects_extra_files(self):
        bundle, _jar, digest = make_bundle(self.tmp.name, extra=('sources.jar',))
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.verify_product(bundle, jar_sha=digest)
        self.assertEqual(caught.exception.error_class, 'product_extra')

    def test_rejects_missing_jar(self):
        bundle, jar, digest = make_bundle(self.tmp.name)
        jar.unlink()
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.verify_product(bundle, jar_sha=digest)
        self.assertEqual(caught.exception.error_class, 'product_missing')

    def test_rejects_a_manifest_from_another_commit(self):
        bundle, _jar, digest = make_bundle(self.tmp.name, commit='0' * 40)
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.verify_product(bundle, jar_sha=digest)
        self.assertEqual(caught.exception.error_class, 'product_manifest_mismatch')

    def test_manifest_without_a_commit_field_is_accepted(self):
        # The pinned 0.23.1 artifact predates the `commit` field; the SHA-256 pin is the real identity.
        bundle, jar, digest = make_bundle(self.tmp.name, commit=None)
        self.assertEqual(recovery.verify_product(bundle, jar_sha=digest), jar)


class GuardTests(unittest.TestCase):
    def test_intent_absent(self):
        recovery.check_no_intent([])

    def test_live_intent_blocks(self):
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.check_no_intent([recovery.INTENT_NAME])
        self.assertEqual(caught.exception.error_class, 'intent_present')

    def test_expired_intent_is_ignored(self):
        payload = {'artifacts': [{'name': recovery.INTENT_NAME, 'expired': True}]}
        self.assertEqual(recovery.active_intent_names(payload), [])

    def test_malformed_artifact_payload_blocks(self):
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.active_intent_names({'nope': []})
        self.assertEqual(caught.exception.error_class, 'intent_query_failed')

    def test_release_absent(self):
        recovery.check_no_release(['v0.22.2-neoforge-1.21.1'])

    def test_existing_release_blocks(self):
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.check_no_release([recovery.TAG])
        self.assertEqual(caught.exception.error_class, 'release_present')


class PreflightTests(unittest.TestCase):
    def test_metadata_carries_four_names(self):
        project, _token, metadata = recovery.preflight(ENV, NOTES, [], Recorder())
        self.assertEqual(project, '1766445')
        self.assertEqual(metadata['gameVersionNames'], ['1.21.1', 'NeoForge', 'Client', 'Server'])
        self.assertEqual(metadata['releaseType'], 'beta')
        self.assertEqual([item['type'] for item in metadata['relations']['projects']],
                         ['requiredDependency', 'optionalDependency'])

    def test_missing_environment_tag_blocks_before_any_post(self):
        catalog = [row for row in CATALOG if row['name'] != 'Client']
        opener = Recorder(catalog=catalog)
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.preflight(ENV, NOTES, [], opener)
        self.assertEqual(caught.exception.error_class, 'catalog_missing_name')
        self.assertIn('Client', str(caught.exception.detail))
        self.assertEqual([request.get_method() for request in opener.requests], ['GET'])

    def test_malformed_catalog_blocks(self):
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.preflight(ENV, NOTES, [], Recorder(catalog={'not': 'a list'}))
        self.assertEqual(caught.exception.error_class, 'catalog_error')

    def test_missing_changelog_section_blocks(self):
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.release_notes('## 0.22.2 — old\nhistory\n')
        self.assertEqual(caught.exception.error_class, 'notes_missing')


class UploadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        bundle, self.jar, digest = make_bundle(self.tmp.name)
        self.bundle = bundle
        self.digest = digest

    def _metadata(self):
        _project, _token, metadata = recovery.preflight(ENV, NOTES, [], Recorder())
        return metadata

    def test_single_post_returns_the_file_id(self):
        opener = Recorder(post={'id': 555})
        file_id = recovery.upload_once(self._metadata(), self.jar, '1766445', TOKEN, opener)
        self.assertEqual(file_id, 555)
        methods = [request.get_method() for request in opener.requests]
        self.assertEqual(methods.count('POST'), 1)

    def test_request_is_addressed_to_the_fixed_endpoint(self):
        opener = Recorder(post={'id': 556})
        recovery.upload_once(self._metadata(), self.jar, '1766445', TOKEN, opener)
        post = [request for request in opener.requests if request.get_method() == 'POST'][0]
        self.assertEqual(post.full_url, 'https://minecraft.curseforge.com/api/projects/1766445/upload-file')
        self.assertEqual(post.get_header('X-api-token'), TOKEN)
        self.assertIn(b'"gameVersionNames": ["1.21.1", "NeoForge", "Client", "Server"]', post.data)
        self.assertNotIn(TOKEN.encode(), post.data)

    def test_http_400_is_classified_and_not_retried(self):
        import urllib.error
        error = urllib.error.HTTPError('https://example.invalid', 400, 'Bad Request', {}, None)
        opener = Recorder(post=error)
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.upload_once(self._metadata(), self.jar, '1766445', TOKEN, opener)
        self.assertEqual(caught.exception.error_class, 'http_400')
        self.assertEqual([r.get_method() for r in opener.requests].count('POST'), 1)

    def test_server_error_is_classified_as_5xx(self):
        import urllib.error
        error = urllib.error.HTTPError('https://example.invalid', 503, 'Unavailable', {}, None)
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.upload_once(self._metadata(), self.jar, '1766445', TOKEN, Recorder(post=error))
        self.assertEqual(caught.exception.error_class, 'http_5xx')

    def test_timeout_is_a_network_class(self):
        import urllib.error
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.upload_once(self._metadata(), self.jar, '1766445', TOKEN,
                                 Recorder(post=urllib.error.URLError('timed out')))
        self.assertEqual(caught.exception.error_class, 'network_error')

    def test_success_without_file_id_is_not_success(self):
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.upload_once(self._metadata(), self.jar, '1766445', TOKEN, Recorder(post={'ok': True}))
        self.assertEqual(caught.exception.error_class, 'no_file_id')

    def test_negative_file_id_is_rejected(self):
        with self.assertRaises(recovery.Blocked) as caught:
            recovery.upload_once(self._metadata(), self.jar, '1766445', TOKEN, Recorder(post={'id': -1}))
        self.assertEqual(caught.exception.error_class, 'no_file_id')


class MainOutputTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        _bundle, jar, digest = make_bundle(self.tmp.name)
        self.jar = jar
        self.digest = digest
        self.cwd = os.getcwd()

    def tearDown(self):
        os.chdir(self.cwd)

    def _run(self, argv, opener):
        with mock.patch.dict(os.environ, ENV, clear=False), \
                mock.patch.object(recovery.release, 'release_notes', lambda text, version: NOTES), \
                mock.patch.object(recovery, 'verify_product', lambda *a, **k: self.jar), \
                mock.patch.object(recovery.release, 'safe_opener', opener):
            buffer = io.StringIO()
            with mock.patch('sys.stdout', buffer):
                code = recovery.main(argv)
        return code, buffer.getvalue()

    def test_failed_upload_reports_unknown_and_forbids_retry(self):
        import urllib.error
        error = urllib.error.HTTPError('https://example.invalid', 400, 'Bad Request', {}, None)
        code, out = self._run(['upload'], Recorder(post=error))
        self.assertEqual(code, 1)
        self.assertIn('result=blocked', out)
        self.assertIn('error_class=http_400', out)
        self.assertIn('curseforge_outcome=unknown', out)
        self.assertIn('retry_policy=forbidden_second_upload', out)
        self.assertNotIn(TOKEN, out)
        self.assertNotIn('Bad Request', out)

    def test_successful_upload_prints_only_whitelisted_fields(self):
        code, out = self._run(['upload'], Recorder(post={'id': 4242}))
        self.assertEqual(code, 0)
        self.assertIn('result=success', out)
        self.assertIn('curseforge_file_id=4242', out)
        self.assertIn('upload_attempts=1', out)
        self.assertNotIn(TOKEN, out)

    def test_unknown_command_is_rejected(self):
        code, out = self._run(['publish-anything'], Recorder())
        self.assertEqual(code, 1)
        self.assertIn('error_class=unexpected', out)

    def test_intent_record_is_token_free(self):
        destination = Path(self.tmp.name) / 'intent.json'
        recovery.record_intent(destination, project='1766445', run_id='123')
        text = destination.read_text(encoding='utf-8')
        payload = json.loads(text)
        self.assertEqual(payload['kind'], 'recovery-upload-intent')
        self.assertEqual(payload['sourceArtifactRunId'], recovery.SOURCE_RUN_ID)
        self.assertEqual(payload['jarSha256'], recovery.EXPECTED_JAR_SHA256)
        self.assertNotIn('token', text.lower())


if __name__ == '__main__':
    unittest.main()
