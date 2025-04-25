package com.example.videoeditor.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Data
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String status; // DRAFT, PUBLISHED

    @Column(nullable = false)
    private LocalDateTime lastModified;

    @Column(columnDefinition = "TEXT")
    private String timelineState; // JSON string of editing state

    private Integer width;
    private Integer height;

    @Column(nullable = false, columnDefinition = "FLOAT DEFAULT 25.0")
    private Float fps = 25.0f; // New field for FPS with default 25

    private String exportedVideoPath;

    public String getExportedVideoPath() {
        return exportedVideoPath;
    }

    //    Videos Column
    @Column(columnDefinition = "TEXT")
    private String videosJson; // Stores a JSON array of video data

    // Image column
    @Column(columnDefinition = "TEXT")
    private String imagesJson; // Stores a JSON array of image data


    //    Audio column
    @Column(columnDefinition = "TEXT")
    private String audioJson; // Stores a JSON array of audio data

    @Column(columnDefinition = "TEXT")
    private String extractedAudioJson;

    // New field for elements
    @Column(name = "element_json", columnDefinition = "TEXT")
    private String elementJson;

    // Getters and setters
    public String getElementJson() {
        return elementJson;
    }

    public void setElementJson(String elementJson) {
        this.elementJson = elementJson;
    }

    public String getExtractedAudioJson() {
        return extractedAudioJson;
    }

    public void setExtractedAudioJson(String extractedAudioJson) {
        this.extractedAudioJson = extractedAudioJson;
    }

    public void setExportedVideoPath(String exportedVideoPath) {
        this.exportedVideoPath = exportedVideoPath;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }

    public String getTimelineState() {
        return timelineState;
    }

    public void setTimelineState(String timelineState) {
        this.timelineState = timelineState;
    }

    public String getVideosJson() {
        return videosJson;
    }

    public void setVideosJson(String videosJson) {
        this.videosJson = videosJson;
    }

    public String getImagesJson() {
        return imagesJson;
    }

    public void setImagesJson(String imagesJson) {
        this.imagesJson = imagesJson;
    }

    public String getAudioJson() {
        return audioJson;
    }

    public void setAudioJson(String audioJson) {
        this.audioJson = audioJson;
    }

    public Float getFps() {
        return fps;
    }

    public void setFps(Float fps) {
        this.fps = fps;
    }
}