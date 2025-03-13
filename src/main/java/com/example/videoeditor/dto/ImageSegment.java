package com.example.videoeditor.dto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;
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
    // New properties for filters and size adjustment
    private int customWidth; // For custom width resizing
    private int customHeight; // For custom height resizing
    private boolean maintainAspectRatio = true; // Default to maintain aspect ratio
    private Map<String, String> filters = new HashMap<>(); // Store filter types and their values

    // Existing getters and setters

    // New getters and setters
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

    public Map<String, String> getFilters() {
        return filters;
    }

    public void setFilters(Map<String, String> filters) {
        this.filters = filters;
    }

    public void addFilter(String filterType, String filterValue) {
        this.filters.put(filterType, filterValue);
    }

    public void removeFilter(String filterType) {
        this.filters.remove(filterType);
    }

    // Helper method to calculate effective width for rendering
    public int getEffectiveWidth() {
        if (customWidth > 0) {
            return customWidth;
        }
        return (int) (width * scale);
    }

    // Helper method to calculate effective height for rendering
    public int getEffectiveHeight() {
        if (customHeight > 0) {
            return customHeight;
        }
        return (int) (height * scale);
    }

    public ImageSegment(String id, String imagePath, int layer, int positionX, int positionY, double scale, double opacity, double timelineStartTime, double timelineEndTime, int width, int height, int customWidth, int customHeight, boolean maintainAspectRatio, Map<String, String> filters) {
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
        this.customWidth = customWidth;
        this.customHeight = customHeight;
        this.maintainAspectRatio = maintainAspectRatio;
        this.filters = filters;
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

