#!/bin/bash

MONITOR_SERVER="monitorapp1.modum.intern"
JUMP_HOST="jump.modum.io"

#if only one key is provided, both servers have the same key
PRIV_PROXY=$1
if [ "$#" -gt 1 ]; then
   PRIV_APP=$2
else
   PRIV_APP=$1
fi

if [ ! -f "$PRIV_PROXY" ]; then
    echo "Proxy private key not found! Please make sure you have the valid private key: deploy.sh private_proxy.key private_app.key"
    exit 1
fi

if [ ! -f "$PRIV_APP" ]; then
    echo "App private key not found! Please make sure you have the valid private key: deploy.sh private_proxy.key private_app.key"
    exit 1
fi

#Build and install of the frontend
if ! gradle clean distTar; then
    echo "gradle build failed"
    exit 1
fi

# Deployment
echo "Uploading tar file"
ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -f -L 1337:"$MONITOR_SERVER":22 -i "$PRIV_PROXY" -p 2202 ubuntu@"$JUMP_HOST" sleep 3;
scp -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no  -r -i "$PRIV_PROXY" -P 1337 build/distributions/monitoring-1.0-SNAPSHOT.tar ubuntu@localhost:/var/lib/monitoring/monitoring.tar
sleep 3

echo "Unpacking tar"
ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -f -L 1337:"$MONITOR_SERVER":22 -i "$PRIV_PROXY" -p 2202 ubuntu@"$JUMP_HOST" sleep 3;
ssh -i "$PRIV_PROXY" -p 1337 ubuntu@localhost tar xf /var/lib/monitoring/monitoring.tar -C /var/lib/monitoring
sleep 3

echo "Restarting rates service"
ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -f -L 1337:"$MONITOR_SERVER":22 -i "$PRIV_PROXY" -p 2202 ubuntu@"$JUMP_HOST" sleep 3;
ssh -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no  -i "$PRIV_PROXY" -p 1337 ubuntu@localhost sudo systemctl restart monitoring.service
