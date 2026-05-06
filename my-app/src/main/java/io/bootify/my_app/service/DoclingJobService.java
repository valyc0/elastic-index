package io.bootify.my_app.service;

import io.bootify.my_app.model.DoclingJobStatus;
import io.bootify.my_app.model.DoclingParseResponse;
import io.bootify.my_app.model.DoclingPythonJobStatus;
import io.bootify.my_app.model.DocumentExtractionResult;
import io.bootify.my_app.service.ParsedDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gestisce i job di indicizzazione asincrona Docling lato Java.
 *
 * <p>Flusso per ogni job:
 * <ol>
 *   <li>Invia il file a Docling Python via {@code /parse/async} → ottiene pythonJobId</li>
 *   <li>Crea un job Java con stato QUEUED e avvia un thread di background</li>
 *   <li>Il thread di background esegue il polling di Python ogni 5s fino a DONE/ERROR</li>
 *   <li>Quando Python è DONE, converte il risultato e lo indicizza in Elasticsearch</li>
 *   <li>Aggiorna lo stato del job Java a DONE o ERROR</li>
 * </ol>
 *
 * <p>I job vengono mantenuti in memoria per {@value #JOB_TTL_MS} ms (5 ore) e poi rimossi.
 */
@Service
public class DoclingJobService {

    private static final Logger log = LoggerFactory.getLogger(DoclingJobService.class);

    /** Intervallo di polling verso il servizio Python (ms). */
    private static final int POLL_INTERVAL_MS = 5_000;

    /** Numero massimo di tentativi di polling: 30 minuti totali. */
    private static final int MAX_POLL_ATTEMPTS = 360;

    /** TTL dei job in memoria: 5 ore. */
    private static final long JOB_TTL_MS = 5L * 60 * 60 * 1000;

    private final Map<String, DoclingJobStatus> jobs = new ConcurrentHashMap<>();
    private final DoclingClient doclingClient;
    private final ParsedDocumentService parsedDocumentService;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    public DoclingJobService(DoclingClient doclingClient,
                              ParsedDocumentService parsedDocumentService) {
        this.doclingClient = doclingClient;
        this.parsedDocumentService = parsedDocumentService;

        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "docling-job-worker");
            t.setDaemon(true);
            return t;
        });

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "docling-job-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::cleanupOldJobs, 1, 1, TimeUnit.HOURS);
    }

    /**
     * Avvia il processo asincrono di parsing + indicizzazione per il file dato.
     *
     * @param file file da elaborare
     * @return jobId Java da usare per monitorare lo stato
     * @throws DoclingException se la submission al servizio Python fallisce
     */
    public String submitJob(MultipartFile file) {
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename() : "document";

        // Crea subito il record su H2 con stato PROCESSING (visibile all'utente)
        String pendingDocId = parsedDocumentService.createPending(fileName);

        // Invia a Python (bloccante ma veloce: solo submission, non elaborazione)
        String pythonJobId;
        try {
            pythonJobId = doclingClient.submitParseAsync(file);
        } catch (Exception e) {
            parsedDocumentService.markError(pendingDocId, "Errore submit a Docling: " + e.getMessage());
            throw e;
        }

        // Crea il job Java
        String javaJobId = UUID.randomUUID().toString();
        DoclingJobStatus job = new DoclingJobStatus();
        job.setJobId(javaJobId);
        job.setFileName(fileName);
        job.setStatus("QUEUED");
        job.setCreatedAt(System.currentTimeMillis());
        job.setUpdatedAt(System.currentTimeMillis());
        jobs.put(javaJobId, job);

        log.info("Job avviato: javaJobId={}, pythonJobId={}, pendingDocId={}, file={}",
                javaJobId, pythonJobId, pendingDocId, fileName);

        // Avvia il thread di background per il polling + indicizzazione
        Future<?> future = executor.submit(() -> processJob(javaJobId, pythonJobId, fileName, pendingDocId));
        // Loggare eccezioni non catturate che altrimenti sarebbero silenziate
        executor.submit(() -> {
            try {
                future.get();
            } catch (Exception ex) {
                log.error("Eccezione non catturata nel job {}: {}", javaJobId, ex.getMessage(), ex);
                DoclingJobStatus j = jobs.get(javaJobId);
                if (j != null && "QUEUED".equals(j.getStatus())) {
                    markError(j, "Errore interno: " + ex.getMessage());
                }
            }
        });

        return javaJobId;
    }

    /**
     * Recupera lo stato di un job per jobId Java.
     *
     * @param jobId jobId Java restituito da {@link #submitJob}
     * @return stato del job, o {@code null} se non trovato
     */
    public DoclingJobStatus getJob(String jobId) {
        return jobs.get(jobId);
    }

    /**
     * Restituisce tutti i job presenti in memoria.
     */
    public List<DoclingJobStatus> listJobs() {
        return new ArrayList<>(jobs.values());
    }

    // ── Background processing ────────────────────────────────────────────────

    private void processJob(String javaJobId, String pythonJobId, String fileName, String pendingDocId) {
        // Pulisce il flag interrupt in caso di riutilizzo di thread con stato inconsistente
        boolean wasInterrupted = Thread.interrupted();
        if (wasInterrupted) {
            log.warn("processJob: thread aveva flag interrupt settato per job {}, pulito", javaJobId);
        }
        log.info("processJob START: javaJobId={}, pythonJobId={}, thread={}",
                javaJobId, pythonJobId, Thread.currentThread().getName());

        DoclingJobStatus job = jobs.get(javaJobId);
        if (job == null) {
            log.error("processJob: job {} non trovato nella mappa! jobs.size()={}", javaJobId, jobs.size());
            return;
        }

        try {
            job.setStatus("PARSING");
            job.setUpdatedAt(System.currentTimeMillis());

            // Polling Python fino a completamento
            DocumentExtractionResult extracted = null;
            for (int attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    markError(job, pendingDocId, "Interrupted");
                    return;
                }

                DoclingPythonJobStatus pythonStatus;
                try {
                    pythonStatus = doclingClient.getPythonJobStatus(pythonJobId);
                } catch (DoclingException e) {
                    log.warn("Polling job {} fallito (tentativo {}): {}", javaJobId, attempt + 1, e.getMessage());
                    Thread.interrupted(); // pulisce flag interrupt se getPythonJobStatus ha wrappato InterruptedException
                    continue; // riprova al prossimo ciclo
                }

                String pythonStatusStr = pythonStatus.getStatus();
                if ("DONE".equals(pythonStatusStr)) {
                    DoclingParseResponse result = pythonStatus.getResult();
                    if (result == null) {
                        markError(job, pendingDocId, "Python DONE ma nessun risultato restituito");
                        return;
                    }
                    extracted = doclingClient.convertResult(result, fileName);
                    break;
                } else if ("ERROR".equals(pythonStatusStr)) {
                    String err = pythonStatus.getError() != null
                            ? pythonStatus.getError() : "Errore parsing Python";
                    markError(job, pendingDocId, err);
                    return;
                }
                // QUEUED o PROCESSING: continua il polling
            }

            if (extracted == null) {
                markError(job, pendingDocId, "Timeout: parsing non completato entro "
                        + (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000) + "s");
                return;
            }

            // Aggiorna il record H2 da PROCESSING a TRANSCRIBED con i dati estratti
            int sectionCount = extracted.getChapters() != null ? extracted.getChapters().size() : 0;
            parsedDocumentService.updateToTranscribed(pendingDocId, extracted);

            job.setStatus("DONE");
            job.setSections(sectionCount);
            job.setMessage("Trascrizione completata: " + sectionCount + " sezioni estratte. Documento pronto per revisione.");
            job.setUpdatedAt(System.currentTimeMillis());

            log.info("Job completato: javaJobId={}, parsedDocId={}, sezioni={}",
                    javaJobId, pendingDocId, sectionCount);

        } catch (Exception e) {
            log.error("Errore nel job {}: {}", javaJobId, e.getMessage(), e);
            markError(job, pendingDocId, e.getMessage());
        }
    }

    private void markError(DoclingJobStatus job, String pendingDocId, String error) {
        job.setStatus("ERROR");
        job.setError(error);
        job.setUpdatedAt(System.currentTimeMillis());
        log.error("Job {} fallito: {}", job.getJobId(), error);
        try {
            parsedDocumentService.markError(pendingDocId, error);
        } catch (Exception ex) {
            log.warn("Impossibile aggiornare stato ERROR su H2 per pendingDocId={}: {}", pendingDocId, ex.getMessage());
        }
    }

    private void markError(DoclingJobStatus job, String error) {
        job.setStatus("ERROR");
        job.setError(error);
        job.setUpdatedAt(System.currentTimeMillis());
        log.error("Job {} fallito: {}", job.getJobId(), error);
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    private void cleanupOldJobs() {
        long cutoff = System.currentTimeMillis() - JOB_TTL_MS;
        int removed = 0;
        for (Map.Entry<String, DoclingJobStatus> entry : jobs.entrySet()) {
            if (entry.getValue().getCreatedAt() < cutoff) {
                jobs.remove(entry.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Rimossi {} job scaduti (TTL 5h)", removed);
        }
    }
}
