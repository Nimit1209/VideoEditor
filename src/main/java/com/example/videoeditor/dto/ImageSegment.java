package com.example.videoeditor.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class ImageSegment {
    private String id;
    private String imagePath;
    private int layer;
    private int positionX;
    private int positionY;
    private double scale;
    private double opacity = 1.0;
    private double timelineStartTime;
    private double timelineEndTime;
    private int width;
    private int height;

    public ImageSegment(String id, String imagePath, int layer, int positionX, int positionY, double scale, double opacity, double timelineStartTime, double timelineEndTime, int width, int height) {
        this.id = id;
        this.imagePath = imagePath;
        this.layer = layer;
        this.positionX = positionX;
        this.positionY = positionY;
        this.scale = scale;
        this.opacity = opacity;
        this.timelineStartTime = timelineStartTime;
        this.timelineEndTime = timelineEndTime;
        this.width = width;
        this.height = height;
    }

    public ImageSegment() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }

    public int getLayer() {
        return layer;
    }

    public void setLayer(int layer) {
        this.layer = layer;
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

    public double getOpacity() {
        return opacity;
    }

    public void setOpacity(double opacity) {
        this.opacity = opacity;
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

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }
}

