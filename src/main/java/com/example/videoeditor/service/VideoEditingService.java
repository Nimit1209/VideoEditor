package com.example.videoeditor.service;

import com.example.videoeditor.entity.Project;
import com.example.videoeditor.dto.*;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.EditedVideoRepository;
import com.example.videoeditor.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
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

    public Project createProject(User user, String name) throws JsonProcessingException {
        Project project = new Project();
        project.setUser(user);
        project.setName(name);
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
        } else {
            timelineState = new TimelineState();
        }

        // Ensure 'operations' is initialized
        if (timelineState.getOperations() == null) {
            timelineState.setOperations(new ArrayList<>());
        }

        session.setTimelineState(timelineState);
        activeSessions.put(sessionId, session);

        return sessionId;
    }


    public void splitVideo(String sessionId, String videoPath, double splitTime, String segmentId) throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);

        System.out.println("Splitting video: " + videoPath);
        System.out.println("Split time requested: " + splitTime);
        System.out.println("Segment index: " + segmentId);

        File file = new File(videoPath);
        System.out.println("Checking file exists at: " + file.getAbsolutePath());

        if (!file.exists()) {
            // Try with a different path format if the file doesn't exist
            File alternativeFile = new File("videos/" + videoPath);
            System.out.println("Original file not found, checking alternative path: " + alternativeFile.getAbsolutePath());

            if (alternativeFile.exists()) {
                videoPath = "videos/" + videoPath;
                file = alternativeFile;
                System.out.println("Found file at alternative path: " + file.getAbsolutePath());
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

        System.out.println("Found segment for splitting at index: " + originalSegmentIndex);
        System.out.println("Segment video path: " + originalSegment.getSourceVideoPath());

        double startTime = originalSegment.getStartTime();
        double endTime;

        if (originalSegment.getEndTime() == -1) {
            try {
                endTime = getVideoDuration(videoPath);
                System.out.println("Video duration fetched: " + endTime + " seconds");
            } catch (Exception e) {
                System.err.println("Error getting video duration: " + e.getMessage());
                e.printStackTrace();
                // Default to a reasonable duration if we can't get it
                endTime = 3600; // 1 hour default
                System.out.println("Using default duration: " + endTime + " seconds");
            }
        } else {
            endTime = originalSegment.getEndTime();
        }

        System.out.println("Segment start time: " + startTime);
        System.out.println("Segment end time: " + endTime);
        System.out.println("Split time: " + splitTime);

        // Add a small buffer to avoid floating point comparison issues
        if (splitTime <= (startTime + 0.1) || splitTime >= (endTime - 0.1)) {
            System.err.println("Invalid split point. Must be between " + (startTime + 0.1) + " and " + (endTime - 0.1));
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

        // Replace the original segment with the new segments
        session.getTimelineState().getSegments().set(originalSegmentIndex, firstPart);
        session.getTimelineState().getSegments().add(originalSegmentIndex + 1, secondPart);

        // Create the split operation
        EditOperation split = new EditOperation();
        split.setOperationType("SPLIT");
        split.setSourceVideoPath(videoPath);
        split.setParameters(Map.of("splitTime", splitTime));
        session.getTimelineState().getOperations().add(split);

        session.setLastAccessTime(System.currentTimeMillis());
    }

    private double getVideoDuration(String videoPath) throws IOException, InterruptedException {
        System.out.println("Getting duration for: " + videoPath);

        // Verify the file exists
        File videoFile = new File(videoPath);
        if (!videoFile.exists()) {
            System.err.println("Video file does not exist: " + videoPath);
            throw new IOException("Video file not found: " + videoPath);
        }

        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-i", videoPath
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



    public void saveProject(String sessionId) throws JsonProcessingException {
        EditSession session = getSession(sessionId);
        Project project = projectRepository.findById(session.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found"));

        project.setTimelineState(objectMapper.writeValueAsString(session.getTimelineState()));
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);
    }

    public ResponseEntity<Map<String, String>> exportProject(String sessionId) throws IOException, InterruptedException {
        EditSession session = getSession(sessionId);
        TimelineState timeline = session.getTimelineState();

        if (timeline.getSegments().isEmpty()) {
            throw new RuntimeException("No video segments to export.");
        }

        // Ensure the output directory exists
        File outputDir = new File("edited_videos");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        // Generate a unique filename for the final output
        String finalVideoId = UUID.randomUUID().toString();
        String finalVideoPath = "edited_videos/final_" + finalVideoId + ".mp4";

        // Create a temporary file for concatenation
        File concatFile = new File("temp_concat_" + finalVideoId + ".txt");
        List<String> tempFiles = new ArrayList<>(); // Track temp files to delete later

        try (PrintWriter writer = new PrintWriter(concatFile)) {
            // Process each segment in the timeline
            for (VideoSegment segment : timeline.getSegments()) {
                // Process this segment with all its applicable operations
                String processedPath = processSegment(segment, timeline.getOperations());
                writer.println("file '" + processedPath + "'");

                // Add to list of temp files if it's a processed segment (not original source)
                if (processedPath.startsWith("edited_videos/segment_")) {
                    tempFiles.add(processedPath);
                }
            }
        }

        // Concatenate all processed segments into the final video
        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-f", "concat", "-safe", "0",
                "-i", concatFile.getAbsolutePath(),
                "-c:v", "libx264",
                "-c:a", "aac",
                "-b:v", "5M",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                finalVideoPath
        );

        executeFFmpegCommand(builder);

        // Clean up temporary files
        concatFile.delete();

        // Delete all temporary segment files
        for (String tempFile : tempFiles) {
            try {
                File file = new File(tempFile);
                if (file.exists()) {
                    boolean deleted = file.delete();
                    if (!deleted) {
                        System.out.println("Warning: Could not delete temporary file: " + tempFile);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error deleting temporary file " + tempFile + ": " + e.getMessage());
            }
        }

        // Return the path to the final video
        Map<String, String> response = new HashMap<>();
        response.put("videoPath", finalVideoPath);

        return ResponseEntity.ok(response);
    }
    // Process a segment with all applicable operations
    // Process a segment with all applicable operations
    private String processSegment(VideoSegment segment, List<EditOperation> allOperations)
            throws IOException, InterruptedException {

        // Get the input path, ensuring it's properly formatted
        String inputPath = segment.getSourceVideoPath();
        if (!inputPath.contains("/")) {
            inputPath = "videos/" + inputPath;
        }

        // Find all operations that apply to this segment
        List<EditOperation> applicableOperations = allOperations.stream()
                .filter(op -> isOperationApplicableToSegment(op, segment.getId()))
                .collect(Collectors.toList());

        // If no operations and no trimming needed, return the original path
        if (applicableOperations.isEmpty() && segment.getStartTime() == 0 && segment.getEndTime() == -1) {
            return inputPath;
        }

        // Create a temporary output path for this processed segment
        String outputPath = "edited_videos/segment_" + UUID.randomUUID() + ".mp4";

        // Build the FFmpeg command
        List<String> command = buildFFmpegCommand(inputPath, outputPath, segment, applicableOperations);

        // Execute the command
        ProcessBuilder builder = new ProcessBuilder(command);
        executeFFmpegCommand(builder);

        return outputPath;
    }

    // Determine if an operation applies to a specific segment
    private boolean isOperationApplicableToSegment(EditOperation operation, String segmentId) {
        String operationType = operation.getOperationType();

        // For FILTER operations, check if the segmentId matches
        if ("FILTER".equals(operationType)) {
            return segmentId.equals(operation.getParameters().get("segmentId"));
        }

        // For ADD operations, check if this is the segment that was added
        if ("ADD".equals(operationType)) {
            return segmentId.equals(operation.getParameters().get("segmentId"));
        }

        // For SPLIT operations, currently no direct action needed during export
        // as splits are already represented by multiple segments
        if ("SPLIT".equals(operationType)) {
            return false;
        }

        // For future operation types, add handling here
        // Return false by default for unknown operations
        return false;
    }

    // Build an FFmpeg command for processing a segment
    private List<String> buildFFmpegCommand(String inputPath, String outputPath,
                                            VideoSegment segment, List<EditOperation> operations) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-i");
        command.add(inputPath);

        // Add segment start time
        if (segment.getStartTime() > 0) {
            command.add("-ss");
            command.add(String.valueOf(segment.getStartTime()));
        }

        // Add segment duration if an end time is specified
        if (segment.getEndTime() > 0) {
            command.add("-t");
            command.add(String.valueOf(segment.getEndTime() - segment.getStartTime()));
        }

        // Extract all filter operations
        List<String> filters = operations.stream()
                .filter(op -> "FILTER".equals(op.getOperationType()))
                .map(op -> (String) op.getParameters().get("filter"))
                .collect(Collectors.toList());

        // Add video filters if any
        if (!filters.isEmpty()) {
            command.add("-vf");
            command.add(String.join(",", filters));
        }

        // Add future operation types here with appropriate FFmpeg parameters

        // Output options
        command.add("-c:a");
        command.add("copy");
        command.add(outputPath);

        return command;
    }
    // Helper method to combine multiple filters into a single FFmpeg filter chain
    private String combineFilters(List<EditOperation> filterOperations) {
        return filterOperations.stream()
                .map(op -> (String) op.getParameters().get("filter"))
                .collect(Collectors.joining(","));
    }

    // Get all filter operations for a specific segment
    private List<EditOperation> getFilterOperationsForSegment(String segmentId, TimelineState timelineState) {
        return timelineState.getOperations().stream()
                .filter(op -> "FILTER".equals(op.getOperationType()) &&
                        segmentId.equals(op.getParameters().get("segmentId")))
                .collect(Collectors.toList());
    }

    private File renderFinalVideo(List<String> segmentPaths, String outputPath) throws IOException, InterruptedException {
        File outputDir = new File("edited_videos");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        File concatFile = new File("temp_concat_export.txt"); // Ensure correct path
        try (PrintWriter writer = new PrintWriter(concatFile)) {
            for (String path : segmentPaths) {
                // Path handling logic (as fixed in previous response)  FOR WINDOWS
//                if (!path.contains("/") && !path.contains("\\")) {
//                    path = "videos/" + path;
//                }
                if (!path.contains("/")) { // If it's just a filename, prepend correct path
                    path = "videos/" + path;
                }
//                // If `path` is just a filename, prepend the full directory path if needed
//                if (!path.startsWith("D:/") && !path.startsWith("edited_videos/")) {
//                    path = "D:/videoEditor/videos/" + path;
//                }
                writer.println("file '" + path + "'");
            }
        }

        ProcessBuilder builder = new ProcessBuilder(
                ffmpegPath, "-f", "concat", "-safe", "0",
                "-i", concatFile.getAbsolutePath(),
                "-c:v", "libx264", // Use H.264 codec for video
                "-c:a", "aac",     // Use AAC codec for audio
                "-b:v", "5M",      // Set video bitrate
                "-pix_fmt", "yuv420p", // Use common pixel format
                "-movflags", "+faststart", // Optimize for web streaming
                outputPath
        );
        System.out.println("Executing FFmpeg command: " + String.join(" ", builder.command())); // Logging command
        executeFFmpegCommand(builder);

        concatFile.delete();
        return new File(outputPath);
    }


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



    public void addVideoToTimeline(String sessionId, String videoPath) {
        // Log the incoming request parameters
        System.out.println("addVideoToTimeline called with sessionId: " + sessionId);
        System.out.println("Video path: " + videoPath);

        EditSession session = getSession(sessionId);

        if (session == null) {
            System.err.println("No active session found for sessionId: " + sessionId);
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }

        System.out.println("Session found, project ID: " + session.getProjectId());

        try {
            // Create a video segment for the entire video - use the constructor
            VideoSegment segment = new VideoSegment();  // This will set UUID automatically
            segment.setSourceVideoPath(videoPath);
            segment.setStartTime(0);
            segment.setEndTime(-1); // -1 indicates full duration
            // No need to set ID as it's done in the constructor

            // Add to timeline
            if (session.getTimelineState() == null) {
                System.out.println("Timeline state was null, creating new one");
                session.setTimelineState(new TimelineState());
            }

            session.getTimelineState().getSegments().add(segment);
            System.out.println("Added segment to timeline, now have " +
                    session.getTimelineState().getSegments().size() + " segments");

            // Create an ADD operation for tracking
            EditOperation addOperation = new EditOperation();
            addOperation.setOperationType("ADD");
            addOperation.setSourceVideoPath(videoPath);
            addOperation.setParameters(Map.of(
                    "time", System.currentTimeMillis(),
                    "segmentId", segment.getId()  // Use the ID from the segment
            ));

            session.getTimelineState().getOperations().add(addOperation);
            session.setLastAccessTime(System.currentTimeMillis());

            System.out.println("Successfully added video to timeline with segment ID: " + segment.getId());
        } catch (Exception e) {
            System.err.println("Error in addVideoToTimeline: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    public void applyFilter(String sessionId, String videoPath, String segmentId, String filter) {
        EditSession session = getSession(sessionId);

        // Find the specific segment in the timeline by ID
        VideoSegment targetSegment = findSegmentById(session.getTimelineState(), segmentId);

        if (targetSegment == null) {
            throw new RuntimeException("Video segment not found with ID: " + segmentId);
        }

        // Create filter operation and associate it with the specific segment
        EditOperation filterOperation = new EditOperation();
        filterOperation.setOperationType("FILTER");
        filterOperation.setSourceVideoPath(videoPath);

        // Create a map with both the filter and the segmentId to link them
        Map<String, Object> params = new HashMap<>();
        params.put("filter", filter);
        params.put("segmentId", segmentId);
        params.put("appliedAt", System.currentTimeMillis());
        params.put("filterId", UUID.randomUUID().toString());  // Generate unique ID for each filter

        filterOperation.setParameters(params);

        // Add to timeline state operations
        session.getTimelineState().getOperations().add(filterOperation);
        session.setLastAccessTime(System.currentTimeMillis());

        System.out.println("Applied filter: " + filter + " to video segment: " + segmentId);
    }

    // Helper method to find a segment by its source video path
    private VideoSegment findSegmentByPath(TimelineState timelineState, String videoPath) {
        return timelineState.getSegments().stream()
                .filter(segment -> segment.getSourceVideoPath().equals(videoPath))
                .findFirst()
                .orElse(null);
    }

    // Helper method to find a segment by its ID
    private VideoSegment findSegmentById(TimelineState timelineState, String segmentId) {
        return timelineState.getSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElse(null);
    }

    // Method to get active filters for a specific segment - useful for UI display
    public List<String> getActiveFiltersForSegment(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);

        return session.getTimelineState().getOperations().stream()
                .filter(op -> "FILTER".equals(op.getOperationType()) &&
                        segmentId.equals(op.getParameters().get("segmentId")))
                .map(op -> (String) op.getParameters().get("filter"))
                .collect(Collectors.toList());
    }

    // Method to remove a filter from a segment
    public void removeFilter(String sessionId, String segmentId, String filterId) {
        EditSession session = getSession(sessionId);

        // Remove the specific filter operation
        session.getTimelineState().getOperations().removeIf(op ->
                "FILTER".equals(op.getOperationType()) &&
                        segmentId.equals(op.getParameters().get("segmentId")) &&
                        filterId.equals(op.getParameters().get("filterId")));

        session.setLastAccessTime(System.currentTimeMillis());
    }
}