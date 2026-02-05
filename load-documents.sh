#!/bin/bash

# Script per caricare i documenti JSON estratti in Elasticsearch

# Ottiene la directory dello script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Verifico se l'applicazione è in esecuzione..."
if ! curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "Applicazione non in esecuzione. Avvio in corso..."
    cd "$SCRIPT_DIR/my-app"
    nohup ./mvnw spring-boot:run > app.log 2>&1 &
    echo "Attendo l'avvio dell'applicazione..."
    sleep 15
fi

# Attende che l'applicazione sia pronta
MAX_WAIT=30
WAITED=0
while ! curl -s http://localhost:8080/actuator/health > /dev/null 2>&1; do
    if [ $WAITED -ge $MAX_WAIT ]; then
        echo "Timeout: l'applicazione non si è avviata"
        exit 1
    fi
    echo "In attesa dell'applicazione..."
    sleep 2
    WAITED=$((WAITED + 2))
done

echo "Applicazione pronta!"
echo ""

# Carica tutti i file JSON dalla directory extracted-documents
DOCS_DIR="$SCRIPT_DIR/my-app/extracted-documents"
cd "$DOCS_DIR"

for json_file in *.json; do
    if [ -f "$json_file" ]; then
        echo "Caricamento di $json_file..."
        response=$(curl -s -X POST "http://localhost:8080/api/index/from-json?jsonFile=$json_file")
        echo "Risposta: $response"
        echo ""
    fi
done

echo "Caricamento completato!"
