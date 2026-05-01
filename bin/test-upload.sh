#!/bin/bash

# Script per testare l'upload di un PDF al servizio di estrazione

if [ -z "$1" ]; then
    echo "Uso: $0 <path-al-file.pdf>"
    echo "Esempio: $0 documento.pdf"
    exit 1
fi

FILE_PATH="$1"

if [ ! -f "$FILE_PATH" ]; then
    echo "Errore: File '$FILE_PATH' non trovato"
    exit 1
fi

echo "Caricamento file: $FILE_PATH"
echo "Endpoint: http://localhost:8080/api/documents/extract"
echo ""

curl -X POST \
  -F "file=@$FILE_PATH" \
  http://localhost:8080/api/documents/extract

echo ""
echo ""
echo "Risultato salvato anche in: my-app/extracted-documents/"
