package com.example.videoeditor.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private String exportedVideoPath;

    // Change from single image to list of images
    @Column(columnDefinition = "TEXT")
    private String imagesJson; // Stores a JSON array of image data

    // Getters and setters
    public List<Map<String, String>> getImages() throws JsonProcessingException {
        if (imagesJson == null || imagesJson.isEmpty()) {
            return new ArrayList<>();
        }
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(imagesJson, new TypeReference<List<Map<String, String>>>() {});
    }

    public void addImage(String imagePath, String imageFileName) throws JsonProcessingException {
        List<Map<String, String>> images = getImages();
        Map<String, String> imageData = new HashMap<>();
        imageData.put("imagePath", imagePath);
        imageData.put("imageFileName", imageFileName);
        images.add(imageData);
        ObjectMapper mapper = new ObjectMapper();
        this.imagesJson = mapper.writeValueAsString(images);
    }

    @Column(columnDefinition = "TEXT")
    private String audioJson; // Stores a JSON array of audio data

    public List<Map<String, String>> getAudio() throws JsonProcessingException {
        if (audioJson == null || audioJson.isEmpty()) {
            return new ArrayList<>();
        }
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(audioJson, new TypeReference<List<Map<String, String>>>() {});
    }

    public void addAudio(String audioPath, String audioFileName) throws JsonProcessingException {
        List<Map<String, String>> audioFiles = getAudio();
        Map<String, String> audioData = new HashMap<>();
        audioData.put("audioPath", audioPath);
        audioData.put("audioFileName", audioFileName);
        audioFiles.add(audioData);
        ObjectMapper mapper = new ObjectMapper();
        this.audioJson = mapper.writeValueAsString(audioFiles);
    }

    public String getExportedVideoPath() {
        return exportedVideoPath;
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

}