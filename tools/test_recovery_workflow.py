"""Offline checks for .github/workflows/recovery-publish.yml (no network, no token, no upload).

The recovery workflow holds the single permitted CurseForge POST, so its *shape* is tested too:

  * manual dispatch only, no inputs — the tag, source run, commit and JAR hash must be constants;
  * those constants must equal the ones in `tools/cf_recovery.py` (no drift between code and YAML);
  * the product is downloaded from the pinned run and never rebuilt;
  * order is guards → product pin → intent check → release check → preflight → intent persist → POST;
  * exactly one upload invocation, no retry loop, no `workflow_run`/`schedule`/`push` trigger;
  * the secret is only wired through `secrets.CURSEFORGE_TOKEN` into step environments, never echoed.

Run from this directory: python -m unittest test_recovery_workflow
"""
import re
import unittest
from pathlib import Path

import yaml

import cf_recovery as recovery

WORKFLOW = Path(__file__).resolve().parent.parent / '.github/workflows/recovery-publish.yml'


def load():
    document = yaml.safe_load(WORKFLOW.read_text(encoding='utf-8'))
    return document, document['jobs']['recovery']


class RecoveryWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.document, self.job = load()
        self.steps = self.job['steps']
        self.names = [step.get('name', step.get('uses', '')) for step in self.steps]
        self.text = WORKFLOW.read_text(encoding='utf-8')
        # Comment lines explain the rules; only executable YAML is inspected below.
        self.body = chr(10).join(
            line for line in self.text.splitlines() if not line.lstrip().startswith('#'))

    def test_manual_dispatch_only_without_inputs(self):
        triggers = self.document[True]
        self.assertEqual(list(triggers), ['workflow_dispatch'])
        self.assertIsNone(triggers['workflow_dispatch'])
        self.assertNotIn('push:', self.body)
        self.assertNotIn('schedule:', self.body)
        self.assertNotIn('workflow_run:', self.body)

    def test_permissions_and_environment_are_narrow(self):
        self.assertEqual(self.document['permissions'], {'contents': 'write', 'actions': 'read'})
        self.assertEqual(self.job['environment'], 'release')
        self.assertEqual(self.job['timeout-minutes'], 15)
        self.assertEqual(self.document['concurrency']['group'], 'cf-recovery-v0.23.1')
        self.assertFalse(self.document['concurrency']['cancel-in-progress'])

    def test_yaml_constants_match_the_control_code(self):
        env = self.job['env']
        self.assertEqual(env['RELEASE_TAG'], recovery.TAG)
        self.assertEqual(env['SOURCE_RUN_ID'], recovery.SOURCE_RUN_ID)
        self.assertEqual(env['EXPECTED_COMMIT'], recovery.EXPECTED_COMMIT)
        self.assertEqual(env['EXPECTED_JAR_SHA256'], recovery.EXPECTED_JAR_SHA256)
        self.assertEqual(env['RECOVERY_INTENT_NAME'], recovery.INTENT_NAME)
        self.assertEqual(env['RELEASE_TYPE'], recovery.RELEASE_TYPE)

    def test_product_comes_from_the_pinned_run_and_is_never_rebuilt(self):
        joined = '\n'.join(step.get('run', '') for step in self.steps)
        self.assertIn('gh run download "$SOURCE_RUN_ID"', joined)
        self.assertIn('-n verified-release', joined)
        for forbidden in ('gradlew', 'gradle ', 'python tools/release_pipeline.py package'):
            self.assertNotIn(forbidden, joined, forbidden)

    def test_single_upload_invocation_and_no_retry_loop(self):
        upload_calls = self.text.count('cf_recovery.py upload')
        self.assertEqual(upload_calls, 1)
        for forbidden in ('retry', 'for i in', 'while ', '|| true', 'continue-on-error'):
            self.assertNotIn(forbidden, self.body, forbidden)

    def test_step_order_keeps_guards_before_the_post(self):
        def index(fragment):
            for position, name in enumerate(self.names):
                if fragment in name:
                    return position
            raise AssertionError('missing step: ' + fragment)

        identity = index('Guard the release identity')
        download = index('Download the previously verified product artifact')
        verify = index('Verify the product pin')
        intent = index('Refuse a second recovery attempt')
        release = index('Refuse a duplicate GitHub release')
        preflight = index('CurseForge catalog and configuration preflight')
        record = index('Record the recovery intent')
        persist = index('Persist the recovery intent')
        upload = index('Upload the verified JAR to CurseForge exactly once')
        gh_release = index('Create the GitHub release')
        self.assertLess(identity, download)
        self.assertLess(download, verify)
        self.assertLess(verify, intent)
        self.assertLess(intent, release)
        self.assertLess(release, preflight)
        self.assertLess(preflight, record)
        self.assertLess(record, persist)
        self.assertLess(persist, upload)
        self.assertLess(upload, gh_release)

    def test_intent_artifact_is_persisted_before_the_post(self):
        persist = next(step for step in self.steps if 'Persist the recovery intent' in step.get('name', ''))
        self.assertEqual(persist['uses'], 'actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02')
        self.assertEqual(persist['with']['name'], '${{ env.RECOVERY_INTENT_NAME }}')
        self.assertEqual(persist['with']['retention-days'], 90)
        self.assertEqual(persist['with']['if-no-files-found'], 'error')

    def test_token_is_only_wired_from_secrets_into_step_environments(self):
        self.assertIn('CURSEFORGE_API_TOKEN: ${{ secrets.CURSEFORGE_TOKEN }}', self.text)
        self.assertNotRegex(self.text, r'\$\{\{\s*secrets\.CURSEFORGE_TOKEN\s*\}\}.*echo')
        self.assertNotRegex(self.text, r'echo.*CURSEFORGE_API_TOKEN')
        self.assertNotIn('printenv', self.text)
        # The token is never passed as a command argument.
        self.assertNotRegex(self.text, r'--token\s')
        # All secrets appear only inside step-level env blocks.
        for line in self.text.splitlines():
            if 'secrets.CURSEFORGE_TOKEN' in line:
                self.assertRegex(line.strip(), r'^CURSEFORGE_API_TOKEN: \$\{\{ secrets\.CURSEFORGE_TOKEN \}\}$')

    def test_tag_is_never_moved_and_no_git_write_happens(self):
        for forbidden in ('git tag', 'git push', 'git commit', 'force', 'gh release delete'):
            self.assertNotIn(forbidden, self.text, forbidden)

    def test_checkout_does_not_persist_credentials(self):
        checkout = next(step for step in self.steps if str(step.get('uses', '')).startswith('actions/checkout'))
        self.assertEqual(checkout['with']['persist-credentials'], False)


if __name__ == '__main__':
    unittest.main()
