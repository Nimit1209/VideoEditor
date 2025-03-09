package com.example.videoeditor.service;

import com.example.videoeditor.entity.Project;
import com.example.videoeditor.dto.*;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.EditedVideoRepository;
import com.example.videoeditor.repository.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
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


    public void saveProject(String sessionId) throws JsonProcessingException {
        EditSession session = getSession(sessionId);
        Project project = projectRepository.findById(session.getProjectId())
                .orElseThrow(() -> new RuntimeException("Project not found"));

        project.setTimelineState(objectMapper.writeValueAsString(session.getTimelineState()));
        project.setLastModified(LocalDateTime.now());
        projectRepository.save(project);
    }

    private EditSession getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId))
                .orElseThrow(() -> new RuntimeException("No active session found"));
    }



    public void addVideoToTimeline(String sessionId, String videoPath) throws IOException, InterruptedException {
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

            double duration = getVideoDuration(videoPath);
            System.out.println("Actual video duration: " + duration);

            // Create a video segment for the entire video
            VideoSegment segment = new VideoSegment();
            segment.setSourceVideoPath(videoPath);
            segment.setStartTime(0);
            segment.setEndTime(duration); // -1 indicates full duration

            segment.setPositionX(0);
            segment.setPositionY(0);
            segment.setScale(1.0);

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
            addOperation.setParameters(Map.of("time", System.currentTimeMillis()));

            session.getTimelineState().getOperations().add(addOperation);
            session.setLastAccessTime(System.currentTimeMillis());

            System.out.println("Successfully added video to timeline");
        } catch (Exception e) {
            System.err.println("Error in addVideoToTimeline: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    public void updateVideoSegment(String sessionId, String segmentId,
                                   Integer positionX, Integer positionY, Double scale) {
        // Log the incoming request parameters
        System.out.println("updateVideoSegment called with sessionId: " + sessionId);
        System.out.println("Segment ID: " + segmentId);
        System.out.println("Position X: " + positionX + ", Position Y: " + positionY + ", Scale: " + scale);

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

        // Create an UPDATE operation for tracking
        EditOperation updateOperation = new EditOperation();
        updateOperation.setOperationType("UPDATE");
        updateOperation.setSourceVideoPath(segmentToUpdate.getSourceVideoPath());

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("time", System.currentTimeMillis());
        parameters.put("segmentId", segmentId);
        if (positionX != null) parameters.put("positionX", positionX);
        if (positionY != null) parameters.put("positionY", positionY);
        if (scale != null) parameters.put("scale", scale);
        updateOperation.setParameters(parameters);

        session.getTimelineState().getOperations().add(updateOperation);
        session.setLastAccessTime(System.currentTimeMillis());

        System.out.println("Successfully updated video segment");
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
        String outputFileName = project.getName().replaceAll("[^a-zA-Z0-9]", "") + ""
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

        // Validate timeline
        if (timelineState.getSegments() == null || timelineState.getSegments().isEmpty()) {
            throw new RuntimeException("Cannot export empty timeline");
        }

        // Create a temporary directory for intermediate files
        File tempDir = new File("temp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }

        // Create a list to store intermediate files
        List<File> intermediateFiles = new ArrayList<>();

        // Process each segment in the timeline
        for (VideoSegment segment : timelineState.getSegments()) {
            String videoPath = "videos/" + segment.getSourceVideoPath();

            // Verify source video exists
            File sourceVideo = new File(videoPath);
            if (!sourceVideo.exists()) {
                throw new IOException("Source video not found: " + videoPath);
            }

            // Create a temporary file for this segment
            String tempFilename = "temp_" + UUID.randomUUID().toString() + ".mp4";
            File tempFile = new File(tempDir, tempFilename);
            intermediateFiles.add(tempFile);

            // Get segment details
            int positionX = segment.getPositionX();
            int positionY = segment.getPositionY();
            double scale = segment.getScale(); // Scale factor (e.g., 1.0 = original, 0.6 = 60%)


//            TO BE TESTED
            // Get all filters that apply to this segment
            List<String> segmentFilters = getFiltersForSegment(timelineState, segment.getId());
            System.out.println("Found " + segmentFilters.size() + " filters for segment " + segment.getId());

            // Start building the filter_complex string
            StringBuilder filterComplex = new StringBuilder();

            // Add scaling filter
            filterComplex.append("[0:v]");

            // Apply all segment-specific filters first
            if (!segmentFilters.isEmpty()) {
                for (String filter : segmentFilters) {
                    filterComplex.append(filter).append(",");
                }
            }

            // Add scaling after filters
            filterComplex.append("scale=iw*").append(scale).append(":ih*").append(scale).append("[scaled];");

            // Create background and overlay
            filterComplex.append("color=black:size=").append(canvasWidth).append("x").append(canvasHeight).append("[bg];");


            // Calculate scaled dimensions
            String scaleFilter = "scale=iw*" + scale + ":ih*" + scale + "[scaled]";

            // Compute centered position with offset
            String overlayX = "(W-w)/2+" + positionX;
            String overlayY = "(H-h)/2+" + positionY;

            filterComplex.append("[bg][scaled]overlay=x=").append(overlayX).append(":y=").append(overlayY);

            // Build FFmpeg command for trimming, scaling, and positioning
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);
            command.add("-i");
            command.add(videoPath);

            // Trim the segment
            command.add("-ss");
            command.add(String.valueOf(segment.getStartTime()));
            if (segment.getEndTime() != -1) {
                command.add("-to");
                command.add(String.valueOf(segment.getEndTime()));
            }

            // Add the complex filter chain
            command.add("-filter_complex");
            command.add(filterComplex.toString());


            // Position the segment with scaling
            command.add("-filter_complex");
            command.add("[0:v]" + scaleFilter + ";" +
                    "color=black:size=" + canvasWidth + "x" + canvasHeight + "[bg];" +
                    "[bg][scaled]overlay=x=" + overlayX + ":y=" + overlayY);

            // Output the processed segment
            command.add("-c:v");
            command.add("libx264");
            command.add("-c:a");
            command.add("aac");
            command.add("-y"); // Overwrite if exists
            command.add(tempFile.getAbsolutePath());

            // Execute the command
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            // Log the process output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("FFmpeg process: " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("FFmpeg process failed with exit code: " + exitCode);
            }
        }

        // Create a temporary file for the FFmpeg concat script
        File concatFile = File.createTempFile("ffmpeg-concat-", ".txt");
        try (PrintWriter writer = new PrintWriter(new FileWriter(concatFile))) {
            for (File file : intermediateFiles) {
                writer.println("file '" + file.getAbsolutePath().replace("\\", "\\\\") + "'");
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
        command.add("-y"); // Overwrite if exists
        command.add(outputPath);

        // Execute concat command
        System.out.println("Executing FFmpeg concat command: " + String.join(" ", command));
        ProcessBuilder concatBuilder = new ProcessBuilder(command);
        concatBuilder.redirectErrorStream(true);
        Process concatProcess = concatBuilder.start();

        // Log the concat process output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(concatProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("FFmpeg concat: " + line);
            }
        }

        int exitCode = concatProcess.waitFor();
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

    // Helper method to get all filters for a specific segment
    private List<String> getFiltersForSegment(TimelineState timelineState, String segmentId) {
        return timelineState.getOperations().stream()
                .filter(op -> "FILTER".equals(op.getOperationType()) &&
                        segmentId.equals(op.getParameters().get("segmentId")))
                .map(op -> (String) op.getParameters().get("filter"))
                .collect(Collectors.toList());
    }

    // Enhanced method to apply filter with more options
    public void applyFilter(String sessionId, String videoPath, String segmentId, String filterType, Map<String, Object> filterParams) {
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
                // brightness value typically between -1.0 and 1.0
                double brightnessValue = Double.parseDouble(filterParams.getOrDefault("value", "0.0").toString());
                filterString = "eq=brightness=" + brightnessValue;
                break;

            case "contrast":
                // contrast value typically between -2.0 and 2.0
                double contrastValue = Double.parseDouble(filterParams.getOrDefault("value", "1.0").toString());
                filterString = "eq=contrast=" + contrastValue;
                break;

            case "saturation":
                // saturation value typically between 0.0 and 3.0
                double saturationValue = Double.parseDouble(filterParams.getOrDefault("value", "1.0").toString());
                filterString = "eq=saturation=" + saturationValue;
                break;

            case "blur":
                // sigma value typically between 1.0 and 20.0
                double sigmaValue = Double.parseDouble(filterParams.getOrDefault("sigma", "5.0").toString());
                filterString = "boxblur=" + sigmaValue + ":" + sigmaValue;
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
                // rotation angle in degrees
                int angle = Integer.parseInt(filterParams.getOrDefault("angle", "90").toString());
                filterString = "rotate=" + angle + "*PI/180";
                break;

            case "custom":
                // For advanced users who know FFmpeg filter syntax
                filterString = filterParams.getOrDefault("value", "").toString();
                break;

            default:
                throw new RuntimeException("Unsupported filter type: " + filterType);
        }

        // Create filter operation and associate it with the specific segment
        EditOperation filterOperation = new EditOperation();
        filterOperation.setOperationType("FILTER");
        filterOperation.setSourceVideoPath(videoPath);

        // Create a map with both the filter and the segmentId to link them
        Map<String, Object> params = new HashMap<>();
        params.put("filter", filterString);
        params.put("filterType", filterType);
        params.put("filterParams", filterParams);
        params.put("segmentId", segmentId);
        params.put("appliedAt", System.currentTimeMillis());
        params.put("filterId", UUID.randomUUID().toString());  // Generate unique ID for each filter

        filterOperation.setParameters(params);

        // Add to timeline state operations
        session.getTimelineState().getOperations().add(filterOperation);
        session.setLastAccessTime(System.currentTimeMillis());

        System.out.println("Applied filter: " + filterType + " (" + filterString + ") to video segment: " + segmentId);
    }

    // Method to get detailed filter information for UI display
    public List<Map<String, Object>> getFilterDetailsForSegment(String sessionId, String segmentId) {
        EditSession session = getSession(sessionId);

        return session.getTimelineState().getOperations().stream()
                .filter(op -> "FILTER".equals(op.getOperationType()) &&
                        segmentId.equals(op.getParameters().get("segmentId")))
                .map(op -> {
                    Map<String, Object> filterDetails = new HashMap<>();
                    filterDetails.put("filterId", op.getParameters().get("filterId"));
                    filterDetails.put("filterType", op.getParameters().get("filterType"));
                    filterDetails.put("filterString", op.getParameters().get("filter"));
                    filterDetails.put("appliedAt", op.getParameters().get("appliedAt"));

                    // Include original parameters if available
                    if (op.getParameters().containsKey("filterParams")) {
                        filterDetails.put("parameters", op.getParameters().get("filterParams"));
                    }

                    return filterDetails;
                })
                .collect(Collectors.toList());
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

    // Helper method to find a segment by its ID
    private VideoSegment findSegmentById(TimelineState timelineState, String segmentId) {
        return timelineState.getSegments().stream()
                .filter(segment -> segment.getId().equals(segmentId))
                .findFirst()
                .orElse(null);
    }

//    // Enhanced method to process a segment with all operations including filters
//    private String processSegment(VideoSegment segment, List<EditOperation> allOperations)
//            throws IOException, InterruptedException {
//
//        // Get the input path, ensuring it's properly formatted
//        String inputPath = segment.getSourceVideoPath();
//        if (!inputPath.contains("/")) {
//            inputPath = "videos/" + inputPath;
//        }
//
//        // Find all operations that apply to this segment
//        List<EditOperation> applicableOperations = allOperations.stream()
//                .filter(op -> isOperationApplicableToSegment(op, segment.getId()))
//                .collect(Collectors.toList());
//
//        // If no operations and no trimming needed, return the original path
//        if (applicableOperations.isEmpty() && segment.getStartTime() == 0 && segment.getEndTime() == -1) {
//            return inputPath;
//        }
//
//        // Create a temporary output path for this processed segment
//        String outputPath = "edited_videos/segment_" + UUID.randomUUID() + ".mp4";
//
//        // Extract filter operations
//        List<EditOperation> filterOperations = applicableOperations.stream()
//                .filter(op -> "FILTER".equals(op.getOperationType()))
//                .collect(Collectors.toList());
//
//        // Build the filter complex expression
//        StringBuilder filterComplex = new StringBuilder();
//        if (!filterOperations.isEmpty()) {
//            filterComplex.append("[0:v]");
//
//            // Add each filter in sequence
//            for (int i = 0; i < filterOperations.size(); i++) {
//                EditOperation op = filterOperations.get(i);
//                filterComplex.append((String) op.getParameters().get("filter"));
//
//                // Add comma if not the last filter
//                if (i < filterOperations.size() - 1) {
//                    filterComplex.append(",");
//                }
//            }
//
//            // Name the output
//            filterComplex.append("[filtered]");
//        }
//
//        // Build the FFmpeg command
//        List<String> command = new ArrayList<>();
//        command.add(ffmpegPath);
//        command.add("-i");
//        command.add(inputPath);
//
//        // Add segment start time
//        if (segment.getStartTime() > 0) {
//            command.add("-ss");
//            command.add(String.valueOf(segment.getStartTime()));
//        }
//
//        // Add segment duration if an end time is specified
//        if (segment.getEndTime() > 0) {
//            command.add("-t");
//            command.add(String.valueOf(segment.getEndTime() - segment.getStartTime()));
//        }
//
//        // Add filter complex if we have filters
//        if (filterComplex.length() > 0) {
//            command.add("-filter_complex");
//            command.add(filterComplex.toString());
//            command.add("-map");
//            command.add("[filtered]");
//            command.add("-map");
//            command.add("0:a?"); // Map audio if it exists
//        }
//
//        // Output options
//        command.add("-c:v");
//        command.add("libx264");  // Use H.264 video codec
//        command.add("-c:a");
//        command.add("aac");
//        command.add("-y"); // Overwrite if file exists
//        command.add(outputPath);
//
//        // Add quality settings
//        command.add("-preset");
//        command.add("medium");   // Balance between encoding speed and quality
//        command.add("-crf");
//        command.add("23");       // Constant Rate Factor - lower is better quality
//
//        // Log the final command for debugging
//        System.out.println("FFmpeg command: " + String.join(" ", command));
//
//        // Execute the command
//        executeFFmpegCommand(new ProcessBuilder(command));
//
//        return outputPath;
//    }



}