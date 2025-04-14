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

    private final String ffmpegPath = "C:\\Users\\raj.p\\Downloads\\ffmpeg-2025-02-17-git-b92577405b-full_build\\bin\\ffmpeg.exe";
    private final String baseDir = "D:\\Backend\\videoEditor-main"; // Base directory constant

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

    // Updated to reuse extracted audio files as per the proposed solution
    public void addVideoToTimeline(
            String sessionId,
            String videoPath,
            Integer layer,
            Double timelineStartTime,
            Double timelineEndTime,
            Double startTime,
            Double endTime
    ) throws IOException, InterruptedException {
        System.out.println("addVideoToTimeline called with sessionId: " + sessionId);
        System.out.println("Video path: " + videoPath);

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

        // MODIFIED: Round time fields to three decimal places
        startTime = roundToThreeDecimals(startTime);
        endTime = roundToThreeDecimals(endTime);
        timelineStartTime = roundToThreeDecimals(timelineStartTime);

        if (timelineEndTime == null) {
            timelineEndTime = timelineStartTime + (endTime - startTime);
        }

        if (!session.getTimelineState().isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + layer);
        }

        if (startTime < 0 || endTime > fullDuration || startTime >= endTime) {
            throw new RuntimeException("Invalid startTime or endTime for video segment");
        }

        // --- Start of Audio Reuse Logic ---
        Project project = projectRepository.findById(session.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found"));
        String audioPath = null;

        // Check if the video already has an extracted audio file in videosJson
        List<Map<String, String>> videos = getVideos(project);
        for (Map<String, String> video : videos) {
            if (video.get("videoPath").equals(videoPath) && video.containsKey("audioPath")) {
                audioPath = video.get("audioPath");
                System.out.println("Reusing existing audio file: " + audioPath);
                break;
            }
        }

        // If no audio path exists, extract it and update videosJson
        if (audioPath == null) {
            audioPath = extractAudioFromVideo(videoPath, session.getProjectId());
            System.out.println("Extracted new audio file: " + audioPath);

            // Update or add the video entry with the audioPath
            boolean videoExists = false;
            for (Map<String, String> video : videos) {
                if (video.get("videoPath").equals(videoPath)) {
                    video.put("audioPath", audioPath);
                    videoExists = true;
                    break;
                }
            }
            if (!videoExists) {
                addVideo(project, videoPath, new File(videoPath).getName(), audioPath);
            } else {
                project.setVideosJson(objectMapper.writeValueAsString(videos));
            }
            projectRepository.save(project);
        }
        // --- End of Audio Reuse Logic ---
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

        AudioSegment audioSegment = new AudioSegment();
        audioSegment.setAudioPath(audioPath);
        audioSegment.setLayer(-1);
        // MODIFIED: Set rounded time fields for audio segment
        audioSegment.setStartTime(startTime);
        audioSegment.setEndTime(endTime);
        audioSegment.setTimelineStartTime(timelineStartTime);
        audioSegment.setTimelineEndTime(timelineEndTime);
        audioSegment.setVolume(1.0);

        if (!session.getTimelineState().isTimelinePositionAvailable(timelineStartTime, timelineEndTime, -1)) {
            throw new RuntimeException("Timeline position overlaps with an existing audio segment in layer -1");
        }

        segment.setAudioId(audioSegment.getId());

        if (session.getTimelineState() == null) {
            session.setTimelineState(new TimelineState());
        }

        session.getTimelineState().getSegments().add(segment);
        session.getTimelineState().getAudioSegments().add(audioSegment);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    private String extractAudioFromVideo(String videoPath, Long projectId) throws IOException, InterruptedException {
        File videoFile = new File(baseDir, "videos/" + videoPath);
        if (!videoFile.exists()) {
            throw new IOException("Video file not found: " + videoFile.getAbsolutePath());
        }

        File audioDir = new File(baseDir, "audio/projects/" + projectId);
        if (!audioDir.exists()) {
            audioDir.mkdirs();
        }

        String audioFileName = "extracted_" + System.currentTimeMillis() + ".mp3";
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

        return "audio/projects/" + projectId + "/" + audioFileName;
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

    public void updateVideoSegment(String sessionId, String segmentId,
                                   Integer positionX, Integer positionY, Double scale,
                                   Double opacity,
                                   Double timelineStartTime, Integer layer, Double timelineEndTime,
                                   Double startTime, Double endTime,
                                   Map<String, List<Keyframe>> keyframes) throws IOException, InterruptedException {
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
            if (layer != null) segmentToUpdate.setLayer(layer);

            if (timelineStartTime != null) {
                timelineStartTime = roundToThreeDecimals(timelineStartTime);
                segmentToUpdate.setTimelineStartTime(timelineStartTime);
            }
            if (timelineEndTime != null) {
                timelineEndTime = roundToThreeDecimals(timelineEndTime);
                segmentToUpdate.setTimelineEndTime(timelineEndTime);
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

            // MODIFIED: Sync audio segment with rounded times
            if (segmentToUpdate.getAudioId() != null) {
                AudioSegment audioSegment = null;
                for (AudioSegment a : session.getTimelineState().getAudioSegments()) {
                    if (a.getId().equals(segmentToUpdate.getAudioId())) {
                        audioSegment = a;
                        break;
                    }
                }
                if (audioSegment != null) {
                    if (startTime != null) {
                        audioSegment.setStartTime(roundToThreeDecimals(startTime));
                    }
                    if (endTime != null) {
                        audioSegment.setEndTime(roundToThreeDecimals(endTime));
                    }
                    if (timelineStartTime != null) {
                        audioSegment.setTimelineStartTime(roundToThreeDecimals(timelineStartTime));
                    }
                    if (timelineEndTime != null) {
                        audioSegment.setTimelineEndTime(roundToThreeDecimals(timelineEndTime));
                    }
                    // Ensure audio timeline duration matches video
                    double audioTimelineDuration = roundToThreeDecimals(audioSegment.getTimelineEndTime() - audioSegment.getTimelineStartTime());
                    double audioClipDuration = roundToThreeDecimals(audioSegment.getEndTime() - audioSegment.getStartTime());
                    if (audioTimelineDuration < audioClipDuration) {
                        audioSegment.setTimelineEndTime(roundToThreeDecimals(audioSegment.getTimelineStartTime() + audioClipDuration));
                    }
                }
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
                                  Integer positionX, Integer positionY, Double opacity) {
        EditSession session = getSession(sessionId);
        // MODIFIED: Round timeline times to three decimal places
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
        textSegment.setFontSize(fontSize);
        textSegment.setFontColor(fontColor != null ? fontColor : "white");
        textSegment.setBackgroundColor(backgroundColor != null ? backgroundColor : "transparent");
        textSegment.setPositionX(positionX != null ? positionX : 0);
        textSegment.setPositionY(positionY != null ? positionY : 0);
        textSegment.setOpacity(opacity != null ? opacity : 1.0);

        session.getTimelineState().getTextSegments().add(textSegment);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void updateTextSegment(String sessionId, String segmentId, String text,
                                  String fontFamily, Integer fontSize, String fontColor,
                                  String backgroundColor, Integer positionX, Integer positionY,
                                  Double opacity,
                                  Double timelineStartTime, Double timelineEndTime, Integer layer,
                                  Map<String, List<Keyframe>> keyframes) {
        EditSession session = getSession(sessionId);
        TextSegment textSegment = session.getTimelineState().getTextSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Text segment not found with ID: " + segmentId));

        if (keyframes != null && !keyframes.isEmpty()) {
            for (Map.Entry<String, List<Keyframe>> entry : keyframes.entrySet()) {
                String property = entry.getKey();
                List<Keyframe> kfs = entry.getValue();
                for (Keyframe kf : kfs) {
                    if (kf.getTime() < 0 || kf.getTime() > (textSegment.getTimelineEndTime() - textSegment.getTimelineStartTime())) {
                        throw new IllegalArgumentException("Keyframe time out of segment bounds for property " + property);
                    }
                    // MODIFIED: Round keyframe time to three decimal places
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
                }
            }
        } else {
            if (text != null) textSegment.setText(text);
            if (fontFamily != null) textSegment.setFontFamily(fontFamily);
            if (fontSize != null) textSegment.setFontSize(fontSize);
            if (fontColor != null) textSegment.setFontColor(fontColor);
            if (backgroundColor != null) textSegment.setBackgroundColor(backgroundColor);
            if (positionX != null) textSegment.setPositionX(positionX);
            if (positionY != null) textSegment.setPositionY(positionY);
            if (opacity != null) textSegment.setOpacity(opacity);
            if (timelineStartTime != null) {
                // MODIFIED: Round timelineStartTime to three decimal places
                timelineStartTime = roundToThreeDecimals(timelineStartTime);
                textSegment.setTimelineStartTime(timelineStartTime);
            }
            if (timelineEndTime != null) {
                // MODIFIED: Round timelineEndTime to three decimal places
                timelineEndTime = roundToThreeDecimals(timelineEndTime);
                textSegment.setTimelineEndTime(timelineEndTime);
            }
            if (layer != null) textSegment.setLayer(layer);
        }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public Project uploadAudioToProject(User user, Long projectId, MultipartFile audioFile, String audioFileName) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        File projectAudioDir = new File(baseDir, "audio/projects/" + projectId);
        if (!projectAudioDir.exists()) {
            projectAudioDir.mkdirs();
        }

        // Use provided audioFileName or generate a unique one
        String uniqueFileName = audioFileName != null ? audioFileName :
                projectId + "_" + System.currentTimeMillis() + "_" + audioFile.getOriginalFilename();
        File destinationFile = new File(projectAudioDir, uniqueFileName);

        audioFile.transferTo(destinationFile);

        String relativePath = "audio/projects/" + projectId + "/" + uniqueFileName;
        try {
            addAudio(project, relativePath, uniqueFileName); // This now includes audioTitle
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
        if (layer >= 0) {
            throw new RuntimeException("Audio layers must be negative (e.g., -1, -2, -3)");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        List<Map<String, String>> audioFiles = getAudio(project);
        Map<String, String> targetAudio = audioFiles.stream()
                .filter(audio -> audio.get("audioFileName").equals(audioFileName))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("No audio found with filename: " + audioFileName));

        String audioPath = targetAudio.get("audioPath");
        // MODIFIED: Round time fields to three decimal places
        startTime = roundToThreeDecimals(startTime);
        timelineStartTime = roundToThreeDecimals(timelineStartTime);
        double calculatedEndTime = endTime != null ? roundToThreeDecimals(endTime) :
                roundToThreeDecimals(getAudioDuration(audioPath));
        double calculatedTimelineEndTime = timelineEndTime != null ? roundToThreeDecimals(timelineEndTime) :
                roundToThreeDecimals(timelineStartTime + (calculatedEndTime - startTime));

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

    public Project uploadImageToProject(User user, Long projectId, MultipartFile imageFile, String imageFileName) throws IOException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        File projectImageDir = new File(baseDir, "images/projects/" + projectId);
        if (!projectImageDir.exists()) {
            projectImageDir.mkdirs();
        }

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
            Map<String, List<Keyframe>> keyframes) {
        EditSession session = getSession(sessionId);
        TimelineState timelineState = getTimelineState(sessionId);

        ImageSegment targetSegment = timelineState.getImageSegments().stream()
                .filter(segment -> segment.getId().equals(imageSegmentId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Image segment not found: " + imageSegmentId));

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
            if (layer != null) targetSegment.setLayer(layer);
            if (customWidth != null) targetSegment.setCustomWidth(customWidth);
            if (customHeight != null) targetSegment.setCustomHeight(customHeight);
            if (maintainAspectRatio != null) targetSegment.setMaintainAspectRatio(maintainAspectRatio);
            if (timelineStartTime != null) {
                // MODIFIED: Round timelineStartTime to three decimal places
                timelineStartTime = roundToThreeDecimals(timelineStartTime);
                targetSegment.setTimelineStartTime(timelineStartTime);
            }
            if (timelineEndTime != null) {
                // MODIFIED: Round timelineEndTime to three decimal places
                timelineEndTime = roundToThreeDecimals(timelineEndTime);
                targetSegment.setTimelineEndTime(timelineEndTime);
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
                // Trim the source video from startTime to endTime
                filterComplex.append("trim=").append(vs.getStartTime()).append(":").append(vs.getEndTime()).append(",");
                // Shift timestamps to align with timelineStartTime
                filterComplex.append("setpts=PTS-STARTPTS+").append(vs.getTimelineStartTime()).append("/TB,");

                // Apply filters (saturation, blur, hue)
                List<Filter> segmentFilters = timelineState.getFilters().stream()
                        .filter(f -> f.getSegmentId().equals(vs.getId()))
                        .collect(Collectors.toList());
                for (Filter filter : segmentFilters) {
                    switch (filter.getFilterName().toLowerCase()) {
                        case "saturation":
                            filterComplex.append("eq=saturation=").append(filter.getFilterValue()).append(",");
                            break;
                        case "blur":
                            filterComplex.append("gblur=sigma=").append(filter.getFilterValue()).append(",");
                            break;
                        case "hue":
                            filterComplex.append("hue=h=").append(filter.getFilterValue()).append(",");
                            break;
                    }
                }

                // Handle scaling with keyframes
                StringBuilder scaleExpr = new StringBuilder();
                List<Keyframe> scaleKeyframes = vs.getKeyframes().getOrDefault("scale", new ArrayList<>());
                double defaultScale = vs.getScale() != null ? vs.getScale() : 1.0;

                if (!scaleKeyframes.isEmpty()) {
                    Collections.sort(scaleKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) scaleKeyframes.get(0).getValue()).doubleValue();
                    scaleExpr.append(firstKfValue);
                    for (int j = 1; j < scaleKeyframes.size(); j++) {
                        Keyframe prevKf = scaleKeyframes.get(j - 1);
                        Keyframe kf = scaleKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();

                        if (kfTime > prevTime) {
                            // Adjust times to timeline
                            double timelinePrevTime = vs.getTimelineStartTime() + prevTime;
                            double timelineKfTime = vs.getTimelineStartTime() + kfTime;
                            scaleExpr.insert(0, "lerp(").append(",").append(kfValue)
                                    .append(",min(1,max(0,(t-").append(timelinePrevTime).append(")/(")
                                    .append(timelineKfTime).append("-").append(timelinePrevTime).append("))))");
                        }
                    }
                } else {
                    scaleExpr.append(defaultScale);
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
                    xExpr.append(firstKfValue);
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
                            xExpr.insert(0, "lerp(").append(",").append(kfValue)
                                    .append(",min(1,max(0,(t-").append(timelinePrevTime).append(")/(")
                                    .append(timelineKfTime).append("-").append(timelinePrevTime).append("))))");
                        }
                    }
                    xExpr.insert(0, "(").append("+(W-w)/2-w/2*(").append(scaleExpr).append("-1))");
                } else {
                    xExpr.append("(W-w)/2+").append(baseX).append("-w/2*(").append(scaleExpr).append("-1)");
                }

                // Handle position Y with keyframes
                StringBuilder yExpr = new StringBuilder();
                List<Keyframe> posYKeyframes = vs.getKeyframes().getOrDefault("positionY", new ArrayList<>());
                Integer defaultPosY = vs.getPositionY();
                double baseY = defaultPosY != null ? defaultPosY : 0;

                if (!posYKeyframes.isEmpty()) {
                    Collections.sort(posYKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                    yExpr.append(firstKfValue);
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
                            yExpr.insert(0, "lerp(").append(",").append(kfValue)
                                    .append(",min(1,max(0,(t-").append(timelinePrevTime).append(")/(")
                                    .append(timelineKfTime).append("-").append(timelinePrevTime).append("))))");
                        }
                    }
                    yExpr.insert(0, "(").append("+(H-h)/2-h/2*(").append(scaleExpr).append("-1))");
                } else {
                    yExpr.append("(H-h)/2+").append(baseY).append("-h/2*(").append(scaleExpr).append("-1)");
                }

                // Overlay the scaled video onto the previous output
                filterComplex.append("[").append(lastOutput).append("][scaled").append(outputLabel).append("]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("':format=rgb");
                filterComplex.append(":enable='between(t,").append(vs.getTimelineStartTime()).append(",").append(vs.getTimelineEndTime()).append(")'");
                filterComplex.append("[ov").append(outputLabel).append("];");
                lastOutput = "ov" + outputLabel;
            } else if (segment instanceof ImageSegment) {
                ImageSegment is = (ImageSegment) segment;
                String inputIdx = videoInputIndices.get(is.getId());
                double segmentDuration = is.getTimelineEndTime() - is.getTimelineStartTime();

                filterComplex.append("[").append(inputIdx).append(":v]");
                filterComplex.append("trim=0:").append(segmentDuration).append(",");
                filterComplex.append("setpts=PTS-STARTPTS,");

                List<Filter> segmentFilters = timelineState.getFilters().stream()
                        .filter(f -> f.getSegmentId().equals(is.getId()))
                        .collect(Collectors.toList());
                for (Filter filter : segmentFilters) {
                    switch (filter.getFilterName().toLowerCase()) {
                        case "saturation":
                            filterComplex.append("eq=saturation=").append(filter.getFilterValue()).append(",");
                            break;
                        case "blur":
                            filterComplex.append("gblur=sigma=").append(filter.getFilterValue()).append(",");
                            break;
                        case "hue":
                            filterComplex.append("hue=h=").append(filter.getFilterValue()).append(",");
                            break;
                    }
                }

                // Handle scaling with keyframes
                StringBuilder scaleExpr = new StringBuilder();
                List<Keyframe> scaleKeyframes = is.getKeyframes().getOrDefault("scale", new ArrayList<>());
                double defaultScale = is.getScale() != null ? is.getScale() : 1.0;

                if (!scaleKeyframes.isEmpty()) {
                    Collections.sort(scaleKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) scaleKeyframes.get(0).getValue()).doubleValue();
                    scaleExpr.append(firstKfValue);
                    for (int j = 1; j < scaleKeyframes.size(); j++) {
                        Keyframe prevKf = scaleKeyframes.get(j - 1);
                        Keyframe kf = scaleKeyframes.get(j);
                        double prevTime = prevKf.getTime();
                        double kfTime = kf.getTime();
                        double prevValue = ((Number) prevKf.getValue()).doubleValue();
                        double kfValue = ((Number) kf.getValue()).doubleValue();

                        if (kfTime > prevTime) {
                            // Adjust times to timeline
                            double timelinePrevTime = is.getTimelineStartTime() + prevTime;
                            double timelineKfTime = is.getTimelineStartTime() + kfTime;
                            scaleExpr.insert(0, "lerp(").append(",").append(kfValue)
                                    .append(",min(1,max(0,(t-").append(timelinePrevTime).append(")/(")
                                    .append(timelineKfTime).append("-").append(timelinePrevTime).append("))))");
                        }
                    }
                } else {
                    scaleExpr.append(defaultScale);
                }

                filterComplex.append("scale=w='iw*").append(scaleExpr).append("':h='ih*").append(scaleExpr).append("':eval=frame[scaled").append(outputLabel).append("];");

                // Handle position X with keyframes
                StringBuilder xExpr = new StringBuilder();
                List<Keyframe> posXKeyframes = is.getKeyframes().getOrDefault("positionX", new ArrayList<>());
                Integer defaultPosX = is.getPositionX();
                double baseX = defaultPosX != null ? defaultPosX : 0;

                if (!posXKeyframes.isEmpty()) {
                    Collections.sort(posXKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                    xExpr.append(firstKfValue);
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
                            xExpr.insert(0, "lerp(").append(",").append(kfValue)
                                    .append(",min(1,max(0,(t-").append(timelinePrevTime).append(")/(")
                                    .append(timelineKfTime).append("-").append(timelinePrevTime).append("))))");
                        }
                    }
                    // Center the image and adjust for scaling
                    xExpr.insert(0, "(").append("+(W-w)/2-w/2*(").append(scaleExpr).append("-1))");
                } else {
                    // Center the image with offset and adjust for scaling
                    xExpr.append("(W-w)/2+").append(baseX).append("-w/2*(").append(scaleExpr).append("-1)");
                }

                // Handle position Y with keyframes
                StringBuilder yExpr = new StringBuilder();
                List<Keyframe> posYKeyframes = is.getKeyframes().getOrDefault("positionY", new ArrayList<>());
                Integer defaultPosY = is.getPositionY();
                double baseY = defaultPosY != null ? defaultPosY : 0;

                if (!posYKeyframes.isEmpty()) {
                    Collections.sort(posYKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                    yExpr.append(firstKfValue);
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
                            yExpr.insert(0, "lerp(").append(",").append(kfValue)
                                    .append(",min(1,max(0,(t-").append(timelinePrevTime).append(")/(")
                                    .append(timelineKfTime).append("-").append(timelinePrevTime).append("))))");
                        }
                    }
                    // Center the image and adjust for scaling
                    yExpr.insert(0, "(").append("+(H-h)/2-h/2*(").append(scaleExpr).append("-1))");
                } else {
                    // Center the image with offset and adjust for scaling
                    yExpr.append("(H-h)/2+").append(baseY).append("-h/2*(").append(scaleExpr).append("-1)");
                }

                filterComplex.append("[").append(lastOutput).append("][scaled").append(outputLabel).append("]");
                filterComplex.append("overlay=x='").append(xExpr).append("':y='").append(yExpr).append("':format=rgb");
                filterComplex.append(":enable='between(t,").append(is.getTimelineStartTime()).append(",").append(is.getTimelineEndTime()).append(")'");
                filterComplex.append("[ov").append(outputLabel).append("];");
                lastOutput = "ov" + outputLabel;

            } else if (segment instanceof TextSegment) {
                TextSegment ts = (TextSegment) segment;

                filterComplex.append("[").append(lastOutput).append("]");
                filterComplex.append("drawtext=text='").append(ts.getText().replace("'", "\\'")).append("':");
                filterComplex.append("enable='between(t,").append(ts.getTimelineStartTime()).append(",").append(ts.getTimelineEndTime()).append(")':");
                filterComplex.append("fontcolor=").append(ts.getFontColor()).append(":");
                filterComplex.append("fontsize=").append(ts.getFontSize()).append(":");
                filterComplex.append("fontfile='").append(getFontPathByFamily(ts.getFontFamily())).append("':");
                if (ts.getBackgroundColor() != null && !ts.getBackgroundColor().equals("transparent")) {
                    filterComplex.append("box=1:boxcolor=").append(ts.getBackgroundColor()).append("@0.5:");
                }

                // Handle position X with keyframes
                StringBuilder xExpr = new StringBuilder();
                List<Keyframe> posXKeyframes = ts.getKeyframes().getOrDefault("positionX", new ArrayList<>());
                Integer defaultPosX = ts.getPositionX();
                double baseX = defaultPosX != null ? defaultPosX : 0;

                if (!posXKeyframes.isEmpty()) {
                    Collections.sort(posXKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posXKeyframes.get(0).getValue()).doubleValue();
                    xExpr.append(firstKfValue);
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
                            xExpr.insert(0, "lerp(").append(",").append(kfValue)
                                    .append(",min(1,max(0,(t-").append(timelinePrevTime).append(")/(")
                                    .append(timelineKfTime).append("-").append(timelinePrevTime).append("))))");
                        }
                    }
                    xExpr.insert(0, "(").append("+(w-tw)/2)");
                } else {
                    xExpr.append("(w-tw)/2+").append(baseX);
                }

                // Handle position Y with keyframes
                StringBuilder yExpr = new StringBuilder();
                List<Keyframe> posYKeyframes = ts.getKeyframes().getOrDefault("positionY", new ArrayList<>());
                Integer defaultPosY = ts.getPositionY();
                double baseY = defaultPosY != null ? defaultPosY : 0;

                if (!posYKeyframes.isEmpty()) {
                    Collections.sort(posYKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                    double firstKfValue = ((Number) posYKeyframes.get(0).getValue()).doubleValue();
                    yExpr.append(firstKfValue);
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
                            yExpr.insert(0, "lerp(").append(",").append(kfValue)
                                    .append(",min(1,max(0,(t-").append(timelinePrevTime).append(")/(")
                                    .append(timelineKfTime).append("-").append(timelinePrevTime).append("))))");
                        }
                    }
                    yExpr.insert(0, "(").append("+(h-th)/2)");
                } else {
                    yExpr.append("(h-th)/2+").append(baseY);
                }

                filterComplex.append("x='").append(xExpr).append("':y='").append(yExpr).append("'");
                filterComplex.append("[ov").append(outputLabel).append("];");
                lastOutput = "ov" + outputLabel;
            }
        }

        List<String> audioOutputs = new ArrayList<>();
        int audioCount = 0;

