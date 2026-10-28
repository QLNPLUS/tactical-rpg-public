"""Offline release-contract tests: temporary fixtures and mocked official upload API."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import warnings
from zipfile import ZipFile

import expression_lib as library
import release_pipeline as release


class Fixture(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.props = ('mod_version=0.22.2\nminecraft_version=1.21.1\n'
                      'neo_version=21.1.216\nmod_license=LGPL-3.0-only\n')
        (self.root / 'gradle.properties').write_text(self.props)
        for path, content in [('LICENSE', 'original rights'), ('NOTICE.md', 'third-party exception'),
                              ('licenses/YiRanExpressionLib-LICENSE.txt', 'LGPL'), ('licenses/GPL-3.0.txt', 'GPL')]:
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)
        # The bundled artifact is the pinned official build; the fixture ships those exact bytes.
        self.library = (release.ROOT / library.ARTIFACT_PATH).read_bytes()
        library.check_official_bytes(self.library)
        local = library.local_artifact(self.root)
        local.parent.mkdir(parents=True, exist_ok=True)
        local.write_bytes(self.library)
        self.jar = self.root / 'neoforge-1.21.1/build/libs' / release.jar_name('0.22.2')
        self.jar.parent.mkdir(parents=True)
        self.entries = {
            'META-INF/neoforge.mods.toml': 'license = "LGPL-3.0-only"\n[[mods]]\nmodId="tactical"\nversion="0.22.2"\nlogoFile="logo.png"\n',
            'logo.png': b'\x89PNG\r\n\x1a\nfixture',
            'META-INF/LICENSE': b'original rights', 'META-INF/NOTICE.md': b'third-party exception',
            'META-INF/licenses/yiran-expression-lib/YiRanExpressionLib-LICENSE.txt': b'LGPL',
            'META-INF/licenses/yiran-expression-lib/GPL-3.0.txt': b'GPL',
            'META-INF/jarjar/metadata.json': json.dumps({'jars': [{
                'identifier': {'group': library.GROUP, 'artifact': library.ARTIFACT},
                'version': {'range': library.RANGE, 'artifactVersion': library.VERSION},
                'path': library.NESTED_PATH, 'isObfuscated': False}]}),
            library.NESTED_PATH: self.library,
        }
        for path, size in [('branding/tactical-rpg-1024.png', 1024), ('branding/curseforge-icon.png', 400),
                           ('neoforge-1.21.1/src/main/resources/logo.png', 256)]:
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            data = b'\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR' + size.to_bytes(4, 'big') * 2
            target.write_bytes(data)
            if size == 256:
                self.entries['logo.png'] = data
        self.write_jar()
        self.bundle = self.root / 'bundle'
        self.tag = 'v0.22.2-neoforge-1.21.1'
        self.env = {'CURSEFORGE_PROJECT_ID': '123', 'CURSEFORGE_API_TOKEN': 'mock-not-a-real-token',
                    'CURSEFORGE_APRICITYUI_SLUG': 'fixture-ui', 'CURSEFORGE_APRICITYUI_PROJECT_ID': '456',
                    'CURSEFORGE_DUNGEONS_PERSPECTIVE_SLUG': 'fixture-camera',
                    'CURSEFORGE_DUNGEONS_PERSPECTIVE_PROJECT_ID': '789'}

    def write_jar(self, duplicates=()):
        with ZipFile(self.jar, 'w') as archive:
            for name, value in self.entries.items():
                archive.writestr(name, value)
            # A ZIP may legally hold the same name twice; the gate must not collapse them.
            # zipfile warns about that on purpose, so silence it for these fixtures only.
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                for name, value in duplicates:
                    archive.writestr(name, value)

    def package(self):
        return release.package(self.root, self.tag, self.bundle)

    def opener(self, request, timeout):
        self.requests.append(request)
        data = [{'id': 1, 'name': '1.21.1'}, {'id': 2, 'name': 'NeoForge'},
                {'id': 3, 'name': 'Client'}, {'id': 4, 'name': 'Server'}]
        if request.get_method() == 'POST':
            data = {'id': 999}
        return io.BytesIO(json.dumps(data).encode())


class ValidationTests(Fixture):
    def test_exact_version_and_tag(self):
        self.assertEqual(release.validate(self.root, self.tag), ('0.22.2', self.tag))

    def test_branch_build_without_tag_is_allowed(self):
        self.assertEqual(release.validate(self.root)[0], '0.22.2')

    def test_reject_mismatched_or_wrong_platform_tags(self):
        for tag in ['v0.22.1-neoforge-1.21.1', 'v0.22.2', 'v0.22.2-forge-1.20.1', self.tag + '/bad']:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.validate(self.root, tag)

    def test_reject_wrong_mc_neoforge_or_license(self):
        for before, after in [('1.21.1', '1.20.1'), ('21.1.216', '21.1.248'),
                              ('LGPL-3.0-only', 'MIT')]:
            (self.root / 'gradle.properties').write_text(self.props.replace(before, after))
            with self.subTest(after=after), self.assertRaises(ValueError):
                release.validate(self.root)

    @patch.object(release.subprocess, 'run')
    def test_jdk_gate(self, run):
        run.return_value.stdout = ''
        for version, valid in [('21.0.7', True), ('21', True), ('17.0.1', False), ('210.0.1', False)]:
            run.return_value.stderr = f'openjdk version "{version}"'
            if valid:
                release.validate(self.root, java=True)
            else:
                with self.assertRaises(ValueError):
                    release.validate(self.root, java=True)


class BundleTests(Fixture):
    def test_package_and_reverify_identical_jar(self):
        self.package()
        verified = release.verify(self.bundle, self.root, self.tag)
        self.assertEqual(verified.read_bytes(), self.jar.read_bytes())
        self.assertEqual(len(list(self.bundle.iterdir())), 3)

    def test_reject_dirty_bundle(self):
        self.package()
        with self.assertRaisesRegex(ValueError, 'empty'):
            self.package()

    def test_reject_modified_binary(self):
        self.package()
        (self.bundle / self.jar.name).write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            release.verify(self.bundle, self.root, self.tag)

    def test_reject_sources_and_logs(self):
        self.package()
        for name in ['sources.jar', 'latest.log']:
            extra = self.bundle / name
            extra.write_text('not a release payload')
            with self.assertRaisesRegex(ValueError, 'Unexpected release payload'):
                release.verify(self.bundle, self.root, self.tag)
            extra.unlink()

    def test_reject_checksum_tamper(self):
        self.package()
        (self.bundle / 'SHA256SUMS').write_text('invalid')
        with self.assertRaisesRegex(ValueError, 'SHA256SUMS'):
            release.verify(self.bundle, self.root, self.tag)

    def test_reject_manifest_traversal(self):
        self.package()
        target = self.bundle / 'release-manifest.json'
        manifest = json.loads(target.read_text())
        manifest['file'] = '../secret.jar'
        target.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(ValueError, 'manifest'):
            release.verify(self.bundle, self.root, self.tag)

    def test_reject_each_missing_notice_logo_or_library(self):
        for missing in list(self.entries):
            content = self.entries.pop(missing)
            self.write_jar()
            with self.subTest(missing=missing), self.assertRaises((ValueError, KeyError)):
                release.check_jar(self.jar, '0.22.2', self.root)
            self.entries[missing] = content

    def test_reject_stale_license(self):
        self.entries['META-INF/LICENSE'] = 'outdated'
        self.write_jar()
        with self.assertRaisesRegex(ValueError, 'Stale packaged notice'):
            release.check_jar(self.jar, '0.22.2', self.root)

    def test_reject_wrong_jar_version(self):
        with self.assertRaisesRegex(ValueError, 'metadata mismatch'):
            release.check_jar(self.jar, '0.22.1', self.root)

    def test_reject_fake_logo(self):
        self.entries['logo.png'] = b'not PNG'
        self.write_jar()
        with self.assertRaisesRegex(ValueError, 'not a PNG'):
            release.check_jar(self.jar, '0.22.2', self.root)

    def test_reject_wrong_branding_dimensions(self):
        (self.root / 'branding/curseforge-icon.png').write_bytes(self.entries['logo.png'])
        with self.assertRaisesRegex(ValueError, 'dimensions'):
            self.package()

    def test_reject_stale_logo(self):
        self.entries['logo.png'] += b'changed'
        self.write_jar()
        with self.assertRaisesRegex(ValueError, 'Stale packaged logo'):
            release.check_jar(self.jar, '0.22.2', self.root)


class LibraryGateTests(Fixture):
    """The nested library must be the pinned official build under its JarJar identity."""

    def rewrite(self):
        self.write_jar()
        release.check_jar(self.jar, '0.22.2', self.root)

    def test_accepts_official_nested_library(self):
        self.rewrite()

    def test_reject_altered_nested_bytes(self):
        for label, data in [('one byte', self.library.replace(b'\x50\x4b\x03\x04', b'\x50\x4b\x03\x05', 1)),
                            ('truncated', self.library[:-1]),
                            ('empty', b'')]:
            with self.subTest(label=label):
                self.entries[library.NESTED_PATH] = data
                with self.assertRaisesRegex(ValueError, 'official'):
                    self.rewrite()
        self.entries[library.NESTED_PATH] = self.library

    def test_reject_second_nested_jar(self):
        self.entries['META-INF/jarjar/expression-other.jar'] = self.library
        with self.assertRaisesRegex(ValueError, 'exactly one nested JAR'):
            self.rewrite()

    def test_reject_duplicate_jarjar_entries(self):
        metadata = json.loads(self.entries['META-INF/jarjar/metadata.json'])
        metadata['jars'].append(dict(metadata['jars'][0]))
        self.entries['META-INF/jarjar/metadata.json'] = json.dumps(metadata)
        with self.assertRaisesRegex(ValueError, 'exactly one nested library entry'):
            self.rewrite()

    def test_reject_duplicate_zip_entry_for_library_files(self):
        # Two identical names in one ZIP: a set() would hide the second one and read()
        # would silently return the last, so the raw listing must be validated.
        for name in (library.NESTED_PATH, library.METADATA_PATH):
            with self.subTest(name=name):
                self.write_jar(duplicates=[(name, self.entries[name])])
                payload = ZipFile(self.jar).namelist()
                self.assertEqual(payload.count(name), 2)
                with self.assertRaisesRegex(ValueError, 'exactly once'):
                    release.check_jar(self.jar, '0.22.2', self.root)
                self.write_jar()

    def test_reject_duplicate_zip_entry_hiding_official_nested_bytes(self):
        # First nested entry is official, second is tampered: read() returns the tampered one,
        # so the listing check has to reject the archive before the digest check can pass.
        self.write_jar(duplicates=[(library.NESTED_PATH, self.library[:-1])])
        with self.assertRaisesRegex(ValueError, 'exactly once'):
            release.check_jar(self.jar, '0.22.2', self.root)
        self.write_jar()

    def test_reject_missing_jarjar_entry(self):
        self.entries['META-INF/jarjar/metadata.json'] = json.dumps({'jars': []})
        with self.assertRaisesRegex(ValueError, 'exactly one nested library entry'):
            self.rewrite()

    def test_reject_wrong_jarjar_identity(self):
        for group, artifact in [('com.github.yiran1457', 'YiRanExpressionLibCore'),
                                ('com.github.other', 'YiRanExpressionLib')]:
            metadata = json.loads(self.entries['META-INF/jarjar/metadata.json'])
            metadata['jars'][0]['identifier'] = {'group': group, 'artifact': artifact}
            self.entries['META-INF/jarjar/metadata.json'] = json.dumps(metadata)
            with self.subTest(artifact=artifact), self.assertRaisesRegex(ValueError, 'JarJar identity'):
                self.rewrite()

    def test_reject_wrong_jarjar_version(self):
        for range_value, artifact_version in [('[1.0.0,2)', '1.0.1'), ('[1.0.1]', '1.0.2'),
                                              ('[1,2)', '1.0.1')]:
            metadata = json.loads(self.entries['META-INF/jarjar/metadata.json'])
            metadata['jars'][0]['version'] = {'range': range_value, 'artifactVersion': artifact_version}
            self.entries['META-INF/jarjar/metadata.json'] = json.dumps(metadata)
            with self.subTest(range=range_value, version=artifact_version), \
                    self.assertRaisesRegex(ValueError, 'JarJar version'):
                self.rewrite()

    def test_reject_wrong_jarjar_path(self):
        metadata = json.loads(self.entries['META-INF/jarjar/metadata.json'])
        metadata['jars'][0]['path'] = 'META-INF/jarjar/YiRanExpressionLib.jar'
        self.entries['META-INF/jarjar/metadata.json'] = json.dumps(metadata)
        self.entries['META-INF/jarjar/YiRanExpressionLib.jar'] = self.library
        with self.assertRaisesRegex(ValueError, 'JarJar path'):
            self.rewrite()

    def test_reject_stale_local_artifact(self):
        local = library.local_artifact(self.root)
        local.write_bytes(self.library[:-1])
        with self.assertRaisesRegex(ValueError, 'official'):
            self.rewrite()
        local.unlink()
        with self.assertRaisesRegex(ValueError, 'missing bundled library artifact'):
            self.rewrite()

    def test_reject_nested_library_not_matching_resolved_artifact(self):
        # Official bytes in the JAR, but the resolved artifact on disk is something else.
        local = library.local_artifact(self.root)
        self.entries[library.NESTED_PATH] = self.library
        self.write_jar()
        local.unlink()
        local.write_bytes(b'not the resolved artifact')
        with self.assertRaisesRegex(ValueError, 'official'):
            release.check_jar(self.jar, '0.22.2', self.root)

    def test_descriptor_identity_is_checked(self):
        import io
        for descriptor in library.DESCRIPTORS:
            for replacement, label in [((b'modId="yiranexpressionlib"', b'modId="otherlib"'), 'mod id'),
                                       ((b'version="1.0.1"', b'version="1.0.2"'), 'version'),
                                       ((b'license="LGPL v3.0"', b'license="ARR"'), 'license')]:
                payload = io.BytesIO()
                with ZipFile(io.BytesIO(self.library)) as source, ZipFile(payload, 'w') as target:
                    for name in source.namelist():
                        data = source.read(name)
                        if name == descriptor:
                            data = data.replace(*replacement)
                        target.writestr(name, data)
                with self.subTest(descriptor=descriptor, field=label), \
                        self.assertRaises(ValueError):
                    library.check_descriptors(payload.getvalue())


class CurseForgeTests(Fixture):
    def setUp(self):
        super().setUp()
        self.requests = []

    def test_all_settings_are_required_before_network(self):
        for name in self.env:
            env = self.env.copy()
            del env[name]
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Missing release configuration'):
                release.cf_metadata(env, 'notes', opener=self.opener)
        self.assertEqual(self.requests, [])

    def test_reject_invalid_project_or_slug(self):
        for key, value in [('CURSEFORGE_PROJECT_ID', '../123'), ('CURSEFORGE_PROJECT_ID', '0'),
                           ('CURSEFORGE_APRICITYUI_SLUG', 'https://fake'),
                           ('CURSEFORGE_APRICITYUI_PROJECT_ID', '123'),
                           ('CURSEFORGE_DUNGEONS_PERSPECTIVE_PROJECT_ID', '456')]:
            env = dict(self.env, **{key: value})
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                release.cf_config(env)

    def test_invalid_token_header_is_redacted_before_network(self):
        env = dict(self.env, CURSEFORGE_API_TOKEN='mock-private\r\ninvalid')
        with self.assertRaisesRegex(ValueError, 'CURSEFORGE_API_TOKEN') as caught:
            release.cf_metadata(env, '', opener=self.opener)
        self.assertNotIn('mock-private', str(caught.exception))
        self.assertEqual(self.requests, [])

    def test_preflight_is_get_only_and_uses_official_names(self):
        project, token, metadata = release.cf_metadata(self.env, 'notes', opener=self.opener)
        self.assertEqual(project, '123')
        # Official field, no numeric ids chosen from the (possibly duplicated) catalog.
        self.assertEqual(metadata['gameVersionNames'],
                         ['1.21.1', 'NeoForge', 'Client', 'Server'])
        self.assertNotIn('gameVersions', metadata)
        self.assertEqual([p['type'] for p in metadata['relations']['projects']],
                         ['requiredDependency', 'optionalDependency'])
        self.assertEqual(len(self.requests), 1)
        self.assertEqual(self.requests[0].get_method(), 'GET')
        self.assertNotIn(token, self.requests[0].full_url)

    def test_duplicate_catalog_rows_are_accepted(self):
        # Real CurseForge catalog: three entries named 1.21.1, one named NeoForge.
        catalog = [{'id': 11779, 'name': '1.21.1'}, {'id': 12735, 'name': '1.21.1'},
                   {'id': 16115, 'name': '1.21.1'}, {'id': 10150, 'name': 'NeoForge'},
                   {'id': 999, 'name': 'Client'}, {'id': 998, 'name': 'Server'}]
        opener = lambda *args, **kwargs: io.BytesIO(json.dumps(catalog).encode())  # noqa: E731
        _, _, metadata = release.cf_metadata(self.env, 'notes', opener=opener)
        self.assertEqual(metadata['gameVersionNames'],
                         ['1.21.1', 'NeoForge', 'Client', 'Server'])
        self.assertNotIn('gameVersions', metadata)
        # Duplicates for NeoForge too: still accepted, still no id chosen.
        catalog = [{'id': 1, 'name': '1.21.1'}, {'id': 2, 'name': 'NeoForge'}, {'id': 3, 'name': 'NeoForge'},
                   {'id': 4, 'name': 'Client'}, {'id': 5, 'name': 'Server'}]
        _, _, metadata = release.cf_metadata(
            self.env, 'notes', opener=lambda *a, **kw: io.BytesIO(json.dumps(catalog).encode()))
        self.assertNotIn('gameVersions', metadata)

    def test_version_names_are_matched_exactly(self):
        for catalog in ([{'id': 1, 'name': '1.21.1'}, {'id': 2, 'name': 'neoforge'}],
                        [{'id': 1, 'name': '1.21.1'}, {'id': 2, 'name': 'NeoForge '}],
                        [{'id': 1, 'name': '1.21.1 '}, {'id': 2, 'name': 'NeoForge'}],
                        [{'id': 1, 'name': '1.21'}, {'id': 2, 'name': 'NeoForge'}]):
            with self.subTest(catalog=catalog), self.assertRaises(ValueError):
                release.cf_metadata(self.env, '', opener=lambda *a, **kw: io.BytesIO(json.dumps(catalog).encode()))

    def test_entries_without_usable_positive_id_fail(self):
        for catalog in ([], [{'id': 1, 'name': '1.21.1'}],
                        [{'id': '1', 'name': '1.21.1'}, {'id': 2, 'name': 'NeoForge'}],
                        [{'id': True, 'name': '1.21.1'}, {'id': 2, 'name': 'NeoForge'}],
                        [{'id': 0, 'name': '1.21.1'}, {'id': 2, 'name': 'NeoForge'}],
                        [{'id': -5, 'name': '1.21.1'}, {'id': 2, 'name': 'NeoForge'}],
                        [{'id': 1, 'name': '1.21.1'}, {'id': 2}],
                        [{'id': 1, 'name': '1.21.1'}, 'not-an-object', {'id': 2, 'name': 'NeoForge'}],
                        {'id': 1, 'name': '1.21.1'}):
            with self.subTest(catalog=catalog), self.assertRaises(ValueError):
                release.cf_metadata(self.env, '', opener=lambda *a, **kw: io.BytesIO(json.dumps(catalog).encode()))

    def test_missing_environment_tag_fails_closed(self):
        # The upload metadata carries the environment tags itself (v0.23.1 sent only the version
        # and the loader and CurseForge answered HTTP 400), so a catalog without them must stop
        # the release instead of silently publishing a file with an incomplete version list.
        for absent in ('Client', 'Server'):
            catalog = [{'id': 1, 'name': '1.21.1'}, {'id': 2, 'name': 'NeoForge'},
                       {'id': 3, 'name': 'Client'}, {'id': 4, 'name': 'Server'}]
            catalog = [item for item in catalog if item['name'] != absent]
            with self.subTest(absent=absent), self.assertRaisesRegex(ValueError, absent):
                release.cf_metadata(self.env, '', opener=lambda *a, **kw: io.BytesIO(json.dumps(catalog).encode()))

    def test_upload_payload_uses_names_and_keeps_relations(self):
        self.package()
        self.requests.clear()
        file_id = release.cf_upload(self.bundle, self.tag, 'notes', root=self.root,
                                    env=self.env, opener=self.opener)
        self.assertEqual(file_id, 999)
        posts = [request for request in self.requests if request.get_method() == 'POST']
        self.assertEqual(len(posts), 1)
        payload = posts[0].data
        self.assertIn(b'"gameVersionNames": ["1.21.1", "NeoForge", "Client", "Server"]', payload)
        self.assertNotIn(b'gameVersions', payload)
        self.assertIn(b'"relations"', payload)
        self.assertIn(b'requiredDependency', payload)

    def test_invalid_release_type_fails_before_get(self):
        with self.assertRaisesRegex(ValueError, 'release type'):
            release.cf_metadata(self.env, '', 'unknown', self.opener)
        self.assertEqual(self.requests, [])

    def test_mock_upload_uses_same_verified_jar_and_official_contract(self):
        self.package()
        file_id = release.cf_upload(self.bundle, self.tag, 'notes', root=self.root,
                                    env=self.env, opener=self.opener)
        self.assertEqual(file_id, 999)
        self.assertEqual([r.get_method() for r in self.requests], ['GET', 'POST'])
        post = self.requests[1]
        self.assertEqual(post.full_url, release.API + '/projects/123/upload-file')
        self.assertIn(b'name="metadata"', post.data)
        self.assertIn(b'name="file"', post.data)
        self.assertIn(self.jar.read_bytes(), post.data)
        self.assertNotIn(b'sources.jar', post.data)

    def test_tampered_bundle_prevents_api_call(self):
        self.package()
        (self.bundle / self.jar.name).write_bytes(b'tamper')
        with self.assertRaises(ValueError):
            release.cf_upload(self.bundle, self.tag, '', root=self.root, env=self.env, opener=self.opener)
        self.assertEqual(self.requests, [])

    def test_http_error_redacts_body_and_does_not_retry(self):
        calls = []
        def failure(request, **kwargs):
            calls.append(request)
            raise urllib.error.HTTPError(request.full_url, 401, self.env['CURSEFORGE_API_TOKEN'], {},
                                         io.BytesIO(b'private response'))
        with self.assertRaisesRegex(ValueError, 'HTTP 401') as caught:
            release.cf_metadata(self.env, '', opener=failure)
        self.assertNotIn(self.env['CURSEFORGE_API_TOKEN'], str(caught.exception))
        self.assertNotIn('private response', str(caught.exception))
        self.assertEqual(len(calls), 1)

    def test_transport_failure_redacted(self):
        def failure(*args, **kwargs):
            raise urllib.error.URLError(self.env['CURSEFORGE_API_TOKEN'])
        with self.assertRaises(ValueError) as caught:
            release.cf_metadata(self.env, '', opener=failure)
        self.assertNotIn(self.env['CURSEFORGE_API_TOKEN'], str(caught.exception))

    def test_invalid_ack_is_not_success(self):
        self.package()
        def opener(request, **kwargs):
            if request.get_method() == 'POST':
                return io.BytesIO(b'{"error":"denied"}')
            return self.opener(request, **kwargs)
        with self.assertRaisesRegex(ValueError, 'acknowledge'):
            release.cf_upload(self.bundle, self.tag, '', root=self.root, env=self.env, opener=opener)

    def test_redirects_disabled(self):
        self.assertIsNone(release.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.test'))


class ReleaseNotesTests(unittest.TestCase):
    TEXT = ('# Updates\n\n## 0.23.1 — fix metadata\nprerequisite warning line\n\n'
            '### Sub\nkept\n\n## 0.23.0 — older\nold body\n')

    def test_plain_heading_section_only(self):
        notes = release.release_notes(self.TEXT, '0.23.1')
        self.assertIn('prerequisite warning line', notes)
        self.assertIn('### Sub', notes)
        self.assertNotIn('old body', notes)
        self.assertNotIn('0.23.0', notes)

    def test_bracketed_heading_with_date(self):
        text = '## [0.23.1] - 2026-10-28\nbracketed\n## [0.23.0] - 2026-10-27\nold\n'
        self.assertEqual(release.release_notes(text, '0.23.1'), 'bracketed\n')

    def test_missing_empty_duplicate_and_prefix_collision_fail(self):
        cases = ['## 0.23.10 — later\nbody\n',            # prefix collision must not match
                 '## 0.23.1 — only\n## 0.23.0 — older\n',  # empty section
                 '## 0.23.0 — older\nbody\n',             # missing
                 '## 0.23.1 — a\nx\n## 0.23.1 — b\ny\n']  # duplicated
        for text in cases:
            with self.subTest(text=text[:20]), self.assertRaises(ValueError):
                release.release_notes(text, '0.23.1')

    def test_hash_three_and_top_level_headings_are_not_sections(self):
        text = '# 0.23.1 top\nignore\n### 0.23.1\nignore\n## 0.23.1 — real\nbody\n'
        self.assertEqual(release.release_notes(text, '0.23.1'), 'body\n')


class NotesUploadTests(Fixture):
    def setUp(self):
        super().setUp()
        self.requests = []

    def test_metadata_carries_only_current_section_and_environment_probe(self):
        changelog = ('## 0.23.1 — current\nWARNING manual ApricityUI 1.2.8\n## 0.23.0 — old\nhistory\n')
        report = []
        _, _, metadata = release.cf_metadata(
            self.env, release.release_notes(changelog, '0.23.1'), opener=self.opener, report=report)
        self.assertIn('manual ApricityUI 1.2.8', metadata['changelog'])
        self.assertNotIn('history', metadata['changelog'])
        self.assertEqual(metadata['gameVersionNames'],
                         ['1.21.1', 'NeoForge', 'Client', 'Server'])
        self.assertIn('upload_game_version_names=1.21.1,NeoForge,Client,Server', report)


class BundlePurityTests(Fixture):
    def test_bundle_stays_exactly_three_files_when_side_files_live_elsewhere(self):
        self.package()
        expected = sorted([release.jar_name('0.22.2'), 'SHA256SUMS', 'release-manifest.json'])
        self.assertEqual(sorted(item.name for item in self.bundle.iterdir()), expected)
        # The workflow writes the upload intent and the release notes into $RUNNER_TEMP.
        side = self.root / 'runner-temp'
        side.mkdir()
        (side / 'cf-upload-intent.json').write_text('{"tag": "v0.22.2-neoforge-1.21.1"}', encoding='utf-8')
        (side / 'release-notes.md').write_text('notes\n', encoding='utf-8')
        release.verify(self.bundle, self.root, self.tag)
        self.assertEqual(sorted(item.name for item in self.bundle.iterdir()), expected)

    def test_any_extra_file_in_the_bundle_fails_verification(self):
        self.package()
        for stray in ('cf-upload-intent.json', 'release-notes.md', 'sources.jar'):
            with self.subTest(stray=stray):
                (self.bundle / stray).write_text('{}', encoding='utf-8')
                with self.assertRaisesRegex(ValueError, 'Unexpected release payload'):
                    release.verify(self.bundle, self.root, self.tag)
                (self.bundle / stray).unlink()


class WorkflowTests(unittest.TestCase):
    def test_publication_gate_and_pinned_actions(self):
        text = (release.ROOT / '.github/workflows/build-release.yml').read_text(encoding='utf-8')
        self.assertIn("if: github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref_type == 'tag')", text)
        self.assertIn("tags: ['v*-neoforge-1.21.1']", text)
        self.assertIn(':common:test :neoforge-1.21.1:build', text)
        self.assertIn('tools/check_release.py', text)
        self.assertIn('tools/apply_journal_theme.py --check', text)
        import re
        actions = re.findall(r'uses: (\S+)', text)
        self.assertTrue(actions)
        self.assertTrue(all(re.fullmatch(r'actions/[a-z-]+@[a-f0-9]{40}', action) for action in actions))
        self.assertNotIn('pull_request_target', text)
        self.assertNotIn('sources.jar', text)
        self.assertIn('persist-credentials: false', text)
        self.assertIn('case "$INPUT_TARGET" in both|github) ;; *) exit 1 ;; esac', text)
        self.assertIn('case "$INPUT_RELEASE_TYPE" in alpha|beta|release) ;; *) exit 1 ;; esac', text)

    CF_TARGET_CONDITION = "if: github.event_name != 'workflow_dispatch' || inputs.target == 'both'"

    def test_every_curseforge_step_is_limited_to_cf_targets(self):
        text = (release.ROOT / '.github/workflows/build-release.yml').read_text(encoding='utf-8')
        publish = text.split('\n  publish:', 1)[1]
        steps = ['      - ' + block for block in publish.split('\n      - ')[1:]]
        curseforge = [step for step in steps
                      if 'CURSEFORGE_API_TOKEN' in step or 'cf-upload-intent' in step]
        self.assertEqual(len(curseforge), 5, 'expected preflight, gate, intent record, intent artifact, upload')
        for step in curseforge:
            with self.subTest(step=step.splitlines()[0][:60]):
                self.assertIn(self.CF_TARGET_CONDITION, step)
        # Both manual targets: `both` publishes to CurseForge, `github` must skip every CF step
        # (including the duplicate gate), so the condition has to be on all of them.
        for event, target, expected in (('push', None, True), ('workflow_dispatch', 'both', True),
                                        ('workflow_dispatch', 'github', False)):
            with self.subTest(event=event, target=target):
                runs = not (event == 'workflow_dispatch' and target != 'both')
                self.assertEqual(runs, expected)

    def test_side_files_stay_out_of_the_verified_bundle(self):
        text = (release.ROOT / '.github/workflows/build-release.yml').read_text(encoding='utf-8')
        self.assertNotIn('build/release/cf-upload-intent.json', text)
        self.assertNotIn('build/release/release-notes.md', text)
        self.assertIn('${{ runner.temp }}/cf-upload-intent.json', text)
        self.assertIn('$RUNNER_TEMP/release-notes.md', text)
        self.assertIn('release-notes --tag "$RELEASE_TAG" --output "$RUNNER_TEMP/release-notes.md"', text)


if __name__ == '__main__':
    unittest.main()
