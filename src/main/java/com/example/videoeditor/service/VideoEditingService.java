package com.example.videoeditor.service;import com.example.videoeditor.entity.Project;
import com.example.videoeditor.dto.*;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.EditedVideoRepository;
import com.example.videoeditor.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
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

    public TimelineState getTimelineState(String sessionId) {
        EditSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new RuntimeException("Edit session not found: " + sessionId);
        }
        session.setLastAccessTime(System.currentTimeMillis());
        return session.getTimelineState();
    }

    public void saveTimelineState(String sessionId, TimelineState timelineState) {
        EditSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new RuntimeException("Edit session not found: " + sessionId);
        }
        session.setTimelineState(timelineState);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public Project createProject(User user, String name, Integer width, Integer height) throws JsonProcessingException {
        Project project = new Project();
        project.setUser(user);
        project.setName(name);
        project.setWidth(width);
        project.setHeight(height);
        project.setStatus("DRAFT");
        project.setLastModified(LocalDateTime.now());
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

        // Removed EditOperation initialization
        // REMOVED: if (timelineState.getOperations() == null) {
        // REMOVED:     timelineState.setOperations(new ArrayList<>());
        // REMOVED: }

        session.setTimelineState(timelineState);
        activeSessions.put(sessionId, session);
        return sessionId;
    }

    public Project updateProject(Long projectId, User user, String name, Integer width, Integer height) throws JsonProcessingException {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to modify this project");
        }

        if (name != null) project.setName(name);
        if (width != null) project.setWidth(width);
        if (height != null) project.setHeight(height);

        project.setLastModified(LocalDateTime.now());
        return projectRepository.save(project);
    }

    public void deleteProject(Long projectId, User user) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        if (!project.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("Unauthorized to delete this project");
        }

        activeSessions.entrySet().removeIf(entry ->
                entry.getValue().getProjectId() != null &&
                        entry.getValue().getProjectId().equals(projectId));
        projectRepository.delete(project);
    }
    public void splitVideo(String sessionId, String videoPath, double splitTime, String segmentId) throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);

        File file = new File(videoPath);
        if (!file.exists()) {
            File alternativeFile = new File("videos/" + videoPath);
            if (alternativeFile.exists()) {
                videoPath = "videos/" + videoPath;
                file = alternativeFile;
            } else {
                throw new RuntimeException("Video file does not exist: " + videoPath);
            }
        }

        VideoSegment originalSegment = null;
        int originalSegmentIndex = -1;

        for (int i = 0; i < session.getTimelineState().getSegments().size(); i++) {
            VideoSegment segment = session.getTimelineState().getSegments().get(i);
            if (segment.getId().equals(segmentId)) {
                originalSegment = segment;
                originalSegmentIndex = i;
                break;
            }
        }

        if (originalSegment == null) {
            throw new RuntimeException("Original segment not found with ID: " + segmentId);
        }

        double startTime = originalSegment.getStartTime();
        double endTime = originalSegment.getEndTime() == -1 ? getVideoDuration(videoPath) : originalSegment.getEndTime();

        if (splitTime <= (startTime + 0.1) || splitTime >= (endTime - 0.1)) {
            throw new RuntimeException("Invalid split point. Must be between " + startTime + " and " + endTime);
        }

        VideoSegment firstPart = new VideoSegment();
        firstPart.setSourceVideoPath(originalSegment.getSourceVideoPath());
        firstPart.setStartTime(startTime);
        firstPart.setEndTime(splitTime);

        VideoSegment secondPart = new VideoSegment();
        secondPart.setSourceVideoPath(originalSegment.getSourceVideoPath());
        secondPart.setStartTime(splitTime);
        secondPart.setEndTime(endTime);

        session.getTimelineState().getSegments().set(originalSegmentIndex, firstPart);
        session.getTimelineState().getSegments().add(originalSegmentIndex + 1, secondPart);

        // Removed EditOperation creation
        // REMOVED: EditOperation split = new EditOperation();
        // REMOVED: split.setOperationType("SPLIT");
        // REMOVED: split.setSourceVideoPath(videoPath);
        // REMOVED: split.setParameters(Map.of("splitTime", splitTime));
        // REMOVED: session.getTimelineState().getOperations().add(split);

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void updateSplitVideo(String sessionId, String segmentId, double newSplitTime) throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);

        VideoSegment segment = null;
        int segmentIndex = -1;

        for (int i = 0; i < session.getTimelineState().getSegments().size(); i++) {
            VideoSegment s = session.getTimelineState().getSegments().get(i);
            if (s.getId().equals(segmentId)) {
                segment = s;
                segmentIndex = i;
                break;
            }
        }

        if (segment == null) {
            throw new RuntimeException("Segment not found with ID: " + segmentId);
        }

        if (segmentIndex >= session.getTimelineState().getSegments().size() - 1) {
            throw new RuntimeException("Cannot update split: No subsequent segment found");
        }

        VideoSegment nextSegment = session.getTimelineState().getSegments().get(segmentIndex + 1);

        if (!segment.getSourceVideoPath().equals(nextSegment.getSourceVideoPath())) {
            throw new RuntimeException("Cannot update split: Segments are not from the same source video");
        }

        double originalStartTime = segment.getStartTime();
        double originalEndTime = nextSegment.getEndTime();

        if (newSplitTime <= (originalStartTime + 0.1) ||
                (originalEndTime != -1 && newSplitTime >= (originalEndTime - 0.1))) {
            throw new RuntimeException("Invalid split point. Must be between " +
                    (originalStartTime + 0.1) + " and " +
                    (originalEndTime != -1 ? (originalEndTime - 0.1) : "end of video"));
        }

        segment.setEndTime(newSplitTime);
        nextSegment.setStartTime(newSplitTime);

        // Removed EditOperation update
        // REMOVED: for (EditOperation op : session.getTimelineState().getOperations()) {
        // REMOVED:     if (op.getOperationType().equals("SPLIT") &&
        // REMOVED:             op.getSourceVideoPath().equals(segment.getSourceVideoPath())) {
        // REMOVED:         op.setParameters(Map.of("splitTime", newSplitTime));
        // REMOVED:         break;
        // REMOVED:     }
        // REMOVED: }

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void deleteSplitVideo(String sessionId, String firstSegmentId, String secondSegmentId) {
        EditSession session = getSession(sessionId);

        VideoSegment firstSegment = null;
        VideoSegment secondSegment = null;
        int firstIndex = -1;
        int secondIndex = -1;

        List<VideoSegment> segments = session.getTimelineState().getSegments();

        for (int i = 0; i < segments.size(); i++) {
            VideoSegment s = segments.get(i);
            if (s.getId().equals(firstSegmentId)) {
                firstSegment = s;
                firstIndex = i;
            } else if (s.getId().equals(secondSegmentId)) {
                secondSegment = s;
                secondIndex = i;
            }
        }

        if (firstSegment == null || secondSegment == null) {
            throw new RuntimeException("One or both segments not found");
        }

        if (Math.abs(firstIndex - secondIndex) != 1) {
            throw new RuntimeException("Segments must be adjacent to merge");
        }

        if (!firstSegment.getSourceVideoPath().equals(secondSegment.getSourceVideoPath())) {
            throw new RuntimeException("Cannot merge segments from different source videos");
        }

        VideoSegment earlier, later;
        int earlierIndex, laterIndex;

        if (firstIndex < secondIndex) {
            earlier = firstSegment;
            later = secondSegment;
            earlierIndex = firstIndex;
            laterIndex = secondIndex;
        } else {
            earlier = secondSegment;
            later = firstSegment;
            earlierIndex = secondIndex;
            laterIndex = firstIndex;
        }

        VideoSegment mergedSegment = new VideoSegment();
        mergedSegment.setId(UUID.randomUUID().toString());
        mergedSegment.setSourceVideoPath(earlier.getSourceVideoPath());
        mergedSegment.setStartTime(earlier.getStartTime());
        mergedSegment.setEndTime(later.getEndTime());

        if (earlier.getPositionX() != null) {
            mergedSegment.setPositionX(earlier.getPositionX());
            mergedSegment.setPositionY(earlier.getPositionY());
        } else if (later.getPositionX() != null) {
            mergedSegment.setPositionX(later.getPositionX());
            mergedSegment.setPositionY(later.getPositionY());
        }

        if (earlier.getScale() != null) {
            mergedSegment.setScale(earlier.getScale());
        } else if (later.getScale() != null) {
            mergedSegment.setScale(later.getScale());
        }

        segments.remove(laterIndex);
        segments.set(earlierIndex, mergedSegment);

        // Removed EditOperation handling
        // REMOVED: session.getTimelineState().getOperations().removeIf(op ->
        // REMOVED:     op.getOperationType().equals("SPLIT") &&
        // REMOVED:     op.getSourceVideoPath().equals(earlier.getSourceVideoPath()) &&
        // REMOVED:     op.getParameters().containsKey("splitTime") &&
        // REMOVED:     (double)op.getParameters().get("splitTime") == earlier.getEndTime());
        // REMOVED: EditOperation mergeOp = new EditOperation();
        // REMOVED: mergeOp.setOperationType("MERGE");
        // REMOVED: mergeOp.setSourceVideoPath(earlier.getSourceVideoPath());
        // REMOVED: mergeOp.setParameters(Map.of(
        // REMOVED:     "firstSegmentId", earlier.getId(),
        // REMOVED:     "secondSegmentId", later.getId()
        // REMOVED: ));
        // REMOVED: session.getTimelineState().getOperations().add(mergeOp);

        session.setLastAccessTime(System.currentTimeMillis());
    }

    private double getVideoDuration(String videoPath) throws IOException, InterruptedException {
        String fullPath = "videos/" + videoPath;
        File videoFile = new File(fullPath);
        if (!videoFile.exists()) {
            throw new IOException("Video file not found: " + fullPath);
        }

        ProcessBuilder builder = new ProcessBuilder(ffmpegPath, "-i", fullPath);
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

    public void saveProject(String sessionId) throws JsonProcessingException {
        EditSession session = getSession(sessionId);
        Project project = projectRepository.findById(session.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found"));

        project.setTimelineState(objectMapper.writeValueAsString(session.getTimelineState()));
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);
    }

    @Scheduled(fixedRate = 3600000)
    public void cleanupExpiredSessions() {
        long expiryTime = System.currentTimeMillis() - 3600000;
        activeSessions.entrySet().removeIf(entry ->
                entry.getValue().getLastAccessTime() < expiryTime);
    }

    private EditSession getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId))
                .orElseThrow(() -> new RuntimeException("No active session found"));
    }

    public void addVideoToTimeline(String sessionId, String videoPath, Integer layer, Double timelineStartTime, Double timelineEndTime)
            throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);

        double duration = getVideoDuration(videoPath);
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

        if (timelineEndTime == null) {
            timelineEndTime = timelineStartTime + duration;
        }

        if (!session.getTimelineState().isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
            throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + layer);
        }

        VideoSegment segment = new VideoSegment();
        segment.setSourceVideoPath(videoPath);
        segment.setStartTime(0);
        segment.setEndTime(duration);
        segment.setPositionX(0);
        segment.setPositionY(0);
        segment.setScale(1.0);
        segment.setLayer(layer);
        segment.setTimelineStartTime(timelineStartTime);
        segment.setTimelineEndTime(timelineEndTime);

        if (session.getTimelineState() == null) {
            session.setTimelineState(new TimelineState());
        }

        session.getTimelineState().getSegments().add(segment);

        // Removed EditOperation creation
        // REMOVED: EditOperation addOperation = new EditOperation();
        // REMOVED: addOperation.setOperationType("ADD");
        // REMOVED: addOperation.setSourceVideoPath(videoPath);
        // REMOVED: addOperation.setParameters(Map.of(
        // REMOVED:     "time", System.currentTimeMillis(),
        // REMOVED:     "layer", layer,
        // REMOVED:     "timelineStartTime", timelineStartTime,
        // REMOVED:     "timelineEndTime", timelineEndTime
        // REMOVED: ));
        // REMOVED: session.getTimelineState().getOperations().add(addOperation);

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void removeVideoSegment(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);

        VideoSegment segmentToRemove = null;
        for (VideoSegment segment : session.getTimelineState().getSegments()) {
            if (segment.getId().equals(segmentId)) {
                segmentToRemove = segment;
                break;
            }
        }

        if (segmentToRemove == null) {
            throw new RuntimeException("No segment found with ID: " + segmentId);
        }

        session.getTimelineState().getSegments().remove(segmentToRemove);

        // Removed EditOperation creation
        // REMOVED: EditOperation removeOperation = new EditOperation();
        // REMOVED: removeOperation.setOperationType("REMOVE");
        // REMOVED: removeOperation.setSourceVideoPath(segmentToRemove.getSourceVideoPath());
        // REMOVED: removeOperation.setParameters(Map.of(
        // REMOVED:     "time", System.currentTimeMillis(),
        // REMOVED:     "segmentId", segmentId
        // REMOVED: ));
        // REMOVED: session.getTimelineState().getOperations().add(removeOperation);

        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void clearTimeline(String sessionId) {
        EditSession session = getSession(sessionId);

        // Removed EditOperation creation
        // REMOVED: EditOperation clearOperation = new EditOperation();
        // REMOVED: clearOperation.setOperationType("CLEAR");
        // REMOVED: clearOperation.setParameters(Map.of(
        // REMOVED:     "time", System.currentTimeMillis(),
        // REMOVED:     "segmentCount", session.getTimelineState().getSegments().size()
        // REMOVED: ));

        session.getTimelineState().getSegments().clear();

        // REMOVED: session.getTimelineState().getOperations().add(clearOperation);
        session.setLastAccessTime(System.currentTimeMillis());
    }

    public void updateSegmentTiming(String sessionId, String segmentId,
                                    Double timelineStartTime, Double timelineEndTime, Integer layer) {
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

        double oldStartTime = segmentToUpdate.getTimelineStartTime();
        double oldEndTime = segmentToUpdate.getTimelineEndTime();
        int oldLayer = segmentToUpdate.getLayer();

        boolean updated = false;

        if (timelineStartTime != null) {
            segmentToUpdate.setTimelineStartTime(timelineStartTime);
            updated = true;
        }

        if (timelineEndTime != null) {
            segmentToUpdate.setTimelineEndTime(timelineEndTime);
            updated = true;
        }

        if (layer != null) {
            segmentToUpdate.setLayer(layer);
            updated = true;
        }

        if (updated) {
            session.getTimelineState().getSegments().remove(segmentToUpdate);
            boolean positionAvailable = session.getTimelineState().isTimelinePositionAvailable(
                    segmentToUpdate.getTimelineStartTime(),
                    segmentToUpdate.getTimelineEndTime(),
                    segmentToUpdate.getLayer());
            session.getTimelineState().getSegments().add(segmentToUpdate);

            if (!positionAvailable) {
                segmentToUpdate.setTimelineStartTime(oldStartTime);
                segmentToUpdate.setTimelineEndTime(oldEndTime);
                segmentToUpdate.setLayer(oldLayer);
                throw new RuntimeException("Timeline position overlaps with an existing segment");
            }

            // Removed EditOperation creation
            // REMOVED: EditOperation updateOperation = new EditOperation();
            // REMOVED: updateOperation.setOperationType("UPDATE_TIMING");
            // REMOVED: updateOperation.setSourceVideoPath(segmentToUpdate.getSourceVideoPath());
            // REMOVED: Map<String, Object> parameters = new HashMap<>();
            // REMOVED: parameters.put("time", System.currentTimeMillis());
            // REMOVED: parameters.put("segmentId", segmentId);
            // REMOVED: if (timelineStartTime != null) parameters.put("timelineStartTime", timelineStartTime);
            // REMOVED: if (timelineEndTime != null) parameters.put("timelineEndTime", timelineEndTime);
            // REMOVED: if (layer != null) parameters.put("layer", layer);
            // REMOVED: updateOperation.setParameters(parameters);
            // REMOVED: session.getTimelineState().getOperations().add(updateOperation);

            session.setLastAccessTime(System.currentTimeMillis());
        }
    }

    public void updateVideoSegment(String sessionId, String segmentId,
                                   Integer positionX, Integer positionY, Double scale, Double timelineStartTime, Integer layer) {
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
        if (timelineStartTime != null) segmentToUpdate.setTimelineStartTime(timelineStartTime);
        if (layer != null) segmentToUpdate.setLayer(layer);

        // Removed EditOperation creation
        // REMOVED: EditOperation updateOperation = new EditOperation();
        // REMOVED: updateOperation.setOperationType("UPDATE");
        // REMOVED: updateOperation.setSourceVideoPath(segmentToUpdate.getSourceVideoPath());
        // REMOVED: Map<String, Object> parameters = new HashMap<>();
        // REMOVED: parameters.put("time", System.currentTimeMillis());
        // REMOVED: parameters.put("segmentId", segmentId);
        // REMOVED: if (positionX != null) parameters.put("positionX", positionX);
        // REMOVED: if (positionY != null) parameters.put("positionY", positionY);
        // REMOVED: if (scale != null) parameters.put("scale", scale);
        // REMOVED: if (timelineStartTime != null) parameters.put("timelineStartTime", timelineStartTime);
        // REMOVED: if (layer != null) parameters.put("layer", layer);
        // REMOVED: updateOperation.setParameters(parameters);
        // REMOVED: session.getTimelineState().getOperations().add(updateOperation);

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
    public File exportProject(String sessionId) throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);

        if (session == null) {
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }

        Project project = projectRepository.findById(session.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found"));

        String outputFileName = project.getName().replaceAll("[^a-zA-Z0-9]", "_") + "_"
                + System.currentTimeMillis() + ".mp4";
        String outputPath = "exports/" + outputFileName;

        File exportsDir = new File("exports");
        if (!exportsDir.exists()) {
            exportsDir.mkdirs();
        }

        String exportedVideoPath = renderFinalVideo(session.getTimelineState(), outputPath, project.getWidth(), project.getHeight());

        project.setStatus("EXPORTED");
        project.setLastModified(LocalDateTime.now());
        project.setExportedVideoPath(exportedVideoPath);

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

        System.out.println("Rendering final video to: " + outputPath);

        // Validate timeline - allow empty segments at beginning and end
        boolean hasContent = (timelineState.getSegments() != null && !timelineState.getSegments().isEmpty()) ||
                (timelineState.getTextSegments() != null && !timelineState.getTextSegments().isEmpty()) ||
                (timelineState.getImageSegments() != null && !timelineState.getImageSegments().isEmpty());

        if (!hasContent) {
            throw new RuntimeException("Cannot export completely empty timeline");
        }

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

        // Find all unique timeline ranges where composition changes
        Set<Double> timePoints = new TreeSet<>();
        timePoints.add(0.0); // Start from time 0

        double maxEndTime = 0;

        // Add time points from video, text, and image segments
        if (timelineState.getSegments() != null) {
            for (VideoSegment segment : timelineState.getSegments()) {
                timePoints.add(segment.getTimelineStartTime());
                timePoints.add(segment.getTimelineEndTime());
                maxEndTime = Math.max(maxEndTime, segment.getTimelineEndTime());
            }
        }
        if (timelineState.getTextSegments() != null) {
            for (TextSegment textSegment : timelineState.getTextSegments()) {
                timePoints.add(textSegment.getTimelineStartTime());
                timePoints.add(textSegment.getTimelineEndTime());
                maxEndTime = Math.max(maxEndTime, textSegment.getTimelineEndTime());
            }
        }
        if (timelineState.getImageSegments() != null) {
            for (ImageSegment imgSegment : timelineState.getImageSegments()) {
                timePoints.add(imgSegment.getTimelineStartTime());
                timePoints.add(imgSegment.getTimelineEndTime());
                maxEndTime = Math.max(maxEndTime, imgSegment.getTimelineEndTime());
            }
        }

        // Sort time points
        List<Double> sortedTimePoints = new ArrayList<>(timePoints);
        Collections.sort(sortedTimePoints);

        System.out.println("Total video duration will be: " + maxEndTime + " seconds");
        System.out.println("Time points: " + sortedTimePoints);

        // Create a list to store intermediate files
        List<File> intermediateFiles = new ArrayList<>();

        // Process each time segment
        for (int i = 0; i < sortedTimePoints.size() - 1; i++) {
            double segmentStart = sortedTimePoints.get(i);
            double segmentEnd = sortedTimePoints.get(i + 1);

            // Skip zero-length segments
            if (segmentEnd - segmentStart <= 0.001) {
                continue;
            }

            System.out.println("Processing time segment: " + segmentStart + " to " + segmentEnd);

            // Find all video, text, and image segments visible in this time range
            List<VideoSegment> visibleSegments = new ArrayList<>();
            List<TextSegment> visibleTextSegments = new ArrayList<>();
            List<ImageSegment> visibleImages = new ArrayList<>();

            if (timelineState.getSegments() != null) {
                for (VideoSegment segment : timelineState.getSegments()) {
                    if (segment.getTimelineStartTime() < segmentEnd &&
                            segment.getTimelineEndTime() > segmentStart) {
                        visibleSegments.add(segment);
                    }
                }
            }
            if (timelineState.getTextSegments() != null) {
                for (TextSegment textSegment : timelineState.getTextSegments()) {
                    if (textSegment.getTimelineStartTime() < segmentEnd &&
                            textSegment.getTimelineEndTime() > segmentStart) {
                        visibleTextSegments.add(textSegment);
                    }
                }
            }
            if (timelineState.getImageSegments() != null) {
                for (ImageSegment imgSegment : timelineState.getImageSegments()) {
                    if (imgSegment.getTimelineStartTime() < segmentEnd &&
                            imgSegment.getTimelineEndTime() > segmentStart) {
                        visibleImages.add(imgSegment);
                    }
                }
            }

            // Sort by layer (lower layers first)
            Collections.sort(visibleSegments, Comparator.comparingInt(VideoSegment::getLayer));
            Collections.sort(visibleTextSegments, Comparator.comparingInt(TextSegment::getLayer));
            Collections.sort(visibleImages, Comparator.comparingInt(ImageSegment::getLayer));

            // Create a temporary file for this time segment
            String tempFilename = "temp_" + UUID.randomUUID().toString() + ".mp4";
            File tempFile = new File(tempDir, tempFilename);

            // Check if this segment has any content at all
            boolean hasVisibleContent = !visibleSegments.isEmpty() || !visibleTextSegments.isEmpty() || !visibleImages.isEmpty();

            if (!hasVisibleContent) {
                // For empty segments, create a black video with the correct duration
                renderEmptySegment(tempFile, segmentStart, segmentEnd, canvasWidth, canvasHeight);
            } else {
                // For segments with content, use the complex filter approach
                renderContentSegment(tempFile, segmentStart, segmentEnd, visibleSegments,
                        visibleTextSegments, visibleImages, canvasWidth, canvasHeight);
            }

            // Add to intermediate files if successful
            if (tempFile.exists() && tempFile.length() > 0) {
                intermediateFiles.add(tempFile);
            } else {
                System.out.println("Warning: Failed to create segment: " + segmentStart + " to " + segmentEnd);
            }
        }

        // Skip concatenation if there's only one intermediate file
        if (intermediateFiles.size() == 1) {
            File singleFile = intermediateFiles.get(0);
            try {
                Files.copy(singleFile.toPath(), new File(outputPath).toPath(), StandardCopyOption.REPLACE_EXISTING);
                singleFile.delete();
                return outputPath;
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy single output file: " + e.getMessage(), e);
            }
        }

        // Make sure we have files to concatenate
        if (intermediateFiles.isEmpty()) {
            throw new RuntimeException("No intermediate files were created successfully");
        }

        // Concatenate all intermediate files
        File concatFile = File.createTempFile("ffmpeg-concat-", ".txt");
        try (PrintWriter writer = new PrintWriter(new FileWriter(concatFile))) {
            for (File file : intermediateFiles) {
                if (file.exists() && file.length() > 0) {
                    writer.println("file '" + file.getAbsolutePath().replace("\\", "\\\\") + "'");
                }
            }
        }

        // Build FFmpeg command for concatenation
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-f");
        command.add("concat");
        command.add("-safe");
        command.add("0");
        command.add("-i");
        command.add(concatFile.getAbsolutePath());

        // Set video codec and quality
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-crf");
        command.add("22");

        // Set audio codec
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");

        // Output file
        command.add("-y");
        command.add(outputPath);

        // Execute concat command
        System.out.println("Executing FFmpeg concat command: " + String.join(" ", command));
        ProcessBuilder concatBuilder = new ProcessBuilder(command);
        concatBuilder.redirectErrorStream(true);

        // Set environment variables for fontconfig
        Map<String, String> env = concatBuilder.environment();
        env.put("FONTCONFIG_PATH", System.getProperty("user.dir"));

        Process concatProcess = concatBuilder.start();

        // Log the concat process output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(concatProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("FFmpeg concat: " + line);
            }
        }

        // Use a timeout to prevent hanging
        boolean completed = concatProcess.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            concatProcess.destroyForcibly();
            throw new RuntimeException("FFmpeg concat process timed out after 5 minutes");
        }

        int exitCode = concatProcess.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg concat process failed with exit code: " + exitCode);
        }

        // Clean up temporary files
        concatFile.delete();
        for (File file : intermediateFiles) {
            file.delete();
        }

        return outputPath;
    }

    // Helper method to render an empty (black) segment
    private void renderEmptySegment(File outputFile, double startTime, double endTime, int width, int height)
            throws IOException, InterruptedException {

        double duration = endTime - startTime;
        System.out.println("Rendering empty segment from " + startTime + " to " + endTime +
                " (duration: " + duration + " seconds)");

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);

        // Generate a simple black frame video with the correct duration
        command.add("-f");
        command.add("lavfi");
        command.add("-i");
        command.add("color=c=black:s=" + width + "x" + height + ":r=30:d=" + duration);

        // Output settings
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("ultrafast"); // Use ultrafast for empty segments
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-y");
        command.add(outputFile.getAbsolutePath());

        // Execute the FFmpeg command
        System.out.println("Executing FFmpeg command for empty segment: " + String.join(" ", command));
        executeFFmpegCommand(command);
    }

    // Helper method to render a segment with content
    private void renderContentSegment(File outputFile, double segmentStart, double segmentEnd,
                                      List<VideoSegment> visibleSegments, List<TextSegment> visibleTextSegments,
                                      List<ImageSegment> visibleImages, int canvasWidth, int canvasHeight)
            throws IOException, InterruptedException {

        double segmentDuration = segmentEnd - segmentStart;

        // Build FFmpeg command
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);

        // Add inputs for video and image segments
        int videoInputCount = 0;
        Map<Integer, VideoSegment> videoInputMap = new HashMap<>();
        for (VideoSegment segment : visibleSegments) {
            String videoPath = "videos/" + segment.getSourceVideoPath();
            if (new File(videoPath).exists()) {
                command.add("-i");
                command.add(videoPath);
                videoInputMap.put(videoInputCount++, segment);
            } else {
                System.out.println("Warning: Video file not found: " + videoPath);
            }
        }

        int imageInputCount = 0;
        Map<Integer, ImageSegment> imageInputMap = new HashMap<>();
        for (ImageSegment imgSegment : visibleImages) {
            String imagePath = "images/" + imgSegment.getImagePath();
            if (new File(imagePath).exists()) {
                command.add("-i");
                command.add(imagePath);
                imageInputMap.put(imageInputCount++, imgSegment);
            } else {
                System.out.println("Warning: Image file not found: " + imagePath);
            }
        }

        // Create complex filter for compositing
        StringBuilder filterComplex = new StringBuilder();

        // Start with a black background for the segment duration
        filterComplex.append("color=c=black:s=").append(canvasWidth).append("x").append(canvasHeight)
                .append(":d=").append(segmentDuration).append(",format=yuv420p[bg];");

        String lastOutput = "bg";
        int validVideoInputs = videoInputMap.size();

        // Process video segments with filters
        for (int j = 0; j < visibleSegments.size(); j++) {
            VideoSegment segment = visibleSegments.get(j);
            String videoPath = "videos/" + segment.getSourceVideoPath();
            if (!new File(videoPath).exists()) {
                continue;
            }

            int inputIndex = new ArrayList<>(videoInputMap.keySet()).get(j);

            // Calculate trimming
            double relativeStartTime = segment.getStartTime();
            if (segmentStart > segment.getTimelineStartTime()) {
                relativeStartTime += (segmentStart - segment.getTimelineStartTime());
            }
            double trimDuration = Math.min(segmentEnd, segment.getTimelineEndTime()) -
                    Math.max(segmentStart, segment.getTimelineStartTime());

            // Build filter chain: trim -> filters -> scale
            filterComplex.append("[").append(inputIndex).append(":v]");
            filterComplex.append("trim=").append(relativeStartTime).append(":")
                    .append(relativeStartTime + trimDuration).append(",");
            filterComplex.append("setpts=PTS-STARTPTS,");

            // Apply filters from VideoSegment
            if (segment.getFilters() != null && !segment.getFilters().isEmpty()) {
                List<String> filterStrings = segment.getFilters().values().stream()
                        .map(filterData -> (String) filterData.get("filterString"))
                        .collect(Collectors.toList());
                filterComplex.append(String.join(",", filterStrings)).append(",");
            }

            filterComplex.append("scale=iw*").append(segment.getScale()).append(":ih*").append(segment.getScale());
            filterComplex.append("[v").append(j).append("];");

            // Overlay on previous layer
            String nextOutput = "v" + (j + 10);
            String overlayX = "(W-w)/2+" + segment.getPositionX();
            String overlayY = "(H-h)/2+" + segment.getPositionY();

            filterComplex.append("[").append(lastOutput).append("][v").append(j).append("]");
            filterComplex.append("overlay=").append(overlayX).append(":").append(overlayY)
                    .append(":enable='between(t,").append(Math.max(0, segment.getTimelineStartTime() - segmentStart))
                    .append(",").append(Math.min(segmentDuration, segment.getTimelineEndTime() - segmentStart))
                    .append(")'");
            filterComplex.append("[").append(nextOutput).append("];");

            lastOutput = nextOutput;
        }

        // Process text segments
        for (int j = 0; j < visibleTextSegments.size(); j++) {
            TextSegment textSegment = visibleTextSegments.get(j);
            String nextOutput = (j == visibleTextSegments.size() - 1 && visibleImages.isEmpty()) ? "vout" : "text" + (j + 1);

            double relativeStart = Math.max(0, textSegment.getTimelineStartTime() - segmentStart);
            double relativeEnd = Math.min(segmentDuration, textSegment.getTimelineEndTime() - segmentStart);

            filterComplex.append("[").append(lastOutput).append("]");
            filterComplex.append("drawtext=text='").append(escapeText(textSegment.getText())).append("':");
            filterComplex.append("enable='between(t,").append(relativeStart).append(",").append(relativeEnd).append(")':");
            filterComplex.append("fontcolor=").append(textSegment.getFontColor()).append(":");
            filterComplex.append("fontsize=").append(textSegment.getFontSize()).append(":");
            filterComplex.append("x=").append(textSegment.getPositionX()).append(":");
            filterComplex.append("y=").append(textSegment.getPositionY());
            if (textSegment.getFontFamily() != null && !textSegment.getFontFamily().isEmpty()) {
                String fontPath = getFontPathByFamily(textSegment.getFontFamily());
                filterComplex.append(":fontfile='").append(fontPath.replace("\\", "\\\\")).append("'");
            }
            filterComplex.append("[").append(nextOutput).append("];");

            lastOutput = nextOutput;
        }

        // Process image segments
        int imgInputIndex = validVideoInputs;
        for (int j = 0; j < visibleImages.size(); j++) {
            ImageSegment imgSegment = visibleImages.get(j);
            String imagePath = "images/" + imgSegment.getImagePath();
            if (!new File(imagePath).exists()) {
                continue;
            }

            int inputIndex = imgInputIndex + new ArrayList<>(imageInputMap.keySet()).get(j);
            String nextOutput = (j == visibleImages.size() - 1) ? "vout" : "img" + (j + 1);

            filterComplex.append("[").append(inputIndex).append(":v]");
            filterComplex.append(generateImageFilters(imgSegment));
            filterComplex.append("[img_filtered").append(j).append("];");

            String overlayX = "(W-w)/2+" + imgSegment.getPositionX();
            String overlayY = "(H-h)/2+" + imgSegment.getPositionY();
            double relativeStart = Math.max(0, imgSegment.getTimelineStartTime() - segmentStart);
            double relativeEnd = Math.min(segmentDuration, imgSegment.getTimelineEndTime() - segmentStart);

            filterComplex.append("[").append(lastOutput).append("][img_filtered").append(j).append("]");
            filterComplex.append("overlay=").append(overlayX).append(":").append(overlayY)
                    .append(":enable='between(t,").append(relativeStart).append(",").append(relativeEnd).append(")'");
            filterComplex.append("[").append(nextOutput).append("];");

            lastOutput = nextOutput;
        }

        // Ensure final output is mapped
        if (!lastOutput.equals("vout")) {
            filterComplex.append("[").append(lastOutput).append("]setpts=PTS-STARTPTS[vout];");
        }

        // Process audio
        boolean hasAudio = false;
        int validAudioInputs = 0;
        for (int j = 0; j < visibleSegments.size(); j++) {
            VideoSegment segment = visibleSegments.get(j);
            String videoPath = "videos/" + segment.getSourceVideoPath();
            if (!new File(videoPath).exists()) {
                continue;
            }

            int inputIndex = new ArrayList<>(videoInputMap.keySet()).get(j);
            hasAudio = true;

            double relativeStartTime = segment.getStartTime();
            if (segmentStart > segment.getTimelineStartTime()) {
                relativeStartTime += (segmentStart - segment.getTimelineStartTime());
            }
            double trimDuration = Math.min(segmentEnd, segment.getTimelineEndTime()) -
                    Math.max(segmentStart, segment.getTimelineStartTime());

            filterComplex.append("[").append(inputIndex).append(":a?]");
            filterComplex.append("atrim=").append(relativeStartTime).append(":")
                    .append(relativeStartTime + trimDuration).append(",");
            filterComplex.append("asetpts=PTS-STARTPTS");
            filterComplex.append("[a").append(j).append("];");

            validAudioInputs++;
        }

        if (validAudioInputs > 0) {
            filterComplex.append("[a0]");
            for (int j = 1; j < validAudioInputs; j++) {
                filterComplex.append("[a").append(j).append("]");
            }
            filterComplex.append("amix=inputs=").append(validAudioInputs).append(":duration=longest[aout]");
        }

        // Add filter_complex to command
        command.add("-filter_complex");
        command.add(filterComplex.toString());

        // Map outputs
        command.add("-map");
        command.add("[vout]");
        if (hasAudio && validAudioInputs > 0) {
            command.add("-map");
            command.add("[aout]");
        }

        // Output settings
        command.add("-c:v");
        command.add("libx264");
        command.add("-pix_fmt");
        command.add("yuv420p");
        if (hasAudio && validAudioInputs > 0) {
            command.add("-c:a");
            command.add("aac");
        }
        command.add("-shortest");
        command.add("-y");
        command.add(outputFile.getAbsolutePath());

        // Execute and debug
        System.out.println("Executing FFmpeg command for content segment: " + String.join(" ", command));
        executeFFmpegCommand(command);

        // Verify output file
        if (!outputFile.exists() || outputFile.length() == 0) {
            throw new RuntimeException("Failed to render segment: " + outputFile.getAbsolutePath());
        }
    }
    // Helper method to escape text for FFmpeg filters
    private String escapeText(String text) {
        if (text == null) return "";
        return text.replace("'", "\\'").replace(":", "\\:").replace(",", "\\,");
    }

    // Helper method to execute an FFmpeg command
    private void executeFFmpegCommand(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                System.out.println("FFmpeg process: " + line);
            }
        }

        boolean completed = process.waitFor(5, TimeUnit.MINUTES);
        if (!completed) {
            process.destroyForcibly();
            throw new RuntimeException("FFmpeg process timed out after 5 minutes");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg process failed with exit code " + exitCode + ": " + output.toString());
        }
    }

// Filters.......................................................................................

    // Helper method to get all filters for a specific segment
    private List<String> getFiltersForSegment(TimelineState timelineState, String segmentId) {
        VideoSegment segment = findSegmentById(timelineState, segmentId);
        if (segment == null) {
            return new ArrayList<>();
        }
        return segment.getFilters().values().stream()
                .map(filterData -> (String) filterData.get("filterString"))
                .collect(Collectors.toList());
    }

    // Enhanced method to apply filter with more options
    public void applyFilter(String sessionId, String segmentId, String filterType, Map<String, Object> filterParams) {
        EditSession session = getSession(sessionId);

        // Find the specific segment in the timeline by ID
        VideoSegment targetSegment = findSegmentById(session.getTimelineState(), segmentId);

        if (targetSegment == null) {
            throw new RuntimeException("Video segment not found with ID: " + segmentId);
        }

        // Build the appropriate filter string based on filter type and params
        String filterString;

        switch (filterType) {
            case "brightness":
                try {
                    double brightnessValue = Double.parseDouble(filterParams.getOrDefault("value", "0.0").toString());
                    filterString = "eq=brightness=" + brightnessValue;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid brightness value: " + filterParams.get("value"), e);
                }
                break;

            case "contrast":
                try {
                    double contrastValue = Double.parseDouble(filterParams.getOrDefault("value", "1.0").toString());
                    filterString = "eq=contrast=" + contrastValue;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid contrast value: " + filterParams.get("value"), e);
                }
                break;

            case "saturation":
                try {
                    double saturationValue = Double.parseDouble(filterParams.getOrDefault("value", "1.0").toString());
                    filterString = "eq=saturation=" + saturationValue;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid saturation value: " + filterParams.get("value"), e);
                }
                break;

            case "blur":
                try {
                    double sigmaValue = Double.parseDouble(filterParams.getOrDefault("sigma", "5.0").toString());
                    filterString = "boxblur=" + sigmaValue + ":" + sigmaValue;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid sigma value for blur: " + filterParams.get("sigma"), e);
                }
                break;

            case "sharpen":
                filterString = "unsharp=5:5:1.0:5:5:0.0";
                break;

            case "grayscale":
                filterString = "colorchannelmixer=.3:.4:.3:0:.3:.4:.3:0:.3:.4:.3";
                break;

            case "sepia":
                filterString = "colorchannelmixer=.393:.769:.189:0:.349:.686:.168:0:.272:.534:.131";
                break;

            case "mirror":
                filterString = "hflip";
                break;

            case "rotate":
                try {
                    int angle = Integer.parseInt(filterParams.getOrDefault("angle", "90").toString());
                    filterString = "rotate=" + angle + "*PI/180";
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid rotation angle: " + filterParams.get("angle"), e);
                }
                break;

            case "lut":
                String lutFile = filterParams.getOrDefault("file", "").toString();
                filterString = "lut3d=" + lutFile;
                break;

            case "colorbalance":
                try {
                    double rShadows = Double.parseDouble(filterParams.getOrDefault("rShadows", "0.0").toString());
                    double gShadows = Double.parseDouble(filterParams.getOrDefault("gShadows", "0.0").toString());
                    double bShadows = Double.parseDouble(filterParams.getOrDefault("bShadows", "0.0").toString());
                    double rMidtones = Double.parseDouble(filterParams.getOrDefault("rMidtones", "0.0").toString());
                    double gMidtones = Double.parseDouble(filterParams.getOrDefault("gMidtones", "0.0").toString());
                    double bMidtones = Double.parseDouble(filterParams.getOrDefault("bMidtones", "0.0").toString());
                    double rHighlights = Double.parseDouble(filterParams.getOrDefault("rHighlights", "0.0").toString());
                    double gHighlights = Double.parseDouble(filterParams.getOrDefault("gHighlights", "0.0").toString());
                    double bHighlights = Double.parseDouble(filterParams.getOrDefault("bHighlights", "0.0").toString());

                    filterString = "colorbalance=rs=" + rShadows + ":gs=" + gShadows + ":bs=" + bShadows +
                            ":rm=" + rMidtones + ":gm=" + gMidtones + ":bm=" + bMidtones +
                            ":rh=" + rHighlights + ":gh=" + gHighlights + ":bh=" + bHighlights;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid colorbalance parameter", e);
                }
                break;

            case "curves":
                String curvesMaster = filterParams.getOrDefault("master", "0/0 1/1").toString();
                String curvesRed = filterParams.getOrDefault("red", "0/0 1/1").toString();
                String curvesGreen = filterParams.getOrDefault("green", "0/0 1/1").toString();
                String curvesBlue = filterParams.getOrDefault("blue", "0/0 1/1").toString();

                filterString = "curves=master='" + curvesMaster + "':red='" + curvesRed +
                        "':green='" + curvesGreen + "':blue='" + curvesBlue + "'";
                break;

            case "vibrance":
                try {
                    double vibranceValue = Double.parseDouble(filterParams.getOrDefault("value", "0.5").toString());
                    filterString = "vibrance=" + vibranceValue;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid vibrance value: " + filterParams.get("value"), e);
                }
                break;

            case "vignette":
                try {
                    double vignetteAmount = Double.parseDouble(filterParams.getOrDefault("amount", "0.3").toString());
                    filterString = "vignette=angle=PI/4:x0=0.5:y0=0.5:mode=quadratic:amount=" + vignetteAmount;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid vignette amount: " + filterParams.get("amount"), e);
                }
                break;

            case "filmgrain":
                try {
                    double grainAmount = Double.parseDouble(filterParams.getOrDefault("amount", "0.1").toString());
                    filterString = "noise=c0s=" + grainAmount + ":c1s=" + grainAmount + ":c2s=" + grainAmount + ":allf=t";
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid filmgrain amount: " + filterParams.get("amount"), e);
                }
                break;

            case "cinematic":
                String aspectRatio = filterParams.getOrDefault("aspectRatio", "2.39:1").toString();
                filterString = "drawbox=y=0:w=iw:h=iw/(DAR*" + aspectRatio.replace(":", "/") + "):t=fill:c=black," +
                        "drawbox=y=ih-iw/(DAR*" + aspectRatio.replace(":", "/") + "):w=iw:h=iw/(DAR*" + aspectRatio.replace(":", "/") + "):t=fill:c=black," +
                        "eq=contrast=1.1:saturation=0.85:brightness=0.05";
                break;

            case "glow":
                try {
                    double glowIntensity = Double.parseDouble(filterParams.getOrDefault("intensity", "0.3").toString());
                    double glowRadius = Double.parseDouble(filterParams.getOrDefault("radius", "3.0").toString());
                    filterString = "glow=strength=" + glowIntensity + ":radius=" + glowRadius;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid glow parameter", e);
                }
                break;

            case "lensdistortion":
                try {
                    double k1 = Double.parseDouble(filterParams.getOrDefault("k1", "0.1").toString());
                    double k2 = Double.parseDouble(filterParams.getOrDefault("k2", "0.0").toString());
                    filterString = "lenscorrection=k1=" + k1 + ":k2=" + k2;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid lensdistortion parameter", e);
                }
                break;

            case "lensflare":
                try {
                    double intensity = Double.parseDouble(filterParams.getOrDefault("intensity", "0.5").toString());
                    double posX = Double.parseDouble(filterParams.getOrDefault("posX", "0.5").toString());
                    double posY = Double.parseDouble(filterParams.getOrDefault("posY", "0.3").toString());
                    filterString = "lensflare=x=" + posX + ":y=" + posY + ":intensity=" + intensity;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid lensflare parameter", e);
                }
                break;

            case "bleachbypass":
                filterString = "curves=master='0/0 0.25/0.15 0.5/0.35 0.75/0.8 1/1',eq=saturation=0.4:contrast=1.4";
                break;

            case "duotone":
                try {
                    String highlight = filterParams.getOrDefault("highlight", "ffffff").toString();
                    String shadow = filterParams.getOrDefault("shadow", "000000").toString();
                    filterString = "lut=r='clipval*" + Integer.parseInt(highlight.substring(0, 2), 16) + "/255 + (1-clipval)*"
                            + Integer.parseInt(shadow.substring(0, 2), 16) + "/255':g='clipval*"
                            + Integer.parseInt(highlight.substring(2, 4), 16) + "/255 + (1-clipval)*"
                            + Integer.parseInt(shadow.substring(2, 4), 16) + "/255':b='clipval*"
                            + Integer.parseInt(highlight.substring(4, 6), 16) + "/255 + (1-clipval)*"
                            + Integer.parseInt(shadow.substring(4, 6), 16) + "/255'";
                } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                    throw new RuntimeException("Invalid duotone color values: highlight=" + filterParams.get("highlight") + ", shadow=" + filterParams.get("shadow"), e);
                }
                break;

            case "tiltshift":
                try {
                    double blurAmount = Double.parseDouble(filterParams.getOrDefault("blur", "10").toString());
                    double centerY = Double.parseDouble(filterParams.getOrDefault("centerY", "0.5").toString());
                    double width = Double.parseDouble(filterParams.getOrDefault("width", "0.2").toString());
                    filterString = "tiltshift=center_y=" + centerY + ":inner_radius=" + width + ":outer_radius=" + (width * 3) + ":angle=0:bluramount=" + blurAmount;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid tiltshift parameter", e);
                }
                break;

            case "stabilize":
                try {
                    double smoothing = Double.parseDouble(filterParams.getOrDefault("smoothing", "10").toString());
                    filterString = "deshake=rx=64:ry=64:blocksize=16:smooth=" + smoothing;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid stabilize smoothing value: " + filterParams.get("smoothing"), e);
                }
                break;

            case "denoise":
                try {
                    double strength = Double.parseDouble(filterParams.getOrDefault("strength", "5").toString());
                    filterString = "nlmeans=s=" + strength;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid denoise strength value: " + filterParams.get("strength"), e);
                }
                break;

            case "sharpenmask":
                try {
                    double amount = Double.parseDouble(filterParams.getOrDefault("amount", "3.0").toString());
                    double radius = Double.parseDouble(filterParams.getOrDefault("radius", "1.0").toString());
                    double threshold = Double.parseDouble(filterParams.getOrDefault("threshold", "0.05").toString());
                    filterString = "unsharp=luma_msize_x=" + radius + ":luma_msize_y=" + radius +
                            ":luma_amount=" + amount + ":chroma_msize_x=" + radius +
                            ":chroma_msize_y=" + radius + ":chroma_amount=" + (amount * 0.5) +
                            ":alpha_msize_x=" + radius + ":alpha_msize_y=" + radius +
                            ":alpha_amount=" + (amount * 0.5) + ":luma_threshold=" + threshold;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid sharpenmask parameter", e);
                }
                break;

            case "crossprocess":
                filterString = "curves=r='0/0.05 0.5/0.5 1/0.95':g='0/0 0.5/0.4 1/0.95':b='0/0.1 0.5/0.5 1/0.9',eq=saturation=1.3:contrast=1.2";
                break;

            case "splittonemix":
                try {
                    String highlightColor = filterParams.getOrDefault("highlight", "ffeb3b").toString();
                    String shadowColor = filterParams.getOrDefault("shadow", "3f51b5").toString();
                    double balance = Double.parseDouble(filterParams.getOrDefault("balance", "0.5").toString());

                    int hR = Integer.parseInt(highlightColor.substring(0, 2), 16);
                    int hG = Integer.parseInt(highlightColor.substring(2, 4), 16);
                    int hB = Integer.parseInt(highlightColor.substring(4, 6), 16);

                    int sR = Integer.parseInt(shadowColor.substring(0, 2), 16);
                    int sG = Integer.parseInt(shadowColor.substring(2, 4), 16);
                    int sB = Integer.parseInt(shadowColor.substring(4, 6), 16);

                    filterString = "tinterlace=mode=4,curves=r='0/(" + sR + "/255) " + balance + "/0.5 1/(" + hR + "/255)':" +
                            "g='0/(" + sG + "/255) " + balance + "/0.5 1/(" + hG + "/255)':" +
                            "b='0/(" + sB + "/255) " + balance + "/0.5 1/(" + hB + "/255)'";
                } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
                    throw new RuntimeException("Invalid splittonemix parameter", e);
                }
                break;

            case "enhanceselective":
                try {
                    String colorRange = filterParams.getOrDefault("color", "red").toString();
                    double hueShift = Double.parseDouble(filterParams.getOrDefault("hue", "0.0").toString());
                    double satBoost = Double.parseDouble(filterParams.getOrDefault("saturation", "1.2").toString());
                    double lumAdjust = Double.parseDouble(filterParams.getOrDefault("luminance", "0.0").toString());

                    Map<String, double[]> colorRanges = new HashMap<>();
                    colorRanges.put("red", new double[]{0, 0.1, 0.9, 1.0});
                    colorRanges.put("orange", new double[]{0.08, 0.17, 0, 0});
                    colorRanges.put("yellow", new double[]{0.15, 0.25, 0, 0});
                    colorRanges.put("green", new double[]{0.25, 0.45, 0, 0});
                    colorRanges.put("aqua", new double[]{0.45, 0.55, 0, 0});
                    colorRanges.put("blue", new double[]{0.55, 0.65, 0, 0});
                    colorRanges.put("purple", new double[]{0.65, 0.75, 0, 0});
                    colorRanges.put("magenta", new double[]{0.75, 0.85, 0, 0});

                    double[] range = colorRanges.getOrDefault(colorRange, new double[]{0, 0.1, 0.9, 1.0});

                    filterString = "hsvkey=h=" + range[0] + ":" + range[1] + ":s=0:1:v=0:1:soft=1," +
                            "hue=h_expr=" + hueShift + "*PI:s_expr=VAL*" + satBoost + ":v_expr=VAL+" + lumAdjust + "," +
                            "hsvhold";
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid enhanceselective parameter", e);
                }
                break;

            case "fadeintransition":
                try {
                    double duration = Double.parseDouble(filterParams.getOrDefault("duration", "1.0").toString());
                    filterString = "fade=t=in:st=0:d=" + duration;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid fadeintransition duration: " + filterParams.get("duration"), e);
                }
                break;

            case "fadeouttransition":
                try {
                    double outDuration = Double.parseDouble(filterParams.getOrDefault("duration", "1.0").toString());
                    String position = filterParams.getOrDefault("position", "end").toString();
                    if (position.equals("end")) {
                        filterString = "fade=t=out:d=" + outDuration;
                    } else {
                        double startTime = Double.parseDouble(filterParams.getOrDefault("startTime", "0.0").toString());
                        filterString = "fade=t=out:st=" + startTime + ":d=" + outDuration;
                    }
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid fadeouttransition parameter", e);
                }
                break;

            case "whipdissolve":
                try {
                    double wipeAngle = Double.parseDouble(filterParams.getOrDefault("angle", "45.0").toString());
                    double wipeDuration = Double.parseDouble(filterParams.getOrDefault("duration", "0.5").toString());
                    filterString = "wipeleft=0.5:duration=" + wipeDuration + ":angle=" + wipeAngle;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid whipdissolve parameter", e);
                }
                break;

            case "pixelate":
                try {
                    int blockSize = Integer.parseInt(filterParams.getOrDefault("blockSize", "16").toString());
                    filterString = "pixelize=w=" + blockSize + ":h=" + blockSize;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid pixelate blockSize: " + filterParams.get("blockSize"), e);
                }
                break;

            case "watercolor":
                try {
                    double simplify = Double.parseDouble(filterParams.getOrDefault("simplify", "5.0").toString());
                    double pastelize = Double.parseDouble(filterParams.getOrDefault("pastelize", "0.5").toString());
                    filterString = "boxblur=luma_radius=" + simplify + ":luma_power=1:enable='between(t,0,999)'," +
                            "edgedetect=low=0.1:high=0.4:mode=colormix:enable='between(t,0,999)'," +
                            "eq=saturation=" + (1 + pastelize) + ":contrast=1.1:brightness=0.05:enable='between(t,0,999)'";
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid watercolor parameter", e);
                }
                break;

            case "dreamy":
                try {
                    double dreamGlow = Double.parseDouble(filterParams.getOrDefault("glow", "0.3").toString());
                    double dreamBlur = Double.parseDouble(filterParams.getOrDefault("blur", "3.0").toString());
                    double dreamSaturation = Double.parseDouble(filterParams.getOrDefault("saturation", "1.1").toString());
                    filterString = "gblur=sigma=" + dreamBlur + ":steps=3,split[a][b];" +
                            "[a]lutrgb=r=.95*val:g=.95*val:b=1.05*val,tonemap=reinhard," +
                            "eq=brightness=0.015:saturation=" + dreamSaturation + "[a1];" +
                            "[b]lutyuv=y=1.5*val:u=val:v=val[b1];" +
                            "[a1][b1]blend=all_mode=overlay:all_opacity=" + dreamGlow;
                } catch (NumberFormatException e) {
                    throw new RuntimeException("Invalid dreamy parameter", e);
                }
                break;

            case "broadcast":
                filterString = "normalize=blackpt=16:whitept=235:strength=1," +
                        "vectorscope=m=color3:g=green:b=0.75:e=zebra";
                break;

            case "colorgradepreset":
                String preset = filterParams.getOrDefault("preset", "neutral").toString();

                Map<String, String> presets = new HashMap<>();
                presets.put("neutral", "");
                presets.put("warm", "curves=r='0/0 1/0.9':g='0/0.05 1/0.95':b='0/0.1 1/0.9'");
                presets.put("cool", "curves=r='0/0.1 1/0.9':g='0/0.05 1/0.95':b='0/0 1/0.9'");
                presets.put("cine", "curves=master='0/0.05 0.5/0.4 1/0.95',eq=saturation=0.8:contrast=1.1:gamma=1.1");
                presets.put("vintage", "curves=r='0/0.16 .5/0.5 1/0.81':g='0/0.12 .5/0.5 1/0.91':b='0/0.2 .5/0.5 1/0.85'," +
                        "eq=saturation=0.6:contrast=1.1:brightness=0.05,gblur=sigma=0.5:steps=1");
                presets.put("scifi", "curves=r='0/0 .5/0.4 1/1':g='0/0 .5/0.5 1/1':b='0/0.1 .5/0.6 1/1'," +
                        "eq=saturation=0.8:contrast=1.2:brightness=0.05,vignette=angle=PI/4:x0=0.5:y0=0.5:mode=forward");
                presets.put("noir", "lutyuv=y=gammaval(0.7):u=128:v=128");

                filterString = presets.getOrDefault(preset, "");
                if (filterString.isEmpty()) {
                    filterString = "null"; // FFmpeg no-op filter
                }
                break;

            case "custom":
                filterString = filterParams.getOrDefault("value", "").toString();
                break;

            default:
                throw new RuntimeException("Unsupported filter type: " + filterType);
        }

        // Ensure filters map is initialized
        if (targetSegment.getFilters() == null) {
            targetSegment.setFilters(new LinkedHashMap<>());
        }

        String filterId = UUID.randomUUID().toString();
        Map<String, Object> filterData = new HashMap<>();
        filterData.put("filterString", filterString);
        filterData.put("filterType", filterType);
        filterData.put("filterParams", new HashMap<>(filterParams));
        filterData.put("appliedAt", System.currentTimeMillis());

        targetSegment.getFilters().put(filterId, filterData);
        session.setLastAccessTime(System.currentTimeMillis());

        // CHANGED: Removed the addition of a FILTER operation to TimelineState.getOperations()
        // Previously, something like this might have been here:
        // EditOperation filterOp = new EditOperation();
        // filterOp.setOperationType("FILTER");
        // filterOp.setSourceVideoPath(targetSegment.getSourceVideoPath());
        // Map<String, Object> params = new HashMap<>();
        // params.put("filter", filterString);
        // params.put("filterId", filterId);
        // params.put("segmentId", segmentId);
        // params.put("filterType", filterType);
        // params.put("filterParams", filterParams);
        // params.put("appliedAt", System.currentTimeMillis());
        // filterOp.setParameters(params);
        // session.getTimelineState().getOperations().add(filterOp);

        System.out.println("Applied filter: " + filterType + " (" + filterString + ") to video segment: " + segmentId);
    }

    // Method to get detailed filter information for UI display
    public List<Map<String, Object>> getFilterDetailsForSegment(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);
        VideoSegment segment = findSegmentById(session.getTimelineState(), segmentId);

        if (segment == null || segment.getFilters() == null) {
            return new ArrayList<>();
        }

        return segment.getFilters().entrySet().stream()
                .map(entry -> {
                    Map<String, Object> filterDetails = new HashMap<>();
                    Map<String, Object> filterData = (Map<String, Object>) entry.getValue();

                    filterDetails.put("filterId", entry.getKey());
                    filterDetails.put("filterType", filterData.get("filterType"));
                    filterDetails.put("filterString", filterData.get("filterString"));
                    filterDetails.put("appliedAt", filterData.get("appliedAt"));
                    filterDetails.put("parameters", filterData.get("filterParams"));

                    return filterDetails;
                })
                .collect(Collectors.toList());
    }

    public boolean updateFilter(String sessionId, String filterId, String segmentId,
                                String filterType, Map<String, Object> filterParams) {
        EditSession session = getSession(sessionId);
        VideoSegment segment = findSegmentById(session.getTimelineState(), segmentId);

        if (segment == null || segment.getFilters() == null || !segment.getFilters().containsKey(filterId)) {
            return false;
        }

        // Remove old filter and apply new one
        segment.getFilters().remove(filterId);
        applyFilter(sessionId, segmentId, filterType, filterParams);
        // CHANGED: No need to add an operation here either, just update the segment's filters
        return true;
    }

    public boolean removeFilter(String sessionId, String filterId) {
        EditSession session = getSession(sessionId);

        for (VideoSegment segment : session.getTimelineState().getSegments()) {
            if (segment.getFilters() != null && segment.getFilters().containsKey(filterId)) {
                segment.getFilters().remove(filterId);
                session.setLastAccessTime(System.currentTimeMillis());
                // CHANGED: No operation added to TimelineState.getOperations()
                return true;
            }
        }
        return false;
    }

    public Map<String, Object> removeAllFiltersFromSegment(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);
        VideoSegment segment = findSegmentById(session.getTimelineState(), segmentId);

        Map<String, Object> result = new HashMap<>();
        result.put("removed", false);
        result.put("removedFilterIds", new ArrayList<String>());

        if (segment != null && segment.getFilters() != null && !segment.getFilters().isEmpty()) {
            List<String> removedFilterIds = new ArrayList<>(segment.getFilters().keySet());
            segment.getFilters().clear();
            session.setLastAccessTime(System.currentTimeMillis());
            // CHANGED: No operation added to TimelineState.getOperations()

            result.put("removed", true);
            result.put("removedFilterIds", removedFilterIds);
        }

        return result;
    }

    // Helper method to find a segment by its ID (unchanged)
    private VideoSegment findSegmentById(TimelineState timelineState, String segmentId) {
        return timelineState.getSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElse(null);
    }

    // executeFFmpegCommand method remains unchanged
    private void executeFFmpegCommand(ProcessBuilder builder) throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        Process process = builder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("FFmpeg command failed with exit code: " + exitCode);
        }
    }

//    IMAGE ADDING ...................................................................................................

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
            Integer customWidth,
            Integer customHeight,
            Boolean maintainAspectRatio,
            Map<String, String> filters
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
            File imageFile = new File("images/" + imagePath);
            if (!imageFile.exists()) {
                throw new RuntimeException("Image file does not exist: " + imageFile.getAbsolutePath());
            }
            BufferedImage img = ImageIO.read(imageFile);
            imageSegment.setWidth(img.getWidth());
            imageSegment.setHeight(img.getHeight());
        } catch (IOException e) {
            throw new RuntimeException("Error reading image file: " + e.getMessage());
        }

        if (customWidth != null) imageSegment.setCustomWidth(customWidth);
        if (customHeight != null) imageSegment.setCustomHeight(customHeight);
        if (maintainAspectRatio != null) imageSegment.setMaintainAspectRatio(maintainAspectRatio);
        if (filters != null && !filters.isEmpty()) imageSegment.setFilters(new HashMap<>(filters));

        timelineState.getImageSegments().add(imageSegment);

        // Removed EditOperation creation
        // REMOVED: EditOperation addOperation = new EditOperation();
        // REMOVED: addOperation.setOperationType("ADD_IMAGE");
        // REMOVED: addOperation.setSourceVideoPath(imagePath);
        // REMOVED: Map<String, Object> params = new HashMap<>();
        // REMOVED: params.put("imageSegmentId", imageSegment.getId());
        // REMOVED: params.put("time", System.currentTimeMillis());
        // REMOVED: params.put("layer", layer);
        // REMOVED: params.put("timelineStartTime", timelineStartTime);
        // REMOVED: params.put("timelineEndTime", imageSegment.getTimelineEndTime());
        // REMOVED: params.put("positionX", positionX);
        // REMOVED: params.put("positionY", positionY);
        // REMOVED: params.put("scale", scale);
        // REMOVED: if (customWidth != null) params.put("customWidth", customWidth);
        // REMOVED: if (customHeight != null) params.put("customHeight", customHeight);
        // REMOVED: if (maintainAspectRatio != null) params.put("maintainAspectRatio", maintainAspectRatio);
        // REMOVED: if (filters != null && !filters.isEmpty()) params.put("filters", filters);
        // REMOVED: addOperation.setParameters(params);
        // REMOVED: timelineState.getOperations().add(addOperation);

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
            List<String> filtersToRemove
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

        // Removed EditOperation creation
        // REMOVED: EditOperation updateOperation = new EditOperation();
        // REMOVED: updateOperation.setOperationType("UPDATE_IMAGE");
        // REMOVED: updateOperation.setSourceVideoPath(targetSegment.getImagePath());
        // REMOVED: Map<String, Object> params = new HashMap<>();
        // REMOVED: params.put("imageSegmentId", imageSegmentId);
        // REMOVED: params.put("time", System.currentTimeMillis());
        // REMOVED: if (positionX != null) params.put("positionX", positionX);
        // REMOVED: if (positionY != null) params.put("positionY", positionY);
        // REMOVED: if (scale != null) params.put("scale", scale);
        // REMOVED: if (opacity != null) params.put("opacity", opacity);
        // REMOVED: if (layer != null) params.put("layer", layer);
        // REMOVED: if (customWidth != null) params.put("customWidth", customWidth);
        // REMOVED: if (customHeight != null) params.put("customHeight", customHeight);
        // REMOVED: if (maintainAspectRatio != null) params.put("maintainAspectRatio", maintainAspectRatio);
        // REMOVED: if (filters != null) params.put("filtersAdded", filters);
        // REMOVED: if (filtersToRemove != null) params.put("filtersRemoved", filtersToRemove);
        // REMOVED: updateOperation.setParameters(params);
        // REMOVED: timelineState.getOperations().add(updateOperation);

        saveTimelineState(sessionId, timelineState);
    }

//    ADD TEXT ..............................................................................................................

    // Add this method to handle adding text to the timeline
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

        // Removed EditOperation creation
        // REMOVED: EditOperation addOperation = new EditOperation();
        // REMOVED: addOperation.setOperationType("ADD_TEXT");
        // REMOVED: addOperation.setParameters(Map.of(
        // REMOVED:     "time", System.currentTimeMillis(),
        // REMOVED:     "layer", layer,
        // REMOVED:     "timelineStartTime", timelineStartTime,
        // REMOVED:     "timelineEndTime", timelineEndTime
        // REMOVED: ));
        // REMOVED: session.getTimelineState().getOperations().add(addOperation);

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

        // Removed EditOperation creation
        // REMOVED: EditOperation updateOperation = new EditOperation();
        // REMOVED: updateOperation.setOperationType("UPDATE_TEXT");
        // REMOVED: Map<String, Object> parameters = new HashMap<>();
        // REMOVED: parameters.put("time", System.currentTimeMillis());
        // REMOVED: parameters.put("segmentId", segmentId);
        // REMOVED: if (text != null) parameters.put("text", text);
        // REMOVED: if (fontFamily != null) parameters.put("fontFamily", fontFamily);
        // REMOVED: if (fontSize != null) parameters.put("fontSize", fontSize);
        // REMOVED: if (fontColor != null) parameters.put("fontColor", fontColor);
        // REMOVED: if (backgroundColor != null) parameters.put("backgroundColor", backgroundColor);
        // REMOVED: if (positionX != null) parameters.put("positionX", positionX);
        // REMOVED: if (positionY != null) parameters.put("positionY", positionY);
        // REMOVED: if (timelineStartTime != null) parameters.put("timelineStartTime", timelineStartTime);
        // REMOVED: if (timelineEndTime != null) parameters.put("timelineEndTime", timelineEndTime);
        // REMOVED: if (layer != null) parameters.put("layer", layer);
        // REMOVED: updateOperation.setParameters(parameters);
        // REMOVED: session.getTimelineState().getOperations().add(updateOperation);

        session.setLastAccessTime(System.currentTimeMillis());
    }
    private String getFontPathByFamily(String fontFamily) {

//        // Default font path if nothing else matches
//        String defaultFontPath = "C:/Windows/Fonts/Arial.ttf";
        // Default font path
        String defaultFontPath = getSystemDefaultFontPath();

        if (fontFamily == null || fontFamily.trim().isEmpty()) {
            return defaultFontPath;
        }
        // Get platform-specific font directory
        String fontDirectory = getSystemFontDirectory();

        // Platform-specific font file extensions
        Map<String, String> fontExtensions = getPlatformFontExtensions();
        // Map common font families to their file paths
        // You can expand this map with more fonts as needed

//        Map<String, String> fontMap = new HashMap<>();
//        fontMap.put("Arial", "C\:/Windows/Fonts/Arial.ttf");
//        fontMap.put("Times New Roman", "C\:/Windows/Fonts/times.ttf");
//        fontMap.put("Courier New", "C\:/Windows/Fonts/cour.ttf");
//        fontMap.put("Calibri", "C\:/Windows/Fonts/calibri.ttf");
//        fontMap.put("Verdana", "C\:/Windows/Fonts/verdana.ttf");
//        fontMap.put("Georgia", "C\:/Windows/Fonts/georgia.ttf");
//        fontMap.put("Comic Sans MS", "C\:/Windows/Fonts/comic.ttf");
//        fontMap.put("Impact", "C\:/Windows/Fonts/impact.ttf");
//        fontMap.put("Tahoma", "C\:/Windows/Fonts/tahoma.ttf");

        // Map common font families to their file names (without full paths)
        Map<String, String> fontMap = new HashMap<>();
        fontMap.put("Arial", "Arial");
        fontMap.put("Times New Roman", "Times");
        fontMap.put("Courier New", "Courier");
        fontMap.put("Calibri", "Calibri");
        fontMap.put("Verdana", "Verdana");
        fontMap.put("Georgia", "Georgia");
        fontMap.put("Comic Sans MS", "Comic");
        fontMap.put("Impact", "Impact");
        fontMap.put("Tahoma", "Tahoma");

        // Process the font family name to match potential keys
        String processedFontFamily = fontFamily.trim();

// Try to find the font in our map
        String fontFileName = null;
        for (Map.Entry<String, String> entry : fontMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(processedFontFamily)) {
                fontFileName = entry.getValue();
                break;
            }
        }

        // If we found a mapped font, construct the full path using the system directory
        if (fontFileName != null) {
            // Try common extensions for this platform
            for (String ext : fontExtensions.values()) {
                File fontFile = new File(fontDirectory, fontFileName + ext);
                if (fontFile.exists()) {
                    return fontFile.getAbsolutePath();
                }
            }
        }

        // If we couldn't find the font, try font discovery
        String discoveredFont = discoverFont(processedFontFamily);
        if (discoveredFont != null) {
            return discoveredFont;
        }

        // Fall back to default
        System.out.println("Warning: Font family '" + fontFamily + "' not found. Using default font as fallback.");
        return defaultFontPath;
    }

//    Code by Raj
//        // Try direct match
//        if (fontMap.containsKey(processedFontFamily)) {
//            System.out.println("Found exact font match for: " + processedFontFamily);
//            return fontMap.get(processedFontFamily);
//        }
//
//        // Try case-insensitive match
//        for (Map.Entry<String, String> entry : fontMap.entrySet()) {
//            if (entry.getKey().equalsIgnoreCase(processedFontFamily)) {
//                System.out.println("Found case-insensitive font match for: " + processedFontFamily);
//                return entry.getValue();
//            }
//        }

    // If the specified font isn't in our map, you might want to try a more elaborate lookup
    // For example, scanning the Windows fonts directory or using platform-specific APIs
    // For now, we'll just log this and fallback to Arial

//        System.out.println("Warning: Font family '" + fontFamily + "' not found in font map. Using Arial as fallback.");
//        return defaultFontPath;

    private String getSystemFontDirectory() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "C:/Windows/Fonts/";
        } else if (os.contains("mac")) {
            return "/Library/Fonts/";
        } else if (os.contains("nix") || os.contains("nux") || os.contains("aix")) {
            return "/usr/share/fonts/";
        } else {
            return "";
        }
    }

    private Map<String, String> getPlatformFontExtensions() {
        Map<String, String> extensions = new HashMap<>();
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            extensions.put("ttf", ".ttf");
            extensions.put("otf", ".otf");
        } else if (os.contains("mac")) {
            extensions.put("ttf", ".ttf");
            extensions.put("otf", ".otf");
            extensions.put("dfont", ".dfont");
        } else {
            extensions.put("ttf", ".ttf");
            extensions.put("otf", ".otf");
        }
        return extensions;
    }

    private String getSystemDefaultFontPath() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return "C:/Windows/Fonts/Arial.ttf";
        } else if (os.contains("mac")) {
            return "/Library/Fonts/Helvetica.dfont";
        } else {
            return "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
        }
    }

    private String discoverFont(String fontName) {
        // This is where you'd implement a more sophisticated font discovery mechanism
        // For example, you could:
        // 1. Use the Java GraphicsEnvironment to list available fonts
        // 2. Use system-specific commands to list fonts
        // 3. Use a font registry or database

        // Simple implementation using GraphicsEnvironment
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            Font[] fonts = ge.getAllFonts();
            for (Font font : fonts) {
                if (font.getFamily().equalsIgnoreCase(fontName)) {
                    // This doesn't give us the file path, but we can use it to confirm the font exists
                    System.out.println("Found font: " + fontName + " in system fonts");

                    // We'd need additional logic here to map from font name to file path
                    // This is platform-specific and might require JNI calls
                    return lookupFontPath(font);
                }
            }
        } catch (Exception e) {
            System.out.println("Error discovering fonts: " + e.getMessage());
        }

        return null;
    }

    private String lookupFontPath(Font font) {
        // This would need to be implemented with platform-specific code
        // For professional apps, this often involves JNI (Java Native Interface) calls
        // or system commands to get the actual file path

        // Placeholder implementation
        return null;
    }

}

