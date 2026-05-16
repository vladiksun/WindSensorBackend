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

# Disable UFW to allow certbot ACME challenge (port 80/443)
ufw_status_before=$(ufw status verbose 2>/dev/null | head -n1)
ufw_was_enabled=false
if echo "$ufw_status_before" | grep -q "active"; then
  ufw_was_enabled=true
  log "Disabling UFW temporarily for certificate renewal ..."
  echo "y" | ufw disable
fi

# Ensure UFW is restored on any exit (error or success)
cleanup() {
  if [[ "$ufw_was_enabled" == "true" ]]; then
    log "Re-enabling UFW ..."
    echo "y" | ufw enable
  fi
}
trap cleanup EXIT

certbot renew

# Regenerate PKCS12 keystore
openssl pkcs12 -export \
  -in /etc/letsencrypt/live/$DOMAIN/fullchain.pem \
  -inkey /etc/letsencrypt/live/$DOMAIN/privkey.pem \
  -out $KEYSTORE \
  -name micronaut \
  -password pass:$KEY_STORE_PASSWORD

echo "[INFO] Keystore regenerated at $KEYSTORE"

log "Restart container using Docker Compose ..."
(
  cd "$APP_DIR"
  # Try to stop any running stack; ignore errors if nothing is running yet
  docker compose restart windsensorbackend
)

echo "[INFO] Micronaut container restarted to pick up new SSL certificate"
