package com.example.videoeditor.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

public class TimelineState {
    private List<VideoSegment> segments;
    private List<TextSegment> textSegments;
    private Map<String, Object> metadata;
    private Long lastModified;
    private List<AudioSegment> audioSegments = new ArrayList<>();
    private List<ImageSegment> imageSegments = new ArrayList<>();
    private List<Filter> filters = new ArrayList<>();
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    public List<ImageSegment> getImageSegments() {
        return imageSegments;
    }
    private Integer canvasWidth;
    private Integer canvasHeight;

    public void setImageSegments(List<ImageSegment> imageSegments) {
        this.imageSegments = imageSegments;
    }

    public TimelineState() {
        this.segments = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.textSegments = new ArrayList<>();
    }

    public List<Filter> getFilters() {
        return filters;
    }

    public void setFilters(List<Filter> filters) {
        this.filters = filters;
    }

    public Integer getCanvasWidth() {
        return canvasWidth;
    }

    public void setCanvasWidth(Integer canvasWidth) {
        this.canvasWidth = canvasWidth;
    }

    public Integer getCanvasHeight() {
        return canvasHeight;
    }

    public void setCanvasHeight(Integer canvasHeight) {
        this.canvasHeight = canvasHeight;
    }

    public List<TextSegment> getTextSegments() {
        if (textSegments == null) {
            textSegments = new ArrayList<>();
        }
        return textSegments;
    }

    public void setTextSegments(List<TextSegment> textSegments) {
        this.textSegments = textSegments;
    }

    // Getters and setters
    public List<VideoSegment> getSegments() {
        if (segments == null) {
            segments = new ArrayList<>();
        }
        return segments;
    }

    public void setSegments(List<VideoSegment> segments) {
        this.segments = segments;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    // Add a method to get segments by layer
    public List<VideoSegment> getSegmentsByLayer(int layer) {
        List<VideoSegment> layerSegments = new ArrayList<>();
        for (VideoSegment segment : segments) {
            if (segment.getLayer() == layer) {
                layerSegments.add(segment);
            }
        }
        return layerSegments;
    }

    // Add a method to get the maximum layer in the timeline
    public int getMaxLayer() {
        int maxLayer = 0;
        for (VideoSegment segment : segments) {
            if (segment.getLayer() > maxLayer) {
                maxLayer = segment.getLayer();
            }
        }
        return maxLayer;
    }

    // Add this method to your TimelineState class
    public boolean isTimelinePositionAvailable(double startTime, double endTime, int layer) {
        // Check for video segment overlaps
        for (VideoSegment segment : segments) {
            if (segment.getLayer() == layer &&
                    startTime < segment.getTimelineEndTime() &&
                    endTime > segment.getTimelineStartTime()) {
                return false;
            }
        }

        // Check for text segment overlaps
        for (TextSegment segment : textSegments) {
            if (segment.getLayer() == layer &&
                    startTime < segment.getTimelineEndTime() &&
                    endTime > segment.getTimelineStartTime()) {
                return false;
            }
        }

        // Check audio segments (negative layers)
        for (AudioSegment segment : audioSegments) {
            if (segment.getLayer() == layer && layer < 0) {
                if (startTime < segment.getTimelineEndTime() &&
                        endTime > segment.getTimelineStartTime()) {
                    return false;
                }
            }
        }
        return true; // No overlap
    }

    public Long getLastModified() {
        return lastModified;
    }

    public void setLastModified(Long lastModified) {
        this.lastModified = lastModified;
    }

    public List<AudioSegment> getAudioSegments() {
        if (audioSegments == null) {
            audioSegments = new ArrayList<>();
        }
        return audioSegments;
    }

    public void setAudioSegments(List<AudioSegment> audioSegments) {
        this.audioSegments = audioSegments;
    }

    // ADDED: Method to sync legacyFilters for all segments
    public void syncLegacyFilters() {
        // Clear existing legacyFilters
        for (VideoSegment segment : getSegments()) {
            if (segment.getFiltersAsMap() == null) {
                segment.setFiltersAsMap(new HashMap<>());
            } else {
                segment.getFiltersAsMap().clear();
            }
        }
        for (ImageSegment segment : getImageSegments()) {
            if (segment.getFiltersAsMap() == null) {
                segment.setFiltersAsMap(new HashMap<>());
            } else {
                segment.getFiltersAsMap().clear();
            }
        }

        // Populate legacyFilters from top-level filters
        for (Filter filter : getFilters()) {
            String filterValue = filter.getFilterValue() != null ? filter.getFilterValue() : "";
            for (VideoSegment segment : getSegments()) {
                if (segment.getId().equals(filter.getSegmentId())) {
                    segment.getFiltersAsMap().put(filter.getFilterName(), filterValue);
                }
            }
            for (ImageSegment segment : getImageSegments()) {
                if (segment.getId().equals(filter.getSegmentId())) {
                    segment.getFiltersAsMap().put(filter.getFilterName(), filterValue);
                }
            }
        }
    }
}