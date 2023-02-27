#!/bin/bash

# TODO: delete this file if not needed

# This script is used to import the cosmosdb certificate in a container. 👀 See the docker-compose

attempt_counter=0
max_attempts=50
# shellcheck disable=SC2091
until $(wget --no-check-certificate -O "${JAVA_HOME}"/lib/security/emulator.pem https://10.20.0.2:8081/_explorer/emulator.pem); do
    if [ ${attempt_counter} -eq ${max_attempts} ];then
      echo "Max attempts reached"
      exit 1
    fi

    printf '.'
    attempt_counter=$(($attempt_counter+1))
    sleep 10
done
echo 'CosmosDB Started'

"$JAVA_HOME"/bin/keytool -trustcacerts -keystore "${JAVA_HOME}/lib/security/cacerts" -storepass changeit -importcert -alias cosmoskeystore -file "${JAVA_HOME}"/lib/security/emulator.pem -noprompt
sh run.sh
