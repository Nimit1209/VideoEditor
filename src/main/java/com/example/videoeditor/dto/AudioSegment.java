package com.example.videoeditor.dto;

import java.util.HashMap;
import java.util.Map;

public class AudioSegment {
    private String id;
    private String audioPath;
    private int layer;
    private double timelineStartTime;
    private double timelineEndTime;
    private double startTime; // Starting point in the original audio file
    private double volume = 1.0; // Default volume (0.0 to 1.0)
    private Map<String, Object> filters = new HashMap<>(); // Initialize the filters map

    // Getters and setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAudioPath() {
        return audioPath;
    }

    public void setAudioPath(String audioPath) {
        this.audioPath = audioPath;
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

    public double getStartTime() {
        return startTime;
    }

    public void setStartTime(double startTime) {
        this.startTime = startTime;
    }

    public double getVolume() {
        return volume;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }

    public Map<String, Object> getFilters() {
        return filters;
    }

    public void setFilters(Map<String, Object> filters) {
        this.filters = filters;
    }

    // Add a filter to the filters map
    public void addFilter(String key, Object value) {
        if (filters == null) {
            filters = new HashMap<>();
        }
        filters.put(key, value);
    }

    // Remove a filter from the filters map
    public void removeFilter(String key) {
        if (filters != null) {
            filters.remove(key);
        }
    }
}