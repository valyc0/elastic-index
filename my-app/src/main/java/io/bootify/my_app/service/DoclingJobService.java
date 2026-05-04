package io.bootify.my_app.service;

import io.bootify.my_app.model.DoclingJobStatus;
import io.bootify.my_app.model.DoclingParseResponse;
import io.bootify.my_app.model.DoclingPythonJobStatus;
import io.bootify.my_app.model.DocumentExtractionResult;
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
    private final SemanticIndexService semanticIndexService;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;

    public DoclingJobService(DoclingClient doclingClient,
                              SemanticIndexService semanticIndexService) {
        this.doclingClient = doclingClient;
        this.semanticIndexService = semanticIndexService;

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

        // Invia a Python (bloccante ma veloce: solo submission, non elaborazione)
        String pythonJobId = doclingClient.submitParseAsync(file);

        // Crea il job Java
        String javaJobId = UUID.randomUUID().toString();
        DoclingJobStatus job = new DoclingJobStatus();
        job.setJobId(javaJobId);
        job.setFileName(fileName);
        job.setStatus("QUEUED");
        job.setCreatedAt(System.currentTimeMillis());
        job.setUpdatedAt(System.currentTimeMillis());
        jobs.put(javaJobId, job);

        log.info("Job avviato: javaJobId={}, pythonJobId={}, file={}", javaJobId, pythonJobId, fileName);

        // Avvia il thread di background per il polling + indicizzazione
        Future<?> future = executor.submit(() -> processJob(javaJobId, pythonJobId, fileName));
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

    private void processJob(String javaJobId, String pythonJobId, String fileName) {
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
                    markError(job, "Interrupted");
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
                        markError(job, "Python DONE ma nessun risultato restituito");
                        return;
                    }
                    extracted = doclingClient.convertResult(result, fileName);
                    break;
                } else if ("ERROR".equals(pythonStatusStr)) {
                    String err = pythonStatus.getError() != null
                            ? pythonStatus.getError() : "Errore parsing Python";
                    markError(job, err);
                    return;
                }
                // QUEUED o PROCESSING: continua il polling
            }

            if (extracted == null) {
                markError(job, "Timeout: parsing non completato entro "
                        + (MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000) + "s");
                return;
            }

            // Indicizzazione in Elasticsearch
            job.setStatus("INDEXING");
            job.setUpdatedAt(System.currentTimeMillis());

            String documentId = UUID.randomUUID().toString();
            int chunks = semanticIndexService.indexDocument(documentId, extracted);
            int sectionCount = extracted.getChapters() != null ? extracted.getChapters().size() : 0;

            job.setStatus("DONE");
            job.setChunks(chunks);
            job.setSections(sectionCount);
            job.setMessage("Documento indicizzato: " + chunks + " chunks da " + sectionCount + " sezioni");
            job.setUpdatedAt(System.currentTimeMillis());

            log.info("Job completato: javaJobId={}, documentId={}, chunks={}, sezioni={}",
                    javaJobId, documentId, chunks, sectionCount);

        } catch (Exception e) {
            log.error("Errore nel job {}: {}", javaJobId, e.getMessage(), e);
            markError(job, e.getMessage());
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
