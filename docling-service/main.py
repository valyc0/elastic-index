"""
Docling parsing microservice.

Espone una REST API per il parsing strutturato di documenti (PDF, DOCX, HTML, PPTX).
Usa Docling (IBM) per estrarre titoli, sezioni, tabelle e paragrafi.

Endpoints:
  POST /parse         - upload file, restituisce struttura completa
  GET  /health        - health check

Configurazione via variabili d'ambiente:
  DOCLING_THREADS        numero di thread CPU per modello (default: tutti i core)
  DOCLING_MAX_CONCURRENT numero massimo di conversioni simultanee (default: 2)
  DOCLING_TIMEOUT_SEC    timeout per singola conversione in secondi (default: 300)
  DOCLING_MAX_FILE_MB    dimensione massima file in MB (default: 100)
  DOCLING_DEVICE         dispositivo acceleratore: CPU | CUDA | MPS | AUTO (default: AUTO)
"""

import asyncio
import json
import logging
import tempfile
import os
import functools
import uuid
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

import redis

from fastapi import FastAPI, File, UploadFile, HTTPException
from pydantic import BaseModel

from docling.document_converter import DocumentConverter, PdfFormatOption
from docling.datamodel.base_models import InputFormat
from docling.datamodel.pipeline_options import PdfPipelineOptions, AcceleratorOptions
from docling.datamodel.document import SectionHeaderItem, TextItem, TableItem

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# ── Configurazione da environment ─────────────────────────────────────────────

_NUM_THREADS    = int(os.environ.get("DOCLING_THREADS", os.cpu_count() or 4))
_MAX_CONCURRENT = int(os.environ.get("DOCLING_MAX_CONCURRENT", 2))
_TIMEOUT_SEC    = int(os.environ.get("DOCLING_TIMEOUT_SEC", 300))
_MAX_FILE_MB    = int(os.environ.get("DOCLING_MAX_FILE_MB", 100))
_DEVICE_STR     = os.environ.get("DOCLING_DEVICE", "AUTO").upper()

_DEVICE_MAP = {
    "CPU":  "cpu",
    "CUDA": "cuda",
    "MPS":  "mps",
}
_DEVICE = _DEVICE_MAP.get(_DEVICE_STR, "cpu")

logger.info(
    "Docling config: threads=%d, max_concurrent=%d, timeout=%ds, max_file=%dMB, device=%s",
    _NUM_THREADS, _MAX_CONCURRENT, _TIMEOUT_SEC, _MAX_FILE_MB, _DEVICE_STR,
)

# ── Semaforo: limita le conversioni concorrenti ───────────────────────────────
# I modelli PyTorch non sono thread-safe per inferenza parallela.
# Il semaforo serializza l'accesso garantendo al massimo _MAX_CONCURRENT
# conversioni attive contemporaneamente.
_semaphore: asyncio.Semaphore  # inizializzato in startup

# ── Job store Redis ──────────────────────────────────────────────────────────
# I job vengono salvati in Redis con TTL automatico.
# Fallback a dict in-memory se Redis non è disponibile.
_REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379")
_JOB_TTL_SECONDS = 5 * 3600  # 5 ore
_redis_client: Optional[redis.Redis] = None
_jobs_fallback: dict = {}  # usato solo se Redis non è raggiungibile


def _get_redis() -> Optional[redis.Redis]:
    """Restituisce il client Redis se disponibile, altrimenti None."""
    return _redis_client


def _job_set(job_id: str, data: dict) -> None:
    r = _get_redis()
    if r is not None:
        r.setex(f"job:{job_id}", _JOB_TTL_SECONDS, json.dumps(data, default=str))
    else:
        _jobs_fallback[job_id] = data


def _job_get(job_id: str) -> Optional[dict]:
    r = _get_redis()
    if r is not None:
        raw = r.get(f"job:{job_id}")
        return json.loads(raw) if raw else None
    return _jobs_fallback.get(job_id)


def _job_list() -> list[dict]:
    r = _get_redis()
    if r is not None:
        keys = r.keys("job:*")
        result = []
        for key in keys:
            raw = r.get(key)
            if raw:
                result.append(json.loads(raw))
        return result
    return list(_jobs_fallback.values())


