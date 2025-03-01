package com.example.videoeditor.dto;

import lombok.Data;

@Data
public class FilterRequest {
    private String videoPath;
    private String segmentId;  // Add this field
    private String filter;

    // Getters and setters
    public String getVideoPath() {
        return videoPath;
    }

    public void setVideoPath(String videoPath) {
        this.videoPath = videoPath;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public void setSegmentId(String segmentId) {
        this.segmentId = segmentId;
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }
}