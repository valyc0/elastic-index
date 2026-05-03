# RAG Client Web - Vaadin 24 + Spring Boot 3

Client web Vaadin 24 per my-app con interfaccia grafica per upload Docling, elenco documenti indicizzati e chat RAG.

## Funzionalità

- Query RAG con sessione server-side
- Upload documenti tramite API Docling
- Elenco dei documenti presenti nell'indice semantico
- Cancellazione documenti tramite API semantic
- Stato servizi Docling e RAG

## Prerequisiti

1. Backend my-app attivo su http://localhost:8080
2. Java 17+
3. Maven 3.6+

## Quick Start

### 1. Avvia il backend

```bash
cd /home/valerio/lavoro/appo/elastic-index
./start-all.sh
```

Verifica che sia attivo su: http://localhost:8080

### 2. Avvia il client web

```bash
cd /home/valerio/lavoro/appo/elastic-index/client-web
chmod +x start.sh
./start.sh
```

### 3. Accedi all'applicazione

Apri il browser su http://localhost:8093

## Configurazione

Modifica src/main/resources/application.yml:

```yaml
server:
  port: 8093

rag:
  api:
    base-url: http://localhost:8080
    timeout: 60000
```

## API backend usate

### Upload API (/api/docling)
- POST /index
- GET /health

### RAG API (/api/rag)
- POST /session
- POST /ask
- GET /documents
- GET /health

### Semantic API (/api/semantic)
- DELETE /document?fileName=...

## Sviluppo

### Build

```bash
mvn clean package
```

### Run in dev mode

```bash
mvn spring-boot:run
```

## Troubleshooting

### Backend non raggiungibile

```bash
curl http://localhost:8080/api/rag/health
```

Se non risponde:

```bash
cd /home/valerio/lavoro/appo/elastic-index
./start-all.sh
```

### Porta 8093 già in uso

Modifica la porta in src/main/resources/application.yml.

### Errore di build Maven

```bash
mvn clean package -DskipTests
```

Progetto educativo/di sviluppo.

## 🆘 Supporto

Per problemi:
1. Controlla che il backend sia attivo
2. Verifica i log dell'applicazione
3. Controlla la configurazione in `application.yml`
4. Testa le API backend direttamente con curl

## 🎉 Features Avanzate

- **Keyboard shortcuts**: Ctrl+Enter per inviare query
- **Auto-refresh**: Aggiornamento documenti con un click
- **Real-time feedback**: Notifiche per ogni operazione
- **Error handling**: Gestione errori user-friendly
- **Confirm dialogs**: Sicurezza per operazioni critiche
- **Responsive grid**: Tabelle adattive e ordinabili
