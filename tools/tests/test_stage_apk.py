import pathlib
import sys
import tempfile
import unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from stage_apk import artifact_directory, artifact_name, stage_file, verify_metadata, VerificationError, BASE_ID

class StagingTests(unittest.TestCase):
    def badging(self, version='1.4.1-dev', code=8, app=BASE_ID+'.debug', debug=True):
        return f"package: name='{app}' versionCode='{code}' versionName='{version}'\n" + ('application-debuggable' if debug else '')
    def test_name_uses_supplied_variant_version(self):
        for version in ['1.4.1-dev','1.4.2-dev','1.4.3-beta']:
            self.assertEqual('Pumping-'+version+'.apk',artifact_name(version))
        for version in ['../bad','1.4.1','1.4.1-dev.1','1.4.1-beta.1']:
            with self.assertRaises(VerificationError): artifact_name(version)
    def test_development_directory_uses_version_and_release_path_is_unchanged(self):
        root=pathlib.Path('/synthetic/project')
        for version in ['1.4.1-dev','1.4.2-dev']:
            self.assertEqual(root/'dev-builds'/version,artifact_directory(root/'dev-builds',version,'debug'))
        self.assertEqual(root/'test-builds',artifact_directory(root/'test-builds','1.4.2-beta','release'))
        with self.assertRaises(VerificationError): artifact_directory(root/'dev-builds','../bad','debug')
    def test_embedded_identity_must_match_variant(self):
        verify_metadata(self.badging(),BASE_ID+'.debug','1.4.1-dev',8,'debug')
        for text in [self.badging(version='1.4.0-dev'),self.badging(code=7),self.badging(app=BASE_ID),self.badging(debug=False),self.badging()+'\napplication-testOnly']:
            with self.assertRaises(VerificationError): verify_metadata(text,BASE_ID+'.debug','1.4.1-dev',8,'debug')
    def test_release_is_distinct_and_non_debuggable(self):
        verify_metadata(self.badging('1.4.1-beta',8,BASE_ID,False),BASE_ID,'1.4.1-beta',8,'release')
        with self.assertRaises(VerificationError): verify_metadata(self.badging(),BASE_ID+'.debug','1.4.1-dev',8,'release')
    def test_staging_is_idempotent_and_refuses_collision(self):
        with tempfile.TemporaryDirectory() as d:
            root=pathlib.Path(d); source=root/'app-debug.apk'; source.write_bytes(b'synthetic apk bytes')
            target,digest=stage_file(source,root/'out','1.4.1-dev')
            self.assertEqual('Pumping-1.4.1-dev.apk',target.name)
            self.assertEqual((target,digest),stage_file(source,root/'out','1.4.1-dev'))
            source.write_bytes(b'different synthetic build')
            with self.assertRaises(VerificationError): stage_file(source,root/'out','1.4.1-dev')
            self.assertEqual(b'synthetic apk bytes',target.read_bytes())
            self.assertEqual([],list((root/'out').glob('.staging-*')))
