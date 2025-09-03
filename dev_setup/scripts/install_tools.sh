#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

# force  IPv4
# echo 'Acquire::ForceIPv4 "true";' | sudo tee /etc/apt/apt.conf.d/99force-ipv4

echo "Updating package lists..."
sudo apt update

echo "Installing zip and unzip..."
sudo apt install -y zip unzip

echo "Installing git..."
sudo apt install -y git

echo "Installing nano..."
sudo apt install -y nano

# Check if SDKMAN is already installed
if [ -d "$HOME/.sdkman" ]; then
    echo "SDKMAN is already installed."
else
    echo "Installing SDKMAN..."
    curl -s "https://get.sdkman.io" | bash

    # Load SDKMAN into current shell
    source "$HOME/.sdkman/bin/sdkman-init.sh"
fi

echo "Installation completed successfully!"
