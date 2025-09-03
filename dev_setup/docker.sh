docker images

docker rmi windsensorbackend

docker load -i jib-image.tar

docker run --rm -it -p 443:443 windsensorbackend:latest

docker run --rm -it \
  -p 443:443 \
  -v /home/keystore.p12:/home/keystore.p12:ro \
  -e KEY_STORE_PATH=file:/home/keystore.p12 \
windsensorbackend:latest
