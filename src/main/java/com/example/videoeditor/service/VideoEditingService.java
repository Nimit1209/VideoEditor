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
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

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

    /**
     * Gets the timeline state for a given session ID
     * @param sessionId The session ID
     * @return The timeline state
     */
    public TimelineState getTimelineState(String sessionId) {
        EditSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new RuntimeException("Edit session not found: " + sessionId);
        }

        // Update last access time
        session.setLastAccessTime(System.currentTimeMillis());

        return session.getTimelineState();
    }

    /**
     * Saves the timeline state for a given session ID
     * @param sessionId The session ID
     * @param timelineState The timeline state to save
     */
    public void saveTimelineState(String sessionId, TimelineState timelineState) {
        EditSession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new RuntimeException("Edit session not found: " + sessionId);
        }

        // Update the timeline state
        session.setTimelineState(timelineState);
        session.setLastAccessTime(System.currentTimeMillis());
    }

//    public void persistTimelineState(String sessionId) throws JsonProcessingException {
//        EditSession session = activeSessions.get(sessionId);
//        if (session == null) {
//            throw new RuntimeException("Edit session not found: " + sessionId);
//        }
//
//        Project project = projectRepository.findById(session.getProjectId())
//                .orElseThrow(() -> new RuntimeException("Project not found"));
//
//        project.setTimelineState(objectMapper.writeValueAsString(session.getTimelineState()));
//        project.setLastModified(LocalDateTime.now());
//        projectRepository.save(project);
//    }

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



    public void addVideoToTimeline(String sessionId, String videoPath, Integer layer, Double timelineStartTime, Double timelineEndTime)
            throws IOException, InterruptedException {

        // Log the incoming request parameters
        System.out.println("addVideoToTimeline called with sessionId: " + sessionId);
        System.out.println("Video path: " + videoPath);
        System.out.println("Layer: " + layer);
        System.out.println("Timeline start time: " + timelineStartTime);
        System.out.println("Timeline end time: " + timelineEndTime);

        EditSession session = getSession(sessionId);

        if (session == null) {
            System.err.println("No active session found for sessionId: " + sessionId);
            throw new RuntimeException("No active session found for sessionId: " + sessionId);
        }

        System.out.println("Session found, project ID: " + session.getProjectId());

        try {

            double duration = getVideoDuration(videoPath);
            System.out.println("Actual video duration: " + duration);

            // If layer is not provided, default to 0
            layer = layer != null ? layer : 0;

            // If timelineStartTime is not provided, calculate it based on the last segment in the layer
            if (timelineStartTime == null) {
                double lastSegmentEndTime = 0.0;

                // Find the end time of the last segment in the same layer
                for (VideoSegment segment : session.getTimelineState().getSegments()) {
                    if (segment.getLayer() == layer && segment.getTimelineEndTime() > lastSegmentEndTime) {
                        lastSegmentEndTime = segment.getTimelineEndTime();
                    }
                }

                timelineStartTime = lastSegmentEndTime;
            }

            // If timelineEndTime is not provided, calculate it based on the video duration
            if (timelineEndTime == null) {
                timelineEndTime = timelineStartTime + duration;
            }

            // Validate timeline position
            if (!session.getTimelineState().isTimelinePositionAvailable(timelineStartTime, timelineEndTime, layer)) {
                throw new RuntimeException("Timeline position overlaps with an existing segment in layer " + layer);
            }

            // Create a video segment for the entire video
            VideoSegment segment = new VideoSegment();
            segment.setSourceVideoPath(videoPath);
            segment.setStartTime(0);
            segment.setEndTime(duration); // -1 indicates full duration

            segment.setPositionX(0);
            segment.setPositionY(0);
            segment.setScale(1.0);

            segment.setLayer(layer); // Set layer if provided, else default to 0
            segment.setTimelineStartTime(timelineStartTime); // Set timeline start time
            segment.setTimelineEndTime(timelineEndTime);     // Set timeline end time

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
                    "layer", layer,
                    "timelineStartTime", timelineStartTime,
                    "timelineEndTime", timelineEndTime
            ));

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

        // Find all unique timeline ranges where composition changes
        Set<Double> timePoints = new TreeSet<>();
        for (VideoSegment segment : timelineState.getSegments()) {
            timePoints.add(segment.getTimelineStartTime());
            timePoints.add(segment.getTimelineEndTime());
        }

        // Add image segments time points
        for (ImageSegment imgSegment : timelineState.getImageSegments()) {
            timePoints.add(imgSegment.getTimelineStartTime());
            timePoints.add(imgSegment.getTimelineEndTime());
        }

        // Add text segment time points if you have them
        for (TextSegment textSegment : timelineState.getTextSegments()) {
            timePoints.add(textSegment.getTimelineStartTime());
            timePoints.add(textSegment.getTimelineEndTime());
        }

        // Sort time points
        List<Double> sortedTimePoints = new ArrayList<>(timePoints);
        Collections.sort(sortedTimePoints);

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

            // Find all video segments visible in this time range
            List<VideoSegment> visibleSegments = new ArrayList<>();
            for (VideoSegment segment : timelineState.getSegments()) {
                if (segment.getTimelineStartTime() <= segmentEnd &&
                        segment.getTimelineEndTime() >= segmentStart) {
                    visibleSegments.add(segment);
                }
            }

            // Find all image segments visible in this time range
            List<ImageSegment> visibleImages = new ArrayList<>();
            for (ImageSegment imgSegment : timelineState.getImageSegments()) {
                if (imgSegment.getTimelineStartTime() <= segmentEnd &&
                        imgSegment.getTimelineEndTime() >= segmentStart) {
                    visibleImages.add(imgSegment);
                }
            }

            // Skip if no segments in this time range
            if (visibleSegments.isEmpty() && visibleImages.isEmpty()) {
                continue;
            }

            // Sort by layer (lower layers first)
            Collections.sort(visibleSegments, Comparator.comparingInt(VideoSegment::getLayer));
            Collections.sort(visibleImages, Comparator.comparingInt(ImageSegment::getLayer));

            // Create a temporary file for this time segment
            String tempFilename = "temp_" + UUID.randomUUID().toString() + ".mp4";
            File tempFile = new File(tempDir, tempFilename);
            intermediateFiles.add(tempFile);

            // Build FFmpeg command
            List<String> command = new ArrayList<>();
            command.add(ffmpegPath);

            // Add each video source as input
            for (VideoSegment segment : visibleSegments) {
                command.add("-i");
                command.add("videos/" + segment.getSourceVideoPath());
            }

            // Add each image as an input
            for (ImageSegment imgSegment : visibleImages) {
                command.add("-i");
                command.add("images/" + imgSegment.getImagePath());
            }

            // Create complex filter for compositing
            StringBuilder filterComplex = new StringBuilder();

            // Create background
            filterComplex.append("color=black:size=").append(canvasWidth).append("x").append(canvasHeight)
                    .append(":duration=").append(segmentEnd - segmentStart).append("[bg];");

            // Process each visible segment
            String lastOutput = "bg";
            for (int j = 0; j < visibleSegments.size(); j++) {
                VideoSegment segment = visibleSegments.get(j);

                // Calculate offset for trimming
                double relativeStartTime = segment.getStartTime() + (segmentStart - segment.getTimelineStartTime());
                double trimDuration = segmentEnd - segmentStart;

                // Get all filters for this segment
                List<String> segmentFilters = getFiltersForSegment(timelineState, segment.getId());

                // Create scale and trim filters
                filterComplex.append("[").append(j).append(":v]");
                filterComplex.append("trim=").append(relativeStartTime).append(":")
                        .append(relativeStartTime + trimDuration).append(",");
                filterComplex.append("setpts=PTS-STARTPTS,");

                // Apply segment-specific filters
                if (!segmentFilters.isEmpty()) {
                    for (String filter : segmentFilters) {
                        filterComplex.append(filter).append(",");
                    }
                }

                // Add scaling
                filterComplex.append("scale=iw*").append(segment.getScale()).append(":ih*").append(segment.getScale());
                filterComplex.append("[v").append(j).append("];");

                // Overlay this layer on top of previous layers
                String nextOutput = (j == visibleSegments.size() - 1 && visibleImages.isEmpty()) ? "vout" : "v" + (j + 10);

                String overlayX = "(W-w)/2+" + segment.getPositionX();
                String overlayY = "(H-h)/2+" + segment.getPositionY();

                filterComplex.append("[").append(lastOutput).append("][v").append(j).append("]");
                filterComplex.append("overlay=").append(overlayX).append(":").append(overlayY);

                if (j < visibleSegments.size() - 1 || !visibleImages.isEmpty()) {
                    filterComplex.append("[").append(nextOutput).append("];");
                } else {
                    filterComplex.append("[").append(nextOutput).append("];");
                }

                lastOutput = nextOutput;
            }

            // Process each visible image
            int imgInputIndex = visibleSegments.size(); // Start after video inputs
            for (int j = 0; j < visibleImages.size(); j++) {
                ImageSegment imgSegment = visibleImages.get(j);

                // Create an input for the image that lasts for the required duration
                filterComplex.append("[").append(imgInputIndex + j).append(":v]");

                // Generate and apply all filters for this image
                String imageFilters = generateImageFilters(imgSegment);
                filterComplex.append(imageFilters);

                filterComplex.append("[img").append(j).append("];");

                // Overlay this image on top of previous layers
                String nextOutput = (j == visibleImages.size() - 1) ? "vout" : "v" + (j + 20);

                String overlayX = "(W-w)/2+" + imgSegment.getPositionX();
                String overlayY = "(H-h)/2+" + imgSegment.getPositionY();

            // Calculate the relative start and end times within this segment
                double relativeStart = Math.max(0, imgSegment.getTimelineStartTime() - segmentStart);
                double relativeEnd = Math.min(segmentEnd - segmentStart, imgSegment.getTimelineEndTime() - segmentStart);

                filterComplex.append("[").append(lastOutput).append("][img").append(j).append("]");
                filterComplex.append("overlay=").append(overlayX).append(":")
                        .append(overlayY)
                        .append(":enable='between(t,").append(relativeStart).append(",").append(relativeEnd).append(")'");

                filterComplex.append("[").append(nextOutput).append("];");
                lastOutput = nextOutput;
            }


            // Ensure the final output is mapped to "vout"
            if (!lastOutput.equals("vout")) {
                filterComplex.append("[").append(lastOutput).append("]setpts=PTS-STARTPTS[vout];");
            }

            // Process each visible segment - AUDIO
            for (int j = 0; j < visibleSegments.size(); j++) {
                VideoSegment segment = visibleSegments.get(j);

                // Calculate offset for trimming
                double relativeStartTime = segment.getStartTime() + (segmentStart - segment.getTimelineStartTime());
                double trimDuration = segmentEnd - segmentStart;

                // Create trim filters for audio
                filterComplex.append("[").append(j).append(":a]");
                filterComplex.append("atrim=").append(relativeStartTime).append(":")
                        .append(relativeStartTime + trimDuration).append(",");
                filterComplex.append("asetpts=PTS-STARTPTS");
                filterComplex.append("[a").append(j).append("];");
            }

            // Mix all audio streams together
            if (visibleSegments.size() > 0) {
                // First check if all inputs have audio
                for (int j = 0; j < visibleSegments.size(); j++) {
                    filterComplex.append("[a").append(j).append("]");
                }

                // Add the amix filter with normalize=0 to preserve volume
                filterComplex.append("amix=inputs=").append(visibleSegments.size()).append(":duration=longest:dropout_transition=0:normalize=0[aout]");
            }

            // Add filter_complex to command
            command.add("-filter_complex");
            command.add(filterComplex.toString());

            // Map the video and audio outputs
            command.add("-map");
            command.add("[vout]");

            // Only map audio if there are audio streams
            if (visibleSegments.size() > 0) {
                command.add("-map");
                command.add("[aout]");
            }

            // Output settings
            command.add("-c:v");
            command.add("libx264");
            command.add("-c:a");
            command.add("aac");
            command.add("-shortest");
            command.add("-y");
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

            // Advanced color grading filters
            case "lut":
                // Apply a 3D LUT file for professional color grading
                String lutFile = filterParams.getOrDefault("file", "").toString();
                filterString = "lut3d=" + lutFile;
                break;

            case "colorbalance":
                // Professional color balance adjustments for shadows, midtones, and highlights
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
                break;

            case "curves":
                // Apply custom RGB curves for precise color adjustments
                String curvesMaster = filterParams.getOrDefault("master", "0/0 1/1").toString();
                String curvesRed = filterParams.getOrDefault("red", "0/0 1/1").toString();
                String curvesGreen = filterParams.getOrDefault("green", "0/0 1/1").toString();
                String curvesBlue = filterParams.getOrDefault("blue", "0/0 1/1").toString();

                filterString = "curves=master='" + curvesMaster + "':red='" + curvesRed +
                        "':green='" + curvesGreen + "':blue='" + curvesBlue + "'";
                break;

            case "vibrance":
                // Intelligent saturation that preserves skin tones
                double vibranceValue = Double.parseDouble(filterParams.getOrDefault("value", "0.5").toString());
                // Implement using a combination of HSL adjustment and masks
                filterString = "vibrance=" + vibranceValue;
                break;

            // Cinematic effects
            case "vignette":
                // Darkens the corners of the frame
                double vignetteAmount = Double.parseDouble(filterParams.getOrDefault("amount", "0.3").toString());
                filterString = "vignette=angle=PI/4:x0=0.5:y0=0.5:mode=quadratic:amount=" + vignetteAmount;
                break;

            case "filmgrain":
                // Adds realistic film grain
                double grainAmount = Double.parseDouble(filterParams.getOrDefault("amount", "0.1").toString());
                filterString = "noise=c0s=" + grainAmount + ":c1s=" + grainAmount + ":c2s=" + grainAmount + ":allf=t";
                break;

            case "cinematic":
                // Letterbox with anamorphic-style color grading
                String aspectRatio = filterParams.getOrDefault("aspectRatio", "2.39:1").toString();
                filterString = "drawbox=y=0:w=iw:h=iw/(DAR*" + aspectRatio.replace(":", "/") + "):t=fill:c=black," +
                        "drawbox=y=ih-iw/(DAR*" + aspectRatio.replace(":", "/") + "):w=iw:h=iw/(DAR*" + aspectRatio.replace(":", "/") + "):t=fill:c=black," +
                        "eq=contrast=1.1:saturation=0.85:brightness=0.05";
                break;

            // Light effects
            case "glow":
                // Adds a subtle glow to bright areas
                double glowIntensity = Double.parseDouble(filterParams.getOrDefault("intensity", "0.3").toString());
                double glowRadius = Double.parseDouble(filterParams.getOrDefault("radius", "3.0").toString());
                filterString = "glow=strength=" + glowIntensity + ":radius=" + glowRadius;
                break;

            case "lensdistortion":
                // Adds lens barrel or pincushion distortion
                double k1 = Double.parseDouble(filterParams.getOrDefault("k1", "0.1").toString());
                double k2 = Double.parseDouble(filterParams.getOrDefault("k2", "0.0").toString());
                filterString = "lenscorrection=k1=" + k1 + ":k2=" + k2;
                break;

            case "lensflare":
                // Simulates lens flare effect
                double intensity = Double.parseDouble(filterParams.getOrDefault("intensity", "0.5").toString());
                double posX = Double.parseDouble(filterParams.getOrDefault("posX", "0.5").toString());
                double posY = Double.parseDouble(filterParams.getOrDefault("posY", "0.3").toString());
                filterString = "lensflare=x=" + posX + ":y=" + posY + ":intensity=" + intensity;
                break;

            // Stylistic effects
            case "bleachbypass":
                // High-contrast, desaturated look popular in films
                filterString = "curves=master='0/0 0.25/0.15 0.5/0.35 0.75/0.8 1/1',eq=saturation=0.4:contrast=1.4";
                break;

            case "duotone":
                // Creates a two-tone color effect
                String highlight = filterParams.getOrDefault("highlight", "ffffff").toString();
                String shadow = filterParams.getOrDefault("shadow", "000000").toString();
                filterString = "lut=r='clipval*" + Integer.parseInt(highlight.substring(0, 2), 16) + "/255 + (1-clipval)*"
                        + Integer.parseInt(shadow.substring(0, 2), 16) + "/255':g='clipval*"
                        + Integer.parseInt(highlight.substring(2, 4), 16) + "/255 + (1-clipval)*"
                        + Integer.parseInt(shadow.substring(2, 4), 16) + "/255':b='clipval*"
                        + Integer.parseInt(highlight.substring(4, 6), 16) + "/255 + (1-clipval)*"
                        + Integer.parseInt(shadow.substring(4, 6), 16) + "/255'";
                break;

            case "tiltshift":
                // Simulates a tilt-shift miniature effect
                double blurAmount = Double.parseDouble(filterParams.getOrDefault("blur", "10").toString());
                double centerY = Double.parseDouble(filterParams.getOrDefault("centerY", "0.5").toString());
                double width = Double.parseDouble(filterParams.getOrDefault("width", "0.2").toString());
                filterString = "tiltshift=center_y=" + centerY + ":inner_radius=" + width + ":outer_radius=" + (width * 3) + ":angle=0:bluramount=" + blurAmount;
                break;

            // Utility filters
            case "stabilize":
                // Video stabilization
                double smoothing = Double.parseDouble(filterParams.getOrDefault("smoothing", "10").toString());
                filterString = "deshake=rx=64:ry=64:blocksize=16:smooth=" + smoothing;
                break;

            case "denoise":
                // Noise reduction
                double strength = Double.parseDouble(filterParams.getOrDefault("strength", "5").toString());
                filterString = "nlmeans=s=" + strength;
                break;

            case "sharpenmask":
                // Advanced sharpening with masking
                double amount = Double.parseDouble(filterParams.getOrDefault("amount", "3.0").toString());
                double radius = Double.parseDouble(filterParams.getOrDefault("radius", "1.0").toString());
                double threshold = Double.parseDouble(filterParams.getOrDefault("threshold", "0.05").toString());
                filterString = "unsharp=luma_msize_x=" + radius + ":luma_msize_y=" + radius +
                        ":luma_amount=" + amount + ":chroma_msize_x=" + radius +
                        ":chroma_msize_y=" + radius + ":chroma_amount=" + (amount * 0.5) +
                        ":alpha_msize_x=" + radius + ":alpha_msize_y=" + radius +
                        ":alpha_amount=" + (amount * 0.5) + ":luma_threshold=" + threshold;
                break;

            // NEW FILTERS - ADVANCED COLOR EFFECTS
            case "crossprocess":
                // Cross-processing effect popular in film photography
                filterString = "curves=r='0/0.05 0.5/0.5 1/0.95':g='0/0 0.5/0.4 1/0.95':b='0/0.1 0.5/0.5 1/0.9',eq=saturation=1.3:contrast=1.2";
                break;

            case "splittonemix":
                // Professional split toning for highlights and shadows
                String highlightColor = filterParams.getOrDefault("highlight", "ffeb3b").toString();
                String shadowColor = filterParams.getOrDefault("shadow", "3f51b5").toString();
                double balance = Double.parseDouble(filterParams.getOrDefault("balance", "0.5").toString());

                // Convert hex colors to RGB values for highlights
                int hR = Integer.parseInt(highlightColor.substring(0, 2), 16);
                int hG = Integer.parseInt(highlightColor.substring(2, 4), 16);
                int hB = Integer.parseInt(highlightColor.substring(4, 6), 16);

                // Convert hex colors to RGB values for shadows
                int sR = Integer.parseInt(shadowColor.substring(0, 2), 16);
                int sG = Integer.parseInt(shadowColor.substring(2, 4), 16);
                int sB = Integer.parseInt(shadowColor.substring(4, 6), 16);

                filterString = "tinterlace=mode=4,curves=r='0/(" + sR + "/255) " + balance + "/0.5 1/(" + hR + "/255)':" +
                        "g='0/(" + sG + "/255) " + balance + "/0.5 1/(" + hG + "/255)':" +
                        "b='0/(" + sB + "/255) " + balance + "/0.5 1/(" + hB + "/255)'";
                break;

            case "enhanceselective":
                // Selective color enhancement (similar to Lightroom's HSL panel)
                String colorRange = filterParams.getOrDefault("color", "red").toString();
                double hueShift = Double.parseDouble(filterParams.getOrDefault("hue", "0.0").toString());
                double satBoost = Double.parseDouble(filterParams.getOrDefault("saturation", "1.2").toString());
                double lumAdjust = Double.parseDouble(filterParams.getOrDefault("luminance", "0.0").toString());

                // Define color ranges in HSV
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

                // Create HSV-based color adjustment
                filterString = "hsvkey=h=" + range[0] + ":" + range[1] + ":s=0:1:v=0:1:soft=1," +
                        "hue=h_expr=" + hueShift + "*PI:s_expr=VAL*" + satBoost + ":v_expr=VAL+" + lumAdjust + "," +
                        "hsvhold";
                break;

            // NEW FILTERS - TRANSITIONS
            case "fadeintransition":
                // Fade in from black
                double duration = Double.parseDouble(filterParams.getOrDefault("duration", "1.0").toString());
                filterString = "fade=t=in:st=0:d=" + duration;
                break;

            case "fadeouttransition":
                // Fade out to black
                double outDuration = Double.parseDouble(filterParams.getOrDefault("duration", "1.0").toString());
                String position = filterParams.getOrDefault("position", "end").toString();
                if (position.equals("end")) {
                    filterString = "fade=t=out:d=" + outDuration;
                } else {
                    double startTime = Double.parseDouble(filterParams.getOrDefault("startTime", "0.0").toString());
                    filterString = "fade=t=out:st=" + startTime + ":d=" + outDuration;
                }
                break;

            case "whipdissolve":
                // Fast dissolve transition effect
                double wipeAngle = Double.parseDouble(filterParams.getOrDefault("angle", "45.0").toString());
                double wipeDuration = Double.parseDouble(filterParams.getOrDefault("duration", "0.5").toString());
                filterString = "wipeleft=0.5:duration=" + wipeDuration + ":angle=" + wipeAngle;
                break;

            // NEW FILTERS - CREATIVE DISTORTIONS
            case "pixelate":
                // Mosaic/pixelation effect
                int blockSize = Integer.parseInt(filterParams.getOrDefault("blockSize", "16").toString());
                filterString = "pixelize=w=" + blockSize + ":h=" + blockSize;
                break;

            case "watercolor":
                // Artistic watercolor effect
                double simplify = Double.parseDouble(filterParams.getOrDefault("simplify", "5.0").toString());
                double pastelize = Double.parseDouble(filterParams.getOrDefault("pastelize", "0.5").toString());
                filterString = "boxblur=luma_radius=" + simplify + ":luma_power=1:enable='between(t,0,999)'," +
                        "edgedetect=low=0.1:high=0.4:mode=colormix:enable='between(t,0,999)'," +
                        "eq=saturation=" + (1 + pastelize) + ":contrast=1.1:brightness=0.05:enable='between(t,0,999)'";
                break;

            case "dreamy":
                // Dreamy soft ethereal effect
                double dreamGlow = Double.parseDouble(filterParams.getOrDefault("glow", "0.3").toString());
                double dreamBlur = Double.parseDouble(filterParams.getOrDefault("blur", "3.0").toString());
                double dreamSaturation = Double.parseDouble(filterParams.getOrDefault("saturation", "1.1").toString());
                filterString = "gblur=sigma=" + dreamBlur + ":steps=3,split[a][b];" +
                        "[a]lutrgb=r=.95*val:g=.95*val:b=1.05*val,tonemap=reinhard," +
                        "eq=brightness=0.015:saturation=" + dreamSaturation + "[a1];" +
                        "[b]lutyuv=y=1.5*val:u=val:v=val[b1];" +
                        "[a1][b1]blend=all_mode=overlay:all_opacity=" + dreamGlow;
                break;

            // NEW FILTERS - BROADCAST STANDARD CORRECTIONS
            case "broadcast":
                // Broadcast-safe color correction
                filterString = "normalize=blackpt=16:whitept=235:strength=1," +
                        "vectorscope=m=color3:g=green:b=0.75:e=zebra";
                break;

            case "colorgradepreset":
                // Professional color grade presets
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

        // For ADD_IMAGE operations, check if the image is associated with the segment
        if ("ADD_IMAGE".equals(operationType)) {
            return segmentId.equals(operation.getParameters().get("segmentId"));
        }

        // For UPDATE_IMAGE operations, check if the image is associated with the segment
        if ("UPDATE_IMAGE".equals(operationType)) {
            return segmentId.equals(operation.getParameters().get("segmentId"));
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

        // Create a new image segment
        ImageSegment imageSegment = new ImageSegment();
        imageSegment.setId(UUID.randomUUID().toString()); // Generate unique ID for the image segment
        imageSegment.setImagePath(imagePath);
        imageSegment.setLayer(layer);
        imageSegment.setPositionX(positionX);
        imageSegment.setPositionY(positionY);
        imageSegment.setScale(scale);

        // Set timeline timing
        imageSegment.setTimelineStartTime(timelineStartTime);
        // If no end time provided, default to 5 seconds duration
        if (timelineEndTime == null) {
            imageSegment.setTimelineEndTime(timelineStartTime + 5.0);
        } else {
            imageSegment.setTimelineEndTime(timelineEndTime);
        }

        // Get image dimensions using ImageIO
        try {
            File imageFile = new File("images/" + imagePath);
            System.out.println("Attempting to read image file: " + imageFile.getAbsolutePath());
            if (!imageFile.exists()) {
                throw new RuntimeException("Image file does not exist: " + imageFile.getAbsolutePath());
            }
            BufferedImage img = ImageIO.read(imageFile);
            imageSegment.setWidth(img.getWidth());
            imageSegment.setHeight(img.getHeight());
        } catch (IOException e) {
            throw new RuntimeException("Error reading image file: " + e.getMessage());
        }

        // Set optional custom dimensions
        if (customWidth != null) {
            imageSegment.setCustomWidth(customWidth);
        }

        if (customHeight != null) {
            imageSegment.setCustomHeight(customHeight);
        }

        if (maintainAspectRatio != null) {
            imageSegment.setMaintainAspectRatio(maintainAspectRatio);
        }

        // Set filters if provided
        if (filters != null && !filters.isEmpty()) {
            imageSegment.setFilters(new HashMap<>(filters));
        }

        // Add the image segment to the timeline
        timelineState.getImageSegments().add(imageSegment);

        // Create an ADD operation for tracking
        EditOperation addOperation = new EditOperation();
        addOperation.setOperationType("ADD_IMAGE");
        addOperation.setSourceVideoPath(imagePath);

        // Create a parameters map with all properties
        Map<String, Object> params = new HashMap<>();
        params.put("imageSegmentId", imageSegment.getId());
        params.put("time", System.currentTimeMillis());
        params.put("layer", layer);
        params.put("timelineStartTime", timelineStartTime);
        params.put("timelineEndTime", imageSegment.getTimelineEndTime());
        params.put("positionX", positionX);
        params.put("positionY", positionY);
        params.put("scale", scale);

        if (customWidth != null) params.put("customWidth", customWidth);
        if (customHeight != null) params.put("customHeight", customHeight);
        if (maintainAspectRatio != null) params.put("maintainAspectRatio", maintainAspectRatio);
        if (filters != null && !filters.isEmpty()) params.put("filters", filters);

        addOperation.setParameters(params);

        timelineState.getOperations().add(addOperation);

        // Save the updated timeline state
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

        // Find the image segment by ID
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

        // Update the segment properties if provided
        if (positionX != null) targetSegment.setPositionX(positionX);
        if (positionY != null) targetSegment.setPositionY(positionY);
        if (scale != null) targetSegment.setScale(scale);
        if (opacity != null) targetSegment.setOpacity(opacity);
        if (layer != null) targetSegment.setLayer(layer);

        // Update custom size properties
        if (customWidth != null) targetSegment.setCustomWidth(customWidth);
        if (customHeight != null) targetSegment.setCustomHeight(customHeight);
        if (maintainAspectRatio != null) targetSegment.setMaintainAspectRatio(maintainAspectRatio);

        // Update filters
        if (filters != null && !filters.isEmpty()) {
            for (Map.Entry<String, String> filter : filters.entrySet()) {
                targetSegment.addFilter(filter.getKey(), filter.getValue());
            }
        }

        // Remove specified filters
        if (filtersToRemove != null && !filtersToRemove.isEmpty()) {
            for (String filterToRemove : filtersToRemove) {
                targetSegment.removeFilter(filterToRemove);
            }
        }

        // Create an UPDATE operation for tracking
        EditOperation updateOperation = new EditOperation();
        updateOperation.setOperationType("UPDATE_IMAGE");
        updateOperation.setSourceVideoPath(targetSegment.getImagePath());

        // Build parameters map
        Map<String, Object> params = new HashMap<>();
        params.put("imageSegmentId", imageSegmentId);
        params.put("time", System.currentTimeMillis());

        if (positionX != null) params.put("positionX", positionX);
        if (positionY != null) params.put("positionY", positionY);
        if (scale != null) params.put("scale", scale);
        if (opacity != null) params.put("opacity", opacity);
        if (layer != null) params.put("layer", layer);
        if (customWidth != null) params.put("customWidth", customWidth);
        if (customHeight != null) params.put("customHeight", customHeight);
        if (maintainAspectRatio != null) params.put("maintainAspectRatio", maintainAspectRatio);
        if (filters != null) params.put("filtersAdded", filters);
        if (filtersToRemove != null) params.put("filtersRemoved", filtersToRemove);

        updateOperation.setParameters(params);
        timelineState.getOperations().add(updateOperation);

        // Save the updated timeline state
        saveTimelineState(sessionId, timelineState);
    }
}