#!/usr/bin/env python3
"""Offline unit checks for archive/source provenance rejection; no download or native execution."""
import copy
import importlib.util
import io
from pathlib import Path
import unittest
from unittest.mock import patch
spec = importlib.util.spec_from_file_location('reuse', Path(__file__).with_name('materialize-native-libraries.py'))
reuse = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reuse)

class ReuseTests(unittest.TestCase):
    def metadata(self):
        return {'id': reuse.ARTIFACT_ID, 'expired': False, 'size_in_bytes': 211809425,
                'digest': 'sha256:' + reuse.ZIP_SHA,
                'workflow_run': {'id': reuse.RUN_ID, 'head_sha': reuse.SOURCE, 'repository_id': 1393547812}}
    def test_expected_metadata_passes(self):
        reuse.verify_metadata(self.metadata())
    def test_every_identity_field_is_required(self):
        for key, value in [('id', 0), ('expired', True), ('size_in_bytes', 1), ('digest', 'sha256:bad')]:
            with self.subTest(key=key):
                data = self.metadata(); data[key] = value
                with self.assertRaises(AssertionError): reuse.verify_metadata(data)
        for key, value in [('id', 0), ('head_sha', 'bad'), ('repository_id', 0)]:
            with self.subTest(key=key):
                data = self.metadata(); data['workflow_run'][key] = value
                with self.assertRaises(AssertionError): reuse.verify_metadata(data)
    def test_equal_native_boundaries_allow_reuse(self):
        def identity(ref):
            path = ref.split(':', 1)[1]
            return reuse.NATIVE_TREE if path == 'native' else path
        with patch.object(reuse, 'git', side_effect=identity): self.assertTrue(reuse.identities_match())
    def test_each_changed_boundary_rejects_reuse(self):
        for changed in reuse.BOUNDARIES:
            def identity(ref):
                path = ref.split(':', 1)[1]
                if ref.startswith('HEAD:') and path == changed: return 'different'
                return reuse.NATIVE_TREE if path == 'native' else path
            with self.subTest(path=changed), patch.object(reuse, 'git', side_effect=identity):
                self.assertFalse(reuse.identities_match())
    def test_missing_source_requires_fresh_build(self):
        with patch.object(reuse, 'git', side_effect=reuse.subprocess.CalledProcessError(128, 'git')):
            self.assertFalse(reuse.identities_match())
    def test_stream_digest_is_exact(self):
        self.assertEqual(reuse.digest(io.BytesIO(b'abc')), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')

if __name__ == '__main__': unittest.main()