def _make_converter() -> DocumentConverter:
    """Crea un converter Docling isolato (uno per processo worker)."""
    opts = PdfPipelineOptions()
    opts.do_ocr = False
    opts.do_table_structure = True
    opts.generate_page_images = False
    opts.generate_picture_images = False
    opts.accelerator_options = AcceleratorOptions(
        num_threads=_NUM_THREADS,
        device=_DEVICE,
    )
    return DocumentConverter(
        format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=opts)}
    )


# ── Worker process pool ───────────────────────────────────────────────────────
# Ogni worker ha il proprio converter inizializzato una volta sola
# (aggira il GIL per workload CPU-bound).
_worker_pool: ProcessPoolExecutor
_worker_converter: Optional[DocumentConverter] = None  # solo nei processi worker


def _worker_init():
    """Inizializzazione del processo worker: carica i modelli una sola volta."""
    global _worker_converter
    _worker_converter = _make_converter()
    logger.info("Worker PID=%d: converter inizializzato", os.getpid())


def _convert_in_worker(tmp_path: str) -> dict:
    """
    Eseguito nel processo worker.
    Converte il file e restituisce un dict serializzabile (no oggetti Docling).
    """
    result = _worker_converter.convert(tmp_path)
    doc = result.document

    sections_raw = []
    tables_raw = []
    chapter_index = 0
    current_h1_title = None
    current_h1_index = None

    for item, _ in doc.iterate_items():
        if isinstance(item, SectionHeaderItem):
            heading_level = int(item.level) if hasattr(item, "level") else 1
            page = _get_page(item)
            if heading_level == 1:
                current_h1_title = item.text
                current_h1_index = chapter_index
                sections_raw.append(dict(
                    title=item.text, chapter_index=chapter_index,
                    text="", level=1, page_number=page,
                    parent_chapter_title=None, parent_chapter_index=None,
                ))
            else:
                sections_raw.append(dict(
                    title=item.text, chapter_index=chapter_index,
                    text="", level=heading_level, page_number=page,
                    parent_chapter_title=current_h1_title,
                    parent_chapter_index=current_h1_index,
                ))
            chapter_index += 1

        elif isinstance(item, TextItem):
            if sections_raw:
                sep = " " if sections_raw[-1]["text"] else ""
                sections_raw[-1]["text"] += sep + item.text
            elif item.text.strip():
                sections_raw.append(dict(
                    title="", chapter_index=chapter_index,
                    text=item.text, level=0, page_number=None,
                    parent_chapter_title=None, parent_chapter_index=None,
                ))
                chapter_index += 1

        elif isinstance(item, TableItem):
            try:
                df = item.export_to_dataframe()
                text_repr = df.to_string(index=False)
            except Exception:
                text_repr = item.export_to_markdown() if hasattr(item, "export_to_markdown") else ""
            if text_repr.strip():
                tables_raw.append(dict(
                    caption=getattr(item, "caption", None),
                    text_representation=text_repr,
                    page_number=_get_page(item),
                ))

    sections_raw = [s for s in sections_raw if len(s["text"].strip()) >= 10]

    metadata: dict[str, str] = {}
    if hasattr(doc, "metadata") and doc.metadata:
        for k, v in vars(doc.metadata).items():
            if v is not None:
                metadata[str(k)] = str(v)

    page_count = len(doc.pages) if hasattr(doc, "pages") and doc.pages else None

    return dict(
        full_text=doc.export_to_text(),
        sections=sections_raw,
        tables=tables_raw,
        metadata=metadata,
        page_count=page_count,
    )


app = FastAPI(title="Docling Parsing Service", version="1.0.0")


@app.on_event("startup")
async def startup():
    global _semaphore, _worker_pool, _redis_client
    _semaphore = asyncio.Semaphore(_MAX_CONCURRENT)
    _worker_pool = ProcessPoolExecutor(
        max_workers=_MAX_CONCURRENT,
        initializer=_worker_init,
    )
    logger.info("ProcessPoolExecutor avviato con %d worker(s)", _MAX_CONCURRENT)
    try:
        _redis_client = redis.Redis.from_url(_REDIS_URL, decode_responses=True, socket_connect_timeout=3)
        _redis_client.ping()
        logger.info("Redis connesso: %s", _REDIS_URL)
    except Exception as e:
        _redis_client = None
        logger.warning("Redis non disponibile (%s), uso fallback in-memory", e)


