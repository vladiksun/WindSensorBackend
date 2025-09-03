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

certbot certonly --standalone -d $DOMAIN

# Regenerate PKCS12 keystore
openssl pkcs12 -export \
  -in /etc/letsencrypt/live/$DOMAIN/fullchain.pem \
  -inkey /etc/letsencrypt/live/$DOMAIN/privkey.pem \
  -out $KEYSTORE \
  -name micronaut \
  -password pass:$KEY_STORE_PASSWORD

echo "[INFO] Keystore regenerated at $KEYSTORE"

