package com.example.videoeditor.dto;


import lombok.Data;

import java.util.UUID;

@Data
public class TextSegment {
    private String id;
    private String text;
    private String fontFamily = "ARIAL";
    private int fontSize = 24;
    private String fontColor = "white";
    private String backgroundColor = "transparent";
    private int positionX = 0;
    private int positionY = 0;
    private double timelineStartTime; // Start time of the text in the timeline (in seconds)
    private double timelineEndTime;   // End time of the text in the timeline (in seconds)
    private int layer = 0; // Layer of the text (for multi-level timelines)

    public TextSegment() {
        this.id = UUID.randomUUID().toString(); // Generate unique ID on creation
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = fontSize;
    }

    public String getFontColor() {
        return fontColor;
    }

    public void setFontColor(String fontColor) {
        this.fontColor = fontColor;
    }

    public String getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(String backgroundColor) {
        this.backgroundColor = backgroundColor;
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

    public int getLayer() {
        return layer;
    }

    public void setLayer(int layer) {
        this.layer = layer;
    }
}