@app.on_event("shutdown")
async def shutdown():
    _worker_pool.shutdown(wait=False)
    logger.info("ProcessPoolExecutor fermato")

# ── Modelli risposta ──────────────────────────────────────────────────────────

class ParsedSection(BaseModel):
    title: str
    chapter_index: int
    text: str
    level: int
    page_number: Optional[int] = None
    parent_chapter_title: Optional[str] = None
    parent_chapter_index: Optional[int] = None

class ParsedTable(BaseModel):
    caption: Optional[str]
    text_representation: str
    page_number: Optional[int] = None

class ParseResponse(BaseModel):
    file_name: str
    full_text: str
    sections: list[ParsedSection]
    tables: list[ParsedTable]
    metadata: dict[str, str]
    page_count: Optional[int] = None

# ── Endpoints ─────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "UP", "service": "docling-service"}


@app.post("/parse", response_model=ParseResponse)
async def parse_document(file: UploadFile = File(...)):
    """
    Parsa un documento con Docling ed estrae struttura gerarchica.
    Accetta: PDF, DOCX, HTML, PPTX, XLSX, Markdown, AsciiDoc.
    """
    if not file.filename:
        raise HTTPException(status_code=400, detail="Nome file mancante")

    suffix = Path(file.filename).suffix.lower()
    allowed = {".pdf", ".docx", ".doc", ".html", ".htm", ".pptx", ".xlsx", ".md", ".adoc"}
    if suffix not in allowed:
        raise HTTPException(
            status_code=415,
            detail=f"Formato non supportato: {suffix}. Supportati: {allowed}",
        )

    content = await file.read()

    # Limite dimensione file
    max_bytes = _MAX_FILE_MB * 1024 * 1024
    if len(content) > max_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"File troppo grande: {len(content) // (1024*1024)}MB (max {_MAX_FILE_MB}MB)",
        )

    logger.info("Parsing documento: %s (%.1f MB)", file.filename, len(content) / (1024 * 1024))

    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(content)
        tmp_path = tmp.name

    try:
        # Acquisisce il semaforo prima di entrare nel worker pool:
        # garantisce al massimo _MAX_CONCURRENT conversioni attive.
        async with _semaphore:
            loop = asyncio.get_running_loop()
            try:
                raw = await asyncio.wait_for(
                    loop.run_in_executor(
                        _worker_pool,
                        functools.partial(_convert_in_worker, tmp_path),
                    ),
                    timeout=_TIMEOUT_SEC,
                )
            except asyncio.TimeoutError:
                raise HTTPException(
                    status_code=504,
                    detail=f"Timeout: conversione superato {_TIMEOUT_SEC}s",
                )

        logger.info(
            "Parsing completato: %s | sezioni=%d, tabelle=%d, pagine=%s",
            file.filename, len(raw["sections"]), len(raw["tables"]), raw["page_count"],
        )

        return ParseResponse(
            file_name=file.filename,
            full_text=raw["full_text"],
            sections=[ParsedSection(**s) for s in raw["sections"]],
            tables=[ParsedTable(**t) for t in raw["tables"]],
            metadata={"fileName": file.filename, **raw["metadata"]},
            page_count=raw["page_count"],
        )

    except HTTPException:
        raise
    except Exception as e:
        logger.exception("Errore nel parsing di %s", file.filename)
        raise HTTPException(status_code=500, detail=f"Errore parsing: {str(e)}")
    finally:
        os.unlink(tmp_path)


def _get_page(item) -> Optional[int]:
    """Estrae il numero di pagina da un item Docling se disponibile."""
    try:
        if item.prov and len(item.prov) > 0:
            return item.prov[0].page_no
    except Exception:
        pass
    return None


# ── Background worker per job asincroni ──────────────────────────────────

