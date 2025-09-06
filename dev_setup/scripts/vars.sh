# Configuration variables for setup_windsensor.sh

# Repository to clone
REPO_URL="${REPO_URL:-https://github.com/vladiksun/WindSensorBackend.git}"

# Local clone directory (change if you want a different path)
APP_DIR="${APP_DIR:-/home/WindSensorBackend}"

# Path to the dev_setup directory inside the repo
SCRIPTS_DIR="${SCRIPTS_DIR:-$APP_DIR/scripts}"

DOMAIN="${DOMAIN:-vm67706.vpsone.xyz}"
KEYSTORE="${KEYSTORE:-/home/keystore.p12}"
KEY_STORE_PASSWORD="${KEY_STORE_PASSWORD:-changeit}"