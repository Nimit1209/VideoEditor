package com.example.videoeditor.dto;

import lombok.Data;

import java.util.*;

@Data
public class TextSegment {
    private String id = UUID.randomUUID().toString();
    private String text;
    private String fontFamily = "ARIAL";
    private int fontSize = 24;
    private String fontColor = "white";
    private String backgroundColor = "transparent";
    private Integer positionX = 0;
    private Integer positionY = 0;
    private Double opacity = 1.0; // Added opacity with default value 1.0
    private double timelineStartTime;
    private double timelineEndTime;
    private int layer = 0;

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
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }
    public int getFontSize() { return fontSize; }
    public void setFontSize(int fontSize) { this.fontSize = fontSize; }
    public String getFontColor() { return fontColor; }
    public void setFontColor(String fontColor) { this.fontColor = fontColor; }
    public String getBackgroundColor() { return backgroundColor; }
    public void setBackgroundColor(String backgroundColor) { this.backgroundColor = backgroundColor; }
    public Integer getPositionX() { return positionX; }
    public void setPositionX(Integer positionX) { this.positionX = positionX; }
    public Integer getPositionY() { return positionY; }
    public void setPositionY(Integer positionY) { this.positionY = positionY; }
    public Double getOpacity() { return opacity; } // Added getter
    public void setOpacity(Double opacity) { this.opacity = opacity; } // Added setter
    public double getTimelineStartTime() { return timelineStartTime; }
    public void setTimelineStartTime(double timelineStartTime) { this.timelineStartTime = timelineStartTime; }
    public double getTimelineEndTime() { return timelineEndTime; }
    public void setTimelineEndTime(double timelineEndTime) { this.timelineEndTime = timelineEndTime; }
    public int getLayer() { return layer; }
    public void setLayer(int layer) { this.layer = layer; }
}