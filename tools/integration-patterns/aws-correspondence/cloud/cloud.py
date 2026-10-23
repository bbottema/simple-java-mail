"""Try the correspondence worker in your own AWS account, then remove only that run.

Start with preflight and plan. Deploy creates a small, billable stack; scenario
sends only to the SES mailbox simulator. Keep the run name and local manifest:
destroy uses them to check ownership before removing resources. An existing SES
sending identity is a read-only prerequisite, never something this script edits.
"""
import argparse
import base64
import datetime
from email import policy
from email.parser import BytesParser
import hashlib
import hmac
import json
import os
import pathlib
import re
import shutil
import subprocess
import time
import uuid

import boto3
from botocore.exceptions import ClientError

SOURCE = pathlib.Path(__file__).resolve().parent
ROOT = SOURCE.parents[3]
TERRAFORM = "hashicorp/terraform:1.15.0@sha256:015350b1d4482a767d4bab61ca8ee60e838b195e6fc6de8435a267bed267f610"
PROJECT = "sjm-strategic-patterns"


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2, default=str) + "\n", encoding="utf-8")


def await_value(read, predicate, description, timeout=600):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = read()
        if predicate(value):
            return value
        time.sleep(3)
    raise RuntimeError(description + " did not finish; use status/destroy with this same run name")


def require_additive_plan(plan):
    changes = [change for change in plan.get("resource_changes", []) if change.get("mode") == "managed"]
    if any(change["change"]["actions"] not in (["create"], ["no-op"]) for change in changes):
        raise ValueError("This prototype only applies additive plans; inspect changes rather than replacing existing resources")
    return sum(change["change"]["actions"] == ["create"] for change in changes)


def require_owned_deletion(plan, inventory):
    for resource in plan.get("resource_changes", []):
        if resource.get("mode") != "managed" or resource["change"]["actions"] == ["no-op"]:
            continue
        if resource["change"]["actions"] != ["delete"] or inventory.get(resource["address"]) != resource["change"]["before"].get("id"):
            raise ValueError("The destroy plan contains a resource not recorded by this run; no deletion was applied")


def derive_ses_smtp_password(secret_access_key, region):
    """Convert this demo user's IAM secret into its region-specific SMTP password."""
    # SES specifies this HMAC chain, fixed date and version byte; they are not this run's date or a new signing scheme.
    # https://docs.aws.amazon.com/ses/latest/dg/smtp-credentials.html#smtp-credentials-convert
    signing_key = ("AWS4" + secret_access_key).encode("utf-8")
    for part in ("11111111", region, "ses", "aws4_request", "SendRawEmail"):
        signing_key = hmac.new(signing_key, part.encode("utf-8"), hashlib.sha256).digest()
    return base64.b64encode(bytes([0x04]) + signing_key).decode("ascii")


class CloudRun:
    """Keep one deployment's identity, owned resource inventory and cleanup trail together."""

    def __init__(self, args):
        self.args = args
        if not re.fullmatch(r"sjm-pattern-[a-z0-9-]{6,28}", args.run):
            raise ValueError("Use a unique sjm-pattern- run name, 18–40 lowercase characters")
        self.session = boto3.Session(profile_name=args.profile, region_name=args.region)
        identity = self.client("sts").get_caller_identity()
        if identity["Account"] != args.account:
            raise ValueError("The active AWS account differs from --account; no resources were changed")
        self.home = ROOT / ".local" / "strategic-extension" / "aws" / args.run
        self.home.mkdir(parents=True, exist_ok=True)
        self.manifest_path = self.home / "manifest.json"
        self.manifest = json.loads(self.manifest_path.read_text()) if self.manifest_path.exists() else {
            "run": args.run, "account": args.account, "region": args.region, "format": args.format,
            "created": datetime.datetime.now(datetime.timezone.utc).isoformat(), "stacks": {}, "phase": "NEW",
        }
        for field in ("run", "account", "region", "format"):
            if self.manifest[field] != getattr(args, field):
                raise ValueError("Do not reuse a manifest for another run/account/region/deployment format")
        self.outputs = self.manifest.get("outputs", {})
        network = {"vpc": getattr(args, "vpc", None) or "", "subnets": getattr(args, "subnet", None) or []}
        if not network["vpc"] and not network["subnets"]:
            network = self.manifest.get("network", network)
        if bool(network["vpc"]) != bool(network["subnets"]):
            raise ValueError("Supply --vpc and one or more --subnet values together, or omit both for an isolated network")
        if self.manifest.get("network", network) != network:
            raise ValueError("Do not change a recorded run's borrowed network")
        self.manifest["network"] = network
        for field in ("identity", "sender"):
            supplied = getattr(args, field)
            if field in self.manifest and self.manifest[field] != supplied:
                raise ValueError("Use the original sending identity and sender for this run")
            self.manifest[field] = supplied

    def client(self, name):
        return self.session.client(name)

    def persist(self):
        self.manifest["outputs"] = self.outputs
        save_json(self.manifest_path, self.manifest)

    def preflight(self):
        """Check the destination account and existing sending identity without changing either."""
        identity = self.client("sesv2").get_email_identity(EmailIdentity=self.args.identity)
        if not identity["VerifiedForSendingStatus"] or not self.args.sender.endswith("@" + self.args.identity):
            raise ValueError("Supply an existing verified domain and a sender beneath it; this tool does not change verification or DNS")
        snapshot = json.loads(json.dumps({key: identity.get(key) for key in
            ("DkimAttributes", "MailFromAttributes", "FeedbackForwardingStatus", "ConfigurationSetName")}, default=str))
        previous = self.manifest.get("protectedIdentity")
        if previous is not None and previous != snapshot:
            raise ValueError("The protected identity changed since preflight; inspect concurrent work before proceeding")
        self.manifest["protectedIdentity"] = snapshot
        network = self.manifest["network"]
        if network["vpc"]:
            subnets = self.client("ec2").describe_subnets(SubnetIds=network["subnets"])["Subnets"]
            if len(subnets) != len(set(network["subnets"])) or any(
                    subnet["VpcId"] != network["vpc"] or subnet["OwnerId"] != self.args.account for subnet in subnets):
                raise ValueError("Supplied subnets must belong to the expected account, region and VPC")
        self.persist()
        print("Preflight: expected account, region and verified sending identity confirmed; identity is read-only")
        print("Stack: one 0.25-vCPU/1-GiB Fargate task, ECR, S3, DynamoDB on-demand, two queue/DLQ pairs, SNS, scoped IAM, secret and one-day logs")
        print("No NAT gateway, ALB, RDS, DNS changes or account-wide SES settings. Public IPv4, compute, storage and service usage are billable.")

    def terraform(self, phase, *arguments):
        work = self.home / phase
        work.mkdir(exist_ok=True)
        # Freeze reviewed inputs per run. Later source edits must not change the configuration used to remove a deployment.
        if not (work / "main.tf").exists():
            shutil.copy2(SOURCE / "terraform" / phase / "main.tf", work / "main.tf")
        credentials = self.session.get_credentials().get_frozen_credentials()
        environment = dict(os.environ, AWS_ACCESS_KEY_ID=credentials.access_key, AWS_SECRET_ACCESS_KEY=credentials.secret_key,
                           AWS_REGION=self.args.region, AWS_DEFAULT_REGION=self.args.region, TF_IN_AUTOMATION="1")
        environment.pop("TF_LOG", None)
        if credentials.token:
            environment["AWS_SESSION_TOKEN"] = credentials.token
        else:
            environment.pop("AWS_SESSION_TOKEN", None)
        command = ["docker", "run", "--rm", "-v", f"{work}:/work", "-w", "/work"]
        for name in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_REGION", "AWS_DEFAULT_REGION", "TF_IN_AUTOMATION"):
            if name in environment:
                command += ["-e", name]  # Values travel in the environment, never in printed process arguments.
        ca = os.environ.get("AWS_CA_BUNDLE")
        if not ca:
            configured = subprocess.run(["aws", "configure", "get", "ca_bundle", "--profile", self.args.profile], capture_output=True, text=True)
            ca = configured.stdout.strip()
        if ca and pathlib.Path(ca).is_file():
            command += ["-v", f"{ca}:/trust/roots.pem:ro", "-e", "AWS_CA_BUNDLE=/trust/roots.pem", "-e", "SSL_CERT_FILE=/trust/roots.pem"]
        command += [TERRAFORM, *arguments]
        result = subprocess.run(command, env=environment, capture_output=True, text=True)
        with (self.home / "terraform.log").open("a", encoding="utf-8") as log:
            if arguments[0] not in ("show", "output"):
                log.write(result.stdout)
            log.write(result.stderr)
        if result.returncode:
            raise RuntimeError("Terraform failed; inspect the run's terraform.log (no credential values are supplied as Terraform variables)")
        return result.stdout

    def prepare_terraform(self, phase):
        # Foundation owns storage, queues and credentials; worker only adds the executable task using those outputs.
        variables = ({"run_name": self.args.run, "region": self.args.region, "sender": self.args.sender,
                      "identity_arn": f"arn:aws:ses:{self.args.region}:{self.args.account}:identity/{self.args.identity}",
                      "existing_vpc_id": self.manifest["network"]["vpc"], "existing_subnet_ids": self.manifest["network"]["subnets"]}
                     if phase == "foundation" else {"foundation": self.outputs, "image_digest": self.manifest["imageDigest"]})
        work = self.home / phase
        work.mkdir(exist_ok=True)
        save_json(work / "inputs.auto.tfvars.json", variables)
        self.terraform(phase, "init", "-input=false")
        self.terraform(phase, "validate")

    def apply_terraform(self, phase):
        self.prepare_terraform(phase)
        self.terraform(phase, "plan", "-input=false", "-out=reviewed.tfplan")
        plan = json.loads(self.terraform(phase, "show", "-json", "reviewed.tfplan"))
        created = require_additive_plan(plan)
        print(f"Reviewed {phase}: {created} new resources; no updates/deletes")
        self.manifest[phase] = "APPLY_STARTED"
        self.persist()  # A failed apply still has a recoverable, run-scoped state directory.
        try:
            self.terraform(phase, "apply", "-input=false", "reviewed.tfplan")
        finally:
            self.record_terraform_inventory(phase)
        if phase == "foundation":
            self.outputs = json.loads(self.terraform(phase, "output", "-json", "deployment"))
        self.manifest[phase] = "CREATED"
        self.persist()

    def record_terraform_inventory(self, phase):
        state = json.loads(self.terraform(phase, "show", "-json"))
        resources = state.get("values", {}).get("root_module", {}).get("resources", [])
        inventory = {item["address"]: item["values"]["id"] for item in resources if item["mode"] == "managed"}
        self.manifest.setdefault("resources", {})[phase] = inventory
        self.persist()

    def destroy_terraform(self, phase):
        # Inventory also supports an interrupted apply. Never import a foreign resource into this state.
        if phase not in self.manifest.get("resources", {}):
            self.record_terraform_inventory(phase)
        self.terraform(phase, "plan", "-destroy", "-input=false", "-out=destroy.tfplan")
        reviewed = json.loads(self.terraform(phase, "show", "-json", "destroy.tfplan"))
        require_owned_deletion(reviewed, self.manifest["resources"][phase])
        self.terraform(phase, "apply", "-input=false", "destroy.tfplan")

    def apply_cloudformation(self, phase):
        cloud = self.client("cloudformation")
        name = self.args.run + "-" + phase
        parameters = ({"RunName": self.args.run, "Sender": self.args.sender,
                       "IdentityArn": f"arn:aws:ses:{self.args.region}:{self.args.account}:identity/{self.args.identity}",
                       "ExistingVpc": self.manifest["network"]["vpc"], "ExistingSubnets": ",".join(self.manifest["network"]["subnets"])}
                      if phase == "foundation" else {key: value for key, value in self.outputs.items() if key not in ("Repository", "SmtpUser", "Subnet")})
        if phase == "worker":
            parameters["Image"] = self.outputs["Repository"] + "@" + self.manifest["imageDigest"]
        try:
            existing = cloud.describe_stacks(StackName=name)["Stacks"][0]
            if self.manifest["stacks"].get(phase) != existing["StackId"]:
                raise ValueError("A stack already uses this name but is not owned by this manifest")
            raise ValueError("This prototype does not update existing stacks; use a fresh run after scoped cleanup")
        except ClientError as absent:
            if "does not exist" not in str(absent):
                raise
        change = cloud.create_change_set(StackName=name, ChangeSetName="review-" + uuid.uuid4().hex[:12], ChangeSetType="CREATE",
            TemplateBody=(SOURCE / "cloudformation" / (phase + ".yaml")).read_text(), Capabilities=["CAPABILITY_NAMED_IAM"],
            Parameters=[{"ParameterKey": key, "ParameterValue": value} for key, value in parameters.items()],
            Tags=[{"Key": "Project", "Value": PROJECT}, {"Key": "Run", "Value": self.args.run}])
        self.manifest["stacks"][phase] = change["StackId"]
        self.persist()
        cloud.get_waiter("change_set_create_complete").wait(ChangeSetName=change["Id"])
        reviewed = cloud.describe_change_set(ChangeSetName=change["Id"])
        if any(item["ResourceChange"]["Action"] != "Add" for item in reviewed.get("Changes", [])):
            raise ValueError("The prototype change set contains a non-additive operation")
        save_json(self.home / (phase + "-changes.json"), [{"logicalId": item["ResourceChange"]["LogicalResourceId"],
                  "action": item["ResourceChange"]["Action"]} for item in reviewed.get("Changes", [])])
        print(f"Reviewed {phase}: {len(reviewed.get('Changes', []))} additions; no changes to existing resources")
        cloud.execute_change_set(ChangeSetName=change["Id"])
        try:
            cloud.get_waiter("stack_create_complete").wait(StackName=change["StackId"])
        finally:
            self.record_cloudformation_inventory(phase)
        if phase == "foundation":
            self.outputs = {value["OutputKey"]: value["OutputValue"] for value in cloud.describe_stacks(StackName=change["StackId"])["Stacks"][0]["Outputs"]}
        self.persist()

    def record_cloudformation_inventory(self, phase):
        cloud = self.client("cloudformation")
        stack = self.owned_stack(phase)
        # CREATE_FAILED can name a pre-existing resource that this stack never owned. Do not authorize its cleanup.
        owned_states = ("CREATE_COMPLETE", "DELETE_FAILED")
        self.manifest.setdefault("resources", {})[phase] = {
            item["LogicalResourceId"]: item["PhysicalResourceId"]
            for page in cloud.get_paginator("list_stack_resources").paginate(StackName=stack["StackId"])
            for item in page["StackResourceSummaries"]
            if item.get("PhysicalResourceId") and item["ResourceStatus"] in owned_states}
        self.persist()

    def bootstrap_smtp(self):
        """Put a run-owned SMTP credential in Secrets Manager, not in the checkout or Terraform state."""
        user = self.outputs["SmtpUser"]
        self.require_user_ownership(user)
        if self.manifest.get("smtpAccessKey"):
            return
        access = self.client("iam").create_access_key(UserName=user)["AccessKey"]
        self.manifest["smtpAccessKey"] = access["AccessKeyId"]
        self.persist()  # Only the identifier, never the secret/key/password.
        password = derive_ses_smtp_password(access["SecretAccessKey"], self.args.region)
        self.client("secretsmanager").put_secret_value(SecretId=self.outputs["Secret"],
                SecretString=json.dumps({"username": access["AccessKeyId"], "password": password}))
        print("Demo-only SMTP credential stored in Secrets Manager; no secret was written to state or logs")

    def publish_image(self):
        repository = self.outputs["Repository"]
        if not repository.split("/")[-1] == self.args.run:
            raise ValueError("The output repository does not belong to this run")
        # The cloud task has an explicit x86_64 runtime. Do not accidentally publish a host-ARM-only image from an Apple Silicon laptop.
        subprocess.run(["docker", "build", "--platform", "linux/amd64", "--target", "cloud", "--build-arg", "APP=aws-correspondence",
                        "-f", str(ROOT / "tools/integration-patterns/applications.Dockerfile"),
                        "-t", "sjm-pattern-aws-correspondence:cloud", str(ROOT)], cwd=ROOT, check=True)
        isolated = subprocess.run(["docker", "run", "--rm", "--platform", "linux/amd64", "--entrypoint", "sh",
            "sjm-pattern-aws-correspondence:cloud", "-ec",
            "test ! -e /opt/demo/server.p12; test ! -e /opt/demo/server.pem; test ! -e /opt/demo/server.crt; "
            "! keytool -list -cacerts -storepass changeit -alias local-demo-only >/dev/null 2>&1"], capture_output=True)
        if isolated.returncode:
            raise ValueError("The cloud image contains local TLS material or cannot verify its trust store; no image was published")
        image = repository + ":prototype"
        subprocess.run(["docker", "tag", "sjm-pattern-aws-correspondence:cloud", image], check=True)
        authorization = self.client("ecr").get_authorization_token()["authorizationData"][0]
        username, password = base64.b64decode(authorization["authorizationToken"]).decode().split(":", 1)
        config = self.home / "docker-auth"
        config.mkdir(exist_ok=True)
        try:
            subprocess.run(["docker", "--config", str(config), "login", "--username", username, "--password-stdin", repository.split("/")[0]],
                           input=password, text=True, check=True, stdout=subprocess.DEVNULL)
            subprocess.run(["docker", "--config", str(config), "push", image], check=True)
        finally:
            (config / "config.json").unlink(missing_ok=True)
        images = self.client("ecr").describe_images(repositoryName=self.args.run, imageIds=[{"imageTag": "prototype"}])["imageDetails"]
        self.manifest["imageDigest"] = images[0]["imageDigest"]
        self.persist()

    def deploy(self):
        # Create the foundation first so the image/credential can be ready before any worker starts sending.
        self.preflight()
        apply = self.apply_terraform if self.args.format == "terraform" else self.apply_cloudformation
        apply("foundation")
        self.bootstrap_smtp()
        self.publish_image()
        apply("worker")
        self.client("ecs").get_waiter("services_stable").wait(cluster=self.outputs["Cluster"], services=[self.args.run])
        self.manifest["phase"] = "DEPLOYED"
        self.persist()
        print("Owned worker deployment is stable; run scenario, then destroy with the same arguments")

    def plan(self):
        # The worker needs real foundation outputs and an image digest. Its deploy step is reviewed separately before it is applied.
        self.preflight()
        if self.args.format == "terraform":
            self.prepare_terraform("foundation")
            self.terraform("foundation", "plan", "-input=false", "-out=reviewed.tfplan")
            print("Foundation plan saved. The dependent worker plan is reviewed during deploy, once its inputs exist.")
        else:
            for phase in ("foundation", "worker"):
                self.client("cloudformation").validate_template(TemplateBody=(SOURCE / "cloudformation" / (phase + ".yaml")).read_text())
            print("Both templates validated. Deploy creates and reviews an additive change set before executing each phase.")
        print("No infrastructure was provisioned.")

    def scenario(self):
        """Follow duplicate work through SMTP acceptance, protected archival and later simulator feedback."""
        self.require_deployed()
        queues, objects = self.client("sqs"), self.client("s3")
        database = self.session.resource("dynamodb").Table(self.outputs["Table"])
        evidence = []
        for case in ("success", "bounce", "complaint"):
            id = "cloud_" + case + "_" + uuid.uuid4().hex[:16]
            key = "documents/" + id + ".txt"
            objects.put_object(Bucket=self.outputs["Bucket"], Key=key, Body=b"Synthetic protected correspondence; no personal data",
                               ServerSideEncryption="AES256")
            job = json.dumps({"id": id, "recipient": case + "@simulator.amazonses.com", "documentKey": key})
            for _ in range(2):
                queues.send_message(QueueUrl=self.outputs["WorkQueue"], MessageBody=job)
            row = await_value(lambda: database.get_item(Key={"jobId": id, "kind": "JOB"}, ConsistentRead=True).get("Item", {}),
                              lambda item: item.get("status") in ("SUBMITTED", "HELD", "HELD_PREPARATION"), "SMTP completion", 240)
            if row.get("smtpStatus") != "ACCEPTED":
                raise AssertionError("The cloud attempt was not accepted: " + row.get("failureTypes", row.get("status", "unknown")))
            expected = {"success": "Delivery", "bounce": "Bounce", "complaint": "Complaint"}[case]
            events = await_value(lambda: database.query(KeyConditionExpression="jobId=:id AND begins_with(kind,:event)",
                ExpressionAttributeValues={":id": id, ":event": "EVENT#"}, ConsistentRead=True).get("Items", []),
                lambda rows: any(item.get("eventType") == expected for item in rows), "SES simulator feedback", 300)
            archive = objects.get_object(Bucket=self.outputs["Bucket"], Key="archive/" + id + ".eml")
            with archive["Body"] as body:
                raw = body.read()
            digest = hashlib.sha256(raw).hexdigest()
            if digest != row["archiveSha256"] or archive.get("ServerSideEncryption") != "AES256":
                raise AssertionError("Archive hash/encryption evidence did not match")
            if BytesParser(policy=policy.default).parsebytes(raw).get_content_type() not in ("application/pkcs7-mime", "application/x-pkcs7-mime"):
                raise AssertionError("The cloud archive is not the expected encrypted MIME representation")
            message_ids = {item["sesMessageId"] for item in events}
            if len(message_ids) != 1:
                raise AssertionError("The observed SES events refer to more than one submission for one application job")
            evidence.append({"case": case, "job": id, "smtp": row["smtpStatus"], "feedback": sorted({item["eventType"] for item in events}),
                             "archiveHashMatches": True, "serverSideEncryption": "AES256", "encryptedMime": True,
                             "duplicateInput": True, "observedSubmissions": len(message_ids)})
        save_json(self.home / "scenario.json", evidence)
        print(json.dumps(evidence, indent=2))

    def require_deployed(self):
        if not self.outputs or self.outputs.get("RunName") != self.args.run:
            raise ValueError("No owned deployment recorded for this run")
        self.require_user_ownership(self.outputs["SmtpUser"])

    def require_user_ownership(self, user):
        if user != self.args.run + "-smtp":
            raise ValueError("Refusing an IAM user outside this run")
        tags = {tag["Key"]: tag["Value"] for tag in self.client("iam").list_user_tags(UserName=user)["Tags"]}
        if tags.get("Run") != self.args.run or tags.get("Project") != PROJECT:
            raise ValueError("The IAM user's ownership tags do not match this run")

    def status(self):
        self.require_deployed()
        service = self.client("ecs").describe_services(cluster=self.outputs["Cluster"], services=[self.args.run])["services"]
        print(json.dumps([{key: item.get(key) for key in ("serviceName", "status", "desiredCount", "runningCount", "pendingCount")} for item in service], indent=2))

    def destroy(self):
        """Stop the worker, remove its data and credentials, then verify this run has no live resources."""
        if not self.manifest.get("foundation") and not self.manifest.get("stacks"):
            raise ValueError("No infrastructure creation was recorded for this run")
        self.prepare_cleanup()
        # First remove the owned worker. No producer or sender remains active when data/credentials are removed.
        if self.args.format == "terraform" and self.manifest.get("worker"):
            self.destroy_terraform("worker")
        elif "worker" in self.manifest["stacks"]:
            self.delete_stack("worker")
        self.remove_smtp_key()
        self.empty_owned_bucket()
        self.empty_owned_repository()
        if self.args.format == "terraform":
            self.destroy_terraform("foundation")
        else:
            self.delete_stack("foundation")
        self.manifest["phase"] = "DESTROYED"
        self.persist()
        self.preflight()  # Also checks that the referenced sending identity was not changed.
        self.verify_cleanup()

    def prepare_cleanup(self):
        # Recover interrupted bookkeeping from this run's state/stack, never from a matching resource name or tag.
        if "foundation" not in self.manifest.get("resources", {}):
            if self.args.format == "terraform":
                self.record_terraform_inventory("foundation")
            else:
                self.record_cloudformation_inventory("foundation")
        # Validate both data-bearing targets before stopping the worker or purging either one.
        self.owned_bucket()
        self.owned_repository()

    def recorded_foundation_id(self, terraform_address, stack_logical_id, expected):
        address = terraform_address if self.args.format == "terraform" else stack_logical_id
        recorded = self.manifest.get("resources", {}).get("foundation", {}).get(address)
        if recorded is not None and recorded != expected:
            raise ValueError("The recorded resource differs from this run's expected name; no data was purged")
        return recorded

    @staticmethod
    def missing_resource(failure):
        return failure.response["Error"]["Code"] in ("NoSuchEntity", "NoSuchBucket", "404", "RepositoryNotFoundException")

    def remove_smtp_key(self):
        iam = self.client("iam")
        user = self.args.run + "-smtp"
        try:
            self.require_user_ownership(user)
        except ClientError as absent:
            if self.missing_resource(absent):
                return  # Safe resumption after a partial destroy.
            raise
        keys = iam.list_access_keys(UserName=user)["AccessKeyMetadata"]
        if any(key["AccessKeyId"] != self.manifest.get("smtpAccessKey") for key in keys):
            raise ValueError("An unrecognized key exists on the demo user; stop rather than deleting it")
        for key in keys:
            iam.delete_access_key(UserName=user, AccessKeyId=key["AccessKeyId"])

    def owned_bucket(self):
        bucket = self.recorded_foundation_id("aws_s3_bucket.documents", "Documents", self.args.run + "-" + self.args.account)
        if bucket is None:
            return None  # A failed creation may have collided with an older bucket. Its contents are not ours to remove.
        objects = self.client("s3")
        try:
            tags = objects.get_bucket_tagging(Bucket=bucket)["TagSet"]
        except ClientError as absent:
            if self.missing_resource(absent):
                return None
            raise
        ownership = {tag["Key"]: tag["Value"] for tag in tags}
        if ownership.get("Run") != self.args.run or ownership.get("Project") != PROJECT:
            raise ValueError("Bucket ownership could not be verified")
        return bucket

    def empty_owned_bucket(self):
        bucket = self.owned_bucket()
        if bucket is None:
            return
        objects = self.client("s3")
        for page in objects.get_paginator("list_objects_v2").paginate(Bucket=bucket):
            keys = [{"Key": item["Key"]} for item in page.get("Contents", [])]
            if any(not item["Key"].startswith(("documents/", "archive/")) for item in keys):
                raise ValueError("Unexpected data in the demo bucket; do not purge it automatically")
            if keys:
                result = objects.delete_objects(Bucket=bucket, Delete={"Objects": keys})
                if result.get("Errors"):
                    raise RuntimeError("Some owned objects could not be removed; inspect permissions before retrying cleanup")

    def owned_repository(self):
        name = self.recorded_foundation_id("aws_ecr_repository.worker", "Repository", self.args.run)
        if name is None:
            return None
        registry = self.client("ecr")
        try:
            repository = registry.describe_repositories(repositoryNames=[name])["repositories"][0]
        except ClientError as absent:
            if self.missing_resource(absent):
                return None
            raise
        tags = registry.list_tags_for_resource(resourceArn=repository["repositoryArn"])["tags"]
        ownership = {tag["Key"]: tag["Value"] for tag in tags}
        if ownership.get("Run") != self.args.run or ownership.get("Project") != PROJECT:
            raise ValueError("Repository ownership could not be verified")
        return name

    def empty_owned_repository(self):
        name = self.owned_repository()
        if name is None:
            return
        registry = self.client("ecr")
        for _ in range(6):
            digests = {item["imageDigest"] for page in registry.get_paginator("list_images").paginate(repositoryName=name)
                       for item in page["imageIds"]}
            if not digests:
                return
            # OCI indexes reference child/attestation images. Delete the index, then revisit any initially referenced children.
            result = registry.batch_delete_image(repositoryName=name, imageIds=[{"imageDigest": digest} for digest in digests])
            if any(item["failureCode"] not in ("ImageReferencedByManifestList", "ImageNotFound") for item in result.get("failures", [])):
                raise RuntimeError("An owned image could not be deleted; cleanup stopped without forcing another repository")
        raise RuntimeError("The owned repository still has referenced images; inspect it before retrying cleanup")

    def owned_stack(self, phase):
        cloud = self.client("cloudformation")
        stack_id = self.manifest["stacks"][phase]
        stack = cloud.describe_stacks(StackName=stack_id)["Stacks"][0]
        tags = {tag["Key"]: tag["Value"] for tag in stack.get("Tags", [])}
        if stack["StackName"] != self.args.run + "-" + phase or tags.get("Run") != self.args.run or tags.get("Project") != PROJECT:
            raise ValueError("Refusing a stack outside this manifest's run")
        return stack

    def delete_stack(self, phase):
        cloud = self.client("cloudformation")
        stack = self.owned_stack(phase)
        stack_id = stack["StackId"]
        if stack["StackStatus"] != "DELETE_COMPLETE":
            cloud.delete_stack(StackName=stack_id)
            cloud.get_waiter("stack_delete_complete").wait(StackName=stack_id)

    def verify_cleanup(self):
        remaining = await_value(self.remaining_live_resources, lambda values: not values, "Resource-tag inventory convergence", 120)
        try:
            self.client("iam").get_user(UserName=self.args.run + "-smtp")
            raise RuntimeError("The owned SMTP user still exists")
        except self.client("iam").exceptions.NoSuchEntityException:
            pass
        active = [arn for arn in self.client("ecs").list_task_definitions(familyPrefix=self.args.run, status="ACTIVE")["taskDefinitionArns"]
                  if arn.split("/")[-1].split(":")[0] == self.args.run]
        if active:
            raise RuntimeError("An owned task definition is still active")
        save_json(self.home / "cleanup.json", {"remainingTaggedResources": remaining, "protectedIdentityUnchanged": True})
        if remaining:
            raise RuntimeError("Some tagged resources remain or tag inventory has not converged; inspect cleanup.json")
        print("Cleanup verified: no live owned resources remain; inactive ECS history may remain. The existing SES identity is unchanged")

    def remaining_live_resources(self):
        pages = self.client("resourcegroupstaggingapi").get_paginator("get_resources").paginate(
            TagFilters=[{"Key": "Run", "Values": [self.args.run]}])
        return [item["ResourceARN"] for page in pages for item in page["ResourceTagMappingList"]
                if self.is_live_tagged_resource(item["ResourceARN"])]

    def is_live_tagged_resource(self, arn):
        # The tagging API retains ECS tombstones after native deletion. An INACTIVE record is not a live/billable deployment.
        if ":ecs:" not in arn:
            return True
        ecs = self.client("ecs")
        resource = arn.split(":", 5)[5]
        if resource.startswith("cluster/"):
            return any(item["status"] != "INACTIVE" for item in ecs.describe_clusters(clusters=[arn])["clusters"])
        if resource.startswith("service/"):
            cluster = resource.split("/")[1]
            return any(item["status"] != "INACTIVE" for item in ecs.describe_services(cluster=cluster, services=[arn])["services"])
        if resource.startswith("task-definition/"):
            try:
                return ecs.describe_task_definition(taskDefinition=arn)["taskDefinition"]["status"] == "ACTIVE"
            except ClientError as absent:
                if absent.response["Error"]["Code"] == "ClientException" and "Unable to describe" in str(absent):
                    return False
                raise
        return True


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Keep the same arguments for every command. Always finish with destroy; Docker Compose cleanup does not remove AWS resources.")
    parser.add_argument("command", choices=["preflight", "plan", "deploy", "scenario", "status", "destroy"],
        help="Check prerequisites, inspect infrastructure, create it, try simulator mail, inspect the worker, or remove this run")
    parser.add_argument("--format", choices=["terraform", "cloudformation"], required=True, help="Choose one deployment format for the entire run")
    parser.add_argument("--profile", required=True, help="AWS CLI profile to use; no credentials are copied into the example")
    parser.add_argument("--account", required=True, help="Expected AWS account ID; a mismatch stops before infrastructure changes")
    parser.add_argument("--region", required=True, help="Region containing the existing SES identity and this new stack")
    parser.add_argument("--run", required=True, help="Unique sjm-pattern- name; keep its local manifest until cleanup is verified")
    parser.add_argument("--identity", required=True, help="Existing verified SES domain; read-only")
    parser.add_argument("--sender", required=True, help="Sending address beneath the verified domain, for example demo@your-domain.example")
    parser.add_argument("--vpc", help="Optional existing VPC, read-only; requires --subnet")
    parser.add_argument("--subnet", action="append", help="Optional existing subnet; repeat for more than one. Routes are never modified")
    run = CloudRun(parser.parse_args())
    getattr(run, run.args.command)()


if __name__ == "__main__":
    main()