async def _process_document_async(job_id: str, filename: str, content: bytes, suffix: str):
    """Elabora il documento in background e aggiorna lo stato nel job store."""
    job = _job_get(job_id) or {}
    job["status"] = "PROCESSING"
    job["updated_at"] = datetime.utcnow().isoformat()
    _job_set(job_id, job)

    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        tmp.write(content)
        tmp_path = tmp.name

    try:
        async with _semaphore:
            loop = asyncio.get_running_loop()
            try:
                raw = await asyncio.wait_for(
                    loop.run_in_executor(
                        _worker_pool,
                        functools.partial(_convert_in_worker, tmp_path),
                    ),
                    timeout=_TIMEOUT_SEC,
                )
            except asyncio.TimeoutError:
                job = _job_get(job_id) or {}
                job["status"] = "ERROR"
                job["error"] = f"Timeout: conversione superato {_TIMEOUT_SEC}s"
                job["updated_at"] = datetime.utcnow().isoformat()
                _job_set(job_id, job)
                return

        result = ParseResponse(
            file_name=filename,
            full_text=raw["full_text"],
            sections=[ParsedSection(**s) for s in raw["sections"]],
            tables=[ParsedTable(**t) for t in raw["tables"]],
            metadata={"fileName": filename, **raw["metadata"]},
            page_count=raw["page_count"],
        )

        job = _job_get(job_id) or {}
        job["status"] = "DONE"
        job["result"] = result.model_dump()
        job["updated_at"] = datetime.utcnow().isoformat()
        _job_set(job_id, job)

        logger.info(
            "Job %s completato: %s | sezioni=%d, tabelle=%d",
            job_id, filename, len(raw["sections"]), len(raw["tables"]),
        )

    except Exception as e:
        logger.exception("Errore nel job async %s (%s)", job_id, filename)
        job = _job_get(job_id) or {}
        job["status"] = "ERROR"
        job["error"] = str(e)
        job["updated_at"] = datetime.utcnow().isoformat()
        _job_set(job_id, job)
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


# ── Nuovi endpoint asincroni ──────────────────────────────────────────────────

class AsyncJobResponse(BaseModel):
    jobId: str
    status: str
    fileName: str


class JobStatusResponse(BaseModel):
    jobId: str
    fileName: str
    status: str
    error: Optional[str] = None
    result: Optional[ParseResponse] = None


@app.post("/parse/async", status_code=202, response_model=AsyncJobResponse)
async def parse_document_async(file: UploadFile = File(...)):
    """
    Avvia il parsing asincrono di un documento.
    Restituisce immediatamente un jobId; il parsing avviene in background.
    Controlla lo stato con GET /jobs/{jobId}.
    """
    if not file.filename:
        raise HTTPException(status_code=400, detail="Nome file mancante")

    suffix = Path(file.filename).suffix.lower()
    allowed = {".pdf", ".docx", ".doc", ".html", ".htm", ".pptx", ".xlsx", ".md", ".adoc"}
    if suffix not in allowed:
        raise HTTPException(
            status_code=415,
            detail=f"Formato non supportato: {suffix}. Supportati: {allowed}",
        )

    content = await file.read()

    max_bytes = _MAX_FILE_MB * 1024 * 1024
    if len(content) > max_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"File troppo grande: {len(content) // (1024*1024)}MB (max {_MAX_FILE_MB}MB)",
        )

    job_id = str(uuid.uuid4())
    now = datetime.utcnow().isoformat()
    _job_set(job_id, {
        "job_id": job_id,
        "file_name": file.filename,
        "status": "QUEUED",
        "created_at": now,
        "updated_at": now,
        "error": None,
        "result": None,
    })

    asyncio.create_task(_process_document_async(job_id, file.filename, content, suffix))

    logger.info("Job async creato: jobId=%s, file=%s (%.1f MB)",
                job_id, file.filename, len(content) / (1024 * 1024))

    return AsyncJobResponse(jobId=job_id, status="QUEUED", fileName=file.filename)


@app.get("/jobs/{job_id}", response_model=JobStatusResponse)
def get_job_status(job_id: str):
    """Restituisce lo stato di un job asincrono. Il risultato è incluso solo quando status=DONE."""
    job = _job_get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail=f"Job non trovato: {job_id}")

    result = None
    if job["status"] == "DONE" and job["result"]:
        result = ParseResponse(**job["result"])

    return JobStatusResponse(
        jobId=job["job_id"],
        fileName=job["file_name"],
        status=job["status"],
        error=job["error"],
        result=result,
    )


@app.get("/jobs")
def list_jobs():
    """Elenca tutti i job (senza risultato, solo stato)."""
    return [
        {
            "jobId": j["job_id"],
            "fileName": j["file_name"],
            "status": j["status"],
            "error": j["error"],
            "createdAt": j["created_at"].isoformat(),
            "updatedAt": j["updated_at"].isoformat(),
        }
        for j in _job_list()
    ]
