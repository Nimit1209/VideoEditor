package com.example.videoeditor.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;


public class TimelineState {
    private List<VideoSegment> segments;
    private List<TextSegment> textSegments;
    private Map<String, Object> metadata;
    private List<ImageSegment> imageSegments = new ArrayList<>();
    private List<AudioSegment> audioSegments = new ArrayList<>();

    public List<AudioSegment> getAudioSegments() {
        return audioSegments;
    }

    public void setAudioSegments(List<AudioSegment> audioSegments) {
        this.audioSegments = audioSegments;
    }

    //FOR IMAGE .............................................................................
    public List<ImageSegment> getImageSegments() {
        return imageSegments;
    }

    public void setImageSegments(List<ImageSegment> imageSegments) {
        this.imageSegments = imageSegments;
    }
    //................................................................................
    // Assuming this is in a file like TimelineState.java
    private Integer canvasWidth;
    private Integer canvasHeight;

    public TimelineState() {
        this.segments = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.textSegments = new ArrayList<>();
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
    public Map<String, Object> getMetadata() {
        return metadata;
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

    // Add a method to validate timeline positions
    public boolean isTimelinePositionAvailable(double timelineStartTime, double timelineEndTime, int layer) {
        for (VideoSegment segment : segments) {
            if (segment.getLayer() == layer) {
                // Check for overlap
                if (timelineStartTime < segment.getTimelineEndTime() && timelineEndTime > segment.getTimelineStartTime()) {
                    return false; // Overlap detected
                }
            }
        }
        return true; // No overlap
    }

}