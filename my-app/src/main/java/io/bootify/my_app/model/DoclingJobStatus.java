package io.bootify.my_app.model;

/**
 * Stato di un job di indicizzazione Docling lato Java.
 *
 * <p>Statuses:
 * <ul>
 *   <li>QUEUED   – inviato a Docling Python, in attesa di elaborazione</li>
 *   <li>PARSING  – Python sta elaborando il documento</li>
 *   <li>INDEXING – Python completato, indicizzazione in Elasticsearch in corso</li>
 *   <li>DONE     – completamente indicizzato e ricercabile</li>
 *   <li>ERROR    – fallito in uno degli stadi</li>
 * </ul>
 */
public class DoclingJobStatus {

    private String jobId;
    private String fileName;
    private String status;
    private String message;
    private Integer chunks;
    private Integer sections;
    private String error;
    private long createdAt;
    private long updatedAt;

    public DoclingJobStatus() {}

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Integer getChunks() { return chunks; }
    public void setChunks(Integer chunks) { this.chunks = chunks; }

    public Integer getSections() { return sections; }
    public void setSections(Integer sections) { this.sections = sections; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
