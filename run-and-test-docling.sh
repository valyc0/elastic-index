#!/usr/bin/env bash
# run-and-test-docling.sh — Avvia la stack e indicizza un documento in un unico comando
#
# Equivale a:
#   ./start-all.sh && ./ingest-docling.sh [file.pdf]
#
# Uso:
#   ./run-and-test-docling.sh [percorso/al/documento.pdf]
#
# Se non si passa un file, usa "ventimila-leghe.pdf" in esempi/.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_FILE="${1:-$SCRIPT_DIR/esempi/ventimila-leghe.pdf}"

bash "$SCRIPT_DIR/start-all.sh"
bash "$SCRIPT_DIR/ingest-docling.sh" "$TEST_FILE"
