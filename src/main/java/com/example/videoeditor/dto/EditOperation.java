package com.example.videoeditor.dto;

import lombok.Data;

import java.util.Map;

@Data
public class EditOperation {
    private String operationType; // SPLIT, MERGE, TRIM
    private Map<String, Object> parameters;
    private String sourceVideoPath;

    public String getOperationType() {
        return operationType;
    }

    public void setOperationType(String operationType) {
        this.operationType = operationType;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public String getSourceVideoPath() {
        return sourceVideoPath;
    }

    public void setSourceVideoPath(String sourceVideoPath) {
        this.sourceVideoPath = sourceVideoPath;
    }
}