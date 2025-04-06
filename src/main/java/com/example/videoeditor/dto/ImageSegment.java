package com.example.videoeditor.dto;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
public class ImageSegment {
    private String id = UUID.randomUUID().toString();
    private String imagePath;
    private int layer;
    private int positionX;
    private int positionY;
    private double scale = 1.0;
    private double opacity = 1.0;
    private int width;
    private int height;
    private int customWidth;
    private int customHeight;
    private boolean maintainAspectRatio = true;
    private double timelineStartTime;
    private double timelineEndTime;
    private Map<String, String> filtersAsMap = new HashMap<>();

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

    public int getCustomWidth() {
        return customWidth;
    }

    public void setCustomWidth(int customWidth) {
        this.customWidth = customWidth;
    }

    public int getCustomHeight() {
        return customHeight;
    }

    public void setCustomHeight(int customHeight) {
        this.customHeight = customHeight;
    }

    public boolean isMaintainAspectRatio() {
        return maintainAspectRatio;
    }

    public void setMaintainAspectRatio(boolean maintainAspectRatio) {
        this.maintainAspectRatio = maintainAspectRatio;
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