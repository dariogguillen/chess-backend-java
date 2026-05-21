variable "aws_region" {
  description = "AWS region. The Free Tier limits apply per region; pick one and stick to it."
  type        = string
  default     = "us-east-2"
}

variable "project_name" {
  description = "Logical project name; used as a prefix for resource names so the stack is easy to identify in the console."
  type        = string
  default     = "chess-backend"
}

variable "db_name" {
  description = "Postgres database name created inside the RDS instance."
  type        = string
  default     = "chess"
}

variable "db_username" {
  description = "Master username for the RDS Postgres instance. Matches the local docker-compose convention."
  type        = string
  default     = "chess"
}

variable "db_password" {
  description = "Master password for the RDS Postgres instance. Generate with `openssl rand -base64 24` and paste into terraform.tfvars; never commit."
  type        = string
  sensitive   = true
}

variable "alarm_email" {
  description = "Email address subscribed to the AWS Budget alarm. AWS will send a confirmation email that must be accepted before the subscription is active."
  type        = string
}

variable "ssh_public_key_path" {
  description = "Path to the SSH public key for the EC2 `ubuntu` admin user. Typically `~/.ssh/id_ed25519.pub` or `~/.ssh/id_rsa.pub`."
  type        = string
}

variable "deploy_ssh_public_key_path" {
  description = "Path to the SSH public key for the EC2 `deploy` user (used by feature 7.7's CI/CD). For 7.5 it is fine to reuse the same key as `ssh_public_key_path`."
  type        = string
}

variable "allowed_ssh_cidr" {
  description = "CIDR allowed to reach the EC2 instance on port 22. Use `<your-public-ip>/32` if you have a static IP, otherwise `0.0.0.0/0` is acceptable because SSH key auth is mandatory."
  type        = string
  default     = "0.0.0.0/0"
}

variable "duckdns_subdomain" {
  description = "Duck DNS subdomain (the part before `.duckdns.org`). Must be reserved manually in the Duck DNS web UI before `terraform apply`."
  type        = string
  default     = "chess-backend"
}

variable "instance_type" {
  description = "EC2 instance type. `t3.micro` is in the Free Tier in `us-east-2`."
  type        = string
  default     = "t3.micro"
}

variable "db_instance_class" {
  description = "RDS instance class. `db.t3.micro` is in the Free Tier."
  type        = string
  default     = "db.t3.micro"
}

variable "db_allocated_storage_gb" {
  description = "RDS GP3 storage in GB. Free Tier allows up to 20 GB."
  type        = number
  default     = 20
}

variable "monthly_budget_usd" {
  description = "Monthly AWS budget alarm threshold in USD. $1 keeps the noise floor tight — any real charge is suspicious on Free Tier."
  type        = number
  default     = 1
}
