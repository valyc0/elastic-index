package io.bootify.my_app.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Risposta di stato di un job asincrono dal microservizio Python Docling.
 * Mappa la struttura JSON di {@code GET /jobs/{jobId}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DoclingPythonJobStatus {

    @JsonProperty("jobId")
    private String jobId;

    @JsonProperty("fileName")
    private String fileName;

    @JsonProperty("status")
    private String status;

    @JsonProperty("error")
    private String error;

    /** Risultato completo del parsing; presente solo quando {@code status == "DONE"}. */
    @JsonProperty("result")
    private DoclingParseResponse result;

    public DoclingPythonJobStatus() {}

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public DoclingParseResponse getResult() { return result; }
    public void setResult(DoclingParseResponse result) { this.result = result; }
}