//        for (VideoSegment vs : timelineState.getSegments()) {
//            String inputIdx = audioInputIndices.get(vs.getId());
//            String audioOutput = "va" + audioCount++;
//            double audioStart = vs.getStartTime();
//            double audioEnd = vs.getEndTime();
//            double timelineStart = vs.getTimelineStartTime();
//            double timelineEnd = vs.getTimelineEndTime();
//
//            filterComplex.append("[").append(inputIdx).append(":a]");
//            filterComplex.append("atrim=").append(audioStart).append(":").append(audioEnd).append(",");
//            filterComplex.append("asetpts=PTS-STARTPTS");
//            filterComplex.append(",adelay=").append((int)(timelineStart * 1000)).append("|").append((int)(timelineStart * 1000));
//            filterComplex.append(",apad=pad_dur=").append(totalDuration - timelineEnd);
//            filterComplex.append("[").append(audioOutput).append("];");
//            audioOutputs.add(audioOutput);
//        }

        for (AudioSegment as : timelineState.getAudioSegments()) {
            String inputIdx = audioInputIndices.get(as.getId());
            String audioOutput = "aa" + audioCount++;
            double audioStart = as.getStartTime();
            double audioEnd = as.getEndTime();
            double timelineStart = as.getTimelineStartTime();
            double timelineEnd = as.getTimelineEndTime();
            double sourceDuration = audioEnd - audioStart;
            double timelineDuration = timelineEnd - timelineStart;

            // Validate timing
            if (audioStart < 0 || audioEnd <= audioStart || timelineStart < 0 || timelineEnd <= timelineStart) {
                System.err.println("Invalid timing for audio segment " + as.getId() + ": startTime=" + audioStart +
                        ", endTime=" + audioEnd + ", timelineStartTime=" + timelineStart + ", timelineEndTime=" + timelineEnd);
                continue;
            }
            if (Math.abs(timelineDuration - sourceDuration) > 0.01) {
                System.err.println("Warning: Audio segment " + as.getId() + " timeline duration (" + timelineDuration +
                        ") does not match source duration (" + sourceDuration + ")");
            }

            filterComplex.append("[").append(inputIdx).append(":a]");
            // Trim the audio from startTime to endTime
            filterComplex.append("atrim=").append(audioStart).append(":").append(audioEnd).append(",");
            // Reset timestamps to start at 0
            filterComplex.append("asetpts=PTS-STARTPTS,");

            // Apply volume keyframes or static volume
            List<Keyframe> volumeKeyframes = as.getKeyframes().getOrDefault("volume", new ArrayList<>());
            if (!volumeKeyframes.isEmpty()) {
                StringBuilder volumeExpr = new StringBuilder();
                double defaultVolume = as.getVolume() != null ? as.getVolume() : 1.0;

                Collections.sort(volumeKeyframes, Comparator.comparingDouble(Keyframe::getTime));
                double firstKfTime = volumeKeyframes.get(0).getTime();
                double firstKfValue = ((Number) volumeKeyframes.get(0).getValue()).doubleValue();
                volumeExpr.append("if(lt(t,").append(firstKfTime).append("),").append(defaultVolume);

                for (int j = 1; j < volumeKeyframes.size(); j++) {
                    Keyframe prevKf = volumeKeyframes.get(j - 1);
                    Keyframe kf = volumeKeyframes.get(j);
                    double prevTime = prevKf.getTime();
                    double kfTime = kf.getTime();
                    double prevValue = ((Number) prevKf.getValue()).doubleValue();
                    double kfValue = ((Number) kf.getValue()).doubleValue();

                    if (kfTime > prevTime) {
                        volumeExpr.append(",if(between(t,").append(prevTime).append(",").append(kfTime).append("),");
                        volumeExpr.append(prevValue).append("+((t-").append(prevTime).append(")/(")
                                .append(kfTime).append("-").append(prevTime).append("))*(")
                                .append(kfValue).append("-").append(prevValue).append(")");
                    }
                }
                volumeExpr.append(",").append(((Number) volumeKeyframes.get(volumeKeyframes.size() - 1).getValue()).doubleValue());
                for (int j = 0; j < volumeKeyframes.size(); j++) {
                    volumeExpr.append(")");
                }

                filterComplex.append("volume=").append(volumeExpr).append(",");
            } else if (as.getVolume() != null && as.getVolume() != 1.0) {
                filterComplex.append("volume=").append(as.getVolume()).append(",");
            }

            // Delay the audio to start at timelineStartTime
            filterComplex.append("adelay=").append((int)(timelineStart * 1000)).append("|").append((int)(timelineStart * 1000)).append(",");
            // Ensure the audio duration matches the timeline duration, padding if necessary
            if (sourceDuration < timelineDuration) {
                filterComplex.append("apad=pad_dur=").append(timelineDuration - sourceDuration).append(",");
            } else if (sourceDuration > timelineDuration) {
                filterComplex.append("atrim=0:").append(timelineDuration).append(",");
            }
            // Pad to totalDuration to ensure compatibility with amix
            filterComplex.append("apad=pad_dur=").append(totalDuration - timelineEnd).append(",");
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
        command.add("-y");
        command.add(outputPath);

        System.out.println("FFmpeg command: " + String.join(" ", command));
        executeFFmpegCommand(command);

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