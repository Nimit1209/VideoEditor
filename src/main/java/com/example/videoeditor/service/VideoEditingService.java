package com.example.videoeditor.service;

import com.example.videoeditor.entity.Project;
import com.example.videoeditor.dto.*;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.EditedVideoRepository;
import com.example.videoeditor.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

@Service
public class VideoEditingService {
    private final ProjectRepository projectRepository;
    private final EditedVideoRepository editedVideoRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, EditSession> activeSessions;
    private final String ffmpegPath = "/usr/local/bin/ffmpeg";
    private final String baseDir = "/Users/nimitpatel/Desktop/VideoEditor 2"; // Base directory constant

    public VideoEditingService(
            ProjectRepository projectRepository,
            EditedVideoRepository editedVideoRepository,
            ObjectMapper objectMapper
    ) {
        this.projectRepository = projectRepository;
        this.editedVideoRepository = editedVideoRepository;
        this.objectMapper = objectMapper;
        this.activeSessions = new ConcurrentHashMap<>();
    }

    @Data
    private class EditSession {
        private String sessionId;
        private Long projectId;
        private TimelineState timelineState;
        private long lastAccessTime;

        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        public Long getProjectId() {
            return projectId;
        }

        public void setProjectId(Long projectId) {
            this.projectId = projectId;
        }

        public TimelineState getTimelineState() {
            return timelineState;
        }

        public void setTimelineState(TimelineState timelineState) {
            this.timelineState = timelineState;
        }

        public long getLastAccessTime() {
            return lastAccessTime;
        }

        public void setLastAccessTime(long lastAccessTime) {
            this.lastAccessTime = lastAccessTime;
        }
    }

    // Unchanged methods: getVideos, addVideo, getImages, addImage, getAudio, addAudio
    public List<Map<String, String>> getVideos(Project project) throws JsonProcessingException {
        if (project.getVideosJson() == null || project.getVideosJson().isEmpty()) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(project.getVideosJson(), new TypeReference<List<Map<String, String>>>() {});
    }

    public void addVideo(Project project, String videoPath, String videoFileName) throws JsonProcessingException {
        List<Map<String, String>> videos = getVideos(project);
        Map<String, String> videoData = new HashMap<>();
        videoData.put("videoPath", videoPath);
        videoData.put("videoFileName", videoFileName);
        videos.add(videoData);
        project.setVideosJson(objectMapper.writeValueAsString(videos));
    }

    public List<Map<String, String>> getImages(Project project) throws JsonProcessingException {
        if (project.getImagesJson() == null || project.getImagesJson().isEmpty()) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(project.getImagesJson(), new TypeReference<List<Map<String, String>>>() {});
    }

    public void addImage(Project project, String imagePath, String imageFileName) throws JsonProcessingException {
        List<Map<String, String>> images = getImages(project);
        Map<String, String> imageData = new HashMap<>();
        imageData.put("imagePath", imagePath);
        imageData.put("imageFileName", imageFileName);
        images.add(imageData);
        project.setImagesJson(objectMapper.writeValueAsString(images));
    }

    public List<Map<String, String>> getAudio(Project project) throws JsonProcessingException {
        if (project.getAudioJson() == null || project.getAudioJson().isEmpty()) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(project.getAudioJson(), new TypeReference<List<Map<String, String>>>() {});
    }

    public void addAudio(Project project, String audioPath, String audioFileName) throws JsonProcessingException {
        List<Map<String, String>> audioFiles = getAudio(project);
        Map<String, String> audioData = new HashMap<>();
        audioData.put("audioPath", audioPath);
        audioData.put("audioFileName", audioFileName);
        audioFiles.add(audioData);
        project.setAudioJson(objectMapper.writeValueAsString(audioFiles));
    }

    public Project createProject(User user, String name, Integer width, Integer height) throws JsonProcessingException {
        Project project = new Project();
        project.setUser(user);
        project.setName(name);
        project.setStatus("DRAFT");
        project.setLastModified(LocalDateTime.now());
        project.setWidth(width != null ? width : 1920); // Default: 1920
        project.setHeight(height != null ? height : 1080); // Default: 1080
        project.setTimelineState(objectMapper.writeValueAsString(new TimelineState()));
        return projectRepository.save(project);
    }

    public String startEditingSession(User user, Long projectId) throws JsonProcessingException {
        String sessionId = UUID.randomUUID().toString();
        EditSession session = new EditSession();
        session.setSessionId(sessionId);
        session.setProjectId(projectId);
        session.setLastAccessTime(System.currentTimeMillis());

        TimelineState timelineState;

        if (projectId != null) {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found"));
            timelineState = objectMapper.readValue(project.getTimelineState(), TimelineState.class);

            // Normalize filtersAsMap for all VideoSegments
            for (VideoSegment segment : timelineState.getSegments()) {
                Map<String, String> currentFilters = segment.getFiltersAsMap();
                Map<String, String> normalizedFilters = new HashMap<>();
                if (currentFilters != null) {
                    for (Map.Entry<String, String> entry : currentFilters.entrySet()) {
                        String key = entry.getKey();
                        Object value = entry.getValue();
                        if (value instanceof Map) {
                            // Extract the string value from the object (e.g., {"value": "0.5"})
                            Object filterValue = ((Map<?, ?>) value).get("value");
                            normalizedFilters.put(key, filterValue != null ? filterValue.toString() : "");
                        } else {
                            // Already a string or simple value
                            normalizedFilters.put(key, value != null ? value.toString() : "");
                        }
                    }
                }
                segment.setFiltersAsMap(normalizedFilters); // Overwrite with normalized map
            }

            if (timelineState.getCanvasWidth() == null) {
                timelineState.setCanvasWidth(project.getWidth());
            }
            if (timelineState.getCanvasHeight() == null) {
                timelineState.setCanvasHeight(project.getHeight());
            }
            timelineState.syncLegacyFilters();
        } else {
            timelineState = new TimelineState();
            timelineState.setCanvasWidth(1920);
            timelineState.setCanvasHeight(1080);
        }

        session.setTimelineState(timelineState);
        activeSessions.put(sessionId, session);

        return sessionId;
    }

    public void saveProject(String sessionId) throws JsonProcessingException {
        EditSession session = getSession(sessionId);
        Project project = projectRepository.findById(session.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found"));

        session.getTimelineState().syncLegacyFilters();

        System.out.println("Saving timeline state with " + session.getTimelineState().getSegments().size() + " segments");

        String timelineStateJson = objectMapper.writeValueAsString(session.getTimelineState());
        project.setTimelineState(timelineStateJson);
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);

        System.out.println("Project saved successfully with timeline state: " + timelineStateJson);
    }

    @Scheduled(fixedRate = 3600000) // Every hour
    public void cleanupExpiredSessions() {
        final long expiryTime = System.currentTimeMillis() - 3600000;
        activeSessions.entrySet().removeIf(entry ->
                entry.getValue().getLastAccessTime() < expiryTime);
    }

