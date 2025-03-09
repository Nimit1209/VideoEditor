package com.example.videoeditor.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class TimelineState {
    private List<VideoSegment> segments;
    private List<EditOperation> operations;
    private Map<String, Object> metadata;

    // Assuming this is in a file like TimelineState.java
    private Integer canvasWidth;
    private Integer canvasHeight;

    public TimelineState() {
        this.segments = new ArrayList<>();
        this.operations = new ArrayList<>();
        this.metadata = new HashMap<>();
    }

    public Integer getCanvasWidth() {
        return canvasWidth;
    }

    public void setCanvasWidth(Integer canvasWidth) {
        this.canvasWidth = canvasWidth;
    }

    public Integer getCanvasHeight() {
        return canvasHeight;
    }

    public void setCanvasHeight(Integer canvasHeight) {
        this.canvasHeight = canvasHeight;
    }

    // Getters and setters
    public List<VideoSegment> getSegments() {
        if (segments == null) {
            segments = new ArrayList<>();
        }
        return segments;
    }

    public void setSegments(List<VideoSegment> segments) {
        this.segments = segments;
    }

    public List<EditOperation> getOperations() {
        if (operations == null) {
            operations = new ArrayList<>();
        }
        return operations;
    }

    public void setOperations(List<EditOperation> operations) {
        this.operations = operations;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

}