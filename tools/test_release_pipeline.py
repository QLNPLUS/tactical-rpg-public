"""Offline release-contract tests: temporary fixtures and mocked official upload API."""
import io
import json
from pathlib import Path
import re
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
        if request.full_url.endswith('/game/version-types'):
            # CurseForge splits version names across types: the game version lives
            # under a "Minecraft <series>" type and the same name repeats elsewhere.
            data = [{'id': 77784, 'name': 'Minecraft 1.21'}, {'id': 615, 'name': 'Addons'},
                    {'id': 68441, 'name': 'Modloader'}, {'id': 75208, 'name': 'Environment'}]
        elif request.get_method() == 'POST':
            data = {'id': 999}
        else:
            data = [{'id': 11779, 'name': '1.21.1', 'gameVersionTypeID': 77784},
                    {'id': 16115, 'name': '1.21.1', 'gameVersionTypeID': 615},
                    {'id': 12735, 'name': '1.21.1', 'gameVersionTypeID': 1},
                    {'id': 10150, 'name': 'NeoForge', 'gameVersionTypeID': 68441},
                    {'id': 9638, 'name': 'Client', 'gameVersionTypeID': 75208},
                    {'id': 9639, 'name': 'Server', 'gameVersionTypeID': 75208}]
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
                           ('CURSEFORGE_DUNGEONS_PERSPECTIVE_SLUG', 'https://fake'),
                           ('CURSEFORGE_DUNGEONS_PERSPECTIVE_PROJECT_ID', '123')]:
            env = dict(self.env, **{key: value})
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                release.cf_config(env)

    def test_invalid_token_header_is_redacted_before_network(self):
        env = dict(self.env, CURSEFORGE_API_TOKEN='mock-private\r\ninvalid')
        with self.assertRaisesRegex(ValueError, 'CURSEFORGE_API_TOKEN') as caught:
            release.cf_metadata(env, '', opener=self.opener)
        self.assertNotIn('mock-private', str(caught.exception))
        self.assertEqual(self.requests, [])

    def test_preflight_is_get_only_and_relations_are_explicit(self):
        project, token, metadata = release.cf_metadata(self.env, 'notes', opener=self.opener)
        self.assertEqual(project, '123')
        self.assertEqual(metadata['gameVersions'], [11779, 10150, 9638, 9639])
        # The client UI framework is no longer a relation; the optional camera mod is the only one.
        self.assertEqual([p['type'] for p in metadata['relations']['projects']], ['optionalDependency'])
        # CurseForge validates this field as an integer even though its docs show a string.
        self.assertEqual([p['projectID'] for p in metadata['relations']['projects']], [789])
        self.assertTrue(all(type(p['projectID']) is int for p in metadata['relations']['projects']))
        self.assertEqual(len(self.requests), 2)
        self.assertEqual([r.get_method() for r in self.requests], ['GET', 'GET'])
        for request in self.requests:
            self.assertNotIn(token, request.full_url)

    TYPES = [{'id': 77784, 'name': 'Minecraft 1.21'}, {'id': 615, 'name': 'Addons'},
             {'id': 68441, 'name': 'Modloader'}, {'id': 75208, 'name': 'Environment'}]
    ENTRIES = [{'id': 11779, 'name': '1.21.1', 'gameVersionTypeID': 77784},
               {'id': 10150, 'name': 'NeoForge', 'gameVersionTypeID': 68441},
               {'id': 9638, 'name': 'Client', 'gameVersionTypeID': 75208},
               {'id': 9639, 'name': 'Server', 'gameVersionTypeID': 75208}]

    def test_same_name_under_another_version_type_is_ignored(self):
        # 1.21.1 repeats under "Addons"; only the Minecraft 1.21 id may be selected.
        versions = [{'id': 16115, 'name': '1.21.1', 'gameVersionTypeID': 615}] + self.ENTRIES
        self.assertEqual(release.resolve_game_versions(versions, self.TYPES), [11779, 10150, 9638, 9639])

    def test_game_loader_and_environment_groups_are_required(self):
        for missing, marker in [({77784}, 'no Minecraft version type'),
                                ({68441}, 'no Modloader version type'),
                                ({75208}, 'no Environment version type')]:
            types = [t for t in self.TYPES if t['id'] not in missing]
            with self.subTest(marker=marker), self.assertRaisesRegex(ValueError, marker):
                release.resolve_game_versions(self.ENTRIES, types)

    def test_missing_or_ambiguous_versions_fail(self):
        cases = [[], self.ENTRIES[:3], self.ENTRIES[:2] + [self.ENTRIES[3]],
                 [self.ENTRIES[0], self.ENTRIES[1], self.ENTRIES[2], self.ENTRIES[2], self.ENTRIES[3]],
                 [{'id': 11779, 'name': '1.21.1', 'gameVersionTypeID': 77784},
                  {'id': 11780, 'name': '1.21.1', 'gameVersionTypeID': 77784}] + self.ENTRIES[1:]]
        for versions in cases:
            with self.subTest(versions=versions), self.assertRaises(ValueError):
                release.resolve_game_versions(versions, self.TYPES)
        with self.assertRaises(ValueError):
            release.cf_metadata(self.env, '', opener=lambda *a, **kw: io.BytesIO(json.dumps([]).encode()))

    def test_invalid_release_type_fails_before_get(self):
        with self.assertRaisesRegex(ValueError, 'release type'):
            release.cf_metadata(self.env, '', 'unknown', self.opener)
        self.assertEqual(self.requests, [])

    def test_mock_upload_uses_same_verified_jar_and_official_contract(self):
        self.package()
        file_id = release.cf_upload(self.bundle, self.tag, 'notes', root=self.root,
                                    env=self.env, opener=self.opener)
        self.assertEqual(file_id, 999)
        self.assertEqual([r.get_method() for r in self.requests], ['GET', 'GET', 'POST'])
        post = self.requests[2]
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

    def test_http_error_admits_only_the_api_message(self):
        token = self.env['CURSEFORGE_API_TOKEN']
        body = json.dumps({'errorCode': 1002,
                           'errorMessage': 'Error in field `metadata`: Invalid type.',
                           'headerToken': token}).encode()
        def failure(request, **kwargs):
            raise urllib.error.HTTPError(request.full_url, 400, 'Bad Request', {}, io.BytesIO(body))
        with self.assertRaisesRegex(ValueError, 'HTTP 400') as caught:
            release.cf_metadata(self.env, '', opener=failure)
        message = str(caught.exception)
        self.assertIn('Invalid type', message)
        self.assertNotIn(token, message)
        self.assertNotIn('headerToken', message)

    def test_error_detail_message_is_redacted_and_bounded(self):
        token = 'secret-token-value'
        body = json.dumps({'errorMessage': 'bad ' + token + ' ' + 'y' * 900}).encode()
        error = urllib.error.HTTPError('https://curseforge.invalid', 400, 'Bad', {}, io.BytesIO(body))
        detail = release.error_detail(error, (token,))
        self.assertIn('[redacted]', detail)
        self.assertNotIn(token, detail)
        self.assertLessEqual(len(detail), 516)

    def test_error_detail_ignores_non_json_or_oversized_bodies(self):
        for body in (b'plain html rejection', b'{"errorMessage": 42}', b'x' * 20000, b'[]'):
            error = urllib.error.HTTPError('https://curseforge.invalid', 400, 'Bad', {}, io.BytesIO(body))
            self.assertEqual(release.error_detail(error), '')

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


class ChangelogTests(unittest.TestCase):
    def test_only_the_current_version_section_is_sent(self):
        text = '# log\n\n## 0.23.1 — now\n\nline a\n\n## 0.23.0 — old\n\nline b\n'
        self.assertEqual(release.release_changelog(text, '0.23.1'), '## 0.23.1 — now\n\nline a')

    def test_oversized_or_missing_section_is_clipped(self):
        text = 'x' * 9000
        clipped = release.release_changelog(text, '9.9.9')
        self.assertTrue(clipped.startswith('x' * 8000))
        self.assertIn('truncated for upload', clipped)
        self.assertLess(len(clipped), 8100)


class ReleaseNotesTests(unittest.TestCase):
    def test_writes_only_the_current_section_and_refuses_a_missing_version(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            changelog = root / 'CHANGELOG.md'
            changelog.write_text('# log\n\n## 0.24.0 — now\n\nline a\n\n## 0.23.1 — old\n\nline b\n',
                                 encoding='utf-8')
            output = root / 'notes.md'
            release.release_notes(changelog, '0.24.0', output)
            self.assertEqual(output.read_text(encoding='utf-8'), '## 0.24.0 — now\n\nline a\n')
            missing = root / 'missing.md'
            with self.assertRaisesRegex(ValueError, 'no section'):
                release.release_notes(changelog, '9.9.9', missing)
            self.assertFalse(missing.exists())
            changelog.write_text('# log\n\n## 0.24.01 — not this release\n\nwrong notes\n', encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'no section'):
                release.release_notes(changelog, '0.24.0', missing)
            self.assertFalse(missing.exists())


class RepositoryChangelogTests(unittest.TestCase):
    """The real release notes both services receive must be this version only and English."""

    def test_current_version_section_is_english_and_has_no_history(self):
        version, _ = release.validate()
        text = (release.ROOT / 'CHANGELOG.md').read_text(encoding='utf-8')
        notes = release.release_changelog(text, version)
        self.assertTrue(notes.startswith(f'## {version}'), notes[:80])
        self.assertGreater(len(notes), 500)
        self.assertIsNone(re.search(r'[\u3000-\u303f\u3400-\u9fff\uff00-\uffef]', notes),
                          'the current release notes must not contain CJK text')
        self.assertNotIn('## 0.23.1', notes)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'notes.md'
            self.assertEqual(release.release_notes(release.ROOT / 'CHANGELOG.md', version, output),
                             notes)
            self.assertEqual(output.read_text(encoding='utf-8'), notes + '\n')


class WorkflowTests(unittest.TestCase):
    def test_publication_gate_and_pinned_actions(self):
        text = (release.ROOT / '.github/workflows/build-release.yml').read_text(encoding='utf-8')
        self.assertIn("if: github.event_name == 'workflow_dispatch' || (github.event_name == 'push' && github.ref_type == 'tag')", text)
        self.assertIn("tags: ['v*-neoforge-1.21.1']", text)
        self.assertIn(':common:test :neoforge-1.21.1:build', text)
        self.assertIn('tools/check_release.py', text)
        # The retired AUI theme script no longer exists in tools/; a CI step calling it can only fail.
        self.assertNotIn('apply_journal_theme', text)
        self.assertIn('tools/release_pipeline.py release-notes', text)
        self.assertIn('--notes-file "$RUNNER_TEMP/release-notes.md"', text)
        self.assertNotIn('--notes-file CHANGELOG.md', text)
        actions = re.findall(r'uses: (\S+)', text)
        self.assertTrue(actions)
        self.assertTrue(all(re.fullmatch(r'actions/[a-z-]+@[a-f0-9]{40}', action) for action in actions))
        self.assertNotIn('pull_request_target', text)
        self.assertNotIn('sources.jar', text)
        self.assertIn('persist-credentials: false', text)
        self.assertIn('case "$INPUT_TARGET" in both|github) ;; *) exit 1 ;; esac', text)
        self.assertIn('case "$INPUT_RELEASE_TYPE" in alpha|beta|release) ;; *) exit 1 ;; esac', text)


if __name__ == '__main__':
    unittest.main()
