package com.example.videoeditor.dto;

import lombok.Data;

import java.util.*;

@Data
public class ImageSegment {
    private String id = UUID.randomUUID().toString();
    private String imagePath;
    private int layer;
    private Integer positionX = 0;
    private Integer positionY = 0;
    private Double scale = 1.0;
    private Double opacity = 1.0; // Already present, just ensuring consistency
    private double timelineStartTime;
    private double timelineEndTime;
    private int width;
    private int height;
    private int customWidth;
    private int customHeight;
    private boolean maintainAspectRatio = true;

    private Map<String, List<Keyframe>> keyframes = new HashMap<>();

    public Map<String, List<Keyframe>> getKeyframes() {
        return keyframes;
    }

    public void setKeyframes(Map<String, List<Keyframe>> keyframes) {
        this.keyframes = keyframes;
    }

    public void addKeyframe(String property, Keyframe keyframe) {
        List<Keyframe> propertyKeyframes = keyframes.computeIfAbsent(property, k -> new ArrayList<>());
        propertyKeyframes.removeIf(kf -> Math.abs(kf.getTime() - keyframe.getTime()) < 0.0001);
        propertyKeyframes.add(keyframe);
        propertyKeyframes.sort(Comparator.comparingDouble(Keyframe::getTime));
    }

    public void removeKeyframe(String property, double time) {
        List<Keyframe> propertyKeyframes = keyframes.get(property);
        if (propertyKeyframes != null) {
            propertyKeyframes.removeIf(kf -> Math.abs(kf.getTime() - time) < 0.0001);
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getImagePath() { return imagePath; }
    public void setImagePath(String imagePath) { this.imagePath = imagePath; }
    public int getLayer() { return layer; }
    public void setLayer(int layer) { this.layer = layer; }
    public Integer getPositionX() { return positionX; }
    public void setPositionX(Integer positionX) { this.positionX = positionX; }
    public Integer getPositionY() { return positionY; }
    public void setPositionY(Integer positionY) { this.positionY = positionY; }
    public Double getScale() { return scale; }
    public void setScale(Double scale) { this.scale = scale; }
    public Double getOpacity() { return opacity; }
    public void setOpacity(Double opacity) { this.opacity = opacity; }
    public double getTimelineStartTime() { return timelineStartTime; }
    public void setTimelineStartTime(double timelineStartTime) { this.timelineStartTime = timelineStartTime; }
    public double getTimelineEndTime() { return timelineEndTime; }
    public void setTimelineEndTime(double timelineEndTime) { this.timelineEndTime = timelineEndTime; }
    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }
    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
    public int getCustomWidth() { return customWidth; }
    public void setCustomWidth(int customWidth) { this.customWidth = customWidth; }
    public int getCustomHeight() { return customHeight; }
    public void setCustomHeight(int customHeight) { this.customHeight = customHeight; }
    public boolean isMaintainAspectRatio() { return maintainAspectRatio; }
    public void setMaintainAspectRatio(boolean maintainAspectRatio) { this.maintainAspectRatio = maintainAspectRatio; }
    public int getEffectiveWidth() { return customWidth > 0 ? customWidth : (int) (width * (scale != null ? scale : 1.0)); }
    public int getEffectiveHeight() { return customHeight > 0 ? customHeight : (int) (height * (scale != null ? scale : 1.0)); }
}