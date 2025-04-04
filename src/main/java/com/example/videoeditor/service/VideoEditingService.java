package com.example.videoeditor.service;
import com.example.videoeditor.entity.Project;
import com.example.videoeditor.dto.*;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.EditedVideoRepository;
import com.example.videoeditor.repository.ProjectRepository;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.repository.VideoRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;@Service

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

    //    METHODS TO ADD THE AUDIO, VIDEO AND IMAGE
// Moved from Project entity: Video handling methods
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

    // Moved from Project entity: Image handling methods
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

    // Moved from Project entity: Audio handling methods
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

//        // Ensure 'operations' is initialized
//        if (timelineState.getOperations() == null) {
//            timelineState.setOperations(new ArrayList<>());
//        }

        session.setTimelineState(timelineState);
        activeSessions.put(sessionId, session);

        return sessionId;
    }


    public void saveProject(String sessionId) throws JsonProcessingException {
        EditSession session = getSession(sessionId);
        Project project = projectRepository.findById(session.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found"));

        // Add this debug log to verify the timeline state before saving
        System.out.println("Saving timeline state with " + session.getTimelineState().getSegments().size() + " segments");

        // Serialize the entire timeline state
        String timelineStateJson = objectMapper.writeValueAsString(session.getTimelineState());
        project.setTimelineState(timelineStateJson);
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);

        // Add this debug log to confirm the save was successful
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

    // In VideoEditingService.java

    public void addVideoToTimeline(
            String sessionId,
            String videoPath,
            Integer layer,
            Double timelineStartTime,
            Double timelineEndTime,
            Double startTime, // New parameter: start time within the video
            Double endTime    // New parameter: end time within the video
    ) throws IOException, InterruptedException {
        System.out.println("addVideoToTimeline called with sessionId: " + sessionId);
        System.out.println("Video path: " + videoPath);
        System.out.println("Layer: " + layer);
        System.out.println("Timeline start time: " + timelineStartTime);
        System.out.println("Timeline end time: " + timelineEndTime);
        System.out.println("Start time (video): " + startTime);
        System.out.println("End time (video): " + endTime);

        EditSession session = getSession(sessionId);

        if (session == null) {
            System.err.println("No active session found for sessionId: " + sessionId);
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }

        System.out.println("Session found, project ID: " + session.getProjectId());

        try {
            double fullDuration = getVideoDuration(videoPath);
            System.out.println("Actual video duration: " + fullDuration);

            // If layer is not provided, default to 0
            layer = layer != null ? layer : 0;

            // If timelineStartTime is not provided, calculate it based on the last segment in the layer
            if (timelineStartTime == null) {
                double lastSegmentEndTime = 0.0;
                for (VideoSegment segment : session.getTimelineState().getSegments()) {
                    if (segment.getLayer() == layer && segment.getTimelineEndTime() > lastSegmentEndTime) {
                        lastSegmentEndTime = segment.getTimelineEndTime();
                    }
                }
                timelineStartTime = lastSegmentEndTime;
            }

            // Set startTime and endTime defaults if not provided
            startTime = startTime != null ? startTime : 0.0;
            endTime = endTime != null ? endTime : fullDuration;

            // If timelineEndTime is not provided, calculate it based on the segment duration
            if (timelineEndTime == null) {
                timelineEndTime = timelineStartTime + (endTime - startTime);
            }

            // Validate timeline position
            if (!session.getTimelineState().isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
                throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + layer);
            }

            // Validate startTime and endTime
            if (startTime < 0 || endTime > fullDuration || startTime >= endTime) {
                throw new RuntimeException("Invalid startTime or endTime for video segment");
            }

            // Create a video segment
            VideoSegment segment = new VideoSegment();
            segment.setSourceVideoPath(videoPath);
            segment.setStartTime(startTime); // Set specific start time within video
            segment.setEndTime(endTime);     // Set specific end time within video
            segment.setPositionX(0);
            segment.setPositionY(0);
            segment.setScale(1.0);
            segment.setLayer(layer);
            segment.setTimelineStartTime(timelineStartTime);
            segment.setTimelineEndTime(timelineEndTime);

            // Add to timeline
            if (session.getTimelineState() == null) {
                System.out.println("Timeline state was null, creating new one");
                session.setTimelineState(new TimelineState());
            }

            session.getTimelineState().getSegments().add(segment);
            System.out.println("Added segment to timeline, now have " +
                    session.getTimelineState().getSegments().size() + " segments");
            session.setLastAccessTime(System.currentTimeMillis());

            System.out.println("Successfully added video to timeline");
        } catch (Exception e) {
            System.err.println("Error in addVideoToTimeline: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    private double getVideoDuration(String videoPath) throws IOException, InterruptedException {
        String fullPath = "videos/" + videoPath;

        System.out.println("Getting duration for: " + fullPath);

        // Verify the file exists
        File videoFile = new File(fullPath);
        if (!videoFile.exists()) {
            System.err.println("Video file does not exist: " + fullPath);
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
                System.out.println("FFmpeg output: " + line);
            }
        }

        int exitCode = process.waitFor();
        System.out.println("FFmpeg exit code: " + exitCode);

        // Extract duration from FFmpeg output
        String outputStr = output.toString();
        // FFmpeg outputs duration info in the format: Duration: HH:MM:SS.MS
        int durationIndex = outputStr.indexOf("Duration:");
        if (durationIndex >= 0) {
            String durationStr = outputStr.substring(durationIndex + 10, outputStr.indexOf(",", durationIndex));
            durationStr = durationStr.trim();
            System.out.println("Parsed duration string: " + durationStr);

            // Parse HH:MM:SS.MS format to seconds
            String[] parts = durationStr.split(":");
            if (parts.length == 3) {
                double hours = Double.parseDouble(parts[0]);
                double minutes = Double.parseDouble(parts[1]);
                double seconds = Double.parseDouble(parts[2]);
                double totalSeconds = hours * 3600 + minutes * 60 + seconds;
                System.out.println("Calculated duration in seconds: " + totalSeconds);
                return totalSeconds;
            }
        }

        System.out.println("Could not determine video duration from FFmpeg output, using default value");
        // Default to a reasonable value if we can't determine the duration
        return 300; // Default to 5 minutes
    }


    public void updateVideoSegment(String sessionId, String segmentId,
                                   Integer positionX, Integer positionY, Double scale,
                                   Double timelineStartTime, Integer layer, Double timelineEndTime,
                                   Double startTime, Double endTime) {
        // Log the incoming request parameters
        System.out.println("updateVideoSegment called with sessionId: " + sessionId);
        System.out.println("Segment ID: " + segmentId);
        System.out.println("Position X: " + positionX + ", Position Y: " + positionY + ", Scale: " + scale);
        System.out.println("Timeline Start Time: " + timelineStartTime + ", Layer: " + layer);
        System.out.println("Timeline End Time: " + timelineEndTime);
        System.out.println("Start Time: " + startTime + ", End Time: " + endTime);

        EditSession session = getSession(sessionId);

        if (session == null) {
            System.err.println("No active session found for sessionId: " + sessionId);
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }

        System.out.println("Session found, project ID: " + session.getProjectId());

        // Find the segment with the given ID
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

        // Update the segment properties if provided
        if (positionX != null) {
            segmentToUpdate.setPositionX(positionX);
        }

        if (positionY != null) {
            segmentToUpdate.setPositionY(positionY);
        }

        if (scale != null) {
            segmentToUpdate.setScale(scale);
        }

        // Update timelineStartTime if provided
        if (timelineStartTime != null) {
            double originalDuration = segmentToUpdate.getTimelineEndTime() - segmentToUpdate.getTimelineStartTime();
            segmentToUpdate.setTimelineStartTime(timelineStartTime);
            // If timelineEndTime is not provided, maintain the duration
            if (timelineEndTime == null) {
                segmentToUpdate.setTimelineEndTime(timelineStartTime + originalDuration);
            }
        }

        if (layer != null) {
            segmentToUpdate.setLayer(layer);
        }

        // Update timelineEndTime if provided
        if (timelineEndTime != null) {
            segmentToUpdate.setTimelineEndTime(timelineEndTime);
        }

        // Update startTime if provided
        if (startTime != null) {
            segmentToUpdate.setStartTime(Math.max(0, startTime));
            // If endTime isn't provided, ensure it remains greater than new startTime
            if (endTime == null && segmentToUpdate.getEndTime() <= startTime) {
                throw new IllegalArgumentException("End time must be greater than start time");
            }
        }

        // Update endTime if provided
        if (endTime != null) {
            segmentToUpdate.setEndTime(endTime);
            // Ensure endTime is greater than startTime
            if (endTime <= segmentToUpdate.getStartTime()) {
                throw new IllegalArgumentException("End time must be greater than start time");
            }
            // Ensure endTime doesn't exceed the original video duration
            double originalVideoDuration = 7.43; // You might want to get this from segmentToUpdate.getSourceVideoDuration() or similar
            if (endTime > originalVideoDuration) {
                segmentToUpdate.setEndTime(originalVideoDuration);
            }
        }

        // Validate timeline duration
        double newTimelineDuration = segmentToUpdate.getTimelineEndTime() - segmentToUpdate.getTimelineStartTime();
        double newClipDuration = segmentToUpdate.getEndTime() - segmentToUpdate.getStartTime();
        if (newTimelineDuration < newClipDuration) {
            // Adjust timelineEndTime to match the clip duration if necessary
            segmentToUpdate.setTimelineEndTime(segmentToUpdate.getTimelineStartTime() + newClipDuration);
        }

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("time", System.currentTimeMillis());
        parameters.put("segmentId", segmentId);
        if (positionX != null) parameters.put("positionX", positionX);
        if (positionY != null) parameters.put("positionY", positionY);
        if (scale != null) parameters.put("scale", scale);
        if (timelineStartTime != null) parameters.put("timelineStartTime", timelineStartTime);
        if (layer != null) parameters.put("layer", layer);
        if (timelineEndTime != null) parameters.put("timelineEndTime", timelineEndTime);
        if (startTime != null) parameters.put("startTime", startTime);
        if (endTime != null) parameters.put("endTime", endTime);
        session.setLastAccessTime(System.currentTimeMillis());

        System.out.println("Successfully updated video segment");
    }

    public VideoSegment getVideoSegment(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);

        if (session == null) {
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }

        // Find the segment with the given ID
        for (VideoSegment segment : session.getTimelineState().getSegments()) {
            if (segment.getId().equals(segmentId)) {
                return segment;
            }
        }

        throw new RuntimeException("No segment found with ID: " + segmentId);
    }

    // Add this method to handle adding text to the timeline
    public void addTextToTimeline(String sessionId, String text, int layer, double timelineStartTime, double timelineEndTime,
                                  String fontFamily, int fontSize, String fontColor, String backgroundColor,
                                  int positionX, int positionY) {
        EditSession session = getSession(sessionId);

        // Check if the position is available first
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

        // Create an ADD operation for tracking
//        EditOperation addOperation = new EditOperation();
//        addOperation.setOperationType("ADD_TEXT");
//        addOperation.setParameters(Map.of(
//                "time", System.currentTimeMillis(),
//                "layer", layer,
//                "timelineStartTime", timelineStartTime,
//                "timelineEndTime", timelineEndTime
//        ));

//        session.getTimelineState().getOperations().add(addOperation);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    // Add this method to handle updating text segments
    public void updateTextSegment(String sessionId, String segmentId, String text,
                                  String fontFamily, Integer fontSize, String fontColor,
                                  String backgroundColor, Integer positionX, Integer positionY,
                                  Double timelineStartTime, Double timelineEndTime, Integer layer) {
        // Log the incoming request parameters
        System.out.println("updateTextSegment called with sessionId: " + sessionId);
        System.out.println("Segment ID: " + segmentId);
        System.out.println("Text: " + text + ", Font Family: " + fontFamily + ", Font Size: " + fontSize);
        System.out.println("Font Color: " + fontColor + ", Background Color: " + backgroundColor);
        System.out.println("Position X: " + positionX + ", Position Y: " + positionY);
        System.out.println("Timeline Start Time: " + timelineStartTime + ", Timeline End Time: " + timelineEndTime + ", Layer: " + layer);

        EditSession session = getSession(sessionId);

        if (session == null) {
            System.err.println("No active session found for sessionId: " + sessionId);
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }

        System.out.println("Session found, project ID: " + session.getProjectId());

        // Find the text segment with the given ID
        TextSegment textSegment = session.getTimelineState().getTextSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Text segment not found with ID: " + segmentId));

        // Update the text segment properties if provided
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

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("time", System.currentTimeMillis());
        parameters.put("segmentId", segmentId);
        if (text != null) parameters.put("text", text);
        if (fontFamily != null) parameters.put("fontFamily", fontFamily);
        if (fontSize != null) parameters.put("fontSize", fontSize);
        if (fontColor != null) parameters.put("fontColor", fontColor);
        if (backgroundColor != null) parameters.put("backgroundColor", backgroundColor);
        if (positionX != null) parameters.put("positionX", positionX);
        if (positionY != null) parameters.put("positionY", positionY);
        if (timelineStartTime != null) parameters.put("timelineStartTime", timelineStartTime);
        if (timelineEndTime != null) parameters.put("timelineEndTime", timelineEndTime);
        if (layer != null) parameters.put("layer", layer);
        session.setLastAccessTime(System.currentTimeMillis());

        System.out.println("Successfully updated text segment");
    }

    public Project uploadAudioToProject(User user, Long projectId, MultipartFile audioFile, String audioFileName) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        File projectAudioDir = new File(baseDir, "audio" + File.separator + "projects" + File.separator + projectId);

        if (!projectAudioDir.exists()) {
            boolean dirsCreated = projectAudioDir.mkdirs();
            if (!dirsCreated) {
                throw new IOException("Failed to create directory: " + projectAudioDir.getAbsolutePath());
            }
        }

        String uniqueFileName = projectId + "_" + System.currentTimeMillis() + "_" + audioFile.getOriginalFilename();
        File destinationFile = new File(projectAudioDir, uniqueFileName);

        audioFile.transferTo(destinationFile);

        String relativePath = "audio/projects/" + projectId + "/" + uniqueFileName;
        try {
            // Use the full uniqueFileName instead of the original audioFileName
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
            Double endTime,  // Changed to Double to allow null
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

        List<Map<String, String>> audioFiles;
        try {
            audioFiles = getAudio(project); // Use service method
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to parse project audio", e);
        }

        if (audioFiles.isEmpty()) {
            throw new RuntimeException("No audio files associated with project ID: " + projectId);
        }

        Map<String, String> targetAudio = audioFiles.stream()
                .filter(audio -> audio.get("audioFileName").equals(audioFileName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No audio found with filename: " + audioFileName));

        String audioPath = targetAudio.get("audioPath");
        System.out.println("Adding audio to timeline with path: " + audioPath);

        // Calculate endTime if not provided
        double calculatedEndTime = endTime != null ? endTime : startTime + getAudioDuration(audioPath);

        addAudioToTimeline(sessionId, audioPath, layer, startTime, calculatedEndTime, timelineStartTime, timelineEndTime);
    }


    public void addAudioToTimeline(
            String sessionId,
            String audioPath,
            int layer,
            double startTime,
            double endTime,  // Changed to double since it will always have a value
            double timelineStartTime,
            Double timelineEndTime) throws IOException, InterruptedException {

        if (layer >= 0) {
            throw new RuntimeException("Audio layers must be negative (e.g., -1, -2, -3)");
        }

        EditSession session = getSession(sessionId);
        if (session == null) {
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }
        TimelineState timelineState = session.getTimelineState();

        File audioFile = new File(baseDir, audioPath);
        if (!audioFile.exists()) {
            throw new IOException("Audio file not found: " + audioFile.getAbsolutePath());
        }

        double audioDuration = getAudioDuration(audioPath);

        // Validate audio times
        if (startTime < 0 || endTime > audioDuration || startTime >= endTime) {
            throw new RuntimeException("Invalid audio start/end times: startTime=" + startTime +
                    ", endTime=" + endTime + ", duration=" + audioDuration);
        }

        // Calculate timeline end time if not provided
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

        // Find the audio segment
        AudioSegment targetSegment = timelineState.getAudioSegments().stream()
                .filter(segment -> segment.getId().equals(audioSegmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Audio segment not found: " + audioSegmentId));

        // Store original values
        double originalStartTime = targetSegment.getStartTime();
        double originalEndTime = targetSegment.getEndTime();
        double originalTimelineStartTime = targetSegment.getTimelineStartTime();
        double originalTimelineEndTime = targetSegment.getTimelineEndTime();
        int originalLayer = targetSegment.getLayer();

        // Get audio duration
        double audioDuration = getAudioDuration(targetSegment.getAudioPath());

        // Update provided parameters
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
            if (layer >= 0) {
                throw new RuntimeException("Audio layers must be negative (e.g., -1, -2, -3)");
            }
            targetSegment.setLayer(layer);
        }
        if (volume != null) {
            if (volume < 0 || volume > 1) {
                throw new RuntimeException("Volume must be between 0.0 and 1.0");
            }
            targetSegment.setVolume(volume);
        }

        // Handle adjustments based on provided parameters
        if (startTime != null || endTime != null || timelineChanged) {
            // Update startTime and endTime if provided
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

            // Case 1: Only startTime or endTime changed (no timeline changes)
            if (!timelineChanged) {
                double newStartTime = startTime != null ? startTime : originalStartTime;
                double newEndTime = endTime != null ? endTime : originalEndTime;

                if (startTime != null && timelineStartTime == null) {
                    // Adjust timelineStartTime based on startTime change
                    double startTimeShift = newStartTime - originalStartTime;
                    targetSegment.setTimelineStartTime(originalTimelineStartTime + startTimeShift);
                }
                if (endTime != null && timelineEndTime == null) {
                    // Adjust timelineEndTime based on endTime change
                    double audioDurationUsed = newEndTime - targetSegment.getStartTime();
                    targetSegment.setTimelineEndTime(targetSegment.getTimelineStartTime() + audioDurationUsed);
                }
            }
            // Case 2: Timeline changed, but startTime and endTime not provided
            else if (startTime == null && endTime == null) {
                double newTimelineDuration = targetSegment.getTimelineEndTime() - targetSegment.getTimelineStartTime();
                double originalTimelineDuration = originalTimelineEndTime - originalTimelineStartTime;
                double originalAudioDuration = originalEndTime - originalStartTime;

                if (newTimelineDuration != originalTimelineDuration) {
                    // Adjust based on timeline shift
                    double timelineShift = targetSegment.getTimelineStartTime() - originalTimelineStartTime;
                    double newStartTime = originalStartTime + timelineShift;

                    // Ensure newStartTime is within bounds
                    if (newStartTime < 0) {
                        newStartTime = 0;
                    }

                    double newEndTime = newStartTime + Math.min(newTimelineDuration, originalAudioDuration);

                    // Ensure newEndTime doesn't exceed audio duration
                    if (newEndTime > audioDuration) {
                        newEndTime = audioDuration;
                        newStartTime = Math.max(0, newEndTime - newTimelineDuration);
                    }

                    targetSegment.setStartTime(newStartTime);
                    targetSegment.setEndTime(newEndTime);
                }
            }
            // Case 3: Both timeline and startTime/endTime provided
            else {
                // Use provided startTime and endTime, adjust timelineEndTime if not provided
                if (timelineEndTime == null) {
                    double audioDurationUsed = targetSegment.getEndTime() - targetSegment.getStartTime();
                    targetSegment.setTimelineEndTime(targetSegment.getTimelineStartTime() + audioDurationUsed);
                }
            }
        }

        // Validate timeline position
        timelineState.getAudioSegments().remove(targetSegment);
        boolean positionAvailable = timelineState.isTimelinePositionAvailable(
                targetSegment.getTimelineStartTime(),
                targetSegment.getTimelineEndTime(),
                targetSegment.getLayer());
        timelineState.getAudioSegments().add(targetSegment);

        if (!positionAvailable) {
            // Revert changes if position is not available
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
        if (session == null) {
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }
        TimelineState timelineState = session.getTimelineState();

        // Find and remove the audio segment
        boolean removed = timelineState.getAudioSegments().removeIf(
                segment -> segment.getId().equals(audioSegmentId)
        );

        if (!removed) {
            throw new RuntimeException("Audio segment not found with ID: " + audioSegmentId);
        }

        // Update the session's last access time
        session.setLastAccessTime(System.currentTimeMillis());
    }

    private double getAudioDuration(String audioPath) throws IOException, InterruptedException {
        // Resolve absolute path from base directory
        File audioFile = new File(baseDir, audioPath);
        if (!audioFile.exists()) {
            throw new IOException("Audio file not found: " + audioFile.getAbsolutePath());
        }

        System.out.println("Getting duration for audio file: " + audioFile.getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(ffmpegPath, "-i", audioFile.getAbsolutePath());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                System.out.println("FFmpeg output: " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            System.out.println("FFmpeg failed with exit code: " + exitCode);
        }

        String outputStr = output.toString();
        int durationIndex = outputStr.indexOf("Duration:");
        if (durationIndex >= 0) {
            String durationStr = outputStr.substring(durationIndex + 10, outputStr.indexOf(",", durationIndex)).trim();
            String[] parts = durationStr.split(":");
            if (parts.length == 3) {
                double hours = Double.parseDouble(parts[0]);
                double minutes = Double.parseDouble(parts[1]);
                double seconds = Double.parseDouble(parts[2]);
                double duration = hours * 3600 + minutes * 60 + seconds;
                System.out.println("Audio duration: " + duration + " seconds");
                return duration;
            }
        }
        System.out.println("Could not parse duration, defaulting to 300s");
        return 300; // Default to 5 minutes
    }

    // Add this method to VideoEditingService
    public Project uploadImageToProject(User user, Long projectId, MultipartFile imageFile, String imageFileName) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        File projectImageDir = new File(baseDir, "images" + File.separator + "projects" + File.separator + projectId);

        if (!projectImageDir.exists()) {
            boolean dirsCreated = projectImageDir.mkdirs();
            if (!dirsCreated) {
                throw new IOException("Failed to create directory: " + projectImageDir.getAbsolutePath());
            }
        }

        String uniqueFileName = projectId + "_" + System.currentTimeMillis() + "_" + imageFile.getOriginalFilename();
        File destinationFile = new File(projectImageDir, uniqueFileName);

        imageFile.transferTo(destinationFile);

        String relativePath = "images/projects/" + projectId + "/" + uniqueFileName;
        try {
            // Use the full uniqueFileName instead of the original imageFileName
            addImage(project, relativePath, uniqueFileName);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to process image data", e);
        }
        project.setLastModified(LocalDateTime.now());

        return projectRepository.save(project);
    }


    // Add this method to add the project's image to the timeline
    public void addImageToTimelineFromProject(
            User user,
            String sessionId,
            Long projectId,
            Integer layer,
            Double timelineStartTime,
            Double timelineEndTime,
            Map<String, String> filters, // Still present for compatibility but can be null
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

        if (images.isEmpty()) {
            throw new RuntimeException("No images associated with project ID: " + projectId);
        }

        Map<String, String> targetImage = images.stream()
                .filter(img -> img.get("imageFileName").equals(imageFileName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No image found with filename: " + imageFileName));

        String imagePath = targetImage.get("imagePath");
        int positionX = 0;
        int positionY = 0;
        double scale = 1.0;

        addImageToTimeline(sessionId, imagePath, layer, timelineStartTime, timelineEndTime, positionX, positionY, scale, null);
    }

    // Helper method to generate FFmpeg filter string for an image segment
    private String generateImageFilters(ImageSegment imgSegment) {
        StringBuilder filterStr = new StringBuilder();

        // First handle scaling - either using custom dimensions or scale factor
        if (imgSegment.getCustomWidth() > 0 || imgSegment.getCustomHeight() > 0) {
            if (imgSegment.isMaintainAspectRatio()) {
                // If maintaining aspect ratio, use force_original_aspect_ratio
                if (imgSegment.getCustomWidth() > 0 && imgSegment.getCustomHeight() > 0) {
                    filterStr.append("scale=").append(imgSegment.getCustomWidth()).append(":")
                            .append(imgSegment.getCustomHeight()).append(":force_original_aspect_ratio=decrease");
                } else if (imgSegment.getCustomWidth() > 0) {
                    filterStr.append("scale=").append(imgSegment.getCustomWidth()).append(":-1");
                } else {
                    filterStr.append("scale=-1:").append(imgSegment.getCustomHeight());
                }
            } else {
                // Not maintaining aspect ratio, use exact dimensions
                int width = imgSegment.getCustomWidth() > 0 ? imgSegment.getCustomWidth() : imgSegment.getWidth();
                int height = imgSegment.getCustomHeight() > 0 ? imgSegment.getCustomHeight() : imgSegment.getHeight();
                filterStr.append("scale=").append(width).append(":").append(height);
            }
        } else {
            // Use scale factor
            filterStr.append("scale=").append(imgSegment.getWidth()).append("*")
                    .append(imgSegment.getScale()).append(":")
                    .append(imgSegment.getHeight()).append("*")
                    .append(imgSegment.getScale());
        }

        // Apply image filters
        Map<String, String> filters = imgSegment.getFilters();
        if (filters != null && !filters.isEmpty()) {
            for (Map.Entry<String, String> filter : filters.entrySet()) {
                switch (filter.getKey()) {
                    case "brightness":
                        // Value between -1.0 (black) and 1.0 (white)
                        filterStr.append(",eq=brightness=").append(filter.getValue());
                        break;
                    case "contrast":
                        // Value usually between 0.0 and 2.0
                        filterStr.append(",eq=contrast=").append(filter.getValue());
                        break;
                    case "saturation":
                        // Value usually between 0.0 (grayscale) and 3.0 (hyper-saturated)
                        filterStr.append(",eq=saturation=").append(filter.getValue());
                        break;
                    case "blur":
                        // Gaussian blur with sigma value (1-5 is normal range)
                        filterStr.append(",gblur=sigma=").append(filter.getValue());
                        break;
                    case "sharpen":
                        // Custom convolution kernel for sharpening
                        filterStr.append(",convolution='0 -1 0 -1 5 -1 0 -1 0:0 -1 0 -1 5 -1 0 -1 0:0 -1 0 -1 5 -1 0 -1 0:0 -1 0 -1 5 -1 0 -1 0'");
                        break;
                    case "sepia":
                        filterStr.append(",colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131:0");
                        break;
                    case "grayscale":
                        filterStr.append(",hue=s=0");
                        break;
                    case "vignette":
                        // Add a vignette effect (darkness around the edges)
                        filterStr.append(",vignette=PI/4");
                        break;
                    case "noise":
                        // Add some noise to the image
                        filterStr.append(",noise=alls=").append(filter.getValue()).append(":allf=t");
                        break;
                }
            }
        }

        // Add transparency if needed
        if (imgSegment.getOpacity() < 1.0) {
            filterStr.append(",format=rgba,colorchannelmixer=aa=")
                    .append(imgSegment.getOpacity());
        }

        return filterStr.toString();
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
            Map<String, String> filters // Optional filters
    ) {
        TimelineState timelineState = getTimelineState(sessionId);

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
            File imageFile = new File(baseDir + "\\" + imagePath);
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
            imageSegment.setFilters(new HashMap<>(filters));
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
            Double timelineEndTime
    ) {
        TimelineState timelineState = getTimelineState(sessionId);

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
                targetSegment.addFilter(filter.getKey(), filter.getValue());
            }
        }

        if (filtersToRemove != null && !filtersToRemove.isEmpty()) {
            for (String filterToRemove : filtersToRemove) {
                targetSegment.removeFilter(filterToRemove);
            }
        }

        saveTimelineState(sessionId, timelineState);
    }

    public void removeImageSegment(String sessionId, String segmentId) {
        TimelineState timelineState = getTimelineState(sessionId);

        boolean removed = timelineState.getImageSegments().removeIf(
                segment -> segment.getId().equals(segmentId)
        );

        if (!removed) {
            throw new RuntimeException("Image segment not found with ID: " + segmentId);
        }

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
        String exportedVideoPath = renderFinalVideo(session.getTimelineState(), outputPath, project.getWidth(), project.getHeight());

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

    private String renderFinalVideo(TimelineState timelineState, String outputPath, int canvasWidth, int canvasHeight)
            throws IOException, InterruptedException {

        System.out.println("Rendering final video to: " + outputPath);

        // Use canvas dimensions from timelineState if available
        if (timelineState.getCanvasWidth() != null) {
            canvasWidth = timelineState.getCanvasWidth();
        }
        if (timelineState.getCanvasHeight() != null) {
            canvasHeight = timelineState.getCanvasHeight();
        }

        // Create a temporary directory for intermediate files
        File tempDir = new File("temp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }

        // Collect all unique time points from all segment types
        Set<Double> timePoints = new TreeSet<>();
        timePoints.add(0.0); // Always start at 0

        // Video segments
        if (timelineState.getSegments() != null) {
            for (VideoSegment segment : timelineState.getSegments()) {
                timePoints.add(segment.getTimelineStartTime());
                timePoints.add(segment.getTimelineEndTime());
            }
        }

        // Image segments
        if (timelineState.getImageSegments() != null) {
            for (ImageSegment segment : timelineState.getImageSegments()) {
                timePoints.add(segment.getTimelineStartTime());
                timePoints.add(segment.getTimelineEndTime());
            }
        }

        // Text segments
        if (timelineState.getTextSegments() != null) {
            for (TextSegment segment : timelineState.getTextSegments()) {
                timePoints.add(segment.getTimelineStartTime());
                timePoints.add(segment.getTimelineEndTime());
            }
        }

        // Audio segments
        if (timelineState.getAudioSegments() != null) {
            for (AudioSegment segment : timelineState.getAudioSegments()) {
                timePoints.add(segment.getTimelineStartTime());
                timePoints.add(segment.getTimelineEndTime());
            }
        }

        List<Double> sortedTimePoints = new ArrayList<>(timePoints);
        Collections.sort(sortedTimePoints);

        // Total duration is the last time point
        double totalDuration = sortedTimePoints.get(sortedTimePoints.size() - 1);
        System.out.println("Total video duration: " + totalDuration + " seconds");

        List<File> intermediateFiles = new ArrayList<>();

        // Process each time segment
        for (int i = 0; i < sortedTimePoints.size() - 1; i++) {
            double segmentStart = sortedTimePoints.get(i);
            double segmentEnd = sortedTimePoints.get(i + 1);
            double segmentDuration = segmentEnd - segmentStart;

            if (segmentDuration <= 0.001) continue;

            System.out.println("Processing segment: " + segmentStart + " to " + segmentEnd);

            // Collect all visible segments in this time range, sorted by layer
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

            // Sort by layer (lower to higher)
            visibleVideoSegments.sort(Comparator.comparingInt(VideoSegment::getLayer));
            visibleImageSegments.sort(Comparator.comparingInt(ImageSegment::getLayer));
            visibleTextSegments.sort(Comparator.comparingInt(TextSegment::getLayer));
            visibleAudioSegments.sort(Comparator.comparingInt(AudioSegment::getLayer));

            // Create temporary file for this segment
            String tempFilename = "temp_" + UUID.randomUUID().toString() + ".mp4";
            File tempFile = new File(tempDir, tempFilename);
            intermediateFiles.add(tempFile);

            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);

            StringBuilder filterComplex = new StringBuilder();
            Map<Integer, String> inputIndices = new HashMap<>();
            int inputCount = 0;

            // Add inputs
            for (VideoSegment segment : visibleVideoSegments) {
                command.add("-i");
                command.add(baseDir + "\\videos\\" + segment.getSourceVideoPath());
                inputIndices.put(segment.getLayer(), String.valueOf(inputCount++));
            }

            for (ImageSegment segment : visibleImageSegments) {
                command.add("-loop");
                command.add("1");
                command.add("-i");
                command.add(baseDir + "\\" + segment.getImagePath());
                inputIndices.put(segment.getLayer(), String.valueOf(inputCount++));
            }

            for (AudioSegment segment : visibleAudioSegments) {
                command.add("-i");
                command.add(baseDir + "\\" + segment.getAudioPath());
                inputIndices.put(segment.getLayer(), String.valueOf(inputCount++));
            }

            // Base layer: black background
            filterComplex.append("color=c=black:s=").append(canvasWidth).append("x").append(canvasHeight)
                    .append(":d=").append(segmentDuration).append("[base];");

            String lastOutput = "base";
            int overlayCount = 0;

            // Process video segments
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
                filterComplex.append("[scaled").append(videoOutput).append("];");

                filterComplex.append("[").append(lastOutput).append("][scaled").append(videoOutput).append("]");
                filterComplex.append("overlay=(W-w)/2+").append(segment.getPositionX()).append(":")
                        .append("(H-h)/2+").append(segment.getPositionY()).append("[ov").append(videoOutput).append("];");

                lastOutput = "ov" + videoOutput;
            }

            // Process image segments
            for (ImageSegment segment : visibleImageSegments) {
                String inputIdx = inputIndices.get(segment.getLayer());
                String imageOutput = "img" + overlayCount++;

                double displayDuration = Math.min(segment.getTimelineEndTime(), segmentEnd) -
                        Math.max(segment.getTimelineStartTime(), segmentStart);

                filterComplex.append("[").append(inputIdx).append(":v]");
                filterComplex.append("trim=0:").append(displayDuration).append(",");
                filterComplex.append("setpts=PTS-STARTPTS,");
                filterComplex.append(generateImageFilters(segment));
                filterComplex.append("[scaled").append(imageOutput).append("];");

                // Center the image and apply positionX and positionY offsets
                filterComplex.append("[").append(lastOutput).append("][scaled").append(imageOutput).append("]");
                filterComplex.append("overlay=(W-w)/2+").append(segment.getPositionX()).append(":")
                        .append("(H-h)/2+").append(segment.getPositionY()).append("[ov").append(imageOutput).append("];");

                lastOutput = "ov" + imageOutput;
            }

            // Process text segments
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
                // Center the text and apply positionX and positionY offsets
                filterComplex.append("x=(w-tw)/2+").append(segment.getPositionX()).append(":");
                filterComplex.append("y=(h-th)/2+").append(segment.getPositionY());
                filterComplex.append("[ov").append(textOutput).append("];");

                lastOutput = "ov" + textOutput;
            }

            // Handle audio
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
            command.add("-t"); // Explicitly set duration for this segment
            command.add(String.valueOf(segmentDuration));
            command.add("-y");
            command.add(tempFile.getAbsolutePath());

            System.out.println("FFmpeg command: " + String.join(" ", command));
            executeFFmpegCommand(command);
        }

        // Concatenate all intermediate files
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
        concatCommand.add("-t"); // Explicitly set total duration
        concatCommand.add(String.valueOf(totalDuration));
        concatCommand.add("-y");
        concatCommand.add(outputPath);

        System.out.println("FFmpeg concat command: " + String.join(" ", concatCommand));
        executeFFmpegCommand(concatCommand);

        // Cleanup
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
}
