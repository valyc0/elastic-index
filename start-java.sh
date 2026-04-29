#!/bin/bash
# Avvia l'applicazione Spring Boot

cd "$(dirname "$0")/my-app" || exit 1

echo "Avvio Spring Boot..."
./mvnw spring-boot:run
