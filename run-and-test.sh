#!/bin/bash

echo "=== Avvio completo e test ==="

# Kill existing processes
pkill -9 -f "spring-boot:run" 2>/dev/null
sleep 2

# Start application
cd /home/valerio/lavoro/elastic-index/my-app
mvn spring-boot:run > /tmp/spring-app.log 2>&1 &
APP_PID=$!

echo "Avvio applicazione (PID: $APP_PID)..."
echo "Attesa 20 secondi per startup completo..."

# Wait for startup
for i in {20..1}; do
    echo -n "$i "
    sleep 1
done
echo ""

# Check if app is running
if curl -s http://localhost:8080/ > /dev/null 2>&1; then
    echo "✓ Applicazione avviata su porta 8080"
    echo ""
    
    # Run tests
    cd /home/valerio/lavoro/elastic-index
    ./test-elastic.sh
else
    echo "✗ Applicazione non risponde"
    echo "Log ultimi 50 righe:"
    tail -50 /tmp/spring-app.log
fi
