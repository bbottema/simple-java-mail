"""Source-download checks. No build, network, AWS account or generated fixture key is needed."""
import io
from pathlib import Path, PurePosixPath
import unittest
import zipfile

from package_examples import archive, canonical_bytes, is_source_path


class SourceDownloadTest(unittest.TestCase):
    def test_rabbit_readiness_requires_an_amqp_listener_not_only_a_live_erlang_node(self):
        source = Path(__file__).resolve().parent
        for pattern in ("event-communications", "custom-handoff"):
            compose = (source / pattern / "rabbitmq.yaml").read_text()
            with self.subTest(pattern=pattern):
                self.assertIn("check_port_listener 5672", compose)
                self.assertIn("check_port_connectivity", compose)
                self.assertNotIn('"ping"', compose)

    def test_docker_context_keeps_application_tests_but_excludes_local_secrets_and_state(self):
        source = Path(__file__).resolve().parent
        for name in ("Dockerfile.dockerignore", "applications.Dockerfile.dockerignore"):
            rules = (source / name).read_text().splitlines()
            with self.subTest(name=name):
                self.assertNotIn("**/src/test/java", rules)
                for excluded in ("**/.local", "**/.terraform", "**/.env*", "**/*.p12", "**/*.pfx", "**/*.jks",
                                 "**/*.pem", "**/*.crt", "**/*.key", "**/*.tfstate", "**/*.tfstate.*",
                                 "**/*.tfvars", "**/*.tfvars.json", "**/*.tfplan"):
                    self.assertIn(excluded, rules)

    def test_generated_and_sensitive_material_is_not_source(self):
        for name in ("app/target/application.jar", "cloud/.terraform/provider", "cloud/terraform.tfstate", "cloud/terraform.tfstate.backup",
                     "cloud/terraform.tfstate.1234", "cloud/inputs.auto.tfvars.json", "cloud/reviewed.tfplan", "partner.p12", "server.pfx",
                     "server.jks", "server.pem", "server.crt", ".env", "__pycache__/code.pyc"):
            with self.subTest(name=name):
                self.assertFalse(is_source_path(PurePosixPath(name)))
        for name in ("applications.Dockerfile", "cloud/cloud.py", "cloud/terraform/main.tf", "fixture/relay.py", "README.md", ".gitignore"):
            self.assertTrue(is_source_path(PurePosixPath(name)))

    def test_archive_includes_original_helpers_and_both_cloud_formats(self):
        source = Path(__file__).resolve().parent
        with zipfile.ZipFile(io.BytesIO(archive(source))) as bundle:
            self.assertIn("tools/integration-patterns/aws-correspondence/cloud/cloudformation/worker.yaml", bundle.namelist())
            self.assertIn("tools/integration-patterns/aws-correspondence/cloud/terraform/worker/main.tf", bundle.namelist())
            for study, helper in (("relaydesk", "RelayDeskMailExamples.java"), ("polar-meridian", "PolarMeridianDispatcher.java")):
                name = f"simplejavamail.org/src/assets/journal/articles/{study}/examples/{helper}"
                self.assertEqual(canonical_bytes(source.parents[1] / name), bundle.read(name))
            for item in bundle.infolist():
                self.assertEqual((2026, 10, 16, 0, 0, 0), item.date_time)
                self.assertNotIn(b"\r\n", bundle.read(item.filename))


if __name__ == "__main__":
    unittest.main()
