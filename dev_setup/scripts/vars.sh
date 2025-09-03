# Configuration variables for setup_windsensor.sh

# Repository to clone
REPO_URL="${REPO_URL:-https://github.com/vladiksun/WindSensorBackend.git}"

# Local clone directory (change if you want a different path)
CLONE_DIR="${CLONE_DIR:-$HOME/WindSensorBackend}"

# Path to the dev_setup directory inside the repo
DEV_SETUP_DIR="${DEV_SETUP_DIR:-$CLONE_DIR/dev_setup}"

DOMAIN="${DOMAIN:-vm67706.vpsone.xyz}"
KEYSTORE="${KEYSTORE:-/home/keystore.p12}"
KEY_STORE_PASSWORD="${KEY_STORE_PASSWORD:-changeit}"