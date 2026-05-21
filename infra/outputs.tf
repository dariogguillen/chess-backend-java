output "ec2_elastic_ip" {
  description = "Elastic IP attached to the EC2 instance. Paste this into the Duck DNS web UI for the configured subdomain."
  value       = aws_eip.app.public_ip
}

output "rds_endpoint" {
  description = "RDS Postgres endpoint (host:port). Use the host portion in the `.env` file's JDBC URL on the EC2."
  value       = aws_db_instance.postgres.endpoint
}

output "rds_address" {
  description = "RDS Postgres hostname only (no port). Convenience for building the JDBC URL."
  value       = aws_db_instance.postgres.address
}

output "ecr_repo_url" {
  description = "ECR repository URL. Empty until feature 7.7's CI pushes images here."
  value       = aws_ecr_repository.app.repository_url
}

output "ssh_command" {
  description = "Convenience SSH string for the runbook. Substitute the actual private key path if different."
  value       = "ssh -i ${replace(var.ssh_public_key_path, ".pub", "")} ubuntu@${aws_eip.app.public_ip}"
}

output "deploy_ssh_command" {
  description = "Convenience SSH string for the `deploy` user (used by feature 7.7's CI/CD)."
  value       = "ssh -i ${replace(var.deploy_ssh_public_key_path, ".pub", "")} deploy@${aws_eip.app.public_ip}"
}

output "duckdns_fqdn" {
  description = "Fully-qualified Duck DNS hostname the production stack serves under HTTPS."
  value       = "${var.duckdns_subdomain}.duckdns.org"
}

output "github_actions_role_arn" {
  description = "ARN of the IAM role assumed by GitHub Actions via OIDC. Set this as the AWS_DEPLOY_ROLE_ARN secret in the repository (feature 7.7)."
  value       = aws_iam_role.github_actions.arn
}
