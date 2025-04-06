package com.example.videoeditor.dto;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class VideoSegment {
    private String id = UUID.randomUUID().toString();
    private String sourceVideoPath;
    private double startTime;
    private double endTime;
    private int positionX;
    private int positionY;
    private double scale;
    private int layer;
    private double timelineStartTime;
    private double timelineEndTime;
    private Map<String, String> filtersAsMap = new HashMap<>(); // Revert to Map<String, String>

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

    public int getPositionX() {
        return positionX;
    }

    public void setPositionX(int positionX) {
        this.positionX = positionX;
    }

    public int getPositionY() {
        return positionY;
    }

    public void setPositionY(int positionY) {
        this.positionY = positionY;
    }

    public double getScale() {
        return scale;
    }

    public void setScale(double scale) {
        this.scale = scale;
    }

    public int getLayer() {
        return layer;
    }

    public void setLayer(int layer) {
        this.layer = layer;
    }

    public double getTimelineStartTime() {
        return timelineStartTime;
    }

    public void setTimelineStartTime(double timelineStartTime) {
        this.timelineStartTime = timelineStartTime;
    }

    public double getTimelineEndTime() {
        return timelineEndTime;
    }

    public void setTimelineEndTime(double timelineEndTime) {
        this.timelineEndTime = timelineEndTime;
    }

    public Map<String, String> getFiltersAsMap() {
        return filtersAsMap;
    }

    public void setFiltersAsMap(Map<String, String> filtersAsMap) {
        this.filtersAsMap = filtersAsMap;
    }
}