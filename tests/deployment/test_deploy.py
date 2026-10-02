"""Configuration safety checks; no cloud credentials or Docker engine are needed."""

import argparse
import importlib.util
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("deploy", ROOT / "scripts/deploy.py")
deploy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(deploy)
DOCKER = shutil.which("docker")


class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.env = Path(self.directory.name) / ".env.production"
        self.patch = patch.object(deploy, "ENV_FILE", self.env)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        with patch.object(deploy, "capture", return_value="0123456789ab"):
            deploy.init(argparse.Namespace(domain="labflow.example.org", email="owner@example.org"))

    def test_generation_is_private_unique_and_never_overwrites(self):
        values = deploy.env_values()
        self.assertEqual(self.env.stat().st_mode & 0o777, 0o600)
        self.assertEqual(len({values[name] for name in deploy.SECRET_NAMES}), 4)
        original = self.env.read_bytes()
        with patch.object(deploy, "capture", return_value="0123456789ab"):
            with self.assertRaises(FileExistsError):
                deploy.init(argparse.Namespace(domain="labflow.example.org", email="owner@example.org"))
        self.assertEqual(self.env.read_bytes(), original)

    def test_preflight_rejects_placeholders_and_permissive_permissions(self):
        values = deploy.env_values()
        values["AUTH_JWT_SECRET"] = "replace-with-random-secret"
        deploy.write_env(values)
        with self.assertRaisesRegex(ValueError, "AUTH_JWT_SECRET"):
            deploy.check()
        os.chmod(self.env, 0o644)
        with self.assertRaisesRegex(ValueError, "permissions"):
            deploy.check()

    @unittest.skipUnless(DOCKER, "Docker Compose CLI is not installed")
    def test_real_compose_config_exposes_only_gateway_and_catches_shell_overrides(self):
        command = [DOCKER, "compose", "--project-name", "labflow-prod", "--env-file", str(self.env),
                   "--file", str(ROOT / "docker-compose.prod.yml")]
        with patch.object(deploy, "COMPOSE", command), patch.dict(os.environ, {}, clear=True):
            deploy.check()
            with patch.dict(os.environ, {"AUTH_JWT_SECRET": "wrong-shell-secret"}):
                with self.assertRaisesRegex(ValueError, "Shell environment overrides"):
                    deploy.check()


if __name__ == "__main__":
    unittest.main()