    private EditSession getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId))
                .orElseThrow(() -> new RuntimeException("No active session found"));
    }

    public void addVideoToTimeline(
            String sessionId,
            String videoPath,
            Integer layer,
            Double timelineStartTime,
            Double timelineEndTime,
            Double startTime,
            Double endTime
    ) throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);
        double fullDuration = getVideoDuration(videoPath);

        layer = layer != null ? layer : 0;

        if (timelineStartTime == null) {
            double lastSegmentEndTime = 0.0;
            for (VideoSegment segment : session.getTimelineState().getSegments()) {
                if (segment.getLayer() == layer && segment.getTimelineEndTime() > lastSegmentEndTime) {
                    lastSegmentEndTime = segment.getTimelineEndTime();
                }
            }
            timelineStartTime = lastSegmentEndTime;
        }

        startTime = startTime != null ? startTime : 0.0;
        endTime = endTime != null ? endTime : fullDuration;

        if (timelineEndTime == null) {
            timelineEndTime = timelineStartTime + (endTime - startTime);
        }

        if (!session.getTimelineState().isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + layer);
        }

        if (startTime < 0 || endTime > fullDuration || startTime >= endTime) {
            throw new RuntimeException("Invalid startTime or endTime for video segment");
        }

        VideoSegment segment = new VideoSegment();
        segment.setSourceVideoPath(videoPath);
        segment.setStartTime(startTime);
        segment.setEndTime(endTime);
        segment.setPositionX(0);
        segment.setPositionY(0);
        segment.setScale(1.0);
        segment.setLayer(layer);
        segment.setTimelineStartTime(timelineStartTime);
        segment.setTimelineEndTime(timelineEndTime);

        session.getTimelineState().getSegments().add(segment);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    private double getVideoDuration(String videoPath) throws IOException, InterruptedException {
        String fullPath = "videos/" + videoPath;
        File videoFile = new File(fullPath);
        if (!videoFile.exists()) {
            throw new IOException("Video file not found: " + fullPath);
        }

        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-i", fullPath
        );
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        String outputStr = output.toString();
        int durationIndex = outputStr.indexOf("Duration:");
        if (durationIndex >= 0) {
            String durationStr = outputStr.substring(durationIndex + 10, outputStr.indexOf(",", durationIndex)).trim();
            String[] parts = durationStr.split(":");
            if (parts.length == 3) {
                double hours = Double.parseDouble(parts[0]);
                double minutes = Double.parseDouble(parts[1]);
                double seconds = Double.parseDouble(parts[2]);
                return hours * 3600 + minutes * 60 + seconds;
            }
        }
        return 300; // Default to 5 minutes
    }

    public void updateVideoSegment(String sessionId, String segmentId,
                                   Integer positionX, Integer positionY, Double scale,
                                   Double timelineStartTime, Integer layer, Double timelineEndTime,
                                   Double startTime, Double endTime) {
        EditSession session = getSession(sessionId);
        VideoSegment segmentToUpdate = null;
        for (VideoSegment segment : session.getTimelineState().getSegments()) {
            if (segment.getId().equals(segmentId)) {
                segmentToUpdate = segment;
                break;
            }
        }

        if (segmentToUpdate == null) {
            throw new RuntimeException("No segment found with ID: " + segmentId);
        }

        if (positionX != null) segmentToUpdate.setPositionX(positionX);
        if (positionY != null) segmentToUpdate.setPositionY(positionY);
        if (scale != null) segmentToUpdate.setScale(scale);

        if (timelineStartTime != null) {
            double originalDuration = segmentToUpdate.getTimelineEndTime() - segmentToUpdate.getTimelineStartTime();
            segmentToUpdate.setTimelineStartTime(timelineStartTime);
            if (timelineEndTime == null) {
                segmentToUpdate.setTimelineEndTime(timelineStartTime + originalDuration);
            }
        }

        if (layer != null) segmentToUpdate.setLayer(layer);
        if (timelineEndTime != null) segmentToUpdate.setTimelineEndTime(timelineEndTime);

        if (startTime != null) {
            segmentToUpdate.setStartTime(Math.max(0, startTime));
            if (endTime == null && segmentToUpdate.getEndTime() <= startTime) {
                throw new IllegalArgumentException("End time must be greater than start time");
            }
        }

        if (endTime != null) {
            segmentToUpdate.setEndTime(endTime);
            if (endTime <= segmentToUpdate.getStartTime()) {
                throw new IllegalArgumentException("End time must be greater than start time");
            }
            double originalVideoDuration = 7.43; // Placeholder, should be fetched
            if (endTime > originalVideoDuration) {
                segmentToUpdate.setEndTime(originalVideoDuration);
            }
        }

        double newTimelineDuration = segmentToUpdate.getTimelineEndTime() - segmentToUpdate.getTimelineStartTime();
        double newClipDuration = segmentToUpdate.getEndTime() - segmentToUpdate.getStartTime();
        if (newTimelineDuration < newClipDuration) {
            segmentToUpdate.setTimelineEndTime(segmentToUpdate.getTimelineStartTime() + newClipDuration);
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public VideoSegment getVideoSegment(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);
        for (VideoSegment segment : session.getTimelineState().getSegments()) {
            if (segment.getId().equals(segmentId)) {
                return segment;
            }
        }
        throw new RuntimeException("No segment found with ID: " + segmentId);
    }

    public void addTextToTimeline(String sessionId, String text, int layer, double timelineStartTime, double timelineEndTime,
                                  String fontFamily, int fontSize, String fontColor, String backgroundColor,
                                  int positionX, int positionY) {
        EditSession session = getSession(sessionId);
        if (!session.getTimelineState().isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            throw new IllegalArgumentException("Cannot add text: position overlaps with existing element in layer " + layer);
        }

        TextSegment textSegment = new TextSegment();
        textSegment.setText(text);
        textSegment.setLayer(layer);
        textSegment.setTimelineStartTime(timelineStartTime);
        textSegment.setTimelineEndTime(timelineEndTime);
        textSegment.setFontFamily(fontFamily);
        textSegment.setFontSize(fontSize);
        textSegment.setFontColor(fontColor);
        textSegment.setBackgroundColor(backgroundColor);
        textSegment.setPositionX(positionX);
        textSegment.setPositionY(positionY);

        session.getTimelineState().getTextSegments().add(textSegment);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void updateTextSegment(String sessionId, String segmentId, String text,
                                  String fontFamily, Integer fontSize, String fontColor,
                                  String backgroundColor, Integer positionX, Integer positionY,
                                  Double timelineStartTime, Double timelineEndTime, Integer layer) {
        EditSession session = getSession(sessionId);
        TextSegment textSegment = session.getTimelineState().getTextSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Text segment not found with ID: " + segmentId));

        if (text != null) textSegment.setText(text);
        if (fontFamily != null) textSegment.setFontFamily(fontFamily);
        if (fontSize != null) textSegment.setFontSize(fontSize);
        if (fontColor != null) textSegment.setFontColor(fontColor);
        if (backgroundColor != null) textSegment.setBackgroundColor(backgroundColor);
        if (positionX != null) textSegment.setPositionX(positionX);
        if (positionY != null) textSegment.setPositionY(positionY);
        if (timelineStartTime != null) textSegment.setTimelineStartTime(timelineStartTime);
        if (timelineEndTime != null) textSegment.setTimelineEndTime(timelineEndTime);
        if (layer != null) textSegment.setLayer(layer);

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public Project uploadAudioToProject(User user, Long projectId, MultipartFile audioFile, String audioFileName) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        File projectAudioDir = new File(baseDir, "audio" + File.separator + "projects" + File.separator + projectId);
        if (!projectAudioDir.exists()) projectAudioDir.mkdirs();

        String uniqueFileName = projectId + "_" + System.currentTimeMillis() + "_" + audioFile.getOriginalFilename();
        File destinationFile = new File(projectAudioDir, uniqueFileName);

        audioFile.transferTo(destinationFile);

        String relativePath = "audio/projects/" + projectId + "/" + uniqueFileName;
        try {
            addAudio(project, relativePath, uniqueFileName);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to process audio data", e);
        }
        project.setLastModified(LocalDateTime.now());
        return projectRepository.save(project);
    }

    public void addAudioToTimelineFromProject(
            User user,
            String sessionId,
            Long projectId,
            int layer,
            double startTime,
            Double endTime,
            double timelineStartTime,
            Double timelineEndTime,
            String audioFileName) throws IOException, InterruptedException {
        if (layer >= 0) throw new RuntimeException("Audio layers must be negative");

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        List<Map<String, String>> audioFiles;
        try {
            audioFiles = getAudio(project);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to parse project audio", e);
        }

        Map<String, String> targetAudio = audioFiles.stream()
                .filter(audio -> audio.get("audioFileName").equals(audioFileName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No audio found with filename: " + audioFileName));

        String audioPath = targetAudio.get("audioPath");
        double calculatedEndTime = endTime != null ? endTime : startTime + getAudioDuration(audioPath);

        addAudioToTimeline(sessionId, audioPath, layer, startTime, calculatedEndTime, timelineStartTime, timelineEndTime);
    }

    public void addAudioToTimeline(
            String sessionId,
            String audioPath,
            int layer,
            double startTime,
            double endTime,
            double timelineStartTime,
            Double timelineEndTime) throws IOException, InterruptedException {
        if (layer >= 0) throw new RuntimeException("Audio layers must be negative");

        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        File audioFile = new File(baseDir, audioPath);
        if (!audioFile.exists()) throw new IOException("Audio file not found: " + audioFile.getAbsolutePath());

        double audioDuration = getAudioDuration(audioPath);
        if (startTime < 0 || endTime > audioDuration || startTime >= endTime) {
            throw new RuntimeException("Invalid audio start/end times");
        }

        if (timelineEndTime == null) {
            timelineEndTime = timelineStartTime + (endTime - startTime);
        }

        if (!timelineState.isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            throw new RuntimeException("Timeline position overlaps with existing audio in layer " + layer);
        }

        AudioSegment audioSegment = new AudioSegment();
        audioSegment.setAudioPath(audioPath);
        audioSegment.setLayer(layer);
        audioSegment.setStartTime(startTime);
        audioSegment.setEndTime(endTime);
        audioSegment.setTimelineStartTime(timelineStartTime);
        audioSegment.setTimelineEndTime(timelineEndTime);

        timelineState.getAudioSegments().add(audioSegment);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void updateAudioSegment(
            String sessionId,
            String audioSegmentId,
            Double startTime,
            Double endTime,
            Double timelineStartTime,
            Double timelineEndTime,
            Double volume,
            Integer layer) throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        AudioSegment targetSegment = timelineState.getAudioSegments().stream()
                .filter(segment -> segment.getId().equals(audioSegmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Audio segment not found: " + audioSegmentId));

        double originalStartTime = targetSegment.getStartTime();
        double originalEndTime = targetSegment.getEndTime();
        double originalTimelineStartTime = targetSegment.getTimelineStartTime();
        double originalTimelineEndTime = targetSegment.getTimelineEndTime();
        int originalLayer = targetSegment.getLayer();

        double audioDuration = getAudioDuration(targetSegment.getAudioPath());

        boolean timelineChanged = false;
        if (timelineStartTime != null) {
            targetSegment.setTimelineStartTime(timelineStartTime);
            timelineChanged = true;
        }
        if (timelineEndTime != null) {
            targetSegment.setTimelineEndTime(timelineEndTime);
            timelineChanged = true;
        }
        if (layer != null) {
            if (layer >= 0) throw new RuntimeException("Audio layers must be negative");
            targetSegment.setLayer(layer);
        }
        if (volume != null) {
            if (volume < 0 || volume > 1) throw new RuntimeException("Volume must be between 0.0 and 1.0");
            targetSegment.setVolume(volume);
        }

        if (startTime != null || endTime != null || timelineChanged) {
            if (startTime != null) {
                if (startTime < 0 || startTime >= audioDuration) {
                    throw new RuntimeException("Start time must be between 0 and " + audioDuration);
                }
                targetSegment.setStartTime(startTime);
            }
            if (endTime != null) {
                if (endTime <= targetSegment.getStartTime() || endTime > audioDuration) {
                    throw new RuntimeException("End time must be greater than start time and less than or equal to " + audioDuration);
                }
                targetSegment.setEndTime(endTime);
            }

            if (!timelineChanged) {
                double newStartTime = startTime != null ? startTime : originalStartTime;
                double newEndTime = endTime != null ? endTime : originalEndTime;

                if (startTime != null && timelineStartTime == null) {
                    double startTimeShift = newStartTime - originalStartTime;
                    targetSegment.setTimelineStartTime(originalTimelineStartTime + startTimeShift);
                }
                if (endTime != null && timelineEndTime == null) {
                    double audioDurationUsed = newEndTime - targetSegment.getStartTime();
                    targetSegment.setTimelineEndTime(targetSegment.getTimelineStartTime() + audioDurationUsed);
                }
            } else if (startTime == null && endTime == null) {
                double newTimelineDuration = targetSegment.getTimelineEndTime() - targetSegment.getTimelineStartTime();
                double originalTimelineDuration = originalTimelineEndTime - originalTimelineStartTime;
                double originalAudioDuration = originalEndTime - originalStartTime;

                if (newTimelineDuration != originalTimelineDuration) {
                    double timelineShift = targetSegment.getTimelineStartTime() - originalTimelineStartTime;
                    double newStartTime = originalStartTime + timelineShift;

                    if (newStartTime < 0) newStartTime = 0;

                    double newEndTime = newStartTime + Math.min(newTimelineDuration, originalAudioDuration);
                    if (newEndTime > audioDuration) {
                        newEndTime = audioDuration;
                        newStartTime = Math.max(0, newEndTime - newTimelineDuration);
                    }

                    targetSegment.setStartTime(newStartTime);
                    targetSegment.setEndTime(newEndTime);
                }
            } else {
                if (timelineEndTime == null) {
                    double audioDurationUsed = targetSegment.getEndTime() - targetSegment.getStartTime();
                    targetSegment.setTimelineEndTime(targetSegment.getTimelineStartTime() + audioDurationUsed);
                }
            }
        }

        timelineState.getAudioSegments().remove(targetSegment);
        boolean positionAvailable = timelineState.isTimelinePositionAvailable(
                targetSegment.getTimelineStartTime(),
                targetSegment.getTimelineEndTime(),
                targetSegment.getLayer());
        timelineState.getAudioSegments().add(targetSegment);

        if (!positionAvailable) {
            targetSegment.setStartTime(originalStartTime);
            targetSegment.setEndTime(originalEndTime);
            targetSegment.setTimelineStartTime(originalTimelineStartTime);
            targetSegment.setTimelineEndTime(originalTimelineEndTime);
            targetSegment.setLayer(originalLayer);
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + targetSegment.getLayer());
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void removeAudioSegment(String sessionId, String audioSegmentId) throws IOException {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        boolean removed = timelineState.getAudioSegments().removeIf(
                segment -> segment.getId().equals(audioSegmentId)
        );

        if (!removed) throw new RuntimeException("Audio segment not found with ID: " + audioSegmentId);

        session.setLastAccessTime(System.currentTimeMillis());
    }

    private double getAudioDuration(String audioPath) throws IOException, InterruptedException {
        File audioFile = new File(baseDir, audioPath);
        if (!audioFile.exists()) throw new IOException("Audio file not found: " + audioFile.getAbsolutePath());

        ProcessBuilder builder = new ProcessBuilder(ffmpegPath, "-i", audioFile.getAbsolutePath());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        String outputStr = output.toString();
        int durationIndex = outputStr.indexOf("Duration:");
        if (durationIndex >= 0) {
            String durationStr = outputStr.substring(durationIndex + 10, outputStr.indexOf(",", durationIndex)).trim();
            String[] parts = durationStr.split(":");
            if (parts.length == 3) {
                double hours = Double.parseDouble(parts[0]);
                double minutes = Double.parseDouble(parts[1]);
                double seconds = Double.parseDouble(parts[2]);
                return hours * 3600 + minutes * 60 + seconds;
            }
        }
        return 300; // Default to 5 minutes
    }

    public Project uploadImageToProject(User user, Long projectId, MultipartFile imageFile, String imageFileName) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        File projectImageDir = new File(baseDir, "images" + File.separator + "projects" + File.separator + projectId);
        if (!projectImageDir.exists()) projectImageDir.mkdirs();

        String uniqueFileName = projectId + "_" + System.currentTimeMillis() + "_" + imageFile.getOriginalFilename();
        File destinationFile = new File(projectImageDir, uniqueFileName);

        imageFile.transferTo(destinationFile);

        String relativePath = "images/projects/" + projectId + "/" + uniqueFileName;
        try {
            addImage(project, relativePath, uniqueFileName);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to process image data", e);
        }
        project.setLastModified(LocalDateTime.now());
        return projectRepository.save(project);
    }

    public void addImageToTimelineFromProject(
            User user,
            String sessionId,
            Long projectId,
            Integer layer,
            Double timelineStartTime,
            Double timelineEndTime,
            Map<String, String> filters,
            String imageFileName) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        List<Map<String, String>> images;
        try {
            images = getImages(project);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to parse project images", e);
        }

        Map<String, String> targetImage = images.stream()
                .filter(img -> img.get("imageFileName").equals(imageFileName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No image found with filename: " + imageFileName));

        String imagePath = targetImage.get("imagePath");
        int positionX = 0;
        int positionY = 0;
        double scale = 1.0;

        addImageToTimeline(sessionId, imagePath, layer, timelineStartTime, timelineEndTime, positionX, positionY, scale, filters);
    }

    public void addImageToTimeline(
            String sessionId,
            String imagePath,
            int layer,
            double timelineStartTime,
            Double timelineEndTime,
            int positionX,
            int positionY,
            double scale,
            Map<String, String> filters
    ) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        ImageSegment imageSegment = new ImageSegment();
        imageSegment.setId(UUID.randomUUID().toString());
        imageSegment.setImagePath(imagePath);
        imageSegment.setLayer(layer);
        imageSegment.setPositionX(positionX);
        imageSegment.setPositionY(positionY);
        imageSegment.setScale(scale);
        imageSegment.setTimelineStartTime(timelineStartTime);
        imageSegment.setTimelineEndTime(timelineEndTime == null ? timelineStartTime + 5.0 : timelineEndTime);

        try {
            File imageFile = new File(baseDir + File.separator + imagePath);
            if (!imageFile.exists()) {
                throw new RuntimeException("Image file does not exist: " + imageFile.getAbsolutePath());
            }
            BufferedImage img = ImageIO.read(imageFile);
            imageSegment.setWidth(img.getWidth());
            imageSegment.setHeight(img.getHeight());
        } catch (IOException e) {
            throw new RuntimeException("Error reading image file: " + e.getMessage());
        }

        if (filters != null && !filters.isEmpty()) {
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                Filter filter = new Filter();
                filter.setSegmentId(imageSegment.getId());
                filter.setFilterName(entry.getKey());
                filter.setFilterValue(entry.getValue());
                timelineState.getFilters().add(filter);
            }
        }

        timelineState.getImageSegments().add(imageSegment);
        timelineState.syncLegacyFilters();
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void updateImageSegment(
            String sessionId,
            String imageSegmentId,
            Integer positionX,
            Integer positionY,
            Double scale,
            Double opacity,
            Integer layer,
            Integer customWidth,
            Integer customHeight,
            Boolean maintainAspectRatio,
            Map<String, String> filters,
            List<String> filtersToRemove,
            Double timelineStartTime,
            Double timelineEndTime
    ) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        ImageSegment targetSegment = null;
        for (ImageSegment segment : timelineState.getImageSegments()) {
            if (segment.getId().equals(imageSegmentId)) {
                targetSegment = segment;
                break;
            }
        }

        if (targetSegment == null) {
            throw new RuntimeException("Image segment not found: " + imageSegmentId);
        }

        if (positionX != null) targetSegment.setPositionX(positionX);
        if (positionY != null) targetSegment.setPositionY(positionY);
        if (scale != null) targetSegment.setScale(scale);
        if (opacity != null) targetSegment.setOpacity(opacity);
        if (layer != null) targetSegment.setLayer(layer);
        if (customWidth != null) targetSegment.setCustomWidth(customWidth);
        if (customHeight != null) targetSegment.setCustomHeight(customHeight);
        if (maintainAspectRatio != null) targetSegment.setMaintainAspectRatio(maintainAspectRatio);
        if (timelineStartTime != null) targetSegment.setTimelineStartTime(timelineStartTime);
        if (timelineEndTime != null) targetSegment.setTimelineEndTime(timelineEndTime);

        if (filters != null && !filters.isEmpty()) {
            for (Map.Entry<String, String> filter : filters.entrySet()) {
                Filter newFilter = new Filter();
                newFilter.setSegmentId(targetSegment.getId());
                newFilter.setFilterName(filter.getKey());
                newFilter.setFilterValue(filter.getValue());
                String segmentId = targetSegment.getId();
                timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(segmentId) && f.getFilterName().equals(filter.getKey()));
                timelineState.getFilters().add(newFilter);
            }
        }

        if (filtersToRemove != null && !filtersToRemove.isEmpty()) {
            String segmentId = targetSegment.getId();
            List<String> filtersToRemoveFinal = new ArrayList<>(filtersToRemove);
            timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(segmentId) && filtersToRemoveFinal.contains(f.getFilterId()));
        }

        timelineState.syncLegacyFilters();
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void removeImageSegment(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();
        boolean removed = timelineState.getImageSegments().removeIf(segment -> segment.getId().equals(segmentId));
        if (!removed) throw new RuntimeException("Image segment not found with ID: " + segmentId);
        timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(segmentId));
        timelineState.syncLegacyFilters();
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void saveTimelineState(String sessionId, TimelineState timelineState) {
        EditSession session = activeSessions.get(sessionId);
        if (session == null) throw new RuntimeException("Edit session not found: " + sessionId);
        timelineState.syncLegacyFilters();
        session.setTimelineState(timelineState);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public TimelineState getTimelineState(String sessionId) {
        EditSession session = activeSessions.get(sessionId);
        if (session == null) throw new RuntimeException("Edit session not found: " + sessionId);
        session.getTimelineState().syncLegacyFilters();
        session.setLastAccessTime(System.currentTimeMillis());
        return session.getTimelineState();
    }

    public File exportProject(String sessionId) throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);
        Project project = projectRepository.findById(session.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found"));

        String outputFileName = project.getName().replaceAll("[^a-zA-Z0-9]", "_") + "_" + System.currentTimeMillis() + ".mp4";
        String outputPath = "exports/" + outputFileName;

        File exportsDir = new File("exports");
        if (!exportsDir.exists()) exportsDir.mkdirs();

        String exportedVideoPath = renderFinalVideo(session.getTimelineState(), outputPath, project.getWidth(), project.getHeight());

        project.setStatus("EXPORTED");
        project.setLastModified(LocalDateTime.now());
        project.setExportedVideoPath(exportedVideoPath);

        session.getTimelineState().syncLegacyFilters();
        try {
            project.setTimelineState(objectMapper.writeValueAsString(session.getTimelineState()));
        } catch (JsonProcessingException e) {
            System.err.println("Error saving timeline state: " + e.getMessage());
        }

        projectRepository.save(project);
        return new File(exportedVideoPath);
    }

    private String renderFinalVideo(TimelineState timelineState, String outputPath, int canvasWidth, int canvasHeight)
            throws IOException, InterruptedException {
        if (timelineState.getCanvasWidth() != null) canvasWidth = timelineState.getCanvasWidth();
        if (timelineState.getCanvasHeight() != null) canvasHeight = timelineState.getCanvasHeight();

        File tempDir = new File("temp");
        if (!tempDir.exists()) tempDir.mkdirs();

        Set<Double> timePoints = new TreeSet<>();
        timePoints.add(0.0);

        if (timelineState.getSegments() != null) {
            for (VideoSegment segment : timelineState.getSegments()) {
                timePoints.add(segment.getTimelineStartTime());
                timePoints.add(segment.getTimelineEndTime());
            }
        }

        if (timelineState.getImageSegments() != null) {
            for (ImageSegment segment : timelineState.getImageSegments()) {
                timePoints.add(segment.getTimelineStartTime());
                timePoints.add(segment.getTimelineEndTime());
            }
        }

        if (timelineState.getTextSegments() != null) {
            for (TextSegment segment : timelineState.getTextSegments()) {
                timePoints.add(segment.getTimelineStartTime());
                timePoints.add(segment.getTimelineEndTime());
            }
        }

        if (timelineState.getAudioSegments() != null) {
            for (AudioSegment segment : timelineState.getAudioSegments()) {
                timePoints.add(segment.getTimelineStartTime());
                timePoints.add(segment.getTimelineEndTime());
            }
        }

        List<Double> sortedTimePoints = new ArrayList<>(timePoints);
        Collections.sort(sortedTimePoints);

        double totalDuration = sortedTimePoints.get(sortedTimePoints.size() - 1);
        List<File> intermediateFiles = new ArrayList<>();

        for (int i = 0; i < sortedTimePoints.size() - 1; i++) {
            double segmentStart = sortedTimePoints.get(i);
            double segmentEnd = sortedTimePoints.get(i + 1);
            double segmentDuration = segmentEnd - segmentStart;

            if (segmentDuration <= 0.001) continue;

            List<VideoSegment> visibleVideoSegments = new ArrayList<>();
            List<ImageSegment> visibleImageSegments = new ArrayList<>();
            List<TextSegment> visibleTextSegments = new ArrayList<>();
            List<AudioSegment> visibleAudioSegments = new ArrayList<>();

            if (timelineState.getSegments() != null) {
                for (VideoSegment segment : timelineState.getSegments()) {
                    if (segment.getTimelineStartTime() < segmentEnd && segment.getTimelineEndTime() > segmentStart) {
                        visibleVideoSegments.add(segment);
                    }
                }
            }

            if (timelineState.getImageSegments() != null) {
                for (ImageSegment segment : timelineState.getImageSegments()) {
                    if (segment.getTimelineStartTime() < segmentEnd && segment.getTimelineEndTime() > segmentStart) {
                        visibleImageSegments.add(segment);
                    }
                }
            }

            if (timelineState.getTextSegments() != null) {
                for (TextSegment segment : timelineState.getTextSegments()) {
                    if (segment.getTimelineStartTime() < segmentEnd && segment.getTimelineEndTime() > segmentStart) {
                        visibleTextSegments.add(segment);
                    }
                }
            }

            if (timelineState.getAudioSegments() != null) {
                for (AudioSegment segment : timelineState.getAudioSegments()) {
                    if (segment.getTimelineStartTime() < segmentEnd && segment.getTimelineEndTime() > segmentStart) {
                        visibleAudioSegments.add(segment);
                    }
                }
            }

            visibleVideoSegments.sort(Comparator.comparingInt(VideoSegment::getLayer));
            visibleImageSegments.sort(Comparator.comparingInt(ImageSegment::getLayer));
            visibleTextSegments.sort(Comparator.comparingInt(TextSegment::getLayer));
            visibleAudioSegments.sort(Comparator.comparingInt(AudioSegment::getLayer));

            String tempFilename = "temp_" + UUID.randomUUID().toString() + ".mp4";
            File tempFile = new File(tempDir, tempFilename);
            intermediateFiles.add(tempFile);

            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);

            StringBuilder filterComplex = new StringBuilder();
            Map<Integer, String> inputIndices = new HashMap<>();
            int inputCount = 0;

            for (VideoSegment segment : visibleVideoSegments) {
                command.add("-i");
                command.add(baseDir + File.separator + "videos" + File.separator + segment.getSourceVideoPath());
                inputIndices.put(segment.getLayer(), String.valueOf(inputCount++));
            }

            for (ImageSegment segment : visibleImageSegments) {
                command.add("-loop");
                command.add("1");
                command.add("-i");
                command.add(baseDir + File.separator + segment.getImagePath());
                inputIndices.put(segment.getLayer(), String.valueOf(inputCount++));
            }

            for (AudioSegment segment : visibleAudioSegments) {
                command.add("-i");
                command.add(baseDir + File.separator + segment.getAudioPath());
                inputIndices.put(segment.getLayer(), String.valueOf(inputCount++));
            }

            filterComplex.append("color=c=black:s=").append(canvasWidth).append("x").append(canvasHeight)
                    .append(":d=").append(segmentDuration).append("[base];");

            String lastOutput = "base";
            int overlayCount = 0;

            for (VideoSegment segment : visibleVideoSegments) {
                String inputIdx = inputIndices.get(segment.getLayer());
                String videoOutput = "v" + overlayCount++;

                double relativeStart = Math.max(segment.getStartTime(), segment.getTimelineStartTime() - segmentStart);
                double relativeEnd = Math.min(segment.getEndTime(), segment.getTimelineEndTime() - segmentStart + segment.getStartTime());
                double trimDuration = relativeEnd - relativeStart;

                filterComplex.append("[").append(inputIdx).append(":v]");
                filterComplex.append("trim=").append(relativeStart).append(":").append(relativeEnd).append(",");
                filterComplex.append("setpts=PTS-STARTPTS,");
                filterComplex.append("scale=iw*").append(segment.getScale()).append(":ih*").append(segment.getScale());
                String videoFilters = generateVideoFilters(segment, timelineState);
                if (!videoFilters.isEmpty()) {
                    filterComplex.append(",").append(videoFilters);
                }
                filterComplex.append("[scaled").append(videoOutput).append("];");

                filterComplex.append("[").append(lastOutput).append("][scaled").append(videoOutput).append("]");
                filterComplex.append("overlay=(W-w)/2+").append(segment.getPositionX()).append(":")
                        .append("(H-h)/2+").append(segment.getPositionY()).append("[ov").append(videoOutput).append("];");

                lastOutput = "ov" + videoOutput;
            }

            for (ImageSegment segment : visibleImageSegments) {
                String inputIdx = inputIndices.get(segment.getLayer());
                String imageOutput = "img" + overlayCount++;

                double displayDuration = Math.min(segment.getTimelineEndTime(), segmentEnd) -
                        Math.max(segment.getTimelineStartTime(), segmentStart);

                filterComplex.append("[").append(inputIdx).append(":v]");
                filterComplex.append("trim=0:").append(displayDuration).append(",");
                filterComplex.append("setpts=PTS-STARTPTS,");
                filterComplex.append(generateImageFilters(segment, timelineState));
                filterComplex.append("[scaled").append(imageOutput).append("];");

                filterComplex.append("[").append(lastOutput).append("][scaled").append(imageOutput).append("]");
                filterComplex.append("overlay=(W-w)/2+").append(segment.getPositionX()).append(":")
                        .append("(H-h)/2+").append(segment.getPositionY()).append("[ov").append(imageOutput).append("];");

                lastOutput = "ov" + imageOutput;
            }

            for (TextSegment segment : visibleTextSegments) {
                String textOutput = "text" + overlayCount++;
                double textStartOffset = Math.max(0, segment.getTimelineStartTime() - segmentStart);

                filterComplex.append("[").append(lastOutput).append("]");
                filterComplex.append("drawtext=text='").append(segment.getText().replace("'", "\\'")).append("':");
                filterComplex.append("enable='gte(t,").append(textStartOffset).append(")':");
                filterComplex.append("fontcolor=").append(segment.getFontColor()).append(":");
                filterComplex.append("fontsize=").append(segment.getFontSize()).append(":");
                filterComplex.append("fontfile='").append(getFontPathByFamily(segment.getFontFamily())).append("':");
                if (segment.getBackgroundColor() != null) {
                    filterComplex.append("box=1:boxcolor=").append(segment.getBackgroundColor()).append("@0.5:");
                }
                filterComplex.append("x=(w-tw)/2+").append(segment.getPositionX()).append(":");
                filterComplex.append("y=(h-th)/2+").append(segment.getPositionY());
                filterComplex.append("[ov").append(textOutput).append("];");

                lastOutput = "ov" + textOutput;
            }

            if (!visibleAudioSegments.isEmpty()) {
                for (int j = 0; j < visibleAudioSegments.size(); j++) {
                    AudioSegment segment = visibleAudioSegments.get(j);
                    String inputIdx = inputIndices.get(segment.getLayer());
                    String audioOutput = "a" + j;

                    double relativeStart = Math.max(segment.getStartTime(), segment.getTimelineStartTime() - segmentStart);
                    double relativeEnd = Math.min(segment.getEndTime(), segment.getTimelineEndTime() - segmentStart + segment.getStartTime());
                    double trimDuration = relativeEnd - relativeStart;

                    filterComplex.append("[").append(inputIdx).append(":a]");
                    filterComplex.append("atrim=").append(relativeStart).append(":").append(relativeEnd).append(",");
                    filterComplex.append("asetpts=PTS-STARTPTS");
                    filterComplex.append(",volume=").append(segment.getVolume());
                    filterComplex.append("[aout").append(j).append("];");
                }

                for (int j = 0; j < visibleAudioSegments.size(); j++) {
                    filterComplex.append("[aout").append(j).append("]");
                }
                filterComplex.append("amix=inputs=").append(visibleAudioSegments.size()).append("[aout];");
            }

            filterComplex.append("[").append(lastOutput).append("]setpts=PTS-STARTPTS[vout]");

            command.add("-filter_complex");
            command.add(filterComplex.toString());

            command.add("-map");
            command.add("[vout]");
            if (!visibleAudioSegments.isEmpty()) {
                command.add("-map");
                command.add("[aout]");
            }

            command.add("-c:v");
            command.add("libx264");
            command.add("-c:a");
            command.add("aac");
            command.add("-t");
            command.add(String.valueOf(segmentDuration));
            command.add("-y");
            command.add(tempFile.getAbsolutePath());

            executeFFmpegCommand(command);
        }

        File concatFile = File.createTempFile("ffmpeg-concat-", ".txt");
        try (PrintWriter writer = new PrintWriter(new FileWriter(concatFile))) {
            for (File file : intermediateFiles) {
                if (file.exists() && file.length() > 0) {
                    writer.println("file '" + file.getAbsolutePath().replace("\\", "/") + "'");
                }
            }
        }

        List<String> concatCommand = new ArrayList<>();
        concatCommand.add(ffmpegPath);
        concatCommand.add("-f");
        concatCommand.add("concat");
        concatCommand.add("-safe");
        concatCommand.add("0");
        concatCommand.add("-i");
        concatCommand.add(concatFile.getAbsolutePath());
        concatCommand.add("-c:v");
        concatCommand.add("libx264");
        concatCommand.add("-c:a");
        concatCommand.add("aac");
        concatCommand.add("-t");
        concatCommand.add(String.valueOf(totalDuration));
        concatCommand.add("-y");
        concatCommand.add(outputPath);

        executeFFmpegCommand(concatCommand);

        concatFile.delete();
        for (File file : intermediateFiles) {
            file.delete();
        }

        return outputPath;
    }



    private void executeFFmpegCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("FFmpeg: " + line);
            }
        }

        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("FFmpeg process timed out after 5 minutes");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg process failed with exit code: " + exitCode);
        }
    }

    private String getFontPathByFamily(String fontFamily) {
        String defaultFontPath = "C:/Windows/Fonts/Arial.ttf";
        if (fontFamily == null || fontFamily.trim().isEmpty()) return defaultFontPath;

        Map<String, String> fontMap = new HashMap<>();
        fontMap.put("Arial", "C:/Windows/Fonts/Arial.ttf");
        fontMap.put("Times New Roman", "C:/Windows/Fonts/times.ttf");
        fontMap.put("Courier New", "C:/Windows/Fonts/cour.ttf");
        fontMap.put("Calibri", "C:/Windows/Fonts/calibri.ttf");
        fontMap.put("Verdana", "C:/Windows/Fonts/verdana.ttf");
        fontMap.put("Georgia", "C:/Windows/Fonts/georgia.ttf");
        fontMap.put("Comic Sans MS", "C:/Windows/Fonts/comic.ttf");
        fontMap.put("Impact", "C:/Windows/Fonts/impact.ttf");
        fontMap.put("Tahoma", "C:/Windows/Fonts/tahoma.ttf");

        String processedFontFamily = fontFamily.trim();
        if (fontMap.containsKey(processedFontFamily)) return fontMap.get(processedFontFamily);

        for (Map.Entry<String, String> entry : fontMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(processedFontFamily)) return entry.getValue();
        }

        return defaultFontPath;
    }

    public void applyFilter(String sessionId, String segmentId, String filterName, String filterValue) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        boolean segmentExists = false;
        for (VideoSegment segment : timelineState.getSegments()) {
            if (segment.getId().equals(segmentId)) {
                segmentExists = true;
                break;
            }
        }
        if (!segmentExists) {
            for (ImageSegment segment : timelineState.getImageSegments()) {
                if (segment.getId().equals(segmentId)) {
                    segmentExists = true;
                    break;
                }
            }
        }
        if (!segmentExists) {
            throw new RuntimeException("Segment not found with ID: " + segmentId);
        }

        Filter filter = new Filter();
        filter.setSegmentId(segmentId);
        filter.setFilterName(filterName);
        filter.setFilterValue(filterValue);
        timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(segmentId) && f.getFilterName().equals(filterName));
        timelineState.getFilters().add(filter);

        timelineState.syncLegacyFilters();
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void removeFilter(String sessionId, String segmentId, String filterId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        boolean removed = timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(segmentId) && f.getFilterId().equals(filterId));
        if (!removed) {
            throw new RuntimeException("Filter not found with ID: " + filterId + " for segment: " + segmentId);
        }

        timelineState.syncLegacyFilters();
        session.setLastAccessTime(System.currentTimeMillis());
    }

    private String generateVideoFilters(VideoSegment segment, TimelineState timelineState) {
        StringBuilder filterStr = new StringBuilder();
        List<Filter> segmentFilters = timelineState.getFilters().stream()
                .filter(f -> f.getSegmentId().equals(segment.getId()))
                .toList();

        for (Filter filter : segmentFilters) {
            String filterName = filter.getFilterName().toLowerCase();
            String filterValue = filter.getFilterValue();
            switch (filterName) {
                // Color Adjustments
                case "brightness":
                    filterStr.append("eq=brightness=").append(filterValue).append(",");
                    break;
                case "contrast":
                    filterStr.append("eq=contrast=").append(filterValue).append(",");
                    break;
                case "saturation":
                    filterStr.append("eq=saturation=").append(filterValue).append(",");
                    break;
                case "hue":
                    filterStr.append("hue=h=").append(filterValue).append(",");
                    break;
                case "gamma":
                    filterStr.append("eq=gamma=").append(filterValue).append(",");
                    break;
                case "colorbalance":
                    // Format: "r,g,b" (e.g., "0.1,-0.2,0.3")
                    String[] rgb = filterValue.split(",");
                    if (rgb.length == 3) {
                        filterStr.append("colorbalance=rs=").append(rgb[0])
                                .append(":gs=").append(rgb[1])
                                .append(":bs=").append(rgb[2]).append(",");
                    }
                    break;
                case "levels":
                    // Format: "in_min/in_max/out_min/out_max" (e.g., "0/255/16/235")
                    String[] levels = filterValue.split("/");
                    if (levels.length == 4) {
                        filterStr.append("levels=rimin=").append(levels[0]).append(":rimax=").append(levels[1])
                                .append(":romin=").append(levels[2]).append(":romax=").append(levels[3]).append(",");
                    }
                    break;
                case "curves":
                    // Format: "r='0/0 1/1':g='0/0 1/1':b='0/0 1/1'"
                    filterStr.append("curves=").append(filterValue).append(",");
                    break;

                // Stylization
                case "grayscale":
                    filterStr.append("hue=s=0,");
                    break;
                case "sepia":
                    filterStr.append("colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131:0,");
                    break;
                case "vintage":
                    filterStr.append("curves=preset=vintage,");
                    break;
                case "posterize":
                    filterStr.append("posterize=").append(filterValue).append(",");
                    break;
                case "solarize":
                    filterStr.append("solarize=threshold=").append(filterValue).append(",");
                    break;
                case "invert":
                    filterStr.append("negate,");
                    break;

                // Blur and Sharpen
                case "blur":
                    filterStr.append("gblur=sigma=").append(filterValue).append(",");
                    break;
                case "sharpen":
                    filterStr.append("unsharp=5:5:").append(filterValue).append(":5:5:0,");
                    break;
                case "edge":
                    filterStr.append("edgedetect=mode=colormix:high=").append(filterValue)
                            .append(":low=").append(filterValue).append(",");
                    break;

                // Distortion and Noise
                case "noise":
                    filterStr.append("noise=alls=").append(filterValue).append(",");
                    break;
                case "vignette":
                    filterStr.append("vignette=PI/").append(filterValue).append(",");
                    break;
                case "pixelize":
                    // Format: "size" (e.g., "8" for 8x8 blocks)
                    filterStr.append("pixelize=").append(filterValue).append(":").append(filterValue).append(",");
                    break;

                // Transformation
                case "rotate":
                    filterStr.append("rotate=").append(filterValue).append("*PI/180,");
                    break;
                case "flip":
                    if ("horizontal".equals(filterValue)) {
                        filterStr.append("hflip,");
                    } else if ("vertical".equals(filterValue)) {
                        filterStr.append("vflip,");
                    }
                    break;
                case "crop":
                    // Format: "width:height:x:y" (e.g., "720:480:0:0")
                    filterStr.append("crop=").append(filterValue).append(",");
                    break;
                case "opacity":
                    filterStr.append("format=rgba,colorchannelmixer=aa=").append(filterValue).append(",");
                    break;

                // Special Effects
                case "emboss":
                    filterStr.append("convolution='-2 -1 0 -1 1 1 0 1 2',");
                    break;
                case "glow":
                    filterStr.append("gblur=sigma=").append(filterValue).append(":steps=3,");
                    filterStr.append("blend=all_mode=glow:all_opacity=0.5,");
                    break;
                case "overlay":
                    // Format: "color@opacity" (e.g., "red@0.5")
                    String[] overlayParts = filterValue.split("@");
                    if (overlayParts.length == 2) {
                        filterStr.append("color=").append(overlayParts[0]).append(":s=iwxih[d];")
                                .append("[v][d]overlay=0:0:enable='between(t,0,9999)':format=rgb,")
                                .append("colorchannelmixer=aa=").append(overlayParts[1]).append(",");
                    }
                    break;

                default:
                    System.out.println("Unsupported filter: " + filterName);
            }
        }
        if (filterStr.length() > 0 && filterStr.charAt(filterStr.length() - 1) == ',') {
            filterStr.setLength(filterStr.length() - 1);
        }
        return filterStr.toString();
    }

    private String generateImageFilters(ImageSegment imgSegment, TimelineState timelineState) {
        StringBuilder filterStr = new StringBuilder();

        // Scaling logic (unchanged)
        if (imgSegment.getCustomWidth() > 0 || imgSegment.getCustomHeight() > 0) {
            if (imgSegment.isMaintainAspectRatio()) {
                if (imgSegment.getCustomWidth() > 0 && imgSegment.getCustomHeight() > 0) {
                    filterStr.append("scale=").append(imgSegment.getCustomWidth()).append(":")
                            .append(imgSegment.getCustomHeight()).append(":force_original_aspect_ratio=decrease");
                } else if (imgSegment.getCustomWidth() > 0) {
                    filterStr.append("scale=").append(imgSegment.getCustomWidth()).append(":-1");
                } else {
                    filterStr.append("scale=-1:").append(imgSegment.getCustomHeight());
                }
            } else {
                int width = imgSegment.getCustomWidth() > 0 ? imgSegment.getCustomWidth() : imgSegment.getWidth();
                int height = imgSegment.getCustomHeight() > 0 ? imgSegment.getCustomHeight() : imgSegment.getHeight();
                filterStr.append("scale=").append(width).append(":").append(height);
            }
        } else {
            filterStr.append("scale=").append(imgSegment.getWidth()).append("*")
                    .append(imgSegment.getScale()).append(":")
                    .append(imgSegment.getHeight()).append("*")
                    .append(imgSegment.getScale());
        }

        List<Filter> segmentFilters = timelineState.getFilters().stream()
                .filter(f -> f.getSegmentId().equals(imgSegment.getId()))
                .toList();

        for (Filter filter : segmentFilters) {
            String filterName = filter.getFilterName().toLowerCase();
            String filterValue = filter.getFilterValue();
            switch (filterName) {
                // Color Adjustments
                case "brightness":
                    filterStr.append(",eq=brightness=").append(filterValue);
                    break;
                case "contrast":
                    filterStr.append(",eq=contrast=").append(filterValue);
                    break;
                case "saturation":
                    filterStr.append(",eq=saturation=").append(filterValue);
                    break;
                case "hue":
                    filterStr.append(",hue=h=").append(filterValue);
                    break;
                case "gamma":
                    filterStr.append(",eq=gamma=").append(filterValue);
                    break;
                case "colorbalance":
                    String[] rgb = filterValue.split(",");
                    if (rgb.length == 3) {
                        filterStr.append(",colorbalance=rs=").append(rgb[0])
                                .append(":gs=").append(rgb[1])
                                .append(":bs=").append(rgb[2]);
                    }
                    break;
                case "levels":
                    String[] levels = filterValue.split("/");
                    if (levels.length == 4) {
                        filterStr.append(",levels=rimin=").append(levels[0]).append(":rimax=").append(levels[1])
                                .append(":romin=").append(levels[2]).append(":romax=").append(levels[3]);
                    }
                    break;
                case "curves":
                    filterStr.append(",curves=").append(filterValue);
                    break;

                // Stylization
                case "grayscale":
                    filterStr.append(",hue=s=0");
                    break;
                case "sepia":
                    filterStr.append(",colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131:0");
                    break;
                case "vintage":
                    filterStr.append(",curves=preset=vintage");
                    break;
                case "posterize":
                    filterStr.append(",posterize=").append(filterValue);
                    break;
                case "solarize":
                    filterStr.append(",solarize=threshold=").append(filterValue);
                    break;
                case "invert":
                    filterStr.append(",negate");
                    break;

                // Blur and Sharpen
                case "blur":
                    filterStr.append(",gblur=sigma=").append(filterValue);
                    break;
                case "sharpen":
                    filterStr.append(",unsharp=5:5:").append(filterValue).append(":5:5:0");
                    break;
                case "edge":
                    filterStr.append(",edgedetect=mode=colormix:high=").append(filterValue)
                            .append(":low=").append(filterValue);
                    break;

                // Distortion and Noise
                case "noise":
                    filterStr.append(",noise=alls=").append(filterValue).append(":allf=t");
                    break;
                case "vignette":
                    filterStr.append(",vignette=PI/").append(filterValue);
                    break;
                case "pixelize":
                    filterStr.append(",pixelize=").append(filterValue).append(":").append(filterValue);
                    break;

                // Transformation
                case "rotate":
                    filterStr.append(",rotate=").append(filterValue).append("*PI/180");
                    break;
                case "flip":
                    if ("horizontal".equals(filterValue)) {
                        filterStr.append(",hflip");
                    } else if ("vertical".equals(filterValue)) {
                        filterStr.append(",vflip");
                    }
                    break;
                case "crop":
                    filterStr.append(",crop=").append(filterValue);
                    break;
                case "opacity":
                    filterStr.append(",format=rgba,colorchannelmixer=aa=").append(filterValue);
                    break;

                // Special Effects
                case "emboss":
                    filterStr.append(",convolution='-2 -1 0 -1 1 1 0 1 2'");
                    break;
                case "glow":
                    filterStr.append(",gblur=sigma=").append(filterValue).append(":steps=3");
                    filterStr.append(",blend=all_mode=glow:all_opacity=0.5");
                    break;
                case "overlay":
                    String[] overlayParts = filterValue.split("@");
                    if (overlayParts.length == 2) {
                        filterStr.append(",color=").append(overlayParts[0]).append(":s=iwxih[d];")
                                .append("[v][d]overlay=0:0:enable='between(t,0,9999)':format=rgb,")
                                .append("colorchannelmixer=aa=").append(overlayParts[1]);
                    }
                    break;

                default:
                    System.out.println("Unsupported filter: " + filterName);
            }
        }

        return filterStr.toString();
    }

    // Delete Video Segment from Timeline
    public void deleteVideoFromTimeline(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        boolean removed = timelineState.getSegments().removeIf(segment -> segment.getId().equals(segmentId));
        if (!removed) {
            throw new RuntimeException("Video segment not found with ID: " + segmentId);
        }

        // Remove associated filters
        timelineState.getFilters().removeIf(filter -> filter.getSegmentId().equals(segmentId));
        timelineState.syncLegacyFilters();
        session.setLastAccessTime(System.currentTimeMillis());
    }

    // Delete Image Segment from Timeline
    public void deleteImageFromTimeline(String sessionId, String imageId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        boolean removed = timelineState.getImageSegments().removeIf(segment -> segment.getId().equals(imageId));
        if (!removed) {
            throw new RuntimeException("Image segment not found with ID: " + imageId);
        }

        // Remove associated filters
        timelineState.getFilters().removeIf(filter -> filter.getSegmentId().equals(imageId));
        timelineState.syncLegacyFilters();
        session.setLastAccessTime(System.currentTimeMillis());
    }

    // Delete Audio Segment from Timeline
    public void deleteAudioFromTimeline(String sessionId, String audioId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        boolean removed = timelineState.getAudioSegments().removeIf(segment -> segment.getId().equals(audioId));
        if (!removed) {
            throw new RuntimeException("Audio segment not found with ID: " + audioId);
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    // Delete Text Segment from Timeline
    public void deleteTextFromTimeline(String sessionId, String textId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        boolean removed = timelineState.getTextSegments().removeIf(segment -> segment.getId().equals(textId));
        if (!removed) {
            throw new RuntimeException("Text segment not found with ID: " + textId);
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }
}