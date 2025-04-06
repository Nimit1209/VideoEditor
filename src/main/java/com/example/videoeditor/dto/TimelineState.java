package com.example.videoeditor.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class TimelineState {
    private List<VideoSegment> segments;
    private List<TextSegment> textSegments;
    private Map<String, Object> metadata;
    private List<ImageSegment> imageSegments = new ArrayList<>();
    private List<AudioSegment> audioSegments = new ArrayList<>();
    // ADDED: Top-level filters list
    private List<Filter> filters = new ArrayList<>();
    private Integer canvasWidth;
    private Integer canvasHeight;

    public TimelineState() {
        this.segments = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.textSegments = new ArrayList<>();
    }

    // ADDED: Getter and Setter for filters
    public List<Filter> getFilters() {
        if (filters == null) {
            filters = new ArrayList<>();
        }
        return filters;
    }

    public void setFilters(List<Filter> filters) {
        this.filters = filters != null ? new ArrayList<>(filters) : new ArrayList<>();
    }

    // Existing getters and setters
    public List<AudioSegment> getAudioSegments() {
        if (audioSegments == null) {
            audioSegments = new ArrayList<>();
        }
        return audioSegments;
    }

    public void setAudioSegments(List<AudioSegment> audioSegments) {
        this.audioSegments = audioSegments;
    }

    public List<ImageSegment> getImageSegments() {
        return imageSegments;
    }

    public void setImageSegments(List<ImageSegment> imageSegments) {
        this.imageSegments = imageSegments;
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

    public List<VideoSegment> getSegments() {
        if (segments == null) {
            segments = new ArrayList<>();
        }
        return segments;
    }

    public void setSegments(List<VideoSegment> segments) {
        this.segments = segments;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
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

    // Existing methods
    public List<VideoSegment> getSegmentsByLayer(int layer) {
        List<VideoSegment> layerSegments = new ArrayList<>();
        for (VideoSegment segment : segments) {
            if (segment.getLayer() == layer) {
                layerSegments.add(segment);
            }
        }
        return layerSegments;
    }

    public int getMaxLayer() {
        int maxLayer = 0;
        for (VideoSegment segment : segments) {
            if (segment.getLayer() > maxLayer) {
                maxLayer = segment.getLayer();
            }
        }
        return maxLayer;
    }

    public boolean isTimelinePositionAvailable(double timelineStartTime, double timelineEndTime, int layer) {
        for (VideoSegment segment : segments) {
            if (segment.getLayer() == layer) {
                if (timelineStartTime < segment.getTimelineEndTime() && timelineEndTime > segment.getTimelineStartTime()) {
                    return false;
                }
            }
        }
        for (AudioSegment segment : audioSegments) {
            if (segment.getLayer() == layer && layer < 0) {
                if (timelineStartTime < segment.getTimelineEndTime() && timelineEndTime > segment.getTimelineStartTime()) {
                    return false;
                }
            }
        }
        return true;
    }
}