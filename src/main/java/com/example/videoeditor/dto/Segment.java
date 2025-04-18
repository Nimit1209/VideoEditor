package com.example.videoeditor.dto;

public interface Segment {
    String getId();
    Integer getLayer();
    double getTimelineStartTime();
    double getTimelineEndTime();


}