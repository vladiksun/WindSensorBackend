#!/usr/bin/env bash
set -euo pipefail

log() { echo "[INFO] $*"; }
err() { echo "[ERROR] $*" >&2; }

# Load variables from vars.sh (defaults to file next to this script)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VARS_FILE="${VARS_FILE:-$SCRIPT_DIR/vars.sh}"

if [[ ! -f "$VARS_FILE" ]]; then
  err "vars.sh not found at: $VARS_FILE"
  err "Create vars.sh or set VARS_FILE to your config path."
  exit 1
fi

# Inject variables
# shellcheck disable=SC1090
source "$VARS_FILE"

# Validate required variables
: "${REPO_URL:?REPO_URL is required in vars.sh}"
: "${CLONE_DIR:?CLONE_DIR is required in vars.sh}"
: "${SCRIPTS_DIR:?SCRIPTS_DIR is required in vars.sh}"

# 1) Check git availability only
if ! command -v git >/dev/null 2>&1; then
  err "Git is required but not installed. Please install it (e.g., sudo apt-get install -y git) and rerun."
  exit 1
fi

# 2) Clone or update the repository
if [[ -d "$CLONE_DIR/.git" ]]; then
  log "Repository present at $CLONE_DIR. Pulling latest changes..."
  git -C "$CLONE_DIR" pull --rebase --autostash
elif [[ -d "$CLONE_DIR" ]]; then
  err "Directory $CLONE_DIR exists but is not a Git repository. Adjust CLONE_DIR in vars.sh and retry."
  exit 1
else
  log "Cloning repository to $CLONE_DIR..."
  git clone "$REPO_URL" "$CLONE_DIR"
fi

log "Ensuring any existing Docker Compose stack is stopped (if running) ..."
(
  cd "$SCRIPTS_DIR"
  # Try to stop any running stack; ignore errors if nothing is running yet
  docker compose down --remove-orphans || true
)

# 3) Build Docker image with Gradle Jib
log "Building Docker image with Gradle Jib..."
(
  cd "$CLONE_DIR"
  chmod +x ./gradlew || true
  ./gradlew --no-daemon jibDockerBuild
)

# 4) Run docker compose from dev_setup
if [[ ! -d "$SCRIPTS_DIR" ]]; then
  err "dev_setup directory not found at: $SCRIPTS_DIR"
  exit 1
fi

log "Starting services with Docker Compose from $SCRIPTS_DIR ..."
(
  cd "$SCRIPTS_DIR"
  docker compose up -d
)

log "All done. To see logs, run:"
echo "  cd \"$SCRIPTS_DIR\" && docker compose logs -f"