package com.example.videoeditor.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class VideoSegment {
    private String sourceVideoPath;
    private double startTime;
    private double endTime;
    private String id;

    private Integer positionX = 0;
    private Integer positionY = 0;
    private Double scale = 1.0;

    public Integer getPositionX() {
        return positionX;
    }

    public void setPositionX(Integer positionX) {
        this.positionX = positionX;
    }

    public Integer getPositionY() {
        return positionY;
    }

    public void setPositionY(Integer positionY) {
        this.positionY = positionY;
    }

    public Double getScale() {
        return scale;
    }

    public void setScale(Double scale) {
        this.scale = scale;
    }

    public VideoSegment() {
        this.id = UUID.randomUUID().toString(); // Generate unique ID on creation
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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}