package com.example.videoeditor.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class AudioSegment {
    private String id = UUID.randomUUID().toString();
    private String audioPath;
    private int layer; // Will be negative (-1, -2, -3, etc.)
    private double startTime; // Start time within the audio file
    private double endTime;   // End time within the audio file
    private double timelineStartTime; // Position in timeline
    private double timelineEndTime;   // End position in timeline
    private double volume = 1.0; // Volume level (0.0 to 1.0)

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAudioPath() {
        return audioPath;
    }

    public void setAudioPath(String audioPath) {
        this.audioPath = audioPath;
    }

    public int getLayer() {
        return layer;
    }

    public void setLayer(int layer) {
        this.layer = layer;
    }

    public double getStartTime() {
        return startTime;
    }

    public void setStartTime(double startTime) {
        this.startTime = startTime;
    }

    public double getEndTime() {
        return endTime;
    }

    public void setEndTime(double endTime) {
        this.endTime = endTime;
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

    public double getVolume() {
        return volume;
    }

    public void setVolume(double volume) {
        this.volume = volume;
    }
}