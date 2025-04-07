package com.example.videoeditor.dto;

import lombok.Data;

import java.util.*;

@Data
public class VideoSegment {
    private String sourceVideoPath;
    private double startTime;
    private double endTime;
    private String id;

    private Integer positionX = 0;
    private Integer positionY = 0;
    private Double scale = 1.0;

    private Integer layer = 0;
    private double timelineStartTime;
    private double timelineEndTime;

    // Keyframes for animatable properties
    private Map<String, List<Keyframe>> keyframes = new HashMap<>();
    public VideoSegment() {
        this.id = UUID.randomUUID().toString();
    }

    public Map<String, List<Keyframe>> getKeyframes() {
        return keyframes;
    }

    public void setKeyframes(Map<String, List<Keyframe>> keyframes) {
        this.keyframes = keyframes;
    }

    public void addKeyframe(String property, Keyframe keyframe) {
        List<Keyframe> propertyKeyframes = keyframes.computeIfAbsent(property, k -> new ArrayList<>());
        // Check if a keyframe already exists at this time
        propertyKeyframes.removeIf(kf -> Math.abs(kf.getTime() - keyframe.getTime()) < 0.0001); // Use epsilon for floating-point comparison
        propertyKeyframes.add(keyframe);
        propertyKeyframes.sort(Comparator.comparingDouble(Keyframe::getTime));
    }

    public void removeKeyframe(String property, double time) {
        List<Keyframe> propertyKeyframes = keyframes.get(property);
        if (propertyKeyframes != null) {
            propertyKeyframes.removeIf(kf -> Math.abs(kf.getTime() - time) < 0.0001); // Use epsilon for floating-point comparison
        }
    }

    // Existing getters and setters remain unchanged
    public String getSourceVideoPath() { return sourceVideoPath; }
    public void setSourceVideoPath(String sourceVideoPath) { this.sourceVideoPath = sourceVideoPath; }
    public double getStartTime() { return startTime; }
    public void setStartTime(double startTime) { this.startTime = startTime; }
    public double getEndTime() { return endTime; }
    public void setEndTime(double endTime) { this.endTime = endTime; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Integer getPositionX() { return positionX; }
    public void setPositionX(Integer positionX) { this.positionX = positionX; }
    public Integer getPositionY() { return positionY; }
    public void setPositionY(Integer positionY) { this.positionY = positionY; }
    public Double getScale() { return scale; }
    public void setScale(Double scale) { this.scale = scale; }
    public Integer getLayer() { return layer; }
    public void setLayer(Integer layer) { this.layer = layer; }
    public double getTimelineStartTime() { return timelineStartTime; }
    public void setTimelineStartTime(double timelineStartTime) { this.timelineStartTime = timelineStartTime; }
    public double getTimelineEndTime() { return timelineEndTime; }
    public void setTimelineEndTime(double timelineEndTime) { this.timelineEndTime = timelineEndTime; }
}