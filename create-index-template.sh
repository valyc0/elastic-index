#!/bin/bash

# Script per creare il template degli indici in Elasticsearch

echo "Creazione index template per files_*..."

curl -X PUT "http://localhost:9200/_index_template/files_template" \
  -H "Content-Type: application/json" \
  -d '{
  "index_patterns": ["files_*"],
  "template": {
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 0,
      "analysis": {
        "analyzer": {
          "default": {
            "type": "standard"
          }
        }
      }
    },
    "mappings": {
      "properties": {
        "id": {
          "type": "keyword"
        },
        "documentId": {
          "type": "keyword"
        },
        "chunkIndex": {
          "type": "integer"
        },
        "content": {
          "type": "text",
          "analyzer": "standard"
        },
        "language": {
          "type": "keyword"
        },
        "fileName": {
          "type": "text",
          "fields": {
            "keyword": {
              "type": "keyword"
            }
          }
        },
        "page": {
          "type": "integer"
        },
        "metadata": {
          "type": "object",
          "enabled": true,
          "dynamic": true
        }
      }
    }
  },
  "priority": 100
}'

echo ""
echo "Template creato con successo!"
echo ""
echo "Per applicare il template, elimina gli indici esistenti e ricarica i documenti:"
echo "  curl -X DELETE 'http://localhost:9200/files_*'"
echo "  Poi ricarica i documenti con ./load-documents.sh"
