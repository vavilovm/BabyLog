import importlib.util
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("prepare-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseVersionsTest(unittest.TestCase):
    def test_advances_latest_tag_even_when_source_version_is_older(self):
        self.assertEqual((0, 11, 4), release.next_version(["v0.11.2", "v0.11.3"], (0, 11, 2)))

    def test_ignores_prerelease_and_unrelated_tags(self):
        self.assertEqual((0, 11, 3), release.next_version(["v0.12.0-beta", "backup"], (0, 11, 2)))

    def test_honors_new_major_minor_baseline(self):
        self.assertEqual((1, 0, 1), release.next_version(["v0.11.3"], (1, 0, 0)))

    def test_android_codes_increase_across_patch_minor_and_major(self):
        versions = [(0, 11, 2), (0, 11, 3), (0, 12, 0), (1, 0, 0)]
        codes = list(map(release.version_code, versions))
        self.assertEqual(sorted(set(codes)), codes)
        self.assertGreater(codes[1], 42)

    def test_rejects_ambiguous_or_out_of_range_versions(self):
        with self.assertRaises(ValueError): release.parse_version("v0.11.3-beta")
        with self.assertRaises(ValueError): release.version_code((0, 11, 1000))

class ReleaseReservationTest(unittest.TestCase):
    def prepare(self, refs, responses, event="push", tag=""):
        import json
        import os
        import tempfile
        from unittest.mock import patch
        from types import SimpleNamespace

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            env = {"GITHUB_EVENT_NAME": event, "GITHUB_REF_NAME": tag,
                   "GITHUB_REPOSITORY": "owner/repo", "GITHUB_SHA": "commit",
                   "GITHUB_OUTPUT": str(output)}
            with patch.dict(os.environ, env), patch.object(release.Path, "read_text", return_value='versionName = "0.11.2"'), \
                 patch.object(release.subprocess, "check_output", side_effect=[json.dumps(r) for r in refs]), \
                 patch.object(release.subprocess, "run", side_effect=[SimpleNamespace(returncode=c, stdout="", stderr=e) for c,e in responses]) as run:
                release.prepare()
            return output.read_text(), run.call_count

    def test_rerun_reuses_tag_reserved_for_same_commit(self):
        output, calls = self.prepare([[{"ref":"refs/tags/v0.11.3", "object":{"sha":"commit"}}]], [])
        self.assertIn("tag=v0.11.3", output)
        self.assertEqual(0, calls)

    def test_concurrent_reservation_retries_with_the_next_tag(self):
        output, calls = self.prepare([[], [{"ref":"refs/tags/v0.11.3", "object":{"sha":"other"}}]],
                                     [(1, "Reference already exists"), (0, "")])
        self.assertIn("tag=v0.11.4", output)
        self.assertEqual(2, calls)

    def test_manual_release_keeps_its_version(self):
        output, calls = self.prepare([], [], event="release", tag="v0.11.5")
        self.assertIn("code=11005", output)
        self.assertEqual(0, calls)


if __name__ == "__main__":
    unittest.main()
