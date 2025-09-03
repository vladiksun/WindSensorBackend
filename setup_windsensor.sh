#!/usr/bin/env bash
set -euo pipefail

REPO_URL="https://github.com/vladiksun/WindSensorBackend.git"
CLONE_DIR="${CLONE_DIR:-$HOME/WindSensorBackend}"
DEV_SETUP_DIR="$CLONE_DIR/dev_setup"

log() { echo "[INFO] $*"; }
err() { echo "[ERROR] $*" >&2; }

# 1) Check git availability only
if ! command -v git >/dev/null 2>&1; then
  err "Git is required but not installed. Please install git (e.g., sudo apt-get install -y git) and rerun."
  exit 1
fi

# 2) Clone or update the repository
if [ -d "$CLONE_DIR/.git" ]; then
  log "Repository already present at $CLONE_DIR. Pulling latest changes..."
  git -C "$CLONE_DIR" pull --rebase --autostash
elif [ -d "$CLONE_DIR" ]; then
  err "Directory $CLONE_DIR exists but is not a git repository. Set CLONE_DIR to a different path and retry."
  exit 1
else
  log "Cloning repository to $CLONE_DIR..."
  git clone "$REPO_URL" "$CLONE_DIR"
fi

# 3) Build Docker image with Gradle Jib
log "Building Docker image with Gradle Jib..."
(
  cd "$CLONE_DIR"
  chmod +x ./gradlew || true
  ./gradlew --no-daemon jibDockerBuild
)

# 4) Run docker compose from dev_setup
if [ ! -d "$DEV_SETUP_DIR" ]; then
  err "dev_setup directory not found at: $DEV_SETUP_DIR"
  exit 1
fi

log "Starting services with Docker Compose from $DEV_SETUP_DIR ..."
(
  cd "$DEV_SETUP_DIR"
  docker compose up -d
)

log "All done. To see logs, run:"
echo "  cd \"$DEV_SETUP_DIR\" && docker compose logs -f"