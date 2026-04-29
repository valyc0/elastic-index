#!/bin/bash
# Ferma tutti i processi Java in ascolto su porta 8080

PIDS=$(lsof -ti tcp:8080 2>/dev/null)

if [ -z "$PIDS" ]; then
  echo "Nessun processo Java in ascolto su porta 8080"
  exit 0
fi

echo "Processi trovati su porta 8080: $PIDS"
kill $PIDS 2>/dev/null
sleep 2

# Verifica se ancora in ascolto, forza kill
PIDS=$(lsof -ti tcp:8080 2>/dev/null)
if [ -n "$PIDS" ]; then
  echo "Forzo kill: $PIDS"
  kill -9 $PIDS 2>/dev/null
fi

echo "✔ Porta 8080 libera"
