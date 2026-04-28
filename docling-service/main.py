"""
Docling parsing microservice.

Espone una REST API per il parsing strutturato di documenti (PDF, DOCX, HTML, PPTX).
Usa Docling (IBM) per estrarre titoli, sezioni, tabelle e paragrafi.

Endpoints:
  POST /parse         - upload file, restituisce struttura completa
  GET  /health        - health check
"""

import io
import logging
import tempfile
import os
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from docling.document_converter import DocumentConverter
from docling.datamodel.base_models import InputFormat
from docling.datamodel.pipeline_options import PdfPipelineOptions
from docling.document_converter import PdfFormatOption

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Docling Parsing Service", version="1.0.0")

# ── Configurazione pipeline Docling ──────────────────────────────────────────

pdf_options = PdfPipelineOptions()
pdf_options.do_ocr = False          # disabilita OCR per velocità (abilitare se si hanno scansioni)
pdf_options.do_table_structure = True  # estrae struttura tabelle

converter = DocumentConverter(
    format_options={
        InputFormat.PDF: PdfFormatOption(pipeline_options=pdf_options),
    }
)

# ── Modelli risposta ──────────────────────────────────────────────────────────

class ParsedSection(BaseModel):
    title: str
    chapter_index: int
    text: str
    level: int              # livello gerarchico del titolo (1=H1/capitolo, 2=H2/sezione, ecc.)
    page_number: Optional[int] = None
    parent_chapter_title: Optional[str] = None   # titolo del capitolo H1 padre (None se è già H1)
    parent_chapter_index: Optional[int] = None   # chapter_index del capitolo H1 padre

class ParsedTable(BaseModel):
    caption: Optional[str]
    text_representation: str   # tabella convertita in testo per embedding
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

    Accetta: PDF, DOCX, HTML, PPTX, XLSX, Markdown, AsciiDoc

    Restituisce:
    - full_text: testo completo del documento
    - sections: lista di sezioni con titolo, testo e livello gerarchico
    - tables: tabelle estratte convertite in testo
    - metadata: metadati documento (autore, titolo, ecc.)
    """
    if not file.filename:
        raise HTTPException(status_code=400, detail="Nome file mancante")

    suffix = Path(file.filename).suffix.lower()
    allowed = {".pdf", ".docx", ".doc", ".html", ".htm", ".pptx", ".xlsx", ".md", ".adoc"}
    if suffix not in allowed:
        raise HTTPException(
            status_code=415,
            detail=f"Formato non supportato: {suffix}. Supportati: {allowed}"
        )

    logger.info("Parsing documento: %s", file.filename)

    # Salva temporaneamente il file su disco (Docling richiede path)
    with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
        content = await file.read()
        tmp.write(content)
        tmp_path = tmp.name

    try:
        result = converter.convert(tmp_path)
        doc = result.document

        # ── Estrai sezioni strutturate con gerarchia capitoli ────────────────
        sections: list[ParsedSection] = []
        chapter_index = 0

        # Traccia il capitolo H1 corrente per ereditarlo alle sottosezioni
        current_h1_title: Optional[str] = None
        current_h1_index: Optional[int] = None

        for item, _ in doc.iterate_items():
            from docling.datamodel.document import SectionHeaderItem, TextItem, TableItem

            if isinstance(item, SectionHeaderItem):
                heading_level = int(item.level) if hasattr(item, "level") else 1

                if heading_level == 1:
                    # Capitolo principale: aggiorna il capitolo H1 corrente
                    current_h1_title = item.text
                    current_h1_index = chapter_index
                    sections.append(ParsedSection(
                        title=item.text,
                        chapter_index=chapter_index,
                        text="",
                        level=1,
                        page_number=_get_page(item),
                        parent_chapter_title=None,
                        parent_chapter_index=None,
                    ))
                else:
                    # Sottosezione H2/H3/...: eredita il capitolo H1 padre
                    sections.append(ParsedSection(
                        title=item.text,
                        chapter_index=chapter_index,
                        text="",
                        level=heading_level,
                        page_number=_get_page(item),
                        parent_chapter_title=current_h1_title,
                        parent_chapter_index=current_h1_index,
                    ))
                chapter_index += 1

            elif isinstance(item, TextItem):
                if sections:
                    sep = " " if sections[-1].text else ""
                    sections[-1].text += sep + item.text
                elif item.text.strip():
                    # Testo prima del primo titolo (es. abstract, premessa)
                    sections.append(ParsedSection(
                        title="",
                        chapter_index=chapter_index,
                        text=item.text,
                        level=0,
                        parent_chapter_title=None,
                        parent_chapter_index=None,
                    ))
                    chapter_index += 1

        # Rimuovi sezioni con testo troppo breve (< 10 caratteri)
        sections = [s for s in sections if len(s.text.strip()) >= 10]

        # ── Estrai tabelle ────────────────────────────────────────────────────
        tables: list[ParsedTable] = []
        for item, _ in doc.iterate_items():
            from docling.datamodel.document import TableItem
            if isinstance(item, TableItem):
                try:
                    df = item.export_to_dataframe()
                    text_repr = df.to_string(index=False)
                except Exception:
                    text_repr = item.export_to_markdown() if hasattr(item, "export_to_markdown") else ""

                if text_repr.strip():
                    tables.append(ParsedTable(
                        caption=getattr(item, "caption", None),
                        text_representation=text_repr,
                        page_number=_get_page(item),
                    ))

        # ── Testo completo ────────────────────────────────────────────────────
        full_text = doc.export_to_text()

        # ── Metadati ──────────────────────────────────────────────────────────
        metadata: dict[str, str] = {"fileName": file.filename}
        if hasattr(doc, "metadata") and doc.metadata:
            for k, v in vars(doc.metadata).items():
                if v is not None:
                    metadata[str(k)] = str(v)

        page_count = None
        if hasattr(doc, "pages") and doc.pages:
            page_count = len(doc.pages)

        logger.info(
            "Parsing completato: %s | sezioni=%d, tabelle=%d, pagine=%s",
            file.filename, len(sections), len(tables), page_count
        )

        return ParseResponse(
            file_name=file.filename,
            full_text=full_text,
            sections=sections,
            tables=tables,
            metadata=metadata,
            page_count=page_count,
        )

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
