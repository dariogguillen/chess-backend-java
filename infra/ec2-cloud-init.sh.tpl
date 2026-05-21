#!/bin/bash
# ---------------------------------------------------------------------------
# cloud-init for chess-backend EC2 instance.
#
# Runs once on first boot. Tasks:
#   1. apt update + upgrade
#   2. Install Docker (official apt repo) and Caddy (Cloudsmith repo)
#   3. Create `deploy` user, no sudo, in `docker` group
#   4. Write the Caddyfile from the rendered template
#   5. Create /opt/chess/ owned by `deploy`
#   6. Enable + start docker and caddy systemd units
#
# Logs land in /var/log/cloud-init-output.log. Inspect with `cloud-init status
# --wait` and then `sudo tail -f /var/log/cloud-init-output.log`.
# ---------------------------------------------------------------------------
set -euxo pipefail

export DEBIAN_FRONTEND=noninteractive

# --- Step 1: apt update + upgrade -------------------------------------------
apt-get update -y
apt-get upgrade -y

apt-get install -y \
    ca-certificates \
    curl \
    gnupg \
    debian-keyring \
    debian-archive-keyring \
    apt-transport-https

# --- Step 2a: Docker (official apt repo) ------------------------------------
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
chmod a+r /etc/apt/keyrings/docker.gpg

. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list

apt-get update -y
apt-get install -y \
    docker-ce \
    docker-ce-cli \
    containerd.io \
    docker-buildx-plugin \
    docker-compose-plugin

# --- Step 2b: Caddy (Cloudsmith repo) ---------------------------------------
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
    | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
    | tee /etc/apt/sources.list.d/caddy-stable.list

apt-get update -y
apt-get install -y caddy

# --- Step 3: deploy user (no sudo, in docker group) -------------------------
if ! id -u deploy >/dev/null 2>&1; then
    useradd --create-home --shell /bin/bash deploy
fi
usermod -aG docker deploy

install -d -m 0700 -o deploy -g deploy /home/deploy/.ssh
cat > /home/deploy/.ssh/authorized_keys <<'DEPLOY_KEY_EOF'
${deploy_ssh_public_key}
DEPLOY_KEY_EOF
chmod 0600 /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys

# --- Step 4: /opt/chess workdir for the deploy + .env + compose files -------
install -d -m 0755 -o deploy -g deploy /opt/chess

# --- Step 5: Caddyfile from rendered template -------------------------------
install -d -m 0755 /etc/caddy
cat > /etc/caddy/Caddyfile <<'CADDYFILE_EOF'
${caddyfile}
CADDYFILE_EOF

install -d -m 0755 -o caddy -g caddy /var/log/caddy

# --- Step 6: enable + start systemd units -----------------------------------
systemctl enable docker
systemctl start docker
systemctl enable caddy
systemctl restart caddy

# Done. cloud-init reports `done` once this script exits 0.
