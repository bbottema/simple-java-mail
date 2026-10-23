# This foundation creates one isolated demo's network, storage, queues and identities.
# The separate worker template starts the image only after cloud.py has populated its SMTP secret.
terraform {
  required_version = ">= 1.15.0, < 2.0.0"
  required_providers { aws = { source = "hashicorp/aws", version = "= 6.69.1" } }
}
variable "run_name" {
  description = "Unique run prefix used to name and tag only this demo's resources."
  type        = string
  validation {
    condition     = can(regex("^sjm-pattern-[a-z0-9-]{6,28}$", var.run_name))
    error_message = "Use a unique sjm-pattern- run name, at most 40 characters."
  }
}
variable "region" {
  description = "AWS region containing the existing verified SES identity and this isolated run."
  type        = string
}
variable "sender" {
  description = "From address covered by the existing SES identity; the demo's SMTP permission permits only this sender."
  type        = string
}
variable "identity_arn" {
  description = "ARN of an already verified SES identity. This template references it without changing identity or DNS settings."
  type        = string
}
variable "existing_vpc_id" {
  description = "Optional VPC owned by this account. Supply its usable outbound subnets too; an empty value creates a demo network."
  type        = string
  default     = ""
}
variable "existing_subnet_ids" {
  description = "Optional subnets in the supplied VPC. They are referenced, not modified; the worker needs outbound HTTPS and SMTP."
  type        = list(string)
  default     = []
  validation {
    condition     = (var.existing_vpc_id == "") == (length(var.existing_subnet_ids) == 0)
    error_message = "Supply both an existing VPC and its subnets, or let this run create its own network."
  }
}
provider "aws" {
  region = var.region
  default_tags { tags = { Project = "sjm-strategic-patterns", Run = var.run_name } }
}
data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" { state = "available" }
locals {
  account        = data.aws_caller_identity.current.account_id
  create_network = var.existing_vpc_id == ""
  vpc_id         = local.create_network ? aws_vpc.demo[0].id : var.existing_vpc_id
  subnet_ids     = local.create_network ? [aws_subnet.demo[0].id] : var.existing_subnet_ids
  task_trust = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" },
    Action = "sts:AssumeRole", Condition = { StringEquals = { "aws:SourceAccount" = local.account },
  ArnLike = { "aws:SourceArn" = "arn:aws:ecs:${var.region}:${local.account}:*" } } }] })
}
data "aws_subnet" "supplied" {
  for_each = toset(var.existing_subnet_ids)
  id       = each.value
  lifecycle {
    postcondition {
      condition     = self.vpc_id == var.existing_vpc_id && self.owner_id == local.account
      error_message = "Every supplied subnet must belong to the expected account and VPC."
    }
  }
}

# Give the worker outbound connectivity without exposing an inbound application service.
# Supplied networks remain untouched: cloud.py checks their ownership, but neither template repairs routes or adds a gateway to them.
resource "aws_vpc" "demo" {
  count      = local.create_network ? 1 : 0
  cidr_block = "10.237.0.0/24"
}
resource "aws_internet_gateway" "demo" {
  count  = local.create_network ? 1 : 0
  vpc_id = aws_vpc.demo[0].id
}
resource "aws_subnet" "demo" {
  count             = local.create_network ? 1 : 0
  vpc_id            = aws_vpc.demo[0].id
  cidr_block        = "10.237.0.0/26"
  availability_zone = data.aws_availability_zones.available.names[0]
}
resource "aws_route_table" "demo" {
  count  = local.create_network ? 1 : 0
  vpc_id = aws_vpc.demo[0].id
  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.demo[0].id
  }
}
resource "aws_route_table_association" "demo" {
  count          = local.create_network ? 1 : 0
  subnet_id      = aws_subnet.demo[0].id
  route_table_id = aws_route_table.demo[0].id
}
resource "aws_security_group" "worker" {
  name   = var.run_name
  vpc_id = local.vpc_id
  # No ingress. A public address supplies outbound connectivity; this is not a public HTTP service.
  egress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 587
    to_port     = 587
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# Keep one immutable image and short-lived diagnostic logs for this run. The worker itself is declared in the second template.
resource "aws_ecr_repository" "worker" {
  name                 = var.run_name
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration { scan_on_push = true }
}
resource "aws_cloudwatch_log_group" "worker" {
  name              = "/sjm-patterns/${var.run_name}"
  retention_in_days = 1
}
resource "aws_ecs_cluster" "worker" { name = var.run_name }

# The SQL-free application ledger records claims and evidence. Queue redelivery must consult this guard, not assume SMTP rolled back.
# S3 holds source documents and the protected MIME archive; blocking public access and storage encryption do not replace S/MIME protection.
resource "aws_dynamodb_table" "attempts" {
  name         = var.run_name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "jobId"
  range_key    = "kind"
  attribute {
    name = "jobId"
    type = "S"
  }
  attribute {
    name = "kind"
    type = "S"
  }
}
resource "aws_s3_bucket" "documents" { bucket = "${var.run_name}-${local.account}" }
resource "aws_s3_bucket_public_access_block" "documents" {
  bucket                  = aws_s3_bucket.documents.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}
resource "aws_s3_bucket_server_side_encryption_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id
  rule {
    apply_server_side_encryption_by_default { sse_algorithm = "AES256" }
  }
}

# Work requests and later SES feedback travel on separate queues. A visibility timeout permits another delivery of the same input,
# not another SMTP submission. After three unsuccessful receives, the corresponding dead-letter queue keeps it available for review.
resource "aws_sqs_queue" "work_dead" {
  name                      = "${var.run_name}-work-dead"
  message_retention_seconds = 86400
}
resource "aws_sqs_queue" "feedback_dead" {
  name                      = "${var.run_name}-feedback-dead"
  message_retention_seconds = 86400
}
resource "aws_sqs_queue" "work" {
  name                       = "${var.run_name}-work"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 86400
  redrive_policy             = jsonencode({ deadLetterTargetArn = aws_sqs_queue.work_dead.arn, maxReceiveCount = 3 })
}
resource "aws_sqs_queue" "feedback" {
  name                       = "${var.run_name}-feedback"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 86400
  redrive_policy             = jsonencode({ deadLetterTargetArn = aws_sqs_queue.feedback_dead.arn, maxReceiveCount = 3 })
}

# SES publishes structured service events through SNS to the feedback queue. These JSON events are not MIME delivery-status reports.
# Restrict each service-to-service handoff to this account and this run's topic/configuration set.
resource "aws_sns_topic" "feedback" { name = "${var.run_name}-feedback" }
resource "aws_sqs_queue_policy" "feedback" {
  queue_url = aws_sqs_queue.feedback.url
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "sns.amazonaws.com" },
    Action = "sqs:SendMessage", Resource = aws_sqs_queue.feedback.arn,
  Condition = { ArnEquals = { "aws:SourceArn" = aws_sns_topic.feedback.arn }, StringEquals = { "aws:SourceAccount" = local.account } } }] })
}
resource "aws_sns_topic_subscription" "feedback" {
  topic_arn            = aws_sns_topic.feedback.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.feedback.arn
  raw_message_delivery = true
}
resource "aws_sesv2_configuration_set" "demo" {
  configuration_set_name = var.run_name
  delivery_options { tls_policy = "REQUIRE" }
}
resource "aws_sns_topic_policy" "feedback" {
  arn = aws_sns_topic.feedback.arn
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ses.amazonaws.com" },
    Action = "sns:Publish", Resource = aws_sns_topic.feedback.arn,
    Condition = { StringEquals = { "aws:SourceAccount" = local.account },
  ArnEquals = { "aws:SourceArn" = aws_sesv2_configuration_set.demo.arn } } }] })
}
resource "aws_sesv2_configuration_set_event_destination" "demo" {
  configuration_set_name = aws_sesv2_configuration_set.demo.configuration_set_name
  event_destination_name = "application-feedback"
  event_destination {
    enabled              = true
    matching_event_types = ["SEND", "DELIVERY", "BOUNCE", "COMPLAINT", "DELIVERY_DELAY"]
    sns_destination { topic_arn = aws_sns_topic.feedback.arn }
  }
  depends_on = [aws_sns_topic_policy.feedback, aws_sns_topic_subscription.feedback, aws_sqs_queue_policy.feedback]
}

# SES SMTP uses its own username/password rather than the task's AWS session credentials. cloud.py creates a run-owned IAM access key,
# derives its SMTP password and writes it straight to Secrets Manager: neither the secret value nor that key enters Terraform state.
resource "aws_secretsmanager_secret" "smtp" {
  name                    = "${var.run_name}/smtp"
  recovery_window_in_days = 0
}
resource "aws_iam_user" "smtp" {
  name = "${var.run_name}-smtp"
  path = "/sjm-patterns/"
}
resource "aws_iam_user_policy" "smtp" {
  name = "simulator-only"
  user = aws_iam_user.smtp.name
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = "ses:SendRawEmail",
    Resource = [var.identity_arn, aws_sesv2_configuration_set.demo.arn], Condition = {
  StringEquals = { "ses:FromAddress" = var.sender }, "ForAllValues:StringLike" = { "ses:Recipients" = ["*@simulator.amazonses.com"] } } }] })
}

# ECS uses the execution role to pull this image and write container logs before application code can run.
resource "aws_iam_role" "execution" {
  name               = "${var.run_name}-execution"
  assume_role_policy = local.task_trust
}
resource "aws_iam_role_policy" "execution" {
  name = "image-and-logs"
  role = aws_iam_role.execution.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
    { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = aws_ecr_repository.worker.arn },
    { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.worker.arn}:*" }
  ] })
}

# The Java application uses the task role for its two queues, ledger, document/archive prefixes and SMTP secret.
# Keeping these roles separate avoids giving the container the execution role's registry permissions.
resource "aws_iam_role" "task" {
  name               = "${var.run_name}-task"
  assume_role_policy = local.task_trust
}
resource "aws_iam_role_policy" "task" {
  name = "application-resources"
  role = aws_iam_role.task.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes"], Resource = [aws_sqs_queue.work.arn, aws_sqs_queue.feedback.arn] },
    { Effect = "Allow", Action = ["dynamodb:PutItem", "dynamodb:GetItem", "dynamodb:UpdateItem", "dynamodb:Query", "dynamodb:Scan", "dynamodb:ConditionCheckItem"], Resource = aws_dynamodb_table.attempts.arn },
    { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject"], Resource = ["${aws_s3_bucket.documents.arn}/documents/*", "${aws_s3_bucket.documents.arn}/archive/*"] },
    { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = aws_secretsmanager_secret.smtp.arn }
  ] })
}

# cloud.py passes this generated map to the worker template; users do not need to assemble the resource identifiers by hand.
output "deployment" {
  value = {
    RunName   = var.run_name, Region = var.region, Sender = var.sender, Repository = aws_ecr_repository.worker.repository_url,
    Cluster   = aws_ecs_cluster.worker.arn, Subnet = local.subnet_ids[0], Subnets = join(",", local.subnet_ids), SecurityGroup = aws_security_group.worker.id,
    TaskRole  = aws_iam_role.task.arn, ExecutionRole = aws_iam_role.execution.arn, LogGroup = aws_cloudwatch_log_group.worker.name,
    Bucket    = aws_s3_bucket.documents.id, Table = aws_dynamodb_table.attempts.name,
    WorkQueue = aws_sqs_queue.work.url, FeedbackQueue = aws_sqs_queue.feedback.url,
    Secret    = aws_secretsmanager_secret.smtp.arn, SmtpUser = aws_iam_user.smtp.name, ConfigurationSet = aws_sesv2_configuration_set.demo.configuration_set_name
  }
}
