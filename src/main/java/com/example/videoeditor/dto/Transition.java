package com.example.videoeditor.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class Transition {
    private String id = UUID.randomUUID().toString();
    private String type; // e.g., "fade", "dissolve", "wipe"
    private double duration; // in seconds
    private String fromSegmentId; // ID of the first segment
    private String toSegmentId; // ID of the second segment
    private int layer; // Layer where the transition occurs
    private double timelineStartTime; // When the transition starts on the timeline
    private Map<String, String> parameters = new HashMap<>(); // Additional settings, e.g., {"direction": "left"}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public double getDuration() {
        return duration;
    }

    public void setDuration(double duration) {
        this.duration = duration;
    }

    public String getFromSegmentId() {
        return fromSegmentId;
    }

    public void setFromSegmentId(String fromSegmentId) {
        this.fromSegmentId = fromSegmentId;
    }

    public String getToSegmentId() {
        return toSegmentId;
    }

    public void setToSegmentId(String toSegmentId) {
        this.toSegmentId = toSegmentId;
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

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }
}