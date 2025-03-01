package com.example.videoeditor.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class VideoSegment {
    private String id;  // Unique identifier for tracking this segment
    private String sourceVideoPath;
    private double startTime;
    private double endTime; // -1 for full duration


    public VideoSegment(){
        this.id= UUID.randomUUID().toString();
    }
    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSourceVideoPath() {
        return sourceVideoPath;
    }

    public void setSourceVideoPath(String sourceVideoPath) {
        this.sourceVideoPath = sourceVideoPath;
    }

    public double getStartTime() {
        return startTime;
    }

    public void setStartTime(double startTime) {
        this.startTime = startTime;
    }

    public double getEndTime() {
        return endTime;
    }

    public void setEndTime(double endTime) {
        this.endTime = endTime;
    }
}