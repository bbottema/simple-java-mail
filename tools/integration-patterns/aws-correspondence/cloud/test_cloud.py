"""Deployment guard regressions. These tests never contact AWS or Docker."""
import unittest
from types import SimpleNamespace
from unittest.mock import Mock

from botocore.exceptions import ClientError

from cloud import PROJECT, CloudRun, derive_ses_smtp_password, require_additive_plan, require_owned_deletion


RUN = "sjm-pattern-example"
ACCOUNT = "123456789012"
BUCKET = RUN + "-" + ACCOUNT


def ownership_tags(run=RUN, project=PROJECT):
    return [{"Key": "Run", "Value": run}, {"Key": "Project", "Value": project}]


def recorded_run(format="terraform"):
    run = CloudRun.__new__(CloudRun)
    run.args = SimpleNamespace(run=RUN, account=ACCOUNT, format=format)
    inventory = ({"aws_s3_bucket.documents": BUCKET, "aws_ecr_repository.worker": RUN} if format == "terraform"
                 else {"Documents": BUCKET, "Repository": RUN})
    run.manifest = {"foundation": "CREATED", "resources": {"foundation": inventory}, "stacks": {"foundation": "owned-stack-id"}}
    run.client = Mock()
    run.persist = Mock()
    return run


def owned_stack():
    return {"StackId": "owned-stack-id", "StackName": RUN + "-foundation", "Tags": ownership_tags(), "StackStatus": "CREATE_COMPLETE"}


def change(actions, identifier="owned-id", address="aws_s3_bucket.documents", mode="managed"):
    return {"mode": mode, "address": address, "change": {"actions": actions, "before": {"id": identifier}}}


class DeploymentPlanTest(unittest.TestCase):
    def test_smtp_password_matches_aws_documented_algorithm_for_its_public_example_key(self):
        # Public documentation placeholder, not a credential. Locks down the fixed HMAC inputs during readability refactors.
        secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        self.assertEqual("BF2PynzbSCAjX08zhZZnP/kW+T9P5zs/1Er0pi5vTEmd", derive_ses_smtp_password(secret, "us-west-2"))
        self.assertNotEqual(derive_ses_smtp_password(secret, "us-west-2"), derive_ses_smtp_password(secret, "eu-central-1"))

    def test_only_additions_and_noops_can_be_applied(self):
        self.assertEqual(1, require_additive_plan({"resource_changes": [change(["create"]), change(["no-op"]), change(["read"], mode="data")]}))
        for actions in (["update"], ["delete"], ["delete", "create"], ["create", "delete"]):
            with self.subTest(actions=actions), self.assertRaises(ValueError):
                require_additive_plan({"resource_changes": [change(actions)]})

    def test_deletion_requires_both_the_recorded_address_and_id(self):
        inventory = {"aws_s3_bucket.documents": "owned-id"}
        require_owned_deletion({"resource_changes": [change(["delete"]), change(["read"], mode="data")]}, inventory)
        for resource in (change(["delete"], "foreign-id"), change(["delete"], address="aws_s3_bucket.foreign"), change(["update"])):
            with self.subTest(resource=resource), self.assertRaises(ValueError):
                require_owned_deletion({"resource_changes": [resource]}, inventory)

    def test_empty_and_already_removed_states_are_harmless(self):
        require_owned_deletion({}, {})
        require_owned_deletion({"resource_changes": [change(["no-op"])]}, {})
        self.assertEqual(0, require_additive_plan({}))

    def test_ecr_revisits_children_after_removing_the_oci_index(self):
        registry = Mock()
        registry.describe_repositories.return_value = {"repositories": [{"repositoryArn": "owned-repository"}]}
        registry.list_tags_for_resource.return_value = {"tags": ownership_tags()}
        registry.get_paginator.return_value.paginate.side_effect = [
            [{"imageIds": [{"imageDigest": "index"}, {"imageDigest": "child"}]}],
            [{"imageIds": [{"imageDigest": "child"}]}], [{"imageIds": []}],
        ]
        registry.batch_delete_image.side_effect = [{"failures": [{"failureCode": "ImageReferencedByManifestList"}]}, {}]
        run = recorded_run()
        run.client.return_value = registry
        run.empty_owned_repository()
        self.assertEqual(2, registry.batch_delete_image.call_count)

    def test_ecr_never_purges_a_repository_without_this_runs_tag(self):
        registry = Mock()
        registry.describe_repositories.return_value = {"repositories": [{"repositoryArn": "foreign-repository"}]}
        registry.list_tags_for_resource.return_value = {"tags": [{"Key": "Run", "Value": "another-run"}]}
        run = recorded_run()
        run.client.return_value = registry
        with self.assertRaises(ValueError):
            run.empty_owned_repository()
        registry.batch_delete_image.assert_not_called()

    def test_matching_names_and_tags_do_not_authorize_unrecorded_data_purges(self):
        for format in ("terraform", "cloudformation"):
            with self.subTest(format=format):
                run = recorded_run(format)
                run.manifest["resources"]["foundation"] = {}
                # Even a perfectly tagged older deployment is not ours when creation never recorded these resources.
                resource = run.client.return_value
                resource.get_bucket_tagging.return_value = {"TagSet": ownership_tags()}
                resource.list_tags_for_resource.return_value = {"tags": ownership_tags()}
                run.empty_owned_bucket()
                run.empty_owned_repository()
                run.client.assert_not_called()

    def test_both_formats_purge_only_recorded_and_tagged_targets(self):
        for format in ("terraform", "cloudformation"):
            with self.subTest(format=format):
                run = recorded_run(format)
                objects, registry = Mock(), Mock()
                objects.get_bucket_tagging.return_value = {"TagSet": ownership_tags()}
                objects.get_paginator.return_value.paginate.return_value = [{"Contents": [{"Key": "archive/owned.eml"}]}]
                objects.delete_objects.return_value = {}
                registry.describe_repositories.return_value = {"repositories": [{"repositoryArn": "owned-repository"}]}
                registry.list_tags_for_resource.return_value = {"tags": ownership_tags()}
                registry.get_paginator.return_value.paginate.return_value = [{"imageIds": []}]
                run.client.side_effect = {"s3": objects, "ecr": registry}.__getitem__
                run.empty_owned_bucket()
                run.empty_owned_repository()
                objects.delete_objects.assert_called_once_with(Bucket=BUCKET, Delete={"Objects": [{"Key": "archive/owned.eml"}]})
                registry.get_paginator.return_value.paginate.assert_called_once_with(repositoryName=RUN)

    def test_wrong_recorded_identity_stops_before_aws_access(self):
        for format, bucket_key, repository_key in (("terraform", "aws_s3_bucket.documents", "aws_ecr_repository.worker"),
                                                    ("cloudformation", "Documents", "Repository")):
            for key in (bucket_key, repository_key):
                with self.subTest(format=format, key=key):
                    run = recorded_run(format)
                    run.manifest["resources"]["foundation"][key] = "some-other-resource"
                    purge = run.empty_owned_bucket if key == bucket_key else run.empty_owned_repository
                    with self.assertRaisesRegex(ValueError, "recorded resource differs"):
                        purge()
                    run.client.assert_not_called()

    def test_both_ownership_tags_are_required_before_purging_data(self):
        for tags in ([], ownership_tags(project="another-project"), ownership_tags(run="another-run"), ownership_tags()[:1]):
            for service in ("s3", "ecr"):
                with self.subTest(tags=tags, service=service):
                    run = recorded_run()
                    resource = run.client.return_value
                    resource.get_bucket_tagging.return_value = {"TagSet": tags}
                    resource.describe_repositories.return_value = {"repositories": [{"repositoryArn": "owned-repository"}]}
                    resource.list_tags_for_resource.return_value = {"tags": tags}
                    purge = run.empty_owned_bucket if service == "s3" else run.empty_owned_repository
                    with self.assertRaisesRegex(ValueError, "ownership could not be verified"):
                        purge()
                    resource.delete_objects.assert_not_called()
                    resource.batch_delete_image.assert_not_called()

    def test_destroy_validates_both_data_targets_before_any_destructive_action(self):
        run = recorded_run()
        run.owned_bucket = Mock(return_value=BUCKET)
        run.owned_repository = Mock(side_effect=ValueError("Repository ownership could not be verified"))
        mutations = ("destroy_terraform", "delete_stack", "remove_smtp_key", "empty_owned_bucket", "empty_owned_repository")
        for method in mutations:
            setattr(run, method, Mock())
        with self.assertRaisesRegex(ValueError, "Repository ownership"):
            run.destroy()
        run.owned_bucket.assert_called_once_with()
        for method in mutations:
            getattr(run, method).assert_not_called()

    def test_missing_inventory_is_recovered_only_from_the_owned_state_or_stack(self):
        for format in ("terraform", "cloudformation"):
            with self.subTest(format=format):
                run = recorded_run(format)
                del run.manifest["resources"]["foundation"]
                run.record_terraform_inventory = Mock()
                run.record_cloudformation_inventory = Mock()
                run.prepare_cleanup()
                expected = run.record_terraform_inventory if format == "terraform" else run.record_cloudformation_inventory
                unexpected = run.record_cloudformation_inventory if format == "terraform" else run.record_terraform_inventory
                expected.assert_called_once_with("foundation")
                unexpected.assert_not_called()
                run.client.assert_not_called()

    def test_recorded_empty_inventory_is_not_replaced_by_resource_discovery(self):
        run = recorded_run()
        run.manifest["resources"]["foundation"] = {}
        run.record_terraform_inventory = Mock()
        run.prepare_cleanup()
        run.record_terraform_inventory.assert_not_called()
        run.client.assert_not_called()

    def test_failed_cloudformation_creation_never_blesses_a_preexisting_resource(self):
        run = recorded_run("cloudformation")
        cloud = run.client.return_value
        cloud.describe_stacks.return_value = {"Stacks": [owned_stack()]}
        states = ("CREATE_COMPLETE", "CREATE_FAILED", "CREATE_IN_PROGRESS", "DELETE_FAILED", "DELETE_COMPLETE")
        cloud.get_paginator.return_value.paginate.return_value = [{"StackResourceSummaries": [
            {"LogicalResourceId": state, "PhysicalResourceId": state + "-id", "ResourceStatus": state} for state in states]}]
        run.record_cloudformation_inventory("foundation")
        self.assertEqual({"CREATE_COMPLETE": "CREATE_COMPLETE-id", "DELETE_FAILED": "DELETE_FAILED-id"},
                         run.manifest["resources"]["foundation"])
        cloud.describe_stacks.assert_called_once_with(StackName="owned-stack-id")
        cloud.get_paginator.return_value.paginate.assert_called_once_with(StackName="owned-stack-id")

    def test_stack_identity_and_tags_are_verified_before_inventory_recovery(self):
        for field, value in (("StackName", "another-run-foundation"), ("Tags", ownership_tags(project="another-project"))):
            with self.subTest(field=field):
                run = recorded_run("cloudformation")
                stack = owned_stack()
                stack[field] = value
                cloud = run.client.return_value
                cloud.describe_stacks.return_value = {"Stacks": [stack]}
                before = dict(run.manifest["resources"]["foundation"])
                with self.assertRaisesRegex(ValueError, "outside this manifest"):
                    run.record_cloudformation_inventory("foundation")
                self.assertEqual(before, run.manifest["resources"]["foundation"])
                cloud.get_paginator.assert_not_called()

    def test_resuming_cleanup_tolerates_already_removed_data_resources(self):
        for service, code in (("s3", "NoSuchBucket"), ("ecr", "RepositoryNotFoundException")):
            with self.subTest(service=service):
                run = recorded_run()
                resource = run.client.return_value
                missing = ClientError({"Error": {"Code": code, "Message": "Already removed"}}, "Describe")
                resource.get_bucket_tagging.side_effect = missing
                resource.describe_repositories.side_effect = missing
                purge = run.empty_owned_bucket if service == "s3" else run.empty_owned_repository
                purge()
                resource.delete_objects.assert_not_called()
                resource.batch_delete_image.assert_not_called()

    def test_inactive_ecs_history_is_not_confused_with_a_live_deployment(self):
        ecs = Mock()
        ecs.describe_clusters.return_value = {"clusters": [{"status": "INACTIVE"}]}
        ecs.describe_services.return_value = {"services": []}
        ecs.describe_task_definition.return_value = {"taskDefinition": {"status": "INACTIVE"}}
        run = CloudRun.__new__(CloudRun)
        run.client = lambda name: ecs
        for resource in ("cluster/demo", "service/demo/demo", "task-definition/demo:1"):
            self.assertFalse(run.is_live_tagged_resource("arn:aws:ecs:region:123456789012:" + resource))
        ecs.describe_clusters.return_value = {"clusters": [{"status": "ACTIVE"}]}
        self.assertTrue(run.is_live_tagged_resource("arn:aws:ecs:region:123456789012:cluster/demo"))


if __name__ == "__main__":
    unittest.main()
