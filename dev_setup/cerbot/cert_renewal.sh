#!/bin/bash

DOMAIN="vm67706.vpsone.xyz"
KEYSTORE="/home/keystore.p12"
STOREPASS="changeit"

# Regenerate PKCS12 keystore
openssl pkcs12 -export \
  -in /etc/letsencrypt/live/$DOMAIN/fullchain.pem \
  -inkey /etc/letsencrypt/live/$DOMAIN/privkey.pem \
  -out $KEYSTORE \
  -name micronaut \
  -password pass:$STOREPASS

echo "[INFO] Keystore regenerated at $KEYSTORE"

# Restart container using Docker Compose
docker compose -f /path/to/docker-compose.yml restart windsensorbackend

echo "[INFO] Micronaut container restarted to pick up new SSL certificate"
