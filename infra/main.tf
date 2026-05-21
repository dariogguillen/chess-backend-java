# ---------------------------------------------------------------------------
# main.tf — All AWS resources for the chess-backend production environment.
#
# Resource set (~17 resources):
#   - 1x default VPC data source (no resource created; we reuse the default)
#   - 1x AMI data source (latest Ubuntu 24.04 LTS for us-east-2)
#   - 2x aws_security_group (EC2 + RDS)
#   - 1x aws_key_pair (ubuntu admin)
#   - 1x aws_instance
#   - 1x aws_eip + 1x aws_eip_association
#   - 1x aws_db_subnet_group + 1x aws_db_instance
#   - 1x aws_ecr_repository + 1x aws_ecr_lifecycle_policy
#   - 1x aws_budgets_budget
# ---------------------------------------------------------------------------

# Default VPC — every AWS account has one per region. Using it skips the
# custom-VPC-plus-subnets exercise (out of scope for portfolio).
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Canonical's Ubuntu 24.04 LTS AMI, filtered to the configured region.
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }

  filter {
    name   = "root-device-type"
    values = ["ebs"]
  }
}

# ---------------------------------------------------------------------------
# Security groups
# ---------------------------------------------------------------------------

resource "aws_security_group" "ec2" {
  name        = "${var.project_name}-ec2"
  description = "Inbound 22 (SSH), 80 (HTTP - Caddy ACME + redirect), 443 (HTTPS). Outbound any."
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.allowed_ssh_cidr]
  }

  ingress {
    description = "HTTP (Lets Encrypt HTTP-01 challenge + Caddy redirect to HTTPS)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "Any outbound (Docker pull, apt, ECR, Lets Encrypt, RDS)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "rds" {
  name        = "${var.project_name}-rds"
  description = "Inbound 5432 from the EC2 SG only. No public ingress."
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "Postgres from EC2"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

# ---------------------------------------------------------------------------
# EC2: key pair, instance, Elastic IP
# ---------------------------------------------------------------------------

resource "aws_key_pair" "ubuntu" {
  key_name   = "${var.project_name}-ubuntu"
  public_key = file(var.ssh_public_key_path)
}

# Cloud-init script rendered at plan time. Interpolates the Duck DNS
# subdomain (for the Caddyfile) and the deploy user's public key.
locals {
  caddyfile = templatefile("${path.module}/Caddyfile.tpl", {
    duckdns_subdomain = var.duckdns_subdomain
  })

  user_data = templatefile("${path.module}/ec2-cloud-init.sh.tpl", {
    duckdns_subdomain     = var.duckdns_subdomain
    deploy_ssh_public_key = file(var.deploy_ssh_public_key_path)
    caddyfile             = local.caddyfile
  })
}

resource "aws_instance" "app" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  key_name               = aws_key_pair.ubuntu.key_name
  vpc_security_group_ids = [aws_security_group.ec2.id]
  user_data              = local.user_data

  root_block_device {
    volume_size = 30 # Free Tier allows up to 30 GB EBS GP3
    volume_type = "gp3"
    encrypted   = true
  }

  tags = {
    Name = "${var.project_name}-app"
  }

  # cloud-init can patch sshd_config; do not replace on user_data change.
  lifecycle {
    ignore_changes = [user_data]
  }
}

resource "aws_eip" "app" {
  domain = "vpc"

  tags = {
    Name = "${var.project_name}-eip"
  }
}

resource "aws_eip_association" "app" {
  instance_id   = aws_instance.app.id
  allocation_id = aws_eip.app.id
}

# ---------------------------------------------------------------------------
# RDS Postgres
# ---------------------------------------------------------------------------

resource "aws_db_subnet_group" "rds" {
  name        = "${var.project_name}-rds"
  description = "Default-VPC subnets for the Postgres instance."
  subnet_ids  = data.aws_subnets.default.ids
}

resource "aws_db_instance" "postgres" {
  identifier     = "${var.project_name}-postgres"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage     = var.db_allocated_storage_gb
  max_allocated_storage = var.db_allocated_storage_gb # disable autoscaling — pin to Free Tier ceiling
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.rds.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period = 7
  skip_final_snapshot     = true
  deletion_protection     = false

  auto_minor_version_upgrade = true
  apply_immediately          = true

  tags = {
    Name = "${var.project_name}-postgres"
  }
}

# ---------------------------------------------------------------------------
# ECR repository for the backend image (populated by feature 7.7's CI)
# ---------------------------------------------------------------------------

resource "aws_ecr_repository" "app" {
  name                 = var.project_name
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep only the last 5 images; older ones are expired automatically."
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 5
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

# ---------------------------------------------------------------------------
# Cost guardrail: $1/month budget with email notifications at 50/80/100%.
# ---------------------------------------------------------------------------

resource "aws_budgets_budget" "monthly" {
  name         = "${var.project_name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 50
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alarm_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alarm_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alarm_email]
  }
}
