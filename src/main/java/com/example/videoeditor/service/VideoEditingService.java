package com.example.videoeditor.service;

import com.example.videoeditor.dto.*;
import com.example.videoeditor.entity.Project;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.EditedVideoRepository;
import com.example.videoeditor.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

    // NEW: Helper method to round doubles to three decimal places
    private double roundToThreeDecimals(Double value) {
        if (value == null) return 0.0;
        DecimalFormat df = new DecimalFormat("#.###");
        return Double.parseDouble(df.format(value));
    }

    // METHODS TO ADD THE AUDIO, VIDEO AND IMAGE
    // Moved from Project entity: Video handling methods
    public List<Map<String, String>> getVideos(Project project) throws JsonProcessingException {
        if (project.getVideosJson() == null || project.getVideosJson().isEmpty()) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(project.getVideosJson(), new TypeReference<List<Map<String, String>>>() {
        });
    }

    // Updated to include audioPath for tracking extracted audio
    public void addVideo(Project project, String videoPath, String videoFileName, String audioPath) throws JsonProcessingException {
        List<Map<String, String>> videos = getVideos(project);
        Map<String, String> videoData = new HashMap<>();
        videoData.put("videoPath", videoPath);
        videoData.put("videoFileName", videoFileName);
        if (audioPath != null) {
            videoData.put("audioPath", audioPath); // Store the audio path if provided
        }
        videos.add(videoData);
        project.setVideosJson(objectMapper.writeValueAsString(videos));
    }

    // Overloaded method to maintain compatibility with existing calls
    public void addVideo(Project project, String videoPath, String videoFileName) throws JsonProcessingException {
        addVideo(project, videoPath, videoFileName, null); // Call with null audioPath for backward compatibility
    }

    // Moved from Project entity: Image handling methods
    public List<Map<String, String>> getImages(Project project) throws JsonProcessingException {
        if (project.getImagesJson() == null || project.getImagesJson().isEmpty()) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(project.getImagesJson(), new TypeReference<List<Map<String, String>>>() {
        });
    }

    public void addImage(Project project, String imagePath, String imageFileName) throws JsonProcessingException {
        List<Map<String, String>> images = getImages(project);
        Map<String, String> imageData = new HashMap<>();
        imageData.put("imagePath", imagePath);
        imageData.put("imageFileName", imageFileName);
        images.add(imageData);
        project.setImagesJson(objectMapper.writeValueAsString(images));
    }

    // Moved from Project entity: Audio handling methods
    public List<Map<String, String>> getAudio(Project project) throws JsonProcessingException {
        if (project.getAudioJson() == null || project.getAudioJson().isEmpty()) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(project.getAudioJson(), new TypeReference<List<Map<String, String>>>() {
        });
    }

    public void addAudio(Project project, String audioPath, String audioFileName) throws JsonProcessingException {
        List<Map<String, String>> audioFiles = getAudio(project);
        Map<String, String> audioData = new HashMap<>();
        audioData.put("audioPath", audioPath);
        audioData.put("audioFileName", audioFileName);
        audioFiles.add(audioData);
        project.setAudioJson(objectMapper.writeValueAsString(audioFiles));
    }

    // Get extracted audio metadata from project
    public List<Map<String, String>> getExtractedAudio(Project project) throws JsonProcessingException {
        if (project.getExtractedAudioJson() == null || project.getExtractedAudioJson().isEmpty()) {
            return new ArrayList<>();
        }
        return objectMapper.readValue(project.getExtractedAudioJson(), new TypeReference<List<Map<String, String>>>() {});
    }

    // Add extracted audio metadata to project
    public void addExtractedAudio(Project project, String audioPath, String audioFileName, String sourceVideoPath) throws JsonProcessingException {
        List<Map<String, String>> extractedAudio = getExtractedAudio(project);
        Map<String, String> audioData = new HashMap<>();
        audioData.put("audioPath", audioPath);
        audioData.put("audioFileName", audioFileName);
        audioData.put("sourceVideoPath", sourceVideoPath);
        extractedAudio.add(audioData);
        project.setExtractedAudioJson(objectMapper.writeValueAsString(extractedAudio));
    }

    public Project createProject(User user, String name, Integer width, Integer height, Float fps) throws JsonProcessingException {
        Project project = new Project();
        project.setUser(user);
        project.setName(name);
        project.setStatus("DRAFT");
        project.setLastModified(LocalDateTime.now());
        project.setWidth(width != null ? width : 1920); // Default: 1920
        project.setHeight(height != null ? height : 1080); // Default: 1080
        project.setFps(fps != null ? fps : 25.0f);
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

            // Set canvas dimensions from project if not already set in TimelineState
            if (timelineState.getCanvasWidth() == null) {
                timelineState.setCanvasWidth(project.getWidth());
            }
            if (timelineState.getCanvasHeight() == null) {
                timelineState.setCanvasHeight(project.getHeight());
            }
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

        System.out.println("Saving timeline state with " + session.getTimelineState().getSegments().size() + " segments");

        String timelineStateJson = objectMapper.writeValueAsString(session.getTimelineState());
        project.setTimelineState(timelineStateJson);
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);

        System.out.println("Project saved successfully with timeline state: " + timelineStateJson);
    }

    @Scheduled(fixedRate = 3600000) // Every hour
    public void cleanupExpiredSessions() {
        long expiryTime = System.currentTimeMillis() - 3600000;
        activeSessions.entrySet().removeIf(entry ->
                entry.getValue().getLastAccessTime() < expiryTime);
    }

    private EditSession getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId))
                .orElseThrow(() -> new RuntimeException("No active session found"));
    }

    // Updated addVideoToTimeline method
    public void addVideoToTimeline(
            String sessionId,
            String videoPath,
            Integer layer,
            Double timelineStartTime,
            Double timelineEndTime,
            Double startTime,
            Double endTime,
            boolean createAudioSegment // New parameter
    ) throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);
        if (session == null) {
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }

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

        // Round time fields to three decimal places
        startTime = roundToThreeDecimals(startTime);
        endTime = roundToThreeDecimals(endTime);
        timelineStartTime = roundToThreeDecimals(timelineStartTime);

        if (timelineEndTime == null) {
            timelineEndTime = timelineStartTime + (endTime - startTime);
        }
        timelineEndTime = roundToThreeDecimals(timelineEndTime);

        if (!session.getTimelineState().isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + layer);
        }

        if (startTime < 0 || endTime > fullDuration || startTime >= endTime) {
            throw new RuntimeException("Invalid startTime or endTime for video segment");
        }

        Project project = projectRepository.findById(session.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found"));
        String audioPath = null;
        AudioSegment audioSegment = null;

        if (createAudioSegment) {
            String videoFileName = new File(videoPath).getName();
            String audioFileName = "extracted_" + videoFileName.replaceAll("[^a-zA-Z0-9.]", "_") + ".mp3";
            File projectAudioDir = new File(baseDir, "audio/projects/" + session.getProjectId() + "/extracted");
            File audioFile = new File(projectAudioDir, audioFileName);

            List<Map<String, String>> extractedAudio = getExtractedAudio(project);
            boolean audioExists = extractedAudio.stream()
                    .anyMatch(audio -> audio.get("sourceVideoPath").equals(videoPath) && audio.get("audioFileName").equals(audioFileName));

            if (audioExists && audioFile.exists()) {
                System.out.println("Reusing existing audio file: " + audioFile.getAbsolutePath());
                audioPath = "audio/projects/" + session.getProjectId() + "/extracted/" + audioFileName;
            } else {
                audioPath = extractAudioFromVideo(videoPath, session.getProjectId(), audioFileName);
                System.out.println("Extracted new audio file: " + audioPath);
                addExtractedAudio(project, audioPath, audioFileName, videoPath);
            }

            List<Map<String, String>> videos = getVideos(project);
            boolean videoExists = false;
            for (Map<String, String> video : videos) {
                if (video.get("videoPath").equals(videoPath)) {
                    video.put("audioPath", audioPath);
                    videoExists = true;
                    break;
                }
            }
            if (!videoExists) {
                addVideo(project, videoPath, videoFileName, audioPath);
            } else {
                project.setVideosJson(objectMapper.writeValueAsString(videos));
            }
            projectRepository.save(project);

            audioSegment = new AudioSegment();
            audioSegment.setAudioPath(audioPath);
            int audioLayer = findAvailableAudioLayer(session.getTimelineState(), timelineStartTime, timelineEndTime);
            audioSegment.setLayer(audioLayer);
            audioSegment.setStartTime(startTime);
            audioSegment.setEndTime(endTime);
            audioSegment.setTimelineStartTime(timelineStartTime);
            audioSegment.setTimelineEndTime(timelineEndTime);
            audioSegment.setVolume(1.0);
        }

        VideoSegment segment = new VideoSegment();
        segment.setSourceVideoPath(videoPath);
        segment.setStartTime(startTime);
        segment.setEndTime(endTime);
        segment.setPositionX(0);
        segment.setPositionY(0);
        segment.setScale(1.0);
        segment.setOpacity(1.0);
        segment.setLayer(layer);
        segment.setTimelineStartTime(timelineStartTime);
        segment.setTimelineEndTime(timelineEndTime);

        if (audioSegment != null) {
            segment.setAudioId(audioSegment.getId());
            session.getTimelineState().getAudioSegments().add(audioSegment);
        }

        if (session.getTimelineState() == null) {
            session.setTimelineState(new TimelineState());
        }

        session.getTimelineState().getSegments().add(segment);
        session.setLastAccessTime(System.currentTimeMillis());
    }
    // NEW: Helper method to find an available audio layer
    private int findAvailableAudioLayer(TimelineState timelineState, double timelineStartTime, double timelineEndTime) {
        int layer = -1; // Start with layer -1
        while (true) {
            final int currentLayer = layer;
            boolean hasOverlap = timelineState.getAudioSegments().stream()
                    .filter(a -> a.getLayer() == currentLayer)
                    .anyMatch(a -> {
                        double existingStart = a.getTimelineStartTime();
                        double existingEnd = a.getTimelineEndTime();
                        return timelineStartTime < existingEnd && timelineEndTime > existingStart;
                    });
            if (!hasOverlap) {
                return currentLayer;
            }
            layer--; // Try next layer (-2, -3, etc.)
        }
    }

    private String extractAudioFromVideo(String videoPath, Long projectId, String audioFileName) throws IOException, InterruptedException {
        File videoFile = new File(baseDir, "videos/" + videoPath);
        if (!videoFile.exists()) {
            throw new IOException("Video file not found: " + videoFile.getAbsolutePath());
        }

        // Store audio in project-specific extracted folder: audio/projects/{projectId}/extracted/
        File audioDir = new File(baseDir, "audio/projects/" + projectId + "/extracted");
        if (!audioDir.exists()) {
            audioDir.mkdirs(); // Create the project-specific extracted directory if it doesn't exist
        }

        File audioFile = new File(audioDir, audioFileName);

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(videoFile.getAbsolutePath());
        command.add("-vn"); // No video
        command.add("-acodec");
        command.add("mp3");
        command.add("-y"); // Overwrite output file if it exists
        command.add(audioFile.getAbsolutePath());

        executeFFmpegCommand(command);

        // Return relative path to the project's extracted folder
        return "audio/projects/" + projectId + "/extracted/" + audioFileName;
    }

    private double getVideoDuration(String videoPath) throws IOException, InterruptedException {
        String fullPath = baseDir + "/videos/" + videoPath;
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
                // MODIFIED: Round duration to three decimal places
                return roundToThreeDecimals(hours * 3600 + minutes * 60 + seconds);
            }
        }
        return 300; // Default to 5 minutes
    }

    public void updateVideoSegment(
            String sessionId,
            String segmentId,
            Integer positionX,
            Integer positionY,
            Double scale,
            Double opacity,
            Double timelineStartTime,
            Integer layer,
            Double timelineEndTime,
            Double startTime,
            Double endTime,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException, InterruptedException {
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

        double originalTimelineStartTime = segmentToUpdate.getTimelineStartTime();
        double originalTimelineEndTime = segmentToUpdate.getTimelineEndTime();
        double originalStartTime = segmentToUpdate.getStartTime();
        double originalEndTime = segmentToUpdate.getEndTime();
        int originalLayer = segmentToUpdate.getLayer();

        boolean timelineOrLayerChanged = false;

        if (keyframes != null && !keyframes.isEmpty()) {
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                List<Keyframe> kfs = entry.getValue();
                for (Keyframe kf : kfs) {
                    if (kf.getTime() < 0 || kf.getTime() > (segmentToUpdate.getTimelineEndTime() - segmentToUpdate.getTimelineStartTime())) {
                        throw new IllegalArgumentException("Keyframe time out of segment bounds for property " + property);
                    }
                    kf.setTime(roundToThreeDecimals(kf.getTime()));
                    segmentToUpdate.addKeyframe(property, kf);
                }
                switch (property) {
                    case "positionX":
                        segmentToUpdate.setPositionX(null);
                        break;
                    case "positionY":
                        segmentToUpdate.setPositionY(null);
                        break;
                    case "scale":
                        segmentToUpdate.setScale(null);
                        break;
                    case "opacity":
                        segmentToUpdate.setOpacity(null);
                        break;
                }
            }
        } else {
            if (positionX != null) segmentToUpdate.setPositionX(positionX);
            if (positionY != null) segmentToUpdate.setPositionY(positionY);
            if (scale != null) segmentToUpdate.setScale(scale);
            if (opacity != null) segmentToUpdate.setOpacity(opacity);
            if (layer != null) {
                segmentToUpdate.setLayer(layer);
                timelineOrLayerChanged = true;
            }

            if (timelineStartTime != null) {
                timelineStartTime = roundToThreeDecimals(timelineStartTime);
                segmentToUpdate.setTimelineStartTime(timelineStartTime);
                timelineOrLayerChanged = true;
            }
            if (timelineEndTime != null) {
                timelineEndTime = roundToThreeDecimals(timelineEndTime);
                segmentToUpdate.setTimelineEndTime(timelineEndTime);
                timelineOrLayerChanged = true;
            }
            if (startTime != null) {
                startTime = roundToThreeDecimals(startTime);
                segmentToUpdate.setStartTime(Math.max(0, startTime));
            }
            if (endTime != null) {
                endTime = roundToThreeDecimals(endTime);
                segmentToUpdate.setEndTime(endTime);
                double originalVideoDuration = getVideoDuration(segmentToUpdate.getSourceVideoPath());
                if (endTime > originalVideoDuration) {
                    segmentToUpdate.setEndTime(roundToThreeDecimals(originalVideoDuration));
                }
            }

            // Ensure timeline duration reflects rounded values
            double newTimelineDuration = roundToThreeDecimals(segmentToUpdate.getTimelineEndTime() - segmentToUpdate.getTimelineStartTime());
            double newClipDuration = roundToThreeDecimals(segmentToUpdate.getEndTime() - segmentToUpdate.getStartTime());
            if (newTimelineDuration < newClipDuration) {
                segmentToUpdate.setTimelineEndTime(roundToThreeDecimals(segmentToUpdate.getTimelineStartTime() + newClipDuration));
            }
        }

        // Validate timeline position with rounded values
        TimelineState timelineState = session.getTimelineState();
        timelineState.getSegments().remove(segmentToUpdate);
        boolean positionAvailable = timelineState.isTimelinePositionAvailable(
                segmentToUpdate.getTimelineStartTime(),
                segmentToUpdate.getTimelineEndTime(),
                segmentToUpdate.getLayer());
        timelineState.getSegments().add(segmentToUpdate);

        if (!positionAvailable) {
            segmentToUpdate.setTimelineStartTime(originalTimelineStartTime);
            segmentToUpdate.setTimelineEndTime(originalTimelineEndTime);
            segmentToUpdate.setStartTime(originalStartTime);
            segmentToUpdate.setEndTime(originalEndTime);
            segmentToUpdate.setLayer(originalLayer);
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + segmentToUpdate.getLayer());
        }

        // Update associated transitions if timelineStartTime or layer changed
        if (timelineOrLayerChanged) {
            updateAssociatedTransitions(
                    sessionId,
                    segmentId,
                    segmentToUpdate.getLayer(),
                    segmentToUpdate.getTimelineStartTime(),
                    segmentToUpdate.getTimelineEndTime()
            );
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
                                  String fontFamily, Double scale, String fontColor, String backgroundColor,
                                  Integer positionX, Integer positionY, Double opacity) {
        EditSession session = getSession(sessionId);
        timelineStartTime = roundToThreeDecimals(timelineStartTime);
        timelineEndTime = roundToThreeDecimals(timelineEndTime);

        if (!session.getTimelineState().isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            throw new IllegalArgumentException("Cannot add text: position overlaps with existing element in layer " + layer);
        }

        TextSegment textSegment = new TextSegment();
        textSegment.setText(text);
        textSegment.setLayer(layer);
        textSegment.setTimelineStartTime(timelineStartTime);
        textSegment.setTimelineEndTime(timelineEndTime);
        textSegment.setFontFamily(fontFamily != null ? fontFamily : "Arial");
        textSegment.setScale(scale != null ? scale : 1.0); // Set scale instead of fontSize
        textSegment.setFontColor(fontColor != null ? fontColor : "white");
        textSegment.setBackgroundColor(backgroundColor != null ? backgroundColor : "transparent");
        textSegment.setPositionX(positionX != null ? positionX : 0);
        textSegment.setPositionY(positionY != null ? positionY : 0);
        textSegment.setOpacity(opacity != null ? opacity : 1.0);

        session.getTimelineState().getTextSegments().add(textSegment);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void updateTextSegment(
            String sessionId,
            String segmentId,
            String text,
            String fontFamily,
            Double scale, // Replaced Integer fontSize
            String fontColor,
            String backgroundColor,
            Integer positionX,
            Integer positionY,
            Double opacity,
            Double timelineStartTime,
            Double timelineEndTime,
            Integer layer,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException {
        EditSession session = getSession(sessionId);
        TextSegment textSegment = session.getTimelineState().getTextSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Text segment not found with ID: " + segmentId));

        double originalTimelineStartTime = textSegment.getTimelineStartTime();
        double originalTimelineEndTime = textSegment.getTimelineEndTime();
        int originalLayer = textSegment.getLayer();

        boolean timelineOrLayerChanged = false;

        if (keyframes != null && !keyframes.isEmpty()) {
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                List<Keyframe> kfs = entry.getValue();
                for (Keyframe kf : kfs) {
                    if (kf.getTime() < 0 || kf.getTime() > (textSegment.getTimelineEndTime() - textSegment.getTimelineStartTime())) {
                        throw new IllegalArgumentException("Keyframe time out of segment bounds for property " + property);
                    }
                    kf.setTime(roundToThreeDecimals(kf.getTime()));
                    textSegment.addKeyframe(property, kf);
                }
                switch (property) {
                    case "positionX":
                        textSegment.setPositionX(null);
                        break;
                    case "positionY":
                        textSegment.setPositionY(null);
                        break;
                    case "opacity":
                        textSegment.setOpacity(null);
                        break;
                    case "scale": // Added support for scale keyframes
                        textSegment.setScale(null);
                        break;
                }
            }
        } else {
            if (text != null) textSegment.setText(text);
            if (fontFamily != null) textSegment.setFontFamily(fontFamily);
            if (scale != null) textSegment.setScale(scale); // Replaced fontSize
            if (fontColor != null) textSegment.setFontColor(fontColor);
            if (backgroundColor != null) textSegment.setBackgroundColor(backgroundColor);
            if (positionX != null) textSegment.setPositionX(positionX);
            if (positionY != null) textSegment.setPositionY(positionY);
            if (opacity != null) textSegment.setOpacity(opacity);
            if (timelineStartTime != null) {
                timelineStartTime = roundToThreeDecimals(timelineStartTime);
                textSegment.setTimelineStartTime(timelineStartTime);
                timelineOrLayerChanged = true;
            }
            if (timelineEndTime != null) {
                timelineEndTime = roundToThreeDecimals(timelineEndTime);
                textSegment.setTimelineEndTime(timelineEndTime);
                timelineOrLayerChanged = true;
            }
            if (layer != null) {
                textSegment.setLayer(layer);
                timelineOrLayerChanged = true;
            }
        }

        // Validate timeline position
        TimelineState timelineState = session.getTimelineState();
        timelineState.getTextSegments().remove(textSegment);
        boolean positionAvailable = timelineState.isTimelinePositionAvailable(
                textSegment.getTimelineStartTime(),
                textSegment.getTimelineEndTime(),
                textSegment.getLayer());
        timelineState.getTextSegments().add(textSegment);

        if (!positionAvailable) {
            textSegment.setTimelineStartTime(originalTimelineStartTime);
            textSegment.setTimelineEndTime(originalTimelineEndTime);
            textSegment.setLayer(originalLayer);
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + textSegment.getLayer());
        }

        // Update associated transitions if timelineStartTime or layer changed
        if (timelineOrLayerChanged) {
            updateAssociatedTransitions(
                    sessionId,
                    segmentId,
                    textSegment.getLayer(),
                    textSegment.getTimelineStartTime(),
                    textSegment.getTimelineEndTime()
            );
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public Project uploadAudioToProject(User user, Long projectId, MultipartFile[] audioFiles, String[] audioFileNames) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        File projectAudioDir = new File(baseDir, "audio/projects/" + projectId);
        if (!projectAudioDir.exists()) {
            projectAudioDir.mkdirs();
        }

        List<String> relativePaths = new ArrayList<>();
        for (int i = 0; i < audioFiles.length; i++) {
            MultipartFile audioFile = audioFiles[i];
            String originalFileName = audioFile.getOriginalFilename();
            // Use provided file name or generate a unique one
            String uniqueFileName = (audioFileNames != null && i < audioFileNames.length && audioFileNames[i] != null)
                    ? audioFileNames[i]
                    : projectId + "_" + System.currentTimeMillis() + "_" + originalFileName;

            File destinationFile = new File(projectAudioDir, uniqueFileName);
            audioFile.transferTo(destinationFile);

            String relativePath = "audio/projects/" + projectId + "/" + uniqueFileName;
            relativePaths.add(relativePath);

            try {
                addAudio(project, relativePath, uniqueFileName);
            } catch (JsonProcessingException e) {
                throw new IOException("Failed to process audio data for file: " + uniqueFileName, e);
            }
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
        if (layer >= 0) {
            throw new RuntimeException("Audio layers must be negative (e.g., -1, -2, -3)");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        String audioPath = null;

        // First, try to find the audio in audioJson
        List<Map<String, String>> audioFiles = getAudio(project);
        Map<String, String> targetAudio = audioFiles.stream()
                .filter(audio -> audio.get("audioFileName").equals(audioFileName) || audio.get("audioPath").equals(audioFileName))
                .findFirst()
                .orElse(null);

        if (targetAudio != null) {
            audioPath = targetAudio.get("audioPath");
        } else {
            // If not found in audioJson, try extractedAudioJson
            List<Map<String, String>> extractedAudios = getExtractedAudio(project);
            Map<String, String> extractedAudio = extractedAudios.stream()
                    .filter(audio -> {
                        String basename = audioFileName.contains("/") ?
                                audioFileName.substring(audioFileName.lastIndexOf("/") + 1) :
                                audioFileName;
                        return audio.get("audioFileName").equals(audioFileName) ||
                                audio.get("audioPath").equals(audioFileName) ||
                                audio.get("audioFileName").equals(basename) ||
                                audio.get("audioPath").equals(basename);
                    })
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No audio found with filename: " + audioFileName));
            audioPath = extractedAudio.get("audioPath");
        }

        // Round time fields to three decimal places
        startTime = roundToThreeDecimals(startTime);
        timelineStartTime = roundToThreeDecimals(timelineStartTime);
        double calculatedEndTime = endTime != null ? roundToThreeDecimals(endTime) :
                roundToThreeDecimals(getAudioDuration(audioPath));
        double calculatedTimelineEndTime = timelineEndTime != null ? roundToThreeDecimals(timelineEndTime) :
                roundToThreeDecimals(timelineStartTime + (calculatedEndTime - startTime));

        addAudioToTimeline(sessionId, audioPath, layer, startTime, calculatedEndTime, timelineStartTime, calculatedTimelineEndTime);
    }

    public void addAudioToTimeline(
            String sessionId,
            String audioPath,
            int layer,
            double startTime,
            double endTime,
            double timelineStartTime,
            Double timelineEndTime) throws IOException, InterruptedException {
        if (layer >= 0) {
            throw new RuntimeException("Audio layers must be negative (e.g., -1, -2, -3)");
        }

        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        File audioFile = new File(baseDir, audioPath);
        if (!audioFile.exists()) {
            throw new IOException("Audio file not found: " + audioFile.getAbsolutePath());
        }

        double audioDuration = getAudioDuration(audioPath);
        // MODIFIED: Round all input times
        startTime = roundToThreeDecimals(startTime);
        endTime = roundToThreeDecimals(endTime);
        timelineStartTime = roundToThreeDecimals(timelineStartTime);
        timelineEndTime = roundToThreeDecimals(timelineEndTime != null ? timelineEndTime : timelineStartTime + (endTime - startTime));

        if (startTime < 0 || endTime > audioDuration || startTime >= endTime) {
            throw new RuntimeException("Invalid audio start/end times");
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
        audioSegment.setVolume(1.0);

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
            Integer layer,
            Map<String, List<Keyframe>> keyframes) throws IOException, InterruptedException {
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

        if (keyframes != null && !keyframes.isEmpty()) {
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                List<Keyframe> kfs = entry.getValue();
                for (Keyframe kf : kfs) {
                    if (kf.getTime() < 0 || kf.getTime() > (targetSegment.getTimelineEndTime() - targetSegment.getTimelineStartTime())) {
                        throw new IllegalArgumentException("Keyframe time out of segment bounds for property " + property);
                    }
                    // MODIFIED: Round keyframe time to three decimal places
                    kf.setTime(roundToThreeDecimals(kf.getTime()));
                    targetSegment.addKeyframe(property, kf);
                }
                if ("volume".equals(property)) {
                    targetSegment.setVolume(null);
                }
            }
        } else {
            boolean timelineChanged = false;
            if (timelineStartTime != null) {
                // MODIFIED: Round timelineStartTime to three decimal places
                timelineStartTime = roundToThreeDecimals(timelineStartTime);
                targetSegment.setTimelineStartTime(timelineStartTime);
                timelineChanged = true;
            }
            // MODIFIED: Round timelineEndTime
            if (timelineEndTime != null) {
                timelineEndTime = roundToThreeDecimals(timelineEndTime);
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
                    // MODIFIED: Round startTime to three decimal places
                    startTime = roundToThreeDecimals(startTime);
                    if (startTime < 0 || startTime >= audioDuration) {
                        throw new RuntimeException("Start time out of bounds");
                    }
                    targetSegment.setStartTime(startTime);
                }
                if (endTime != null) {
                    // MODIFIED: Round endTime to three decimal places
                    endTime = roundToThreeDecimals(endTime);
                    if (endTime <= targetSegment.getStartTime() || endTime > audioDuration) {
                        throw new RuntimeException("End time out of bounds");
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
                    double newTimelineDuration = roundToThreeDecimals(targetSegment.getTimelineEndTime() - targetSegment.getTimelineStartTime());
                    double originalTimelineDuration = roundToThreeDecimals(originalTimelineEndTime - originalTimelineStartTime);
                    double originalAudioDuration = roundToThreeDecimals(originalEndTime - originalStartTime);

                    if (newTimelineDuration != originalTimelineDuration) {
                        double timelineShift = targetSegment.getTimelineStartTime() - originalTimelineStartTime;
                        double newStartTime = roundToThreeDecimals(originalStartTime + timelineShift);
                        if (newStartTime < 0) newStartTime = 0;
                        double newEndTime = roundToThreeDecimals(newStartTime + Math.min(newTimelineDuration, originalAudioDuration));
                        if (newEndTime > audioDuration) {
                            newEndTime = roundToThreeDecimals(audioDuration);
                            newStartTime = roundToThreeDecimals(newEndTime - newTimelineDuration);
                        }
                        targetSegment.setStartTime(newStartTime);
                        targetSegment.setEndTime(newEndTime);
                    }
                }
            }
            // MODIFIED: Ensure timeline duration reflects rounded values
            double newTimelineDuration = roundToThreeDecimals(targetSegment.getTimelineEndTime() - targetSegment.getTimelineStartTime());
            double newClipDuration = roundToThreeDecimals(targetSegment.getEndTime() - targetSegment.getStartTime());
            if (newTimelineDuration < newClipDuration) {
                targetSegment.setTimelineEndTime(roundToThreeDecimals(targetSegment.getTimelineStartTime() + newClipDuration));
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
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void removeAudioSegment(String sessionId, String audioSegmentId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        boolean removed = timelineState.getAudioSegments().removeIf(
                segment -> segment.getId().equals(audioSegmentId)
        );

        if (!removed) {
            throw new RuntimeException("Audio segment not found with ID: " + audioSegmentId);
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    private double getAudioDuration(String audioPath) throws IOException, InterruptedException {
        File audioFile = new File(baseDir, audioPath);
        if (!audioFile.exists()) {
            throw new IOException("Audio file not found: " + audioFile.getAbsolutePath());
        }

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
                // MODIFIED: Round duration to three decimal places
                return roundToThreeDecimals(hours * 3600 + minutes * 60 + seconds);
            }
        }
        return 300; // Default to 5 minutes
    }

    public Project uploadImageToProject(User user, Long projectId, MultipartFile[] imageFiles, String[] imageFileNames) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        File projectImageDir = new File(baseDir, "images/projects/" + projectId);
        if (!projectImageDir.exists()) {
            projectImageDir.mkdirs();
        }

        List<String> relativePaths = new ArrayList<>();
        for (int i = 0; i < imageFiles.length; i++) {
            MultipartFile imageFile = imageFiles[i];
            String originalFileName = imageFile.getOriginalFilename();
            // Use provided file name or generate a unique one
            String uniqueFileName = (imageFileNames != null && i < imageFileNames.length && imageFileNames[i] != null)
                    ? imageFileNames[i]
                    : projectId + "_" + System.currentTimeMillis() + "_" + originalFileName;

            File destinationFile = new File(projectImageDir, uniqueFileName);
            imageFile.transferTo(destinationFile);

            String relativePath = "images/projects/" + projectId + "/" + uniqueFileName;
            relativePaths.add(relativePath);

            try {
                addImage(project, relativePath, uniqueFileName);
            } catch (JsonProcessingException e) {
                throw new IOException("Failed to process image data for file: " + uniqueFileName, e);
            }
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
            String imageFileName,
            Double opacity) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        List<Map<String, String>> images = getImages(project);
        Map<String, String> targetImage = images.stream()
                .filter(img -> img.get("imageFileName").equals(imageFileName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No image found with filename: " + imageFileName));

        String imagePath = targetImage.get("imagePath");
        int positionX = 0;
        int positionY = 0;
        double scale = 1.0;
        // MODIFIED: Round timeline times to three decimal places
        timelineStartTime = timelineStartTime != null ? roundToThreeDecimals(timelineStartTime) : 0.0;
        if (timelineEndTime != null) {
            timelineEndTime = roundToThreeDecimals(timelineEndTime);
        }

        addImageToTimeline(sessionId, imagePath, layer, timelineStartTime, timelineEndTime, positionX, positionY, scale, opacity, filters);
    }

    public void addImageToTimeline(
            String sessionId,
            String imagePath,
            int layer,
            double timelineStartTime,
            Double timelineEndTime,
            Integer positionX,
            Integer positionY,
            Double scale,
            Double opacity,
            Map<String, String> filters
    ) {
        TimelineState timelineState = getTimelineState(sessionId);

        // MODIFIED: Round timeline times to three decimal places
        timelineStartTime = roundToThreeDecimals(timelineStartTime);
        if (timelineEndTime == null) {
            timelineEndTime = roundToThreeDecimals(timelineStartTime + 5.0);
        } else {
            timelineEndTime = roundToThreeDecimals(timelineEndTime);
        }

        if (!timelineState.isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            throw new RuntimeException("Timeline position overlaps with existing segment in layer " + layer);
        }

        ImageSegment imageSegment = new ImageSegment();
        imageSegment.setId(UUID.randomUUID().toString());
        imageSegment.setImagePath(imagePath);
        imageSegment.setLayer(layer);
        imageSegment.setPositionX(positionX != null ? positionX : 0);
        imageSegment.setPositionY(positionY != null ? positionY : 0);
        imageSegment.setScale(scale != null ? scale : 1.0);
        imageSegment.setOpacity(opacity != null ? opacity : 1.0);
        imageSegment.setTimelineStartTime(timelineStartTime);
        imageSegment.setTimelineEndTime(timelineEndTime == null ? timelineStartTime + 5.0 : timelineEndTime);

        try {
            File imageFile = new File(baseDir, imagePath);
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
        saveTimelineState(sessionId, timelineState);
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
            Double timelineEndTime,
            Map<String, List<Keyframe>> keyframes
    ) throws IOException {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = getTimelineState(sessionId);

        ImageSegment targetSegment = timelineState.getImageSegments().stream()
                .filter(segment -> segment.getId().equals(imageSegmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Image segment not found: " + imageSegmentId));

        double originalTimelineStartTime = targetSegment.getTimelineStartTime();
        double originalTimelineEndTime = targetSegment.getTimelineEndTime();
        int originalLayer = targetSegment.getLayer();

        boolean timelineOrLayerChanged = false;

        if (keyframes != null && !keyframes.isEmpty()) {
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                List<Keyframe> kfs = entry.getValue();
                for (Keyframe kf : kfs) {
                    if (kf.getTime() < 0 || kf.getTime() > (targetSegment.getTimelineEndTime() - targetSegment.getTimelineStartTime())) {
                        throw new IllegalArgumentException("Keyframe time out of segment bounds for property " + property);
                    }
                    kf.setTime(roundToThreeDecimals(kf.getTime()));
                    targetSegment.addKeyframe(property, kf);
                }
                switch (property) {
                    case "positionX":
                        targetSegment.setPositionX(null);
                        break;
                    case "positionY":
                        targetSegment.setPositionY(null);
                        break;
                    case "scale":
                        targetSegment.setScale(null);
                        break;
                    case "opacity":
                        targetSegment.setOpacity(null);
                        break;
                }
            }
        } else {
            if (positionX != null) targetSegment.setPositionX(positionX);
            if (positionY != null) targetSegment.setPositionY(positionY);
            if (scale != null) targetSegment.setScale(scale);
            if (opacity != null) targetSegment.setOpacity(opacity);
            if (layer != null) {
                targetSegment.setLayer(layer);
                timelineOrLayerChanged = true;
            }
            if (customWidth != null) targetSegment.setCustomWidth(customWidth);
            if (customHeight != null) targetSegment.setCustomHeight(customHeight);
            if (maintainAspectRatio != null) targetSegment.setMaintainAspectRatio(maintainAspectRatio);
            if (timelineStartTime != null) {
                timelineStartTime = roundToThreeDecimals(timelineStartTime);
                targetSegment.setTimelineStartTime(timelineStartTime);
                timelineOrLayerChanged = true;
            }
            if (timelineEndTime != null) {
                timelineEndTime = roundToThreeDecimals(timelineEndTime);
                targetSegment.setTimelineEndTime(timelineEndTime);
                timelineOrLayerChanged = true;
            }
            if (filters != null && !filters.isEmpty()) {
                for (Map.Entry<String, String> filter : filters.entrySet()) {
                    Filter newFilter = new Filter();
                    newFilter.setSegmentId(targetSegment.getId());
                    newFilter.setFilterName(filter.getKey());
                    newFilter.setFilterValue(filter.getValue());
                    timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(targetSegment.getId()) && f.getFilterName().equals(filter.getKey()));
                    timelineState.getFilters().add(newFilter);
                }
            }
            if (filtersToRemove != null && !filtersToRemove.isEmpty()) {
                timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(targetSegment.getId()) && filtersToRemove.contains(f.getFilterId()));
            }
        }

        // Validate timeline position
        timelineState.getImageSegments().remove(targetSegment);
        boolean positionAvailable = timelineState.isTimelinePositionAvailable(
                targetSegment.getTimelineStartTime(),
                targetSegment.getTimelineEndTime(),
                targetSegment.getLayer());
        timelineState.getImageSegments().add(targetSegment);

        if (!positionAvailable) {
            targetSegment.setTimelineStartTime(originalTimelineStartTime);
            targetSegment.setTimelineEndTime(originalTimelineEndTime);
            targetSegment.setLayer(originalLayer);
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + targetSegment.getLayer());
        }

        // Update associated transitions if timelineStartTime or layer changed
        if (timelineOrLayerChanged) {
            updateAssociatedTransitions(
                    sessionId,
                    imageSegmentId,
                    targetSegment.getLayer(),
                    targetSegment.getTimelineStartTime(),
                    targetSegment.getTimelineEndTime()
            );
        }

        session.setLastAccessTime(System.currentTimeMillis());
        saveTimelineState(sessionId, timelineState);
    }

    public void removeImageSegment(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = getTimelineState(sessionId);

        boolean removed = timelineState.getImageSegments().removeIf(
                segment -> segment.getId().equals(segmentId)
        );

        if (!removed) {
            throw new RuntimeException("Image segment not found with ID: " + segmentId);
        }

        timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(segmentId));
        session.setLastAccessTime(System.currentTimeMillis());
        saveTimelineState(sessionId, timelineState);
    }

    public void saveTimelineState(String sessionId, TimelineState timelineState) {
        EditSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new RuntimeException("Edit session not found: " + sessionId);
        }
        session.setTimelineState(timelineState);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public TimelineState getTimelineState(String sessionId) {
        EditSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new RuntimeException("Edit session not found: " + sessionId);
        }
        session.setLastAccessTime(System.currentTimeMillis());
        return session.getTimelineState();
    }

    public void addKeyframeToSegment(String sessionId, String segmentId, String segmentType, String property, Keyframe keyframe) {
        EditSession session = getSession(sessionId);
        switch (segmentType.toLowerCase()) {
            case "video":
                VideoSegment video = session.getTimelineState().getSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Video segment not found: " + segmentId));
                video.addKeyframe(property, keyframe);
                break;
            case "image":
                ImageSegment image = session.getTimelineState().getImageSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Image segment not found: " + segmentId));
                image.addKeyframe(property, keyframe);
                break;
            case "text":
                TextSegment text = session.getTimelineState().getTextSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Text segment not found: " + segmentId));
                text.addKeyframe(property, keyframe);
                break;
            case "audio":
                AudioSegment audio = session.getTimelineState().getAudioSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Audio segment not found: " + segmentId));
                audio.addKeyframe(property, keyframe);
                break;
            default:
                throw new IllegalArgumentException("Invalid segment type: " + segmentType);
        }
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void removeKeyframeFromSegment(String sessionId, String segmentId, String segmentType, String property, double time) {
        EditSession session = getSession(sessionId);
        switch (segmentType.toLowerCase()) {
            case "video":
                VideoSegment video = session.getTimelineState().getSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Video segment not found: " + segmentId));
                video.removeKeyframe(property, time);
                break;
            case "image":
                ImageSegment image = session.getTimelineState().getImageSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Image segment not found: " + segmentId));
                image.removeKeyframe(property, time);
                break;
            case "text":
                TextSegment text = session.getTimelineState().getTextSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Text segment not found: " + segmentId));
                text.removeKeyframe(property, time);
                break;
            case "audio":
                AudioSegment audio = session.getTimelineState().getAudioSegments().stream()
                        .filter(s -> s.getId().equals(segmentId))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Audio segment not found: " + segmentId));
                audio.removeKeyframe(property, time);
                break;
            default:
                throw new IllegalArgumentException("Invalid segment type: " + segmentType);
        }
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void deleteProjectFiles(Long projectId) throws IOException {
        // Define directories
        File videoDir = new File(baseDir, "videos/projects/" + projectId);
        File imageDir = new File(baseDir, "images/projects/" + projectId);
        File audioDir = new File(baseDir, "audio/projects/" + projectId);

        // Delete directories if they exist
        if (videoDir.exists()) {
            deleteDirectory(videoDir);
        }
        if (imageDir.exists()) {
            deleteDirectory(imageDir);
        }
        if (audioDir.exists()) {
            deleteDirectory(audioDir);
        }
    }

    private void deleteDirectory(File directory) throws IOException {
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
        }
        directory.delete();
    }

    public void addTransition(
            String sessionId,
            String type,
            double duration,
            String fromSegmentId,
            String toSegmentId,
            int layer,
            Map<String, String> parameters
    ) throws IOException {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        // Validate toSegment
        Segment toSegment = findSegment(timelineState, toSegmentId);
        if (toSegment == null) {
            throw new RuntimeException("To segment not found");
        }
        if (toSegment.getLayer() != layer) {
            throw new RuntimeException("To segment must be on the same layer as the transition");
        }

        // Validate fromSegment if provided
        Segment fromSegment = fromSegmentId != null ? findSegment(timelineState, fromSegmentId) : null;
        if (fromSegmentId != null && fromSegment == null) {
            throw new RuntimeException("From segment not found");
        }
        if (fromSegment != null) {
            if (fromSegment.getLayer() != layer) {
                throw new RuntimeException("From segment must be on the same layer as the transition");
            }
            if (Math.abs(fromSegment.getTimelineEndTime() - toSegment.getTimelineStartTime()) > 0.001) {
                throw new RuntimeException("Segments must be adjacent on the timeline");
            }
        }

        // Validate duration
        if (duration <= 0) {
            throw new RuntimeException("Invalid transition duration: Duration must be positive");
        }
        if (duration > toSegment.getTimelineEndTime() - toSegment.getTimelineStartTime()) {
            throw new RuntimeException("Invalid transition duration: Duration exceeds toSegment duration");
        }
        if (fromSegment != null && duration > fromSegment.getTimelineEndTime() - fromSegment.getTimelineStartTime()) {
            throw new RuntimeException("Invalid transition duration: Duration exceeds fromSegment duration");
        }

        // Calculate timeline start time
        double timelineStartTime = fromSegment != null
                ? roundToThreeDecimals(fromSegment.getTimelineEndTime() - duration)
                : roundToThreeDecimals(toSegment.getTimelineStartTime());

        // Check for overlapping transitions
        for (Transition existingTransition : timelineState.getTransitions()) {
            if (existingTransition.getLayer() == layer &&
                    timelineStartTime < existingTransition.getTimelineStartTime() + existingTransition.getDuration() &&
                    timelineStartTime + duration > existingTransition.getTimelineStartTime()) {
                throw new RuntimeException("Transition overlaps with an existing transition on layer " + layer);
            }
        }

        // Create and add transition
        Transition transition = new Transition();
        transition.setType(type);
        transition.setDuration(roundToThreeDecimals(duration));
        transition.setFromSegmentId(fromSegmentId);
        transition.setToSegmentId(toSegmentId);
        transition.setLayer(layer);
        transition.setTimelineStartTime(timelineStartTime);
        if (parameters != null) {
            transition.setParameters(parameters);
        }

        timelineState.getTransitions().add(transition);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public Transition updateTransition(
            String sessionId,
            String transitionId,
            String type,
            Double duration,
            String fromSegmentId,
            String toSegmentId,
            Integer layer,
            Map<String, String> parameters
    ) throws IOException {
        Logger log = LoggerFactory.getLogger(VideoEditingService.class);
        log.info("Updating transition: sessionId={}, transitionId={}, type={}, duration={}, fromSegmentId={}, toSegmentId={}, layer={}",
                sessionId, transitionId, type, duration, fromSegmentId, toSegmentId, layer);

        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        Transition transition = timelineState.getTransitions().stream()
                .filter(t -> t.getId().equals(transitionId))
                .findFirst()
                .orElseThrow(() -> {
                    log.error("Transition not found: transitionId={}", transitionId);
                    return new RuntimeException("Transition not found: " + transitionId);
                });

        // Store original values for rollback
        String originalType = transition.getType();
        double originalDuration = transition.getDuration();
        String originalFromSegmentId = transition.getFromSegmentId();
        String originalToSegmentId = transition.getToSegmentId();
        int originalLayer = transition.getLayer();
        double originalTimelineStartTime = transition.getTimelineStartTime();
        Map<String, String> originalParameters = transition.getParameters();

        // Update fields if provided
        if (type != null) transition.setType(type);
        if (duration != null) transition.setDuration(roundToThreeDecimals(duration));
        if (fromSegmentId != null) transition.setFromSegmentId(fromSegmentId);
        if (toSegmentId != null) transition.setToSegmentId(toSegmentId);
        if (layer != null) transition.setLayer(layer);
        if (parameters != null) transition.setParameters(parameters);

        // Validate updated transition
        Segment fromSegment = transition.getFromSegmentId() != null ? findSegment(timelineState, transition.getFromSegmentId()) : null;
        Segment toSegment = transition.getToSegmentId() != null ? findSegment(timelineState, transition.getToSegmentId()) : null;

        // Ensure at least one segment is present
        if (fromSegment == null && toSegment == null) {
            rollbackTransition(transition, originalType, originalDuration, originalFromSegmentId, originalToSegmentId, originalLayer, originalTimelineStartTime, originalParameters);
            throw new RuntimeException("At least one segment (fromSegment or toSegment) must be present");
        }

        // Validate layer consistency
        if (fromSegment != null && fromSegment.getLayer() != transition.getLayer()) {
            rollbackTransition(transition, originalType, originalDuration, originalFromSegmentId, originalToSegmentId, originalLayer, originalTimelineStartTime, originalParameters);
            throw new RuntimeException("fromSegment must be on the same layer as the transition");
        }
        if (toSegment != null && toSegment.getLayer() != transition.getLayer()) {
            rollbackTransition(transition, originalType, originalDuration, originalFromSegmentId, originalToSegmentId, originalLayer, originalTimelineStartTime, originalParameters);
            throw new RuntimeException("toSegment must be on the same layer as the transition");
        }

        // Validate adjacency if both segments are present
        if (fromSegment != null && toSegment != null) {
            if (Math.abs(fromSegment.getTimelineEndTime() - toSegment.getTimelineStartTime()) > 0.001) {
                rollbackTransition(transition, originalType, originalDuration, originalFromSegmentId, originalToSegmentId, originalLayer, originalTimelineStartTime, originalParameters);
                throw new RuntimeException("Segments must be adjacent on the timeline");
            }
        }

        // Validate duration
        if (transition.getDuration() <= 0) {
            rollbackTransition(transition, originalType, originalDuration, originalFromSegmentId, originalToSegmentId, originalLayer, originalTimelineStartTime, originalParameters);
            throw new RuntimeException("Invalid transition duration: Duration must be positive");
        }

        // Validate duration against segment boundaries
        double transitionEndTime = transition.getTimelineStartTime() + transition.getDuration();
        if (fromSegment != null && transitionEndTime > fromSegment.getTimelineEndTime() + 0.001) {
            rollbackTransition(transition, originalType, originalDuration, originalFromSegmentId, originalToSegmentId, originalLayer, originalTimelineStartTime, originalParameters);
            throw new RuntimeException("Invalid transition duration: Transition end time exceeds fromSegment end time");
        }
        if (toSegment != null && transitionEndTime > toSegment.getTimelineEndTime() + 0.001) {
            rollbackTransition(transition, originalType, originalDuration, originalFromSegmentId, originalToSegmentId, originalLayer, originalTimelineStartTime, originalParameters);
            throw new RuntimeException("Invalid transition duration: Transition end time exceeds toSegment end time");
        }

        // Check for overlapping transitions
        timelineState.getTransitions().remove(transition);
        for (Transition existingTransition : timelineState.getTransitions()) {
            if (existingTransition.getLayer() == transition.getLayer() &&
                    transition.getTimelineStartTime() < existingTransition.getTimelineStartTime() + existingTransition.getDuration() &&
                    transition.getTimelineStartTime() + transition.getDuration() > existingTransition.getTimelineStartTime()) {
                timelineState.getTransitions().add(transition);
                rollbackTransition(transition, originalType, originalDuration, originalFromSegmentId, originalToSegmentId, originalLayer, originalTimelineStartTime, originalParameters);
                throw new RuntimeException("Transition overlaps with an existing transition on layer " + transition.getLayer());
            }
        }
        timelineState.getTransitions().add(transition);

        // Recalculate timelineStartTime only if segments or layer changed
        if (fromSegmentId != null || toSegmentId != null || layer != null) {
            if (toSegment != null) {
                transition.setTimelineStartTime(roundToThreeDecimals(toSegment.getTimelineStartTime()));
            } else if (fromSegment != null) {
                transition.setTimelineStartTime(roundToThreeDecimals(fromSegment.getTimelineEndTime() - transition.getDuration()));
            }
        }

        session.setLastAccessTime(System.currentTimeMillis());
        log.info("Transition updated successfully: id={}", transition.getId());
        return transition;
    }

    // Updated rollback method to include parameters
    private void rollbackTransition(
            Transition transition,
            String type,
            double duration,
            String fromSegmentId,
            String toSegmentId,
            int layer,
            double timelineStartTime,
            Map<String, String> parameters
    ) {
        transition.setType(type);
        transition.setDuration(duration);
        transition.setFromSegmentId(fromSegmentId);
        transition.setToSegmentId(toSegmentId);
        transition.setLayer(layer);
        transition.setTimelineStartTime(timelineStartTime);
        transition.setParameters(parameters);
    }

    // NEW: Method to remove a transition
    public void removeTransition(String sessionId, String transitionId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        boolean removed = timelineState.getTransitions().removeIf(t -> t.getId().equals(transitionId));
        if (!removed) {
            throw new RuntimeException("Transition not found: " + transitionId);
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    // NEW: Helper method to find a segment by ID
    private Segment findSegment(TimelineState timelineState, String segmentId) {
        for (VideoSegment segment : timelineState.getSegments()) {
            if (segment.getId().equals(segmentId)) return segment;
        }
        for (ImageSegment segment : timelineState.getImageSegments()) {
            if (segment.getId().equals(segmentId)) return segment;
        }
        for (TextSegment segment : timelineState.getTextSegments()) {
            if (segment.getId().equals(segmentId)) return segment;
        }
        return null;
    }

    private void updateAssociatedTransitions(String sessionId, String segmentId, int newLayer, double newTimelineStartTime, double newTimelineEndTime) throws IOException {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        // Find transitions involving this segment
        List<Transition> transitionsToUpdate = timelineState.getTransitions().stream()
                .filter(t -> segmentId.equals(t.getFromSegmentId()) || segmentId.equals(t.getToSegmentId()))
                .collect(Collectors.toList());

        for (Transition transition : transitionsToUpdate) {
            // Update layer
            transition.setLayer(newLayer);

            // Recalculate timelineStartTime
            Segment fromSegment = transition.getFromSegmentId() != null ? findSegment(timelineState, transition.getFromSegmentId()) : null;
            Segment toSegment = findSegment(timelineState, transition.getToSegmentId());

            if (toSegment == null) {
                throw new RuntimeException("To segment not found for transition: " + transition.getId());
            }

            double timelineStartTime = fromSegment != null
                    ? roundToThreeDecimals(fromSegment.getTimelineEndTime() - transition.getDuration())
                    : roundToThreeDecimals(toSegment.getTimelineStartTime());

            transition.setTimelineStartTime(timelineStartTime);

            // Validate transition
            if (fromSegment != null && Math.abs(fromSegment.getTimelineEndTime() - toSegment.getTimelineStartTime()) > 0.001) {
                throw new RuntimeException("Segments must be adjacent after transition update for transition: " + transition.getId());
            }
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public File exportProject(String sessionId) throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);

        // Check if session exists
        if (session == null) {
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }

        // Get project details
        Project project = projectRepository.findById(session.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found"));

        // Create default output path
        String outputFileName = project.getName().replaceAll("[^a-zA-Z0-9]", "_") + "_"
                + System.currentTimeMillis() + ".mp4";
        String outputPath = "exports/" + outputFileName;

        // Ensure exports directory exists
        File exportsDir = new File("exports");
        if (!exportsDir.exists()) {
            exportsDir.mkdirs();
        }

        // Render the final video
        String exportedVideoPath = renderFinalVideo(session.getTimelineState(), outputPath, project.getWidth(), project.getHeight(), project.getFps());

        // Update project status to exported
        project.setStatus("EXPORTED");
        project.setLastModified(LocalDateTime.now());
        project.setExportedVideoPath(exportedVideoPath);

        try {
            project.setTimelineState(objectMapper.writeValueAsString(session.getTimelineState()));
        } catch (JsonProcessingException e) {
            System.err.println("Error saving timeline state: " + e.getMessage());
            // Continue with export even if saving timeline state fails
        }

        projectRepository.save(project);

        System.out.println("Project successfully exported to: " + exportedVideoPath);

        // Return the File object as per your original implementation
        return new File(exportedVideoPath);
    }

    private String renderFinalVideo(TimelineState timelineState, String outputPath, int canvasWidth, int canvasHeight, Float fps)
            throws IOException, InterruptedException {
        System.out.println("Rendering final video to: " + outputPath);

        if (timelineState.getCanvasWidth() != null) canvasWidth = timelineState.getCanvasWidth();
        if (timelineState.getCanvasHeight() != null) canvasHeight = timelineState.getCanvasHeight();

        File tempDir = new File("temp");
        if (!tempDir.exists()) tempDir.mkdirs();

        double totalDuration = Math.max(
                timelineState.getSegments().stream().mapToDouble(VideoSegment::getTimelineEndTime).max().orElse(0.0),
                Math.max(
                        timelineState.getImageSegments().stream().mapToDouble(ImageSegment::getTimelineEndTime).max().orElse(0.0),
                        Math.max(
                                timelineState.getTextSegments().stream().mapToDouble(TextSegment::getTimelineEndTime).max().orElse(0.0),
                                timelineState.getAudioSegments().stream().mapToDouble(AudioSegment::getTimelineEndTime).max().orElse(0.0)
                        )
                )
        );
        System.out.println("Total video duration: " + totalDuration + " seconds");

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);

        StringBuilder filterComplex = new StringBuilder();
        Map<String, String> videoInputIndices = new HashMap<>();
        Map<String, String> audioInputIndices = new HashMap<>();
        int inputCount = 0;

        filterComplex.append("color=c=black:s=").append(canvasWidth).append("x").append(canvasHeight)
                .append(":d=").append(totalDuration).append("[base];");

        for (VideoSegment vs : timelineState.getSegments()) {
            command.add("-i");
            command.add(baseDir + "\\videos\\" + vs.getSourceVideoPath());
            videoInputIndices.put(vs.getId(), String.valueOf(inputCount));
            audioInputIndices.put(vs.getId(), String.valueOf(inputCount));
            inputCount++;
        }

        for (ImageSegment is : timelineState.getImageSegments()) {
            command.add("-loop");
            command.add("1");
            command.add("-i");
            command.add(baseDir + "\\" + is.getImagePath());
            videoInputIndices.put(is.getId(), String.valueOf(inputCount++));
        }

        for (AudioSegment as : timelineState.getAudioSegments()) {
            command.add("-i");
            command.add(baseDir + "\\" + as.getAudioPath());
            audioInputIndices.put(as.getId(), String.valueOf(inputCount++));
        }

        List<Object> allSegments = new ArrayList<>();
        allSegments.addAll(timelineState.getSegments());
        allSegments.addAll(timelineState.getImageSegments());
        allSegments.addAll(timelineState.getTextSegments());

        allSegments.sort(Comparator.comparingInt(segment -> {
            if (segment instanceof VideoSegment) return ((VideoSegment) segment).getLayer();
            if (segment instanceof ImageSegment) return ((ImageSegment) segment).getLayer();
            if (segment instanceof TextSegment) return ((TextSegment) segment).getLayer();
            return 0;
        }));

        String lastOutput = "base";
        int overlayCount = 0;

        for (Object segment : allSegments) {
            String outputLabel = "ov" + overlayCount++;

            if (segment instanceof VideoSegment) {
                VideoSegment vs = (VideoSegment) segment;
                String inputIdx = videoInputIndices.get(vs.getId());

                filterComplex.append("[").append(inputIdx).append(":v]");
                filterComplex.append("trim=").append(vs.getStartTime()).append(":").append(vs.getEndTime()).append(",");
                filterComplex.append("setpts=PTS-STARTPTS+").append(vs.getTimelineStartTime()).append("/TB,");

                // Apply filters
                List<Filter> segmentFilters = timelineState.getFilters().stream()
                        .filter(f -> f.getSegmentId().equals(vs.getId()))
                        .collect(Collectors.toList());
                for (Filter filter : segmentFilters) {
                    if (filter == null || filter.getFilterName() == null || filter.getFilterName().trim().isEmpty()) {
                        System.err.println("Skipping invalid filter for segment " + vs.getId() + ": null or empty filter name");
                        continue;
                    }
                    String filterName = filter.getFilterName().toLowerCase().trim();
                    String filterValue = filter.getFilterValue() != null ? String.valueOf(filter.getFilterValue()) : "";
                    if (filterValue.isEmpty() && !Arrays.asList("grayscale", "sepia", "invert").contains(filterName)) {
                        System.err.println("Skipping filter " + filterName + " for segment " + vs.getId() + ": empty filter value");
                        continue;
                    }
                    try {
                        switch (filterName) {
                            case "brightness":
                                double brightness = Double.parseDouble(filterValue);
                                if (brightness >= -1 && brightness <= 1) {
                                    double cssBrightnessMultiplier = 1 + brightness;
                                    if (cssBrightnessMultiplier <= 0) {
                                        filterComplex.append("lutrgb=r=0:g=0:b=0,");
                                        break;
                                    }
                                    if (cssBrightnessMultiplier == 1.0) {
                                        break;
                                    }
                                    filterComplex.append("format=rgb24,");
                                    String lut = String.format(
                                            "lutrgb=r='val*%f':g='val*%f':b='val*%f',",
                                            cssBrightnessMultiplier, cssBrightnessMultiplier, cssBrightnessMultiplier
                                    );
                                    filterComplex.append(lut);
                                    filterComplex.append("format=yuv420p,");
                                }
                                break;
                            case "contrast":
                                double contrast = Double.parseDouble(filterValue);
                                if (contrast >= 0 && contrast <= 2) {
                                    if (contrast == 1.0) {
                                        break;
                                    }
                                    filterComplex.append("format=rgb24,");
                                    double offset = 128 * (1 - contrast);
                                    String lut = String.format(
                                            "lutrgb=r='clip(val*%f+%f,0,255)':g='clip(val*%f+%f,0,255)':b='clip(val*%f+%f,0,255)',",
                                            contrast, offset, contrast, offset, contrast, offset
                                    );
                                    filterComplex.append(lut);
                                    filterComplex.append("format=yuv420p,");
                                }
                                break;
                            case "saturation":
                                double saturation = Double.parseDouble(filterValue);
                                if (saturation >= 0 && saturation <= 2) {
                                    if (Math.abs(saturation - 1.0) < 0.01) {
                                        System.out.println("Skipping saturation filter for segment " + vs.getId() + ": value ≈ 1 (" + saturation + ")");
                                        break;
                                    }
                                    System.out.println("Applying saturation filter for segment " + vs.getId() + ": frontend=" + saturation);
                                    filterComplex.append("eq=saturation=").append(String.format("%.2f", saturation)).append(",");
                                }
                                break;
                            case "hue":
                                double hue = Double.parseDouble(filterValue);
                                if (hue >= -180 && hue <= 180) {
                                    if (hue == 0.0) {
                                        break;
                                    }
                                    filterComplex.append("hue=h=").append(String.format("%.1f", hue)).append(",");
                                }
                                break;
                            case "grayscale":
                                if (!filterValue.isEmpty() && Double.parseDouble(filterValue) > 0) {
                                    filterComplex.append("hue=s=0,");
                                }
                                break;
                            case "invert":
                                if (!filterValue.isEmpty() && Double.parseDouble(filterValue) > 0) {
                                    filterComplex.append("negate,");
                                }
                                break;
                            case "rotate":
                                double rotate = Double.parseDouble(filterValue);
                                if (rotate >= -180 && rotate <= 180) {
                                    double angleRad = Math.toRadians(rotate);
                                    filterComplex.append("rotate=").append(angleRad).append(":c=black,");
                                }
                                break;
                            case "flip":
                                if (filterValue.equals("horizontal")) {
                                    filterComplex.append("hflip,");
                                } else if (filterValue.equals("vertical")) {
                                    filterComplex.append("vflip,");
                                }
                                break;
                            default:
                                System.err.println("Unsupported filter: " + filterName + " for segment " + vs.getId());
                                break;
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid filter value for " + filterName + " in segment " + vs.getId() + ": " + filterValue);
                    }
                }

                // Apply transitions and get position and crop parameters
                List<Transition> relevantTransitions = timelineState.getTransitions().stream()
                        .filter(t -> (t.getToSegmentId() != null && t.getToSegmentId().equals(vs.getId())) ||
                                (t.getFromSegmentId() != null && t.getFromSegmentId().equals(vs.getId())))
                        .filter(t -> t.getLayer() == vs.getLayer())
                        .collect(Collectors.toList());

                Map<String, String> transitionOffsets = applyTransitionFilters(filterComplex, relevantTransitions, vs.getTimelineStartTime(), vs.getTimelineEndTime(), canvasWidth, canvasHeight);

// Apply crop filter for wipe transition
                boolean hasCrop = !transitionOffsets.get("cropWidth").equals("iw") || !transitionOffsets.get("cropHeight").equals("ih") ||
                        !transitionOffsets.get("cropX").equals("0") || !transitionOffsets.get("cropY").equals("0");
                if (hasCrop) {
                    double transStart = vs.getTimelineStartTime();
                    double transEnd = Math.min(vs.getTimelineStartTime() + 1.0, vs.getTimelineEndTime());
                    filterComplex.append("crop=")
                            .append("w='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropWidth")).append(",iw)':")
                            .append("h='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropHeight")).append(",ih)':")
                            .append("x='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropX")).append(",0)':")
                            .append("y='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropY")).append(",0)'")
                            .append(",");
                    System.out.println("Crop filter for segment " + vs.getId() + ": w=" + transitionOffsets.get("cropWidth") +
                            ", h=" + transitionOffsets.get("cropHeight") + ", x=" + transitionOffsets.get("cropX") + ", y=" + transitionOffsets.get("cropY") +
                            ", enabled between t=" + transStart + " and t=" + transEnd);
                }

                // Handle scaling with keyframes
                StringBuilder scaleExpr = new StringBuilder();
                List<Keyframe> scaleKeyframes = vs.getKeyframes().getOrDefault("scale", new ArrayList<>());
                double defaultScale = vs.getScale() != null ? vs.getScale() : 1.0;

                if (!scaleKeyframes.isEmpty()) {
                    Collections.sort(scaleKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) scaleKeyframes.get(0).getValue()).doubleValue();
                    scaleExpr.append(String.format("%.6f", firstKfValue));
                    for (int j = 1; j < scaleKeyframes.size(); j++) {
                        Keyframe prevKf = scaleKeyframes.get(j - 1);
                        Keyframe kf = scaleKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();

                        if (kfTime > prevTime) {
                            double timelinePrevTime = vs.getTimelineStartTime() + prevTime;
                            double timelineKfTime = vs.getTimelineStartTime() + kfTime;
                            scaleExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                    .append(",min(1,max(0,(t-").append(String.format("%.6f", timelinePrevTime)).append(")/(")
                                    .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", timelinePrevTime)).append("))))");
                        }
                    }
                } else {
                    scaleExpr.append(String.format("%.6f", defaultScale));
                }

                // Apply transition scale multiplier
                String transitionScale = transitionOffsets.get("scale");
                if (!transitionScale.equals("1")) {
                    scaleExpr.insert(0, "(").append(")*(").append(transitionScale).append(")");
                }

                filterComplex.append("scale=w='iw*").append(scaleExpr).append("':h='ih*").append(scaleExpr).append("':eval=frame[scaled").append(outputLabel).append("];");

                // Handle position X with keyframes
                StringBuilder xExpr = new StringBuilder();
                List<Keyframe> posXKeyframes = vs.getKeyframes().getOrDefault("positionX", new ArrayList<>());
                Integer defaultPosX = vs.getPositionX();
                double baseX = defaultPosX != null ? defaultPosX : 0;

                if (!posXKeyframes.isEmpty()) {
                    Collections.sort(posXKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                    xExpr.append(String.format("%.6f", firstKfValue));
                    for (int j = 1; j < posXKeyframes.size(); j++) {
                        Keyframe prevKf = posXKeyframes.get(j - 1);
                        Keyframe kf = posXKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();

                        if (kfTime > prevTime) {
                            double timelinePrevTime = vs.getTimelineStartTime() + prevTime;
                            double timelineKfTime = vs.getTimelineStartTime() + kfTime;
                            xExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                    .append(",min(1,max(0,(t-").append(String.format("%.6f", timelinePrevTime)).append(")/(")
                                    .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", timelinePrevTime)).append("))))");
                        }
                    }
                } else {
                    xExpr.append(String.format("%.6f", baseX));
                }

                // Add transition offset for x
                String xTransitionOffset = transitionOffsets.get("x");
                if (!xTransitionOffset.equals("0")) {
                    xExpr.append("+").append(xTransitionOffset);
                }
                xExpr.insert(0, "(W/2)+(").append(")-(w/2)");

                // Handle position Y with keyframes
                StringBuilder yExpr = new StringBuilder();
                List<Keyframe> posYKeyframes = vs.getKeyframes().getOrDefault("positionY", new ArrayList<>());
                Integer defaultPosY = vs.getPositionY();
                double baseY = defaultPosY != null ? defaultPosY : 0;

                if (!posYKeyframes.isEmpty()) {
                    Collections.sort(posYKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                    yExpr.append(String.format("%.6f", firstKfValue));
                    for (int j = 1; j < posYKeyframes.size(); j++) {
                        Keyframe prevKf = posYKeyframes.get(j - 1);
                        Keyframe kf = posYKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();

                        if (kfTime > prevTime) {
                            double timelinePrevTime = vs.getTimelineStartTime() + prevTime;
                            double timelineKfTime = vs.getTimelineStartTime() + kfTime;
                            yExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                    .append(",min(1,max(0,(t-").append(String.format("%.6f", timelinePrevTime)).append(")/(")
                                    .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", timelinePrevTime)).append("))))");
                        }
                    }
                } else {
                    yExpr.append(String.format("%.6f", baseY));
                }

                // Add transition offset for y
                String yTransitionOffset = transitionOffsets.get("y");
                if (!yTransitionOffset.equals("0")) {
                    yExpr.append("+").append(yTransitionOffset);
                }
                yExpr.insert(0, "(H/2)+(").append(")-(h/2)");

                // Overlay the scaled video onto the previous output
                filterComplex.append("[").append(lastOutput).append("][scaled").append(outputLabel).append("]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("':format=rgb");
                filterComplex.append(":enable='between(t,").append(vs.getTimelineStartTime()).append(",").append(vs.getTimelineEndTime()).append(")'");
                filterComplex.append("[ov").append(outputLabel).append("];");
                System.out.println("Video segment filter chain for " + vs.getId() + ": " +
                        filterComplex.substring(Math.max(0, filterComplex.length() - 200)));
                lastOutput = "ov" + outputLabel;
            } else if (segment instanceof ImageSegment) {
                ImageSegment is = (ImageSegment) segment;
                String inputIdx = videoInputIndices.get(is.getId());
                double segmentDuration = is.getTimelineEndTime() - is.getTimelineStartTime();

                filterComplex.append("[").append(inputIdx).append(":v]");
                filterComplex.append("trim=0:").append(String.format("%.6f", segmentDuration)).append(",");
                filterComplex.append("setpts=PTS-STARTPTS,");

                // Apply filters (unchanged)
                List<Filter> segmentFilters = timelineState.getFilters().stream()
                        .filter(f -> f.getSegmentId().equals(is.getId()))
                        .collect(Collectors.toList());
                for (Filter filter : segmentFilters) {
                    if (filter == null || filter.getFilterName() == null || filter.getFilterName().trim().isEmpty()) {
                        System.err.println("Skipping invalid filter for segment " + is.getId() + ": null or empty filter name");
                        continue;
                    }
                    String filterName = filter.getFilterName().toLowerCase().trim();
                    String filterValue = filter.getFilterValue() != null ? String.valueOf(filter.getFilterValue()) : "";
                    if (filterValue.isEmpty() && !Arrays.asList("grayscale", "sepia", "invert").contains(filterName)) {
                        System.err.println("Skipping filter " + filterName + " for segment " + is.getId() + ": empty filter value");
                        continue;
                    }
                    try {
                        switch (filterName) {
                            case "brightness":
                                double brightness = Double.parseDouble(filterValue);
                                if (brightness >= -1 && brightness <= 1) {
                                    double cssBrightnessMultiplier = 1 + brightness;
                                    if (cssBrightnessMultiplier <= 0) {
                                        filterComplex.append("lutrgb=r=0:g=0:b=0,");
                                        break;
                                    }
                                    if (cssBrightnessMultiplier == 1.0) {
                                        break;
                                    }
                                    filterComplex.append("format=rgb24,");
                                    String lut = String.format(
                                            "lutrgb=r='val*%f':g='val*%f':b='val*%f',",
                                            cssBrightnessMultiplier, cssBrightnessMultiplier, cssBrightnessMultiplier
                                    );
                                    filterComplex.append(lut);
                                    filterComplex.append("format=yuv420p,");
                                }
                                break;
                            case "contrast":
                                double contrast = Double.parseDouble(filterValue);
                                if (contrast >= 0 && contrast <= 2) {
                                    if (contrast == 1.0) {
                                        break;
                                    }
                                    filterComplex.append("format=rgb24,");
                                    double offset = 128 * (1 - contrast);
                                    String lut = String.format(
                                            "lutrgb=r='clip(val*%f+%f,0,255)':g='clip(val*%f+%f,0,255)':b='clip(val*%f+%f,0,255)',",
                                            contrast, offset, contrast, offset, contrast, offset
                                    );
                                    filterComplex.append(lut);
                                    filterComplex.append("format=yuv420p,");
                                }
                                break;
                            case "saturation":
                                double saturation = Double.parseDouble(filterValue);
                                if (saturation >= 0 && saturation <= 2) {
                                    if (Math.abs(saturation - 1.0) < 0.01) {
                                        System.out.println("Skipping saturation filter for segment " + is.getId() + ": value ≈ 1 (" + saturation + ")");
                                        break;
                                    }
                                    System.out.println("Applying saturation filter for segment " + is.getId() + ": frontend=" + saturation);
                                    filterComplex.append("eq=saturation=").append(String.format("%.2f", saturation)).append(",");
                                }
                                break;
                            case "hue":
                                double hue = Double.parseDouble(filterValue);
                                if (hue >= -180 && hue <= 180) {
                                    if (hue == 0.0) {
                                        break;
                                    }
                                    filterComplex.append("hue=h=").append(String.format("%.1f", hue)).append(",");
                                }
                                break;
                            case "grayscale":
                                if (!filterValue.isEmpty() && Double.parseDouble(filterValue) > 0) {
                                    filterComplex.append("hue=s=0,");
                                }
                                break;
                            case "invert":
                                if (!filterValue.isEmpty() && Double.parseDouble(filterValue) > 0) {
                                    filterComplex.append("negate,");
                                }
                                break;
                            case "rotate":
                                double rotate = Double.parseDouble(filterValue);
                                if (rotate >= -180 && rotate <= 180) {
                                    double angleRad = Math.toRadians(rotate);
                                    filterComplex.append("rotate=").append(angleRad).append(":c=black,");
                                }
                                break;
                            case "flip":
                                if (filterValue.equals("horizontal")) {
                                    filterComplex.append("hflip,");
                                } else if (filterValue.equals("vertical")) {
                                    filterComplex.append("vflip,");
                                }
                                break;
                            default:
                                System.err.println("Unsupported filter: " + filterName + " for segment " + is.getId());
                                break;
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Invalid filter value for " + filterName + " in segment " + is.getId() + ": " + filterValue);
                    }
                }

                // Apply transitions and get position and crop parameters
                List<Transition> relevantTransitions = timelineState.getTransitions().stream()
                        .filter(t -> (t.getToSegmentId() != null && t.getToSegmentId().equals(is.getId())) ||
                                (t.getFromSegmentId() != null && t.getFromSegmentId().equals(is.getId())))
                        .filter(t -> t.getLayer() == is.getLayer())
                        .collect(Collectors.toList());

                Map<String, String> transitionOffsets = applyTransitionFilters(filterComplex, relevantTransitions, is.getTimelineStartTime(), is.getTimelineEndTime(), canvasWidth, canvasHeight);

                // Log transition details for debugging
                for (Transition t : relevantTransitions) {
                    System.out.println("Transition for ImageSegment " + is.getId() + ": type=" + t.getType() +
                            ", toSegmentId=" + t.getToSegmentId() + ", fromSegmentId=" + t.getFromSegmentId() +
                            ", timelineStartTime=" + t.getTimelineStartTime() + ", duration=" + t.getDuration());
                }

                // Apply crop filter for wipe transition with time-based condition
                boolean hasCrop = !transitionOffsets.get("cropWidth").equals("iw") || !transitionOffsets.get("cropHeight").equals("ih") ||
                        !transitionOffsets.get("cropX").equals("0") || !transitionOffsets.get("cropY").equals("0");
                if (hasCrop) {
                    double transStart = is.getTimelineStartTime();
                    double transEnd = Math.min(is.getTimelineStartTime() + 1.0, is.getTimelineEndTime());
                    filterComplex.append("crop=")
                            .append("w='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropWidth")).append(",iw)':")
                            .append("h='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropHeight")).append(",ih)':")
                            .append("x='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropX")).append(",0)':")
                            .append("y='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropY")).append(",0)'")
                            .append(",");
                    System.out.println("Crop filter for image segment " + is.getId() + ": w=" + transitionOffsets.get("cropWidth") +
                            ", h=" + transitionOffsets.get("cropHeight") + ", x=" + transitionOffsets.get("cropX") + ", y=" + transitionOffsets.get("cropY") +
                            ", enabled between t=" + transStart + " and t=" + transEnd);
                }

                // Handle scaling with keyframes
                StringBuilder scaleExpr = new StringBuilder();
                List<Keyframe> scaleKeyframes = is.getKeyframes().getOrDefault("scale", new ArrayList<>());
                double defaultScale = is.getScale() != null ? is.getScale() : 1.0;

                if (!scaleKeyframes.isEmpty()) {
                    Collections.sort(scaleKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) scaleKeyframes.get(0).getValue()).doubleValue();
                    scaleExpr.append(String.format("%.6f", firstKfValue));
                    for (int j = 1; j < scaleKeyframes.size(); j++) {
                        Keyframe prevKf = scaleKeyframes.get(j - 1);
                        Keyframe kf = scaleKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();

                        if (kfTime > prevTime) {
                            double timelinePrevTime = is.getTimelineStartTime() + prevTime;
                            double timelineKfTime = is.getTimelineStartTime() + kfTime;
                            scaleExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                    .append(",min(1,max(0,(t-").append(String.format("%.6f", timelinePrevTime)).append(")/(")
                                    .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", timelinePrevTime)).append("))))");
                        }
                    }
                } else {
                    scaleExpr.append(String.format("%.6f", defaultScale));
                }

                // Apply transition scale multiplier for zoom effect
                String transitionScale = transitionOffsets.get("scale");
                if (!transitionScale.equals("1")) {
                    scaleExpr.insert(0, "(").append(")*(").append(transitionScale).append(")");
                    System.out.println("Applying zoom transition scale for ImageSegment " + is.getId() + ": " + transitionScale +
                            ", segmentStartTime=" + is.getTimelineStartTime() + ", segmentEndTime=" + is.getTimelineEndTime());
                } else {
                    System.out.println("No zoom transition scale applied for ImageSegment " + is.getId() +
                            ": transitionScale=" + transitionScale + ", segmentStartTime=" + is.getTimelineStartTime());
                }

                filterComplex.append("scale=w='iw*").append(scaleExpr).append("':h='ih*").append(scaleExpr).append("':eval=frame[scaled").append(outputLabel).append("];");
                System.out.println("Image segment filter chain before overlay for " + is.getId() + ": " +
                        filterComplex.substring(Math.max(0, filterComplex.length() - 200)));

                // Handle position X with keyframes
                StringBuilder xExpr = new StringBuilder();
                List<Keyframe> posXKeyframes = is.getKeyframes().getOrDefault("positionX", new ArrayList<>());
                Integer defaultPosX = is.getPositionX();
                double baseX = defaultPosX != null ? defaultPosX : 0;

                if (!posXKeyframes.isEmpty()) {
                    Collections.sort(posXKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                    xExpr.append(String.format("%.6f", firstKfValue));
                    for (int j = 1; j < posXKeyframes.size(); j++) {
                        Keyframe prevKf = posXKeyframes.get(j - 1);
                        Keyframe kf = posXKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();

                        if (kfTime > prevTime) {
                            double timelinePrevTime = is.getTimelineStartTime() + prevTime;
                            double timelineKfTime = is.getTimelineStartTime() + kfTime;
                            xExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                    .append(",min(1,max(0,(t-").append(String.format("%.6f", timelinePrevTime)).append(")/(")
                                    .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", timelinePrevTime)).append("))))");
                        }
                    }
                } else {
                    xExpr.append(String.format("%.6f", baseX));
                }

                // Add transition offset for x
                String xTransitionOffset = transitionOffsets.get("x");
                if (!xTransitionOffset.equals("0")) {
                    xExpr.append("+").append(xTransitionOffset);
                }
                xExpr.insert(0, "(W/2)+(").append(")-(w/2)");

                // Handle position Y with keyframes
                StringBuilder yExpr = new StringBuilder();
                List<Keyframe> posYKeyframes = is.getKeyframes().getOrDefault("positionY", new ArrayList<>());
                Integer defaultPosY = is.getPositionY();
                double baseY = defaultPosY != null ? defaultPosY : 0;

                if (!posYKeyframes.isEmpty()) {
                    Collections.sort(posYKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                    yExpr.append(String.format("%.6f", firstKfValue));
                    for (int j = 1; j < posYKeyframes.size(); j++) {
                        Keyframe prevKf = posYKeyframes.get(j - 1);
                        Keyframe kf = posYKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();

                        if (kfTime > prevTime) {
                            double timelinePrevTime = is.getTimelineStartTime() + prevTime;
                            double timelineKfTime = is.getTimelineStartTime() + kfTime;
                            yExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                    .append(",min(1,max(0,(t-").append(String.format("%.6f", timelinePrevTime)).append(")/(")
                                    .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", timelinePrevTime)).append("))))");
                        }
                    }
                } else {
                    yExpr.append(String.format("%.6f", baseY));
                }

                // Add transition offset for y
                String yTransitionOffset = transitionOffsets.get("y");
                if (!yTransitionOffset.equals("0")) {
                    yExpr.append("+").append(yTransitionOffset);
                }
                yExpr.insert(0, "(H/2)+(").append(")-(h/2)");

                // Overlay the scaled image onto the previous output
                filterComplex.append("[").append(lastOutput).append("][scaled").append(outputLabel).append("]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("':format=rgb");
                filterComplex.append(":enable='between(t,").append(String.format("%.6f", is.getTimelineStartTime())).append(",").append(String.format("%.6f", is.getTimelineEndTime())).append(")'");
                filterComplex.append("[ov").append(outputLabel).append("];");
                System.out.println("Image segment filter chain after overlay for " + is.getId() + ": " +
                        filterComplex.substring(Math.max(0, filterComplex.length() - 200)));
                lastOutput = "ov" + outputLabel;
            } else if (segment instanceof TextSegment) {
                TextSegment ts = (TextSegment) segment;

                // Apply transitions and get position and crop parameters
                List<Transition> relevantTransitions = timelineState.getTransitions().stream()
                        .filter(t -> (t.getToSegmentId() != null && t.getToSegmentId().equals(ts.getId())) ||
                                (t.getFromSegmentId() != null && t.getFromSegmentId().equals(ts.getId())))
                        .filter(t -> t.getLayer() == ts.getLayer())
                        .collect(Collectors.toList());

                Map<String, String> transitionOffsets = applyTransitionFilters(filterComplex, relevantTransitions, ts.getTimelineStartTime(), ts.getTimelineEndTime(), canvasWidth, canvasHeight);

                filterComplex.append("[").append(lastOutput).append("]");
                filterComplex.append("drawtext=");
                filterComplex.append("text='").append(ts.getText().replace("'", "\\'")).append("':");
                filterComplex.append("enable='between(t,").append(String.format("%.6f", ts.getTimelineStartTime())).append(",").append(String.format("%.6f", ts.getTimelineEndTime())).append(")':");
                filterComplex.append("fontcolor=").append(ts.getFontColor()).append(":");
                filterComplex.append("fontfile='").append(getFontPathByFamily(ts.getFontFamily())).append("':");

                // Handle background color
                if (ts.getBackgroundColor() != null && !ts.getBackgroundColor().equals("transparent")) {
                    filterComplex.append("box=1:boxcolor=").append(ts.getBackgroundColor()).append("@0.5:");
                }

                // Handle scale with keyframes
                StringBuilder fontSizeExpr = new StringBuilder();
                List<Keyframe> scaleKeyframes = ts.getKeyframes().getOrDefault("scale", new ArrayList<>());
                double defaultScale = ts.getScale() != null ? ts.getScale() : 1.0;
                double baseFontSize = 24.0; // Base font size for scaling

                if (!scaleKeyframes.isEmpty()) {
                    Collections.sort(scaleKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    // Use default scale before first keyframe
                    double firstKfTime = scaleKeyframes.get(0).getTime();
                    fontSizeExpr.append(String.format("if(lt(t,%.6f),%.6f,", ts.getTimelineStartTime() + firstKfTime, defaultScale * baseFontSize));

                    // Interpolate between keyframes
                    for (int j = 0; j < scaleKeyframes.size() - 1; j++) {
                        Keyframe currentKf = scaleKeyframes.get(j);
                        Keyframe nextKf = scaleKeyframes.get(j + 1);
                        double currentTime = currentKf.getTime();
                        double nextTime = nextKf.getTime();
                        double currentValue = ((Number) currentKf.getValue()).doubleValue();
                        double nextValue = ((Number) nextKf.getValue()).doubleValue();
                        double timelineCurrentTime = ts.getTimelineStartTime() + currentTime;
                        double timelineNextTime = ts.getTimelineStartTime() + nextTime;

                        if (nextTime > currentTime) {
                            fontSizeExpr.append(String.format("if(between(t,%.6f,%.6f),%.6f+((t-%.6f)/(%.6f-%.6f))*(%.6f-%.6f),",
                                    timelineCurrentTime, timelineNextTime,
                                    currentValue * baseFontSize,
                                    timelineCurrentTime, timelineNextTime, timelineCurrentTime,
                                    nextValue * baseFontSize, currentValue * baseFontSize));
                        }
                    }

                    // Use last keyframe value after its time
                    double lastKfValue = ((Number) scaleKeyframes.get(scaleKeyframes.size() - 1).getValue()).doubleValue();
                    fontSizeExpr.append(String.format("%.6f", lastKfValue * baseFontSize));

                    // Close all if expressions
                    for (int j = 0; j < scaleKeyframes.size(); j++) {
                        fontSizeExpr.append(")");
                    }

                    System.out.println("TextSegment " + ts.getId() + " fontSizeExpr with keyframes: " + fontSizeExpr);
                } else {
                    fontSizeExpr.append(String.format("%.6f", defaultScale * baseFontSize));
                    System.out.println("TextSegment " + ts.getId() + " default fontSizeExpr: " + fontSizeExpr);
                }

                // Apply transition scale multiplier for zoom effect
                String transitionScale = transitionOffsets.get("scale");
                if (!transitionScale.equals("1")) {
                    fontSizeExpr.insert(0, "(").append(")*(").append(transitionScale).append(")");
                    System.out.println("Applying zoom transition scale for TextSegment " + ts.getId() + ": " + transitionScale +
                            ", segmentStartTime=" + ts.getTimelineStartTime() + ", segmentEndTime=" + ts.getTimelineEndTime());
                } else {
                    System.out.println("No zoom transition scale applied for TextSegment " + ts.getId() +
                            ": transitionScale=" + transitionScale + ", segmentStartTime=" + ts.getTimelineStartTime());
                }

                filterComplex.append("fontsize=").append(fontSizeExpr).append(":");

                // Handle position X with keyframes (original logic preserved, only expression formatting updated)
                StringBuilder xExpr = new StringBuilder();
                List<Keyframe> posXKeyframes = ts.getKeyframes().getOrDefault("positionX", new ArrayList<>());
                Integer defaultPosX = ts.getPositionX();
                double baseX = defaultPosX != null ? defaultPosX : 0;

                if (!posXKeyframes.isEmpty()) {
                    Collections.sort(posXKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                    xExpr.append(String.format("%.6f", firstKfValue));
                    for (int j = 1; j < posXKeyframes.size(); j++) {
                        Keyframe prevKf = posXKeyframes.get(j - 1);
                        Keyframe kf = posXKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();

                        if (kfTime > prevTime) {
                            double timelinePrevTime = ts.getTimelineStartTime() + prevTime;
                            double timelineKfTime = ts.getTimelineStartTime() + kfTime;
                            xExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                    .append(",min(1,max(0,(t-").append(String.format("%.6f", timelinePrevTime)).append(")/(")
                                    .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", timelinePrevTime)).append("))))");
                        }
                    }
                } else {
                    xExpr.append(String.format("%.6f", baseX));
                }

                // Add transition offset for x
                String xTransitionOffset = transitionOffsets.get("x");
                if (!xTransitionOffset.equals("0")) {
                    xExpr.append("+").append(xTransitionOffset);
                }
                // Enclose in quotes with explicit parentheses
                xExpr.insert(0, "'(main_w/2)+(").append(")-(text_w/2)'");

                // Handle position Y with keyframes (original logic preserved, only expression formatting updated)
                StringBuilder yExpr = new StringBuilder();
                List<Keyframe> posYKeyframes = ts.getKeyframes().getOrDefault("positionY", new ArrayList<>());
                Integer defaultPosY = ts.getPositionY();
                double baseY = defaultPosY != null ? defaultPosY : 0;

                if (!posYKeyframes.isEmpty()) {
                    Collections.sort(posYKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                    yExpr.append(String.format("%.6f", firstKfValue));
                    for (int j = 1; j < posYKeyframes.size(); j++) {
                        Keyframe prevKf = posYKeyframes.get(j - 1);
                        Keyframe kf = posYKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();

                        if (kfTime > prevTime) {
                            double timelinePrevTime = ts.getTimelineStartTime() + prevTime;
                            double timelineKfTime = ts.getTimelineStartTime() + kfTime;
                            yExpr.insert(0, "lerp(").append(",").append(String.format("%.6f", kfValue))
                                    .append(",min(1,max(0,(t-").append(String.format("%.6f", timelinePrevTime)).append(")/(")
                                    .append(String.format("%.6f", timelineKfTime)).append("-").append(String.format("%.6f", timelinePrevTime)).append("))))");
                        }
                    }
                } else {
                    yExpr.append(String.format("%.6f", baseY));
                }

                // Add transition offset for y
                String yTransitionOffset = transitionOffsets.get("y");
                if (!xTransitionOffset.equals("0")) {
                    yExpr.append("+").append(yTransitionOffset);
                }
                // Enclose in quotes with explicit parentheses
                yExpr.insert(0, "'(main_h/2)+(").append(")-(text_h/2)'");

                filterComplex.append("x=").append(xExpr).append(":");
                filterComplex.append("y=").append(yExpr);

                // Apply crop filter for wipe transition if needed
                boolean hasCrop = !transitionOffsets.get("cropWidth").equals("iw") || !transitionOffsets.get("cropHeight").equals("ih") ||
                        !transitionOffsets.get("cropX").equals("0") || !transitionOffsets.get("cropY").equals("0");
                if (hasCrop) {
                    double transStart = ts.getTimelineStartTime();
                    double transEnd = Math.min(ts.getTimelineStartTime() + 1.0, ts.getTimelineEndTime());
                    filterComplex.append(",crop=")
                            .append("w='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropWidth")).append(",iw)':")
                            .append("h='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropHeight")).append(",ih)':")
                            .append("x='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropX")).append(",0)':")
                            .append("y='if(between(t,").append(String.format("%.6f", transStart)).append(",")
                            .append(String.format("%.6f", transEnd)).append("),").append(transitionOffsets.get("cropY")).append(",0)'");
                    System.out.println("Crop filter for text segment " + ts.getId() + ": w=" + transitionOffsets.get("cropWidth") +
                            ", h=" + transitionOffsets.get("cropHeight") + ", x=" + transitionOffsets.get("cropX") + ", y=" + transitionOffsets.get("cropY") +
                            ", enabled between t=" + transStart + " and t=" + transEnd);
                }

                filterComplex.append("[ov").append(outputLabel).append("];");
                System.out.println("Text segment filter chain for " + ts.getId() + ": " +
                        filterComplex.substring(Math.max(0, filterComplex.length() - 200)));
                lastOutput = "ov" + outputLabel;
            }
        }

        List<String> audioOutputs = new ArrayList<>();
        int audioCount = 0;

        for (AudioSegment as : timelineState.getAudioSegments()) {
            String inputIdx = audioInputIndices.get(as.getId());
            String audioOutput = "aa" + audioCount++;
            double audioStart = as.getStartTime();
            double audioEnd = as.getEndTime();
            double timelineStart = as.getTimelineStartTime();
            double timelineEnd = as.getTimelineEndTime();
            double sourceDuration = audioEnd - audioStart;
            double timelineDuration = timelineEnd - timelineStart;

            if (audioStart < 0 || audioEnd <= audioStart || timelineStart < 0 || timelineEnd <= timelineStart) {
                System.err.println("Invalid timing for audio segment " + as.getId());
                continue;
            }
            if (Math.abs(timelineDuration - sourceDuration) > 0.01) {
                System.err.println("Warning: Audio segment " + as.getId() + " duration mismatch");
            }

            filterComplex.append("[").append(inputIdx).append(":a]");
            filterComplex.append("atrim=").append(audioStart).append(":").append(audioEnd).append(",");
            filterComplex.append("asetpts=PTS-STARTPTS,");

            List<Keyframe> volumeKeyframes = as.getKeyframes().getOrDefault("volume", new ArrayList<>());
            if (!volumeKeyframes.isEmpty()) {
                StringBuilder volumeExpr = new StringBuilder();
                double defaultVolume = as.getVolume() != null ? as.getVolume() : 1.0;

                Collections.sort(volumeKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                double firstKfTime = volumeKeyframes.get(0).getTime();
                double firstKfValue = ((Number) volumeKeyframes.get(0).getValue()).doubleValue();
                volumeExpr.append("if(lt(t,").append(String.format("%.6f", firstKfTime)).append("),").append(String.format("%.6f", defaultVolume));

                for (int j = 1; j < volumeKeyframes.size(); j++) {
                    Keyframe prevKf = volumeKeyframes.get(j - 1);
                    Keyframe kf = volumeKeyframes.get(j);
                    double prevTime = prevKf.getTime();
                    double kfTime = kf.getTime();
                    double prevValue = ((Number) prevKf.getValue()).doubleValue();
                    double kfValue = ((Number) kf.getValue()).doubleValue();

                    if (kfTime > prevTime) {
                        volumeExpr.append(",if(between(t,").append(String.format("%.6f", prevTime)).append(",").append(String.format("%.6f", kfTime)).append("),");
                        volumeExpr.append(String.format("%.6f", prevValue)).append("+((t-").append(String.format("%.6f", prevTime)).append(")/(")
                                .append(String.format("%.6f", kfTime)).append("-").append(String.format("%.6f", prevTime)).append("))*(")
                                .append(String.format("%.6f", kfValue)).append("-").append(String.format("%.6f", prevValue)).append(")");
                    }
                }
                volumeExpr.append(",").append(String.format("%.6f", ((Number) volumeKeyframes.get(volumeKeyframes.size() - 1).getValue()).doubleValue()));
                for (int j = 0; j < volumeKeyframes.size(); j++) {
                    volumeExpr.append(")");
                }

                filterComplex.append("volume=").append(volumeExpr).append(",");
            } else if (as.getVolume() != null && as.getVolume() != 1.0) {
                filterComplex.append("volume=").append(String.format("%.6f", as.getVolume())).append(",");
            }

            filterComplex.append("adelay=").append((int)(timelineStart * 1000)).append("|").append((int)(timelineStart * 1000)).append(",");
            if (sourceDuration < timelineDuration) {
                filterComplex.append("apad=pad_dur=").append(String.format("%.6f", timelineDuration - sourceDuration)).append(",");
            } else if (sourceDuration > timelineDuration) {
                filterComplex.append("atrim=0:").append(String.format("%.6f", timelineDuration)).append(",");
            }
            filterComplex.append("apad=pad_dur=").append(String.format("%.6f", totalDuration - timelineEnd)).append(",");
            filterComplex.append("asetpts=PTS-STARTPTS");

            filterComplex.append("[").append(audioOutput).append("];");
            audioOutputs.add(audioOutput);
        }

        if (!audioOutputs.isEmpty()) {
            for (String audioOutput : audioOutputs) {
                filterComplex.append("[").append(audioOutput).append("]");
            }
            filterComplex.append("amix=inputs=").append(audioOutputs.size()).append(":duration=longest[aout];");
        }

        filterComplex.append("[").append(lastOutput).append("]setpts=PTS-STARTPTS[vout]");

        command.add("-filter_complex");
        command.add(filterComplex.toString());

        command.add("-map");
        command.add("[vout]");
        if (!audioOutputs.isEmpty()) {
            command.add("-map");
            command.add("[aout]");
        }

        command.add("-c:v");
        command.add("libx264");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("320k");
        command.add("-ar");
        command.add("48000");
        command.add("-t");
        command.add(String.valueOf(totalDuration));
        command.add("-r");
        command.add(String.valueOf(fps));
        command.add("-y");
        command.add(outputPath);

        System.out.println("FFmpeg command: " + String.join(" ", command));
        executeFFmpegCommand(command);

        return outputPath;
    }

    private Map<String, String> applyTransitionFilters(StringBuilder filterComplex, List<Transition> transitions,
                                                       double segmentStartTime, double segmentEndTime, int canvasWidth, int canvasHeight) {
        Map<String, String> transitionOffsets = new HashMap<>();
        transitionOffsets.put("x", "0");
        transitionOffsets.put("y", "0");
        transitionOffsets.put("cropWidth", "iw");
        transitionOffsets.put("cropHeight", "ih");
        transitionOffsets.put("cropX", "0");
        transitionOffsets.put("cropY", "0");
        transitionOffsets.put("scale", "1"); // Scale multiplier

        for (Transition transition : transitions) {
            double transStart = transition.getTimelineStartTime();
            double transDuration = transition.getDuration();
            double transEnd;

            // Force 1-second wipe or zoom transition to start at segmentStartTime
            if (("Wipe".equals(transition.getType()) || "Zoom".equals(transition.getType())) && Math.abs(transDuration - 1.0) < 0.01) {
                transStart = segmentStartTime;
                transEnd = Math.min(segmentStartTime + 1.0, segmentEndTime);
                transDuration = transEnd - transStart;
                System.out.println(transition.getType() + " transition for segment ID=" + transition.getToSegmentId() +
                        ": transStart=" + transStart + ", transEnd=" + transEnd + ", duration=" + transDuration);
            } else {
                transEnd = transStart + transDuration;
            }

            // Skip if transition is outside segment bounds
            if (transStart >= segmentEndTime || transEnd <= segmentStartTime) {
                System.out.println("Skipping transition " + transition.getId() + ": transStart=" + transStart +
                        ", transEnd=" + transEnd + ", segmentStart=" + segmentStartTime + ", segmentEnd=" + segmentEndTime);
                continue;
            }

            String transType = transition.getType();
            Map<String, String> params = transition.getParameters() != null ? transition.getParameters() : new HashMap<>();
            String direction = params.getOrDefault("direction", getDefaultDirection(transType));
            boolean isToSegment = transition.getToSegmentId() != null && transition.getToSegmentId().equals(transition.getToSegmentId());
            boolean isFromSegment = transition.getFromSegmentId() != null && transition.getFromSegmentId().equals(transition.getFromSegmentId());

            // Progress: 0 to 1 during transition
            String progressExpr = String.format("(t-%.6f)/%.6f", transStart, transDuration);

            switch (transType) {
                case "Slide":
                    String slideXExpr = "0";
                    String slideYExpr = "0";
                    if (isToSegment) {
                        switch (direction) {
                            case "right": slideXExpr = String.format("%d*(1-%s)", canvasWidth, progressExpr); break;
                            case "left": slideXExpr = String.format("-%d*(1-%s)", canvasWidth, progressExpr); break;
                            case "top": slideYExpr = String.format("-%d*(1-%s)", canvasHeight, progressExpr); break;
                            case "bottom": slideYExpr = String.format("%d*(1-%s)", canvasHeight, progressExpr); break;
                        }
                    } else if (isFromSegment) {
                        switch (direction) {
                            case "right": slideXExpr = String.format("-%d*%s", canvasWidth, progressExpr); break;
                            case "left": slideXExpr = String.format("%d*%s", canvasWidth, progressExpr); break;
                            case "top": slideYExpr = String.format("%d*%s", canvasHeight, progressExpr); break;
                            case "bottom": slideYExpr = String.format("-%d*%s", canvasHeight, progressExpr); break;
                        }
                    }
                    transitionOffsets.put("x", String.format("if(between(t,%.6f,%.6f),%s,0)", transStart, transEnd, slideXExpr));
                    transitionOffsets.put("y", String.format("if(between(t,%.6f,%.6f),%s,0)", transStart, transEnd, slideYExpr));
                    System.out.println("Slide transition " + transition.getId() + ": x=" + transitionOffsets.get("x") +
                            ", y=" + transitionOffsets.get("y"));
                    break;

                case "Wipe":
                    String cropWidthExpr = "iw";
                    String cropHeightExpr = "ih";
                    String cropXExpr = "0";
                    String cropYExpr = "0";
                    if (isToSegment) {
                        switch (direction) {
                            case "left":
                                cropWidthExpr = String.format("iw*max(0,min(1,%s))", progressExpr);
                                cropXExpr = String.format("iw*(1-max(0,min(1,%s)))", progressExpr);
                                break;
                            case "right":
                                cropWidthExpr = String.format("iw*max(0,min(1,%s))", progressExpr);
                                cropXExpr = "0";
                                break;
                            case "top":
                                cropHeightExpr = String.format("ih*max(0,min(1,%s))", progressExpr);
                                cropYExpr = String.format("ih*(1-max(0,min(1,%s)))", progressExpr);
                                break;
                            case "bottom":
                                cropHeightExpr = String.format("ih*max(0,min(1,%s))", progressExpr);
                                cropYExpr = "0";
                                break;
                        }
                    } else if (isFromSegment) {
                        switch (direction) {
                            case "left":
                                cropWidthExpr = String.format("iw*(1-max(0,min(1,%s)))", progressExpr);
                                cropXExpr = "0";
                                break;
                            case "right":
                                cropWidthExpr = String.format("iw*(1-max(0,min(1,%s)))", progressExpr);
                                cropXExpr = String.format("iw*max(0,min(1,%s))", progressExpr);
                                break;
                            case "top":
                                cropHeightExpr = String.format("ih*(1-max(0,min(1,%s)))", progressExpr);
                                cropYExpr = "0";
                                break;
                            case "bottom":
                                cropHeightExpr = String.format("ih*(1-max(0,min(1,%s)))", progressExpr);
                                cropYExpr = String.format("ih*max(0,min(1,%s))", progressExpr);
                                break;
                        }
                    }
                    transitionOffsets.put("cropWidth", cropWidthExpr);
                    transitionOffsets.put("cropHeight", cropHeightExpr);
                    transitionOffsets.put("cropX", cropXExpr);
                    transitionOffsets.put("cropY", cropYExpr);
                    System.out.println("Wipe transition " + transition.getId() + ": cropWidth=" + cropWidthExpr +
                            ", cropHeight=" + cropHeightExpr + ", cropX=" + cropXExpr + ", cropY=" + cropYExpr);
                    break;

                case "Zoom":
                    String scaleExpr;
                    if (isToSegment) {
                        if ("in".equals(direction)) {
                            // Zoom in: scale from 0.0 to 1.0
                            scaleExpr = String.format("0.0+1.0*%s", progressExpr);
                        } else {
                            // Zoom out: scale from 2.0 to 1.0
                            scaleExpr = String.format("2.0-1.0*%s", progressExpr);
                        }
                    } else if (isFromSegment) {
                        if ("in".equals(direction)) {
                            // Zoom in: scale from 1.0 to 2.0
                            scaleExpr = String.format("1.0+1.0*%s", progressExpr);
                        } else {
                            // Zoom out: scale from 1.0 to 0.1
                            scaleExpr = String.format("1.0-0.9*%s", progressExpr);
                        }
                    } else {
                        scaleExpr = "1";
                    }
                    transitionOffsets.put("scale", String.format("if(between(t,%.6f,%.6f),%s,1)", transStart, transEnd, scaleExpr));
                    System.out.println("Zoom transition " + transition.getId() + ": scale=" + transitionOffsets.get("scale") +
                            ", transStart=" + transStart + ", transEnd=" + transEnd);
                    break;

                default:
                    System.err.println("Unsupported transition type: " + transType);
                    break;
            }
        }
        return transitionOffsets;
    }

    private String getDefaultDirection(String transitionType) {
        switch (transitionType) {
            case "Zoom":
                return "in";
            case "Rotate":
                return "clockwise";
            case "Slide":
            case "Push":
                return "right";
            case "Wipe":
                return "left";
            default:
                return "";
        }
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

    /**
     * Gets the system font path for a given font family name.
     * @param fontFamily The font family name
     * @return The full path to the font file
     */
    private String getFontPathByFamily(String fontFamily) {
        // Default font path if nothing else matches
        String defaultFontPath = "C:/Windows/Fonts/Arial.ttf";

        if (fontFamily == null || fontFamily.trim().isEmpty()) {
            return defaultFontPath;
        }

        // Map common font families to their file paths
        // You can expand this map with more fonts as needed
        Map<String, String> fontMap = new HashMap<>();
        fontMap.put("Arial", "C\\:/Windows/Fonts/Arial.ttf");
        fontMap.put("Times New Roman", "C\\:/Windows/Fonts/times.ttf");
        fontMap.put("Courier New", "C\\:/Windows/Fonts/cour.ttf");
        fontMap.put("Calibri", "C\\:/Windows/Fonts/calibri.ttf");
        fontMap.put("Verdana", "C\\:/Windows/Fonts/verdana.ttf");
        fontMap.put("Georgia", "C\\:/Windows/Fonts/georgia.ttf");
        fontMap.put("Comic Sans MS", "C\\:/Windows/Fonts/comic.ttf");
        fontMap.put("Impact", "C\\:/Windows/Fonts/impact.ttf");
        fontMap.put("Tahoma", "C\\:/Windows/Fonts/tahoma.ttf");

        // Process the font family name to match potential keys
        String processedFontFamily = fontFamily.trim();

        // Try direct match
        if (fontMap.containsKey(processedFontFamily)) {
            System.out.println("Found exact font match for: " + processedFontFamily);
            return fontMap.get(processedFontFamily);
        }

        // Try case-insensitive match
        for (Map.Entry<String, String> entry : fontMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(processedFontFamily)) {
                System.out.println("Found case-insensitive font match for: " + processedFontFamily);
                return entry.getValue();
            }
        }

        // If the specified font isn't in our map, you might want to try a more elaborate lookup
        // For example, scanning the Windows fonts directory or using platform-specific APIs
        // For now, we'll just log this and fallback to Arial
        System.out.println("Warning: Font family '" + fontFamily + "' not found in font map. Using Arial as fallback.");
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

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void removeFilter(String sessionId, String segmentId, String filterId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        boolean removed = timelineState.getFilters().removeIf(f -> f.getSegmentId().equals(segmentId) && f.getFilterId().equals(filterId));
        if (!removed) {
            throw new RuntimeException("Filter not found with ID: " + filterId + " for segment: " + segmentId);
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public List<Filter> getFiltersForSegment(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        // Check if the segment exists in any of the segment types
        boolean segmentExists = timelineState.getSegments().stream().anyMatch(s -> s.getId().equals(segmentId)) ||
                timelineState.getImageSegments().stream().anyMatch(s -> s.getId().equals(segmentId)) ||
                timelineState.getTextSegments().stream().anyMatch(s -> s.getId().equals(segmentId)) ||
                timelineState.getAudioSegments().stream().anyMatch(s -> s.getId().equals(segmentId));

        if (!segmentExists) {
            throw new RuntimeException("Segment not found with ID: " + segmentId);
        }

        // Return filters associated with the segment
        return timelineState.getFilters().stream()
                .filter(f -> f.getSegmentId().equals(segmentId))
                .collect(Collectors.toList());
    }

    public void updateFilter(String sessionId, String segmentId, String filterId, String filterName, String filterValue) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = session.getTimelineState();

        // Verify segment exists
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

        // Find and update the existing filter
        Optional<Filter> filterToUpdate = timelineState.getFilters().stream()
                .filter(f -> f.getSegmentId().equals(segmentId) && f.getFilterId().equals(filterId))
                .findFirst();

        if (filterToUpdate.isPresent()) {
            Filter filter = filterToUpdate.get();
            filter.setFilterName(filterName);
            filter.setFilterValue(filterValue);
        } else {
            throw new RuntimeException("Filter not found with ID: " + filterId + " for segment: " + segmentId);
        }

        session.setLastAccessTime(System.currentTimeMillis());
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