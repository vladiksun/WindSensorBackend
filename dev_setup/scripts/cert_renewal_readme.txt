Put script to the /etc/letsencrypt/renewal-hooks/deploy/cert_renewal.sh


export EDITOR=nano

crontab -e

crontab -l

# remove all jobs
crontab -r

* * * * * /root/WindSensorBackend/dev_setup/scripts/cert_renewal.sh
