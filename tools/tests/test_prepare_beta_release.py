import pathlib
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import prepare_beta_release as release


class ReleaseVerificationTests(unittest.TestCase):
    def badging(self):
        return (f"package: name='{release.APP_ID}' versionCode='7' versionName='{release.VERSION_NAME}' compileSdkVersion='37'\n"
                "minSdkVersion:'26'\ntargetSdkVersion:'36'\n")
    def manifest(self):
        return '\n'.join(release.COMPONENTS)
    def test_expected_identity_and_components(self):
        self.assertFalse(release.verify_identity(self.badging(), self.manifest())['debuggable'])
    def test_debug_and_test_only_are_rejected(self):
        for flag in ['application-debuggable', 'application-testOnly']:
            with self.subTest(flag=flag), self.assertRaises(release.VerificationError):
                release.verify_identity(self.badging() + flag, self.manifest())
        with self.assertRaises(release.VerificationError):
            release.verify_identity(self.badging(), self.manifest() + '\nandroid:testOnly(0x01010272)=(type 0x12)0xffffffff')
    def test_old_development_id_or_version_is_rejected(self):
        for text in [self.badging().replace(release.APP_ID, 'app.pumping'), self.badging().replace("versionCode='7'", "versionCode='6'"), self.badging().replace(release.VERSION_NAME, '1.4.0')]:
            with self.subTest(text=text), self.assertRaises(release.VerificationError):
                release.verify_identity(text, self.manifest())
    def test_incompatible_sdk_or_component_is_rejected(self):
        for text, manifest in [(self.badging().replace("minSdkVersion:'26'", "minSdkVersion:'28'"), self.manifest()), (self.badging()+"maxSdkVersion:'36'", self.manifest()), (self.badging(), '')]:
            with self.subTest(text=text), self.assertRaises(release.VerificationError):
                release.verify_identity(text, manifest)
    def test_certificate_must_match_recorded_permanent_fingerprint(self):
        digest='ab'*32
        self.assertEqual(digest, release.verify_certificate('Signer #1 certificate SHA-256 digest: '+digest, digest.upper()))
        with self.assertRaises(release.VerificationError):
            release.verify_certificate('Signer #1 certificate SHA-256 digest: '+digest, 'cd'*32)
    def test_debug_certificate_or_invalid_digest_rejected(self):
        with self.assertRaises(release.VerificationError):
            release.verify_certificate('Signer #1 certificate DN: CN=Android Debug\nSigner #1 certificate SHA-256 digest: '+'ab'*32, 'ab'*32)
        with self.assertRaises(release.VerificationError):
            release.fingerprint('not-a-fingerprint')
    def test_backups_and_test_code_cannot_be_packaged(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk=pathlib.Path(tmp)/'synthetic-test.zip'
            for name, content in [('assets/synthetic_fixture.pumping', b'synthetic'), ('classes.dex', b'Lapp/pumping/SyntheticBackupProvider;'), ('classes.dex', b'Landroidx/compose/ui/test/junit4/Test;')]:
                with zipfile.ZipFile(apk, 'w') as archive:
                    archive.writestr(name, content)
                with self.subTest(name=name), self.assertRaises(release.VerificationError):
                    release.verify_contents(apk)
    def test_production_only_contents_allowed(self):
        with tempfile.TemporaryDirectory() as tmp:
            apk=pathlib.Path(tmp)/'synthetic-test.zip'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', b'Lapp/pumping/MainActivity;')
                archive.writestr('res/drawable/ic_bottle.xml', b'original vector')
            release.verify_contents(apk)


if __name__ == '__main__':
    unittest.main()
