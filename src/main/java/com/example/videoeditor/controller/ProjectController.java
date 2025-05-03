package com.example.videoeditor.controller;

import com.example.videoeditor.dto.*;
import com.example.videoeditor.entity.Project;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.ProjectRepository;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.example.videoeditor.service.VideoEditingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/projects")
public class ProjectController {
    private final VideoEditingService videoEditingService;
    private final ProjectRepository projectRepository;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public ProjectController(
            VideoEditingService videoEditingService,
            ProjectRepository projectRepository,
            JwtUtil jwtUtil,
            UserRepository userRepository) {
        this.videoEditingService = videoEditingService;
        this.projectRepository = projectRepository;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    private User getUserFromToken(String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping
    public ResponseEntity<Project> createProject(
            @RequestHeader("Authorization") String token,
            @RequestBody Map<String, Object> request) throws JsonProcessingException {
        User user = getUserFromToken(token);

        String name = (String) request.get("name");
        Integer width = request.get("width") != null ?
                ((Number) request.get("width")).intValue() : 1920;
        Integer height = request.get("height") != null ?
                ((Number) request.get("height")).intValue() : 1080;

        Float fps = request.get("fps") != null ?
                ((Number) request.get("fps")).floatValue() : null;

        Project project = videoEditingService.createProject(user, name, width, height, fps);
        return ResponseEntity.ok(project);
    }

    @GetMapping
    public ResponseEntity<List<Project>> getUserProjects(
            @RequestHeader("Authorization") String token) {
        User user = getUserFromToken(token);
        List<Project> projects = projectRepository.findByUserOrderByLastModifiedDesc(user);
        return ResponseEntity.ok(projects);
    }

    @PostMapping("/{projectId}/session")
    public ResponseEntity<String> startEditingSession(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId) throws JsonProcessingException {
        User user = getUserFromToken(token);
        String sessionId = videoEditingService.startEditingSession(user, projectId);
        return ResponseEntity.ok(sessionId);
    }

    @PostMapping("/{projectId}/save")
    public ResponseEntity<?> saveProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId) throws JsonProcessingException {
        videoEditingService.saveProject(sessionId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{projectId}/saveForUndoRedo")
    public ResponseEntity<?> saveForUndoRedo(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> payload) throws JsonProcessingException {
        // Validate token and user
        User user = getUserFromToken(token);

        // Extract timeline_state from payload
        ObjectMapper mapper = new ObjectMapper();
        String timelineStateJson = mapper.writeValueAsString(payload.get("timelineState"));

        // Save project with updated timeline_state for undo/redo
        videoEditingService.saveForUndoRedo(projectId, sessionId, timelineStateJson);

        return ResponseEntity.ok().build();
    }

    @PostMapping("/{projectId}/export")
    public ResponseEntity<String> exportProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId) throws IOException, InterruptedException {
        // Export the project using the existing session ID
        String exportedVideoPath = String.valueOf(videoEditingService.exportProject(sessionId));

        // Create a File object to match the return type of your original method
        File exportedVideo = new File(exportedVideoPath);

        // Return just the filename as in your original implementation
        return ResponseEntity.ok(exportedVideo.getName());
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<Project> getProjectDetails(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId
    ) {
        User user = getUserFromToken(token);
        Project project = projectRepository.findByIdAndUser(projectId, user);

        return ResponseEntity.ok(project);
    }

    @PostMapping("/{projectId}/add-to-timeline")
    public ResponseEntity<?> addVideoToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Extract parameters from the request
            String videoPath = (String) request.get("videoPath");
            Integer layer = request.get("layer") != null ? ((Number) request.get("layer")).intValue() : null;
            Double timelineStartTime = request.get("timelineStartTime") != null ? ((Number) request.get("timelineStartTime")).doubleValue() : null;
            Double timelineEndTime = request.get("timelineEndTime") != null ? ((Number) request.get("timelineEndTime")).doubleValue() : null;
            Double startTime = request.get("startTime") != null ? ((Number) request.get("startTime")).doubleValue() : null;
            Double endTime = request.get("endTime") != null ? ((Number) request.get("endTime")).doubleValue() : null;
            Double opacity = request.get("opacity") != null ? ((Number) request.get("opacity")).doubleValue() : null;
            Boolean createAudioSegment = request.get("createAudioSegment") != null ?
                    Boolean.valueOf(request.get("createAudioSegment").toString()) :
                    (request.get("skipAudio") != null ? !Boolean.valueOf(request.get("skipAudio").toString()) : true);

            // Validate required parameters
            if (videoPath == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: videoPath");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }

            // Call the service method with updated parameters
            videoEditingService.addVideoToTimeline(
                    sessionId,
                    videoPath,
                    layer,
                    timelineStartTime,
                    timelineEndTime,
                    startTime,
                    endTime,
                    createAudioSegment
            );

            // Retrieve the newly added video and audio segments
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            VideoSegment addedVideoSegment = timelineState.getSegments().stream()
                    .filter(s -> s.getSourceVideoPath().equals(videoPath) &&
                            (startTime == null || Math.abs(s.getStartTime() - startTime) < 0.001) &&
                            (timelineStartTime == null || Math.abs(s.getTimelineStartTime() - timelineStartTime) < 0.001))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find added video segment"));
            AudioSegment addedAudioSegment = timelineState.getAudioSegments().stream()
                    .filter(a -> a.getId().equals(addedVideoSegment.getAudioId()))
                    .findFirst()
                    .orElse(null);

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("videoSegmentId", addedVideoSegment.getId());
            response.put("layer", addedVideoSegment.getLayer());
            if (addedAudioSegment != null) {
                response.put("audioSegmentId", addedAudioSegment.getId());
                response.put("audioLayer", addedAudioSegment.getLayer());
                response.put("audioPath", addedAudioSegment.getAudioPath());
                response.put("waveformPath", addedAudioSegment.getWaveformPath());
                response.put("audioStartTime", addedAudioSegment.getStartTime());
                response.put("audioEndTime", addedAudioSegment.getEndTime());
                response.put("audioTimelineStartTime", addedAudioSegment.getTimelineStartTime());
                response.put("audioTimelineEndTime", addedAudioSegment.getTimelineEndTime());
                response.put("audioVolume", addedAudioSegment.getVolume());
                response.put("audioKeyframes", addedAudioSegment.getKeyframes() != null ? addedAudioSegment.getKeyframes() : new HashMap<>());
            }

            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding video to timeline: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding video to timeline: " + e.getMessage());
        }
    }

    @PutMapping("/{projectId}/update-segment")
    public ResponseEntity<?> updateVideoSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String segmentId = (String) request.get("segmentId");
            Integer positionX = request.containsKey("positionX") ? Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.containsKey("positionY") ? Integer.valueOf(request.get("positionY").toString()) : null;
            Double scale = request.containsKey("scale") ? Double.valueOf(request.get("scale").toString()) : null;
            Double opacity = request.containsKey("opacity") ? Double.valueOf(request.get("opacity").toString()) : null;
            Double timelineStartTime = request.containsKey("timelineStartTime") ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.containsKey("timelineEndTime") ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            Double startTime = request.containsKey("startTime") ? Double.valueOf(request.get("startTime").toString()) : null;
            Double endTime = request.containsKey("endTime") ? Double.valueOf(request.get("endTime").toString()) : null;
            Double cropL = request.containsKey("cropL") ? Double.valueOf(request.get("cropL").toString()) : null;
            Double cropR = request.containsKey("cropR") ? Double.valueOf(request.get("cropR").toString()) : null;
            Double cropT = request.containsKey("cropT") ? Double.valueOf(request.get("cropT").toString()) : null;
            Double cropB = request.containsKey("cropB") ? Double.valueOf(request.get("cropB").toString()) : null;
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> keyframes = request.containsKey("keyframes") ? (Map<String, List<Map<String, Object>>>) request.get("keyframes") : null;

            Map<String, List<Keyframe>> parsedKeyframes = null;
            if (keyframes != null) {
                parsedKeyframes = new HashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : keyframes.entrySet()) {
                    List<Keyframe> kfList = new ArrayList<>();
                    for (Map<String, Object> kfData : entry.getValue()) {
                        double time = Double.valueOf(kfData.get("time").toString());
                        Object value = kfData.get("value");
                        String interpolation = (String) kfData.getOrDefault("interpolationType", "linear");
                        kfList.add(new Keyframe(time, value, interpolation));
                    }
                    parsedKeyframes.put(entry.getKey(), kfList);
                }
            }

            // Validation
            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (cropL != null && (cropL < 0 || cropL > 100)) {
                return ResponseEntity.badRequest().body("cropL must be between 0 and 100");
            }
            if (cropR != null && (cropR < 0 || cropR > 100)) {
                return ResponseEntity.badRequest().body("cropR must be between 0 and 100");
            }
            if (cropT != null && (cropT < 0 || cropT > 100)) {
                return ResponseEntity.badRequest().body("cropT must be between 0 and 100");
            }
            if (cropB != null && (cropB < 0 || cropB > 100)) {
                return ResponseEntity.badRequest().body("cropB must be between 0 and 100");
            }
            // Validate total crop if static values are provided (not keyframed)
            if (parsedKeyframes == null ||
                    (!parsedKeyframes.containsKey("cropL") && !parsedKeyframes.containsKey("cropR") &&
                            !parsedKeyframes.containsKey("cropT") && !parsedKeyframes.containsKey("cropB"))) {
                double totalHorizontalCrop = (cropL != null ? cropL : 0.0) + (cropR != null ? cropR : 0.0);
                double totalVerticalCrop = (cropT != null ? cropT : 0.0) + (cropB != null ? cropB : 0.0);
                if (totalHorizontalCrop >= 100) {
                    return ResponseEntity.badRequest().body("Total horizontal crop (cropL + cropR) cannot be 100% or more");
                }
                if (totalVerticalCrop >= 100) {
                    return ResponseEntity.badRequest().body("Total vertical crop (cropT + cropB) cannot be 100% or more");
                }
            }

            videoEditingService.updateVideoSegment(
                    sessionId, segmentId, positionX, positionY, scale, opacity, timelineStartTime, layer,
                    timelineEndTime, startTime, endTime, cropL, cropR, cropT, cropB, parsedKeyframes);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating video segment: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/get-segment")
    public ResponseEntity<?> getVideoSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId) {
        try {
            User user = getUserFromToken(token);

            VideoSegment segment = videoEditingService.getVideoSegment(sessionId, segmentId);
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            AudioSegment audioSegment = segment.getAudioId() != null ?
                    timelineState.getAudioSegments().stream()
                            .filter(a -> a.getId().equals(segment.getAudioId()))
                            .findFirst()
                            .orElse(null) : null;

            Map<String, Object> response = new HashMap<>();
            response.put("videoSegment", segment);
            if (audioSegment != null) {
                response.put("audioSegment", audioSegment);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error getting video segment: " + e.getMessage());
        }
    }

    @PostMapping("/{projectId}/add-text")
    public ResponseEntity<?> addTextToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Existing parameters
            String text = (String) request.get("text");
            Integer layer = request.get("layer") != null ? Integer.valueOf(request.get("layer").toString()) : null;
            Double timelineStartTime = request.get("timelineStartTime") != null ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.get("timelineEndTime") != null ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            String fontFamily = (String) request.get("fontFamily");
            Double scale = request.get("scale") != null ? Double.valueOf(request.get("scale").toString()) : null;
            String fontColor = (String) request.get("fontColor");
            String backgroundColor = (String) request.get("backgroundColor");
            Integer positionX = request.get("positionX") != null ? Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.get("positionY") != null ? Integer.valueOf(request.get("positionY").toString()) : null;
            Double opacity = request.get("opacity") != null ? Double.valueOf(request.get("opacity").toString()) : null;
            String alignment = (String) request.get("alignment");

            // New background parameters
            Double backgroundOpacity = request.get("backgroundOpacity") != null ? Double.valueOf(request.get("backgroundOpacity").toString()) : null;
            Integer backgroundBorderWidth = request.get("backgroundBorderWidth") != null ? Integer.valueOf(request.get("backgroundBorderWidth").toString()) : null;
            String backgroundBorderColor = (String) request.get("backgroundBorderColor");
            Integer backgroundH = request.get("backgroundH") != null ? Integer.valueOf(request.get("backgroundH").toString()) : null;
            Integer backgroundW = request.get("backgroundW") != null ? Integer.valueOf(request.get("backgroundW").toString()) : null;
            Integer backgroundBorderRadius = request.get("backgroundBorderRadius") != null ? Integer.valueOf(request.get("backgroundBorderRadius").toString()) : null;

            // New text border parameters
            String textBorderColor = (String) request.get("textBorderColor");
            Integer textBorderWidth = request.get("textBorderWidth") != null ? Integer.valueOf(request.get("textBorderWidth").toString()) : null;
            Double textBorderOpacity = request.get("textBorderOpacity") != null ? Double.valueOf(request.get("textBorderOpacity").toString()) : null;

            // Existing validation
            if (text == null || layer == null || timelineStartTime == null || timelineEndTime == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: text, layer, timelineStartTime, timelineEndTime");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (alignment != null && !Arrays.asList("left", "right", "center").contains(alignment)) {
                return ResponseEntity.badRequest().body("Alignment must be 'left', 'right', or 'center'");
            }

            // New background validation
            if (backgroundOpacity != null && (backgroundOpacity < 0 || backgroundOpacity > 1)) {
                return ResponseEntity.badRequest().body("Background opacity must be between 0 and 1");
            }
            if (backgroundBorderWidth != null && backgroundBorderWidth < 0) {
                return ResponseEntity.badRequest().body("Background border width must be non-negative");
            }
            if (backgroundH != null && backgroundH < 0) {
                return ResponseEntity.badRequest().body("Background height must be non-negative");
            }
            if (backgroundW != null && backgroundW < 0) {
                return ResponseEntity.badRequest().body("Background width must be non-negative");
            }
            if (backgroundBorderRadius != null && backgroundBorderRadius < 0) {
                return ResponseEntity.badRequest().body("Background border radius must be non-negative");
            }

            // New text border validation
            if (textBorderWidth != null && textBorderWidth < 0) {
                return ResponseEntity.badRequest().body("Text border width must be non-negative");
            }
            if (textBorderOpacity != null && (textBorderOpacity < 0 || textBorderOpacity > 1)) {
                return ResponseEntity.badRequest().body("Text border opacity must be between 0 and 1");
            }

            // Add text to timeline
            videoEditingService.addTextToTimeline(
                    sessionId, text, layer, timelineStartTime, timelineEndTime,
                    fontFamily, scale, fontColor, backgroundColor, positionX, positionY, opacity, alignment,
                    backgroundOpacity, backgroundBorderWidth, backgroundBorderColor, backgroundH,
                    backgroundW, backgroundBorderRadius,
                    textBorderColor, textBorderWidth, textBorderOpacity);

            // Retrieve the newly added text segment from TimelineState
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            TextSegment addedTextSegment = timelineState.getTextSegments().stream()
                    .filter(t -> t.getText().equals(text) &&
                            Math.abs(t.getTimelineStartTime() - timelineStartTime) < 0.001 &&
                            Math.abs(t.getTimelineEndTime() - timelineEndTime) < 0.001 &&
                            t.getLayer() == layer)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find added text segment"));

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("textSegmentId", addedTextSegment.getId());
            response.put("text", addedTextSegment.getText());
            response.put("layer", addedTextSegment.getLayer());
            response.put("timelineStartTime", addedTextSegment.getTimelineStartTime());
            response.put("timelineEndTime", addedTextSegment.getTimelineEndTime());
            response.put("fontFamily", addedTextSegment.getFontFamily());
            response.put("scale", addedTextSegment.getScale());
            response.put("fontColor", addedTextSegment.getFontColor());
            response.put("backgroundColor", addedTextSegment.getBackgroundColor());
            response.put("positionX", addedTextSegment.getPositionX());
            response.put("positionY", addedTextSegment.getPositionY());
            response.put("opacity", addedTextSegment.getOpacity());
            response.put("alignment", addedTextSegment.getAlignment());
            response.put("backgroundOpacity", addedTextSegment.getBackgroundOpacity());
            response.put("backgroundBorderWidth", addedTextSegment.getBackgroundBorderWidth());
            response.put("backgroundBorderColor", addedTextSegment.getBackgroundBorderColor());
            response.put("backgroundH", addedTextSegment.getBackgroundH());
            response.put("backgroundW", addedTextSegment.getBackgroundW());
            response.put("backgroundBorderRadius", addedTextSegment.getBackgroundBorderRadius());
            response.put("textBorderColor", addedTextSegment.getTextBorderColor());
            response.put("textBorderWidth", addedTextSegment.getTextBorderWidth());
            response.put("textBorderOpacity", addedTextSegment.getTextBorderOpacity());
            response.put("keyframes", addedTextSegment.getKeyframes() != null ? addedTextSegment.getKeyframes() : new HashMap<>());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding text to timeline: " + e.getMessage());
        }
    }

    @PutMapping("/{projectId}/update-text")
    public ResponseEntity<?> updateTextSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Existing parameters
            String segmentId = (String) request.get("segmentId");
            String text = (String) request.get("text");
            String fontFamily = (String) request.get("fontFamily");
            Double scale = request.containsKey("scale") ? Double.valueOf(request.get("scale").toString()) : null;
            String fontColor = (String) request.get("fontColor");
            String backgroundColor = (String) request.get("backgroundColor");
            Integer positionX = request.containsKey("positionX") ? Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.containsKey("positionY") ? Integer.valueOf(request.get("positionY").toString()) : null;
            Double opacity = request.containsKey("opacity") ? Double.valueOf(request.get("opacity").toString()) : null;
            Double timelineStartTime = request.containsKey("timelineStartTime") ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.containsKey("timelineEndTime") ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            String alignment = (String) request.get("alignment");
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> keyframes = request.containsKey("keyframes") ? (Map<String, List<Map<String, Object>>>) request.get("keyframes") : null;

            // New background parameters
            Double backgroundOpacity = request.containsKey("backgroundOpacity") ? Double.valueOf(request.get("backgroundOpacity").toString()) : null;
            Integer backgroundBorderWidth = request.containsKey("backgroundBorderWidth") ? Integer.valueOf(request.get("backgroundBorderWidth").toString()) : null;
            String backgroundBorderColor = (String) request.get("backgroundBorderColor");
            Integer backgroundH = request.containsKey("backgroundH") ? Integer.valueOf(request.get("backgroundH").toString()) : null;
            Integer backgroundW = request.containsKey("backgroundW") ? Integer.valueOf(request.get("backgroundW").toString()) : null;
            Integer backgroundBorderRadius = request.containsKey("backgroundBorderRadius") ? Integer.valueOf(request.get("backgroundBorderRadius").toString()) : null;

            // New text border parameters
            String textBorderColor = (String) request.get("textBorderColor");
            Integer textBorderWidth = request.containsKey("textBorderWidth") ? Integer.valueOf(request.get("textBorderWidth").toString()) : null;
            Double textBorderOpacity = request.containsKey("textBorderOpacity") ? Double.valueOf(request.get("textBorderOpacity").toString()) : null;

            // Parse keyframes
            Map<String, List<Keyframe>> parsedKeyframes = null;
            if (keyframes != null) {
                parsedKeyframes = new HashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : keyframes.entrySet()) {
                    String property = entry.getKey();
                    List<Keyframe> kfList = new ArrayList<>();
                    for (Map<String, Object> kfData : entry.getValue()) {
                        double time = Double.valueOf(kfData.get("time").toString());
                        Object value = kfData.get("value");
                        String interpolation = (String) kfData.getOrDefault("interpolationType", "linear");
                        kfList.add(new Keyframe(time, value, interpolation));
                    }
                    parsedKeyframes.put(entry.getKey(), kfList);
                }
            }

            // Existing validation
            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }
            if (text == null || text.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Text content cannot be null or empty");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (alignment != null && !Arrays.asList("left", "right", "center").contains(alignment)) {
                return ResponseEntity.badRequest().body("Alignment must be 'left', 'right', or 'center'");
            }

            // New background validation
            if (backgroundOpacity != null && (backgroundOpacity < 0 || backgroundOpacity > 1)) {
                return ResponseEntity.badRequest().body("Background opacity must be between 0 and 1");
            }
            if (backgroundBorderWidth != null && backgroundBorderWidth < 0) {
                return ResponseEntity.badRequest().body("Background border width must be non-negative");
            }
            if (backgroundH != null && backgroundH < 0) {
                return ResponseEntity.badRequest().body("Background height must be non-negative");
            }
            if (backgroundW != null && backgroundW < 0) {
                return ResponseEntity.badRequest().body("Background width must be non-negative");
            }
            if (backgroundBorderRadius != null && backgroundBorderRadius < 0) {
                return ResponseEntity.badRequest().body("Background border radius must be non-negative");
            }

            // New text border validation
            if (textBorderWidth != null && textBorderWidth < 0) {
                return ResponseEntity.badRequest().body("Text border width must be non-negative");
            }
            if (textBorderOpacity != null && (textBorderOpacity < 0 || textBorderOpacity > 1)) {
                return ResponseEntity.badRequest().body("Text border opacity must be between 0 and 1");
            }

            videoEditingService.updateTextSegment(
                    sessionId, segmentId, text, fontFamily, scale,
                    fontColor, backgroundColor, positionX, positionY, opacity, timelineStartTime, timelineEndTime, layer, alignment,
                    backgroundOpacity, backgroundBorderWidth, backgroundBorderColor, backgroundH,
                    backgroundW, backgroundBorderRadius,
                    textBorderColor, textBorderWidth, textBorderOpacity,
                    parsedKeyframes);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating text segment: " + e.getMessage());
        }
    }

    //    AUDIO FUNCTIONALITY .......................................................................................

    @PostMapping("/{projectId}/upload-audio")
    public ResponseEntity<?> uploadAudio(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam("audio") MultipartFile[] audioFiles,
            @RequestParam(value = "audioFileNames", required = false) String[] audioFileNames
    ) {
        try {
            User user = getUserFromToken(token);
            Project updatedProject = videoEditingService.uploadAudioToProject(user, projectId, audioFiles, audioFileNames);

            // Extract audio metadata with waveform paths from audioJson
            List<Map<String, String>> audioFilesMetadata = videoEditingService.getAudio(updatedProject);
            List<Map<String, String>> responseAudioFiles = audioFilesMetadata.stream()
                    .map(audio -> {
                        Map<String, String> audioData = new HashMap<>();
                        audioData.put("audioFileName", audio.get("audioFileName"));
                        audioData.put("audioPath", audio.get("audioPath"));
                        audioData.put("waveformPath", audio.get("waveformPath"));
                        return audioData;
                    })
                    .collect(Collectors.toList());

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("project", updatedProject);
            response.put("audioFiles", responseAudioFiles);

            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error uploading audio: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        }
    }

    @PostMapping("/{projectId}/add-project-audio-to-timeline")
    public ResponseEntity<?> addProjectAudioToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);
            Integer layer = request.get("layer") != null ? ((Number) request.get("layer")).intValue() : -1;
            Double startTime = request.get("startTime") != null ? ((Number) request.get("startTime")).doubleValue() : 0.0;
            Double endTime = request.get("endTime") != null ? ((Number) request.get("endTime")).doubleValue() : null;
            Double timelineStartTime = request.get("timelineStartTime") != null ?
                    ((Number) request.get("timelineStartTime")).doubleValue() : 0.0;
            Double timelineEndTime = request.get("timelineEndTime") != null ?
                    ((Number) request.get("timelineEndTime")).doubleValue() : null;
            String audioFileName = (String) request.get("audioFileName");
            Double volume = request.get("volume") != null ? ((Number) request.get("volume")).doubleValue() : 1.0;

            // Validate parameters
            if (layer >= 0) {
                return ResponseEntity.badRequest().body("Audio layer must be negative");
            }
            if (startTime < 0) {
                return ResponseEntity.badRequest().body("Start time must be non-negative");
            }
            if (timelineStartTime < 0) {
                return ResponseEntity.badRequest().body("Timeline start time must be non-negative");
            }
            if (audioFileName == null || audioFileName.isEmpty()) {
                return ResponseEntity.badRequest().body("Audio filename is required");
            }
            if (volume < 0 || volume > 1) {
                return ResponseEntity.badRequest().body("Volume must be between 0 and 1");
            }

            // Add audio to timeline
            videoEditingService.addAudioToTimelineFromProject(
                    user, sessionId, projectId, layer, startTime, endTime, timelineStartTime, timelineEndTime, audioFileName);

            // Retrieve the newly added audio segment from TimelineState
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            AudioSegment addedAudioSegment = timelineState.getAudioSegments().stream()
                    .filter(a -> a.getAudioPath().endsWith(audioFileName) &&
                            Math.abs(a.getTimelineStartTime() - timelineStartTime) < 0.001 &&
                            (endTime == null || Math.abs(a.getEndTime() - endTime) < 0.001))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find added audio segment"));

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("audioSegmentId", addedAudioSegment.getId());
            response.put("layer", addedAudioSegment.getLayer());
            response.put("timelineStartTime", addedAudioSegment.getTimelineStartTime());
            response.put("timelineEndTime", addedAudioSegment.getTimelineEndTime());
            response.put("startTime", addedAudioSegment.getStartTime());
            response.put("endTime", addedAudioSegment.getEndTime());
            response.put("volume", addedAudioSegment.getVolume());
            response.put("audioPath", addedAudioSegment.getAudioPath());
            response.put("waveformPath", addedAudioSegment.getWaveformPath());
            response.put("keyframes", addedAudioSegment.getKeyframes() != null ? addedAudioSegment.getKeyframes() : new HashMap<>());

            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding audio to timeline: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding audio to timeline: " + e.getMessage());
        }
    }

    @PutMapping("/{projectId}/update-audio")
    public ResponseEntity<?> updateAudioSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String audioSegmentId = (String) request.get("audioSegmentId");
            Double startTime = request.containsKey("startTime") ? Double.valueOf(request.get("startTime").toString()) : null;
            Double endTime = request.containsKey("endTime") ? Double.valueOf(request.get("endTime").toString()) : null;
            Double timelineStartTime = request.containsKey("timelineStartTime") ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.containsKey("timelineEndTime") ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            Double volume = request.containsKey("volume") ? Double.valueOf(request.get("volume").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> keyframes = request.containsKey("keyframes") ? (Map<String, List<Map<String, Object>>>) request.get("keyframes") : null;

            Map<String, List<Keyframe>> parsedKeyframes = null;
            if (keyframes != null) {
                parsedKeyframes = new HashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : keyframes.entrySet()) {
                    List<Keyframe> kfList = new ArrayList<>();
                    for (Map<String, Object> kfData : entry.getValue()) {
                        double time = Double.valueOf(kfData.get("time").toString());
                        Object value = kfData.get("value");
                        String interpolation = (String) kfData.getOrDefault("interpolationType", "linear");
                        kfList.add(new Keyframe(time, value, interpolation));
                    }
                    parsedKeyframes.put(entry.getKey(), kfList);
                }
            }

            if (audioSegmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: audioSegmentId");
            }
            if (startTime != null && startTime < 0) {
                return ResponseEntity.badRequest().body("Start time must be non-negative");
            }
            if (timelineStartTime != null && timelineStartTime < 0) {
                return ResponseEntity.badRequest().body("Timeline start time must be non-negative");
            }
            if (volume != null && (volume < 0 || volume > 1)) {
                return ResponseEntity.badRequest().body("Volume must be between 0 and 1");
            }
            if (layer != null && layer >= 0) {
                return ResponseEntity.badRequest().body("Audio layer must be negative");
            }

            videoEditingService.updateAudioSegment(
                    sessionId, audioSegmentId, startTime, endTime, timelineStartTime, timelineEndTime, volume, layer, parsedKeyframes);

            // Retrieve the updated audio segment
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            AudioSegment updatedAudioSegment = timelineState.getAudioSegments().stream()
                    .filter(a -> a.getId().equals(audioSegmentId))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find updated audio segment"));

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("audioSegmentId", updatedAudioSegment.getId());
            response.put("layer", updatedAudioSegment.getLayer());
            response.put("timelineStartTime", updatedAudioSegment.getTimelineStartTime());
            response.put("timelineEndTime", updatedAudioSegment.getTimelineEndTime());
            response.put("startTime", updatedAudioSegment.getStartTime());
            response.put("endTime", updatedAudioSegment.getEndTime());
            response.put("volume", updatedAudioSegment.getVolume());
            response.put("audioPath", updatedAudioSegment.getAudioPath());
            response.put("waveformPath", updatedAudioSegment.getWaveformPath());
            response.put("keyframes", updatedAudioSegment.getKeyframes() != null ? updatedAudioSegment.getKeyframes() : new HashMap<>());

            return ResponseEntity.ok(response);
        } catch (IOException | InterruptedException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating audio segment: " + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating audio segment: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/waveforms/{filename:.+}")
    public ResponseEntity<Resource> serveWaveform(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                // Authenticate user if token is provided
                user = getUserFromToken(token);
            }

            // Verify project exists
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            // If token is provided, verify user has access
            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define waveform file path
            String waveformDirectory = "audio/projects/" + projectId + "/waveforms/";
            File waveformFile = new File(waveformDirectory, filename);

            // Verify file existence
            if (!waveformFile.exists() || !waveformFile.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Create resource and set content type
            Resource resource = new FileSystemResource(waveformFile);
            String contentType = "image/png"; // Waveforms are PNG files

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (RuntimeException e) {
            System.err.println("Error serving waveform: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving waveform: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @DeleteMapping("/{projectId}/remove-audio")
    public ResponseEntity<?> removeAudioSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String audioSegmentId) {
        try {
            System.out.println("Received request with token: " + token);
            User user = getUserFromToken(token);
            System.out.println("User authenticated: " + user.getId());

            if (audioSegmentId == null || audioSegmentId.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing required parameter: audioSegmentId");
            }

            videoEditingService.removeAudioSegment(sessionId, audioSegmentId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing audio segment: " + e.getMessage());
        }
    }

    //    IMAGE FUNCTIONALITY.........................................................................................
    @PostMapping("/{projectId}/upload-image")
    public ResponseEntity<?> uploadImage(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam("image") MultipartFile[] imageFiles,
            @RequestParam(value = "imageFileNames", required = false) String[] imageFileNames
    ) {
        try {
            User user = getUserFromToken(token);
            Project updatedProject = videoEditingService.uploadImageToProject(user, projectId, imageFiles, imageFileNames);
            return ResponseEntity.ok(updatedProject);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error uploading image: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        }
    }
    @PostMapping("/{projectId}/add-project-image-to-timeline")
    public ResponseEntity<?> addProjectImageToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);
            Integer layer = request.get("layer") != null ? ((Number) request.get("layer")).intValue() : 0;
            Double timelineStartTime = request.get("timelineStartTime") != null ?
                    ((Number) request.get("timelineStartTime")).doubleValue() : 0.0;
            Double timelineEndTime = request.get("timelineEndTime") != null ?
                    ((Number) request.get("timelineEndTime")).doubleValue() : null;
            String imageFileName = (String) request.get("imageFileName");
            Double opacity = request.get("opacity") != null ? ((Number) request.get("opacity")).doubleValue() : null;
            Boolean isElement = request.get("isElement") != null ? Boolean.valueOf(request.get("isElement").toString()) : false;

            if (layer < 0) {
                return ResponseEntity.badRequest().body("Layer must be a non-negative integer");
            }
            if (timelineStartTime < 0) {
                return ResponseEntity.badRequest().body("Timeline start time must be non-negative");
            }
            if (timelineEndTime != null && timelineEndTime < timelineStartTime) {
                return ResponseEntity.badRequest().body("Timeline end time must be greater than start time");
            }
            if (imageFileName == null || imageFileName.isEmpty()) {
                return ResponseEntity.badRequest().body("Image or element filename is required");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }

            videoEditingService.addImageToTimelineFromProject(
                    user, sessionId, projectId, layer, timelineStartTime, timelineEndTime, null, imageFileName, opacity, isElement);

            // Retrieve the updated timeline state to get the newly added segment
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            ImageSegment newSegment = timelineState.getImageSegments().stream()
                    .filter(segment -> segment.getImagePath().endsWith(imageFileName) &&
                            segment.getLayer() == layer &&
                            Math.abs(segment.getTimelineStartTime() - timelineStartTime) < 0.001)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find newly added segment"));

            return ResponseEntity.ok(newSegment);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding project image or element to timeline: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        }
    }

    @PutMapping("/{projectId}/update-image")
    public ResponseEntity<?> updateImageSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);
            String segmentId = (String) request.get("segmentId");
            Integer positionX = request.containsKey("positionX") ? Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.containsKey("positionY") ? Integer.valueOf(request.get("positionY").toString()) : null;
            Double scale = request.containsKey("scale") ? Double.valueOf(request.get("scale").toString()) : null;
            Double opacity = request.containsKey("opacity") ? Double.valueOf(request.get("opacity").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            Integer customWidth = request.containsKey("customWidth") ? Integer.valueOf(request.get("customWidth").toString()) : null;
            Integer customHeight = request.containsKey("customHeight") ? Integer.valueOf(request.get("customHeight").toString()) : null;
            Boolean maintainAspectRatio = request.containsKey("maintainAspectRatio") ? Boolean.valueOf(request.get("maintainAspectRatio").toString()) : null;
            Double timelineStartTime = request.containsKey("timelineStartTime") ? Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.containsKey("timelineEndTime") ? Double.valueOf(request.get("timelineEndTime").toString()) : null;
            Double cropL = request.containsKey("cropL") ? Double.valueOf(request.get("cropL").toString()) : null;
            Double cropR = request.containsKey("cropR") ? Double.valueOf(request.get("cropR").toString()) : null;
            Double cropT = request.containsKey("cropT") ? Double.valueOf(request.get("cropT").toString()) : null;
            Double cropB = request.containsKey("cropB") ? Double.valueOf(request.get("cropB").toString()) : null;

            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> keyframes = request.containsKey("keyframes") ? (Map<String, List<Map<String, Object>>>) request.get("keyframes") : null;

            Map<String, List<Keyframe>> parsedKeyframes = null;
            if (keyframes != null) {
                parsedKeyframes = new HashMap<>();
                for (Map.Entry<String, List<Map<String, Object>>> entry : keyframes.entrySet()) {
                    List<Keyframe> kfList = new ArrayList<>();
                    for (Map<String, Object> kfData : entry.getValue()) {
                        double time = Double.valueOf(kfData.get("time").toString());
                        Object value = kfData.get("value");
                        String interpolation = (String) kfData.getOrDefault("interpolationType", "linear");
                        kfList.add(new Keyframe(time, value, interpolation));
                    }
                    parsedKeyframes.put(entry.getKey(), kfList);
                }
            }

            // Validation
            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }
            if (customWidth != null && customWidth <= 0) {
                return ResponseEntity.badRequest().body("Custom width must be a positive value");
            }
            if (customHeight != null && customHeight <= 0) {
                return ResponseEntity.badRequest().body("Custom height must be a positive value");
            }
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }
            if (cropL != null && (cropL < 0 || cropL > 100)) {
                return ResponseEntity.badRequest().body("cropL must be between 0 and 100");
            }
            if (cropR != null && (cropR < 0 || cropR > 100)) {
                return ResponseEntity.badRequest().body("cropR must be between 0 and 100");
            }
            if (cropT != null && (cropT < 0 || cropT > 100)) {
                return ResponseEntity.badRequest().body("cropT must be between 0 and 100");
            }
            if (cropB != null && (cropB < 0 || cropB > 100)) {
                return ResponseEntity.badRequest().body("cropB must be between 0 and 100");
            }
            // Validate total crop if static values are provided (not keyframed)
            if (parsedKeyframes == null ||
                    (!parsedKeyframes.containsKey("cropL") && !parsedKeyframes.containsKey("cropR") &&
                            !parsedKeyframes.containsKey("cropT") && !parsedKeyframes.containsKey("cropB"))) {
                double totalHorizontalCrop = (cropL != null ? cropL : 0.0) + (cropR != null ? cropR : 0.0);
                double totalVerticalCrop = (cropT != null ? cropT : 0.0) + (cropB != null ? cropB : 0.0);
                if (totalHorizontalCrop >= 100) {
                    return ResponseEntity.badRequest().body("Total horizontal crop (cropL + cropR) cannot be 100% or more");
                }
                if (totalVerticalCrop >= 100) {
                    return ResponseEntity.badRequest().body("Total vertical crop (cropT + cropB) cannot be 100% or more");
                }
            }

            videoEditingService.updateImageSegment(
                    sessionId, segmentId, positionX, positionY, scale, opacity, layer,
                    customWidth, customHeight, maintainAspectRatio,
                    timelineStartTime, timelineEndTime, cropL, cropR, cropT, cropB, parsedKeyframes);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating image segment: " + e.getMessage());
        }
    }

    @DeleteMapping("/{projectId}/remove-image")
    public ResponseEntity<?> removeImageSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId) {
        try {
            User user = getUserFromToken(token);

            // Validate segmentId
            if (segmentId == null || segmentId.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }

            videoEditingService.removeImageSegment(sessionId, segmentId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing image segment: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/images/{filename:.+}")
    public ResponseEntity<Resource> serveImage(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                // Authenticate user if token is provided
                user = getUserFromToken(token);
            }

            // Verify project exists
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            // Check if the file is in the elements directory first (global access)
            String elementsDirectory = "/Users/nimitpatel/Desktop/VideoEditor 2/elements/";
            Path elementsPath = Paths.get(elementsDirectory).resolve(filename).normalize();
            Resource resource = new UrlResource(elementsPath.toUri());

            if (resource.exists() && resource.isReadable()) {
                String contentType = determineContentType(filename);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .body(resource);
            }

            // If not in elements, check project-specific images (requires ownership)
            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            String projectImageDirectory = "images/projects/" + projectId + "/";
            Path projectImagePath = Paths.get(projectImageDirectory).resolve(filename).normalize();
            resource = new UrlResource(projectImagePath.toUri());

            if (resource.exists() && resource.isReadable()) {
                String contentType = determineContentType(filename);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .body(resource);
            }

            // File not found in either location
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);

        } catch (RuntimeException e) {
            System.err.println("Error serving image: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving image: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    @PostMapping("/{projectId}/add-keyframe")
    public ResponseEntity<?> addKeyframe(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String segmentId = (String) request.get("segmentId");
            String segmentType = (String) request.get("segmentType");
            String property = (String) request.get("property");
            Double time = request.containsKey("time") ? Double.valueOf(request.get("time").toString()) : null;
            Object value = request.get("value");
            String interpolationType = (String) request.getOrDefault("interpolationType", "linear");

            if (segmentId == null || segmentType == null || property == null || time == null || value == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: segmentId, segmentType, property, time, or value");
            }
            if (time < 0) {
                return ResponseEntity.badRequest().body("Time must be non-negative");
            }

            Keyframe keyframe = new Keyframe(time, value, interpolationType);
            videoEditingService.addKeyframeToSegment(sessionId, segmentId, segmentType, property, keyframe);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding keyframe: " + e.getMessage());
        }
    }

    @DeleteMapping("/{projectId}/remove-keyframe")
    public ResponseEntity<?> removeKeyframe(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId,
            @RequestParam String segmentType,
            @RequestParam String property,
            @RequestParam Double time) {
        try {
            User user = getUserFromToken(token);

            if (segmentId == null || segmentType == null || property == null || time == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: segmentId, segmentType, property, or time");
            }
            if (time < 0) {
                return ResponseEntity.badRequest().body("Time must be non-negative");
            }

            videoEditingService.removeKeyframeFromSegment(sessionId, segmentId, segmentType, property, time);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing keyframe: " + e.getMessage());
        }
    }

    // Helper method to determine content type
    private String determineContentType(String filename) {
        filename = filename.toLowerCase();
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".gif")) return "image/gif";
        if (filename.endsWith(".webp")) return "image/webp";
        return "application/octet-stream"; // Default fallback
    }

    @PostMapping("/{projectId}/apply-filter")
    public ResponseEntity<?> applyFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String segmentId = (String) request.get("segmentId");
            String filterName = (String) request.get("filterName");
            String filterValue = request.get("filterValue") != null ? request.get("filterValue").toString() : null;

            if (segmentId == null || filterName == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: segmentId, filterName");
            }

            videoEditingService.applyFilter(sessionId, segmentId, filterName, filterValue);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error applying filter: " + e.getMessage());
        }
    }

    @PutMapping("/{projectId}/update-filter")
    public ResponseEntity<Filter> updateFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Filter request) {
        try {
            User user = getUserFromToken(token);

            // Validate required parameters
            if (request.getSegmentId() == null || request.getFilterId() == null ||
                    request.getFilterName() == null || request.getFilterValue() == null) {
                return ResponseEntity.badRequest()
                        .body(null);
            }

            // Call the service method to update the filter
            videoEditingService.updateFilter(
                    sessionId,
                    request.getSegmentId(),
                    request.getFilterId(),
                    request.getFilterName(),
                    request.getFilterValue()
            );

            // Return the updated filter object
            Filter updatedFilter = new Filter();
            updatedFilter.setFilterId(request.getFilterId());
            updatedFilter.setSegmentId(request.getSegmentId());
            updatedFilter.setFilterName(request.getFilterName());
            updatedFilter.setFilterValue(request.getFilterValue());

            return ResponseEntity.ok(updatedFilter);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(null);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
    }

    // Adding the new GET mapping here, after other segment-related endpoints
    @GetMapping("/{projectId}/segments/{segmentId}/filters")
    public ResponseEntity<List<Filter>> getFiltersForSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @PathVariable String segmentId,
            @RequestParam String sessionId) {
        try {
            User user = getUserFromToken(token); // Authenticate the user
            // Optionally, you could verify project ownership here:
            // Project project = projectRepository.findByIdAndUser(projectId, user);
            // if (project == null) throw new RuntimeException("Project not found or unauthorized");

            List<Filter> filters = videoEditingService.getFiltersForSegment(sessionId, segmentId);
            return ResponseEntity.ok(filters);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(null); // Return 404 if segment not found
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null); // Return 500 for other unexpected errors
        }
    }

    @DeleteMapping("/{projectId}/remove-filter")
    public ResponseEntity<?> removeFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId) {
        try {
            User user = getUserFromToken(token);

            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: filterId, segmentId");
            }

            videoEditingService.removeFilter(sessionId, segmentId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing filter: " + e.getMessage());
        }
    }

    @PostMapping("/{projectId}/add-transition")
    public ResponseEntity<?> addTransition(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String type = (String) request.get("type");
            Double duration = request.get("duration") != null ? Double.valueOf(request.get("duration").toString()) : null;
            String segmentId = (String) request.get("segmentId");
            Boolean start = request.get("start") != null ? Boolean.valueOf(request.get("start").toString()) : null;
            Boolean end = request.get("end") != null ? Boolean.valueOf(request.get("end").toString()) : null;
            Integer layer = request.get("layer") != null ? Integer.valueOf(request.get("layer").toString()) : null;
            @SuppressWarnings("unchecked")
            Map<String, String> parameters = request.containsKey("parameters") ? (Map<String, String>) request.get("parameters") : null;

            // Validate required parameters
            if (type == null || duration == null || segmentId == null || start == null || end == null || layer == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: type, duration, segmentId, start, end, layer");
            }
            if (duration <= 0) {
                return ResponseEntity.badRequest().body("Duration must be positive");
            }
            if (!start && !end) {
                return ResponseEntity.badRequest().body("Transition must be applied at start, end, or both");
            }

            videoEditingService.addTransition(sessionId, type, duration, segmentId, start, end, layer, parameters);

            // Retrieve the newly added transition
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            Transition addedTransition = timelineState.getTransitions().stream()
                    .filter(t -> t.getSegmentId().equals(segmentId) &&
                            t.isStart() == start &&
                            t.isEnd() == end &&
                            t.getLayer() == layer &&
                            t.getType().equals(type) &&
                            Math.abs(t.getDuration() - duration) < 0.001)
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Failed to find added transition"));

            return ResponseEntity.ok(Map.of(
                    "transitionId", addedTransition.getId(),
                    "type", addedTransition.getType(),
                    "duration", addedTransition.getDuration(),
                    "segmentId", addedTransition.getSegmentId(),
                    "start", addedTransition.isStart(),
                    "end", addedTransition.isEnd(),
                    "layer", addedTransition.getLayer(),
                    "timelineStartTime", addedTransition.getTimelineStartTime(),
                    "parameters", addedTransition.getParameters()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding transition: " + e.getMessage());
        }
    }

    // NEW: Endpoint to update a transition
    @PutMapping("/{projectId}/update-transition")
    public ResponseEntity<?> updateTransition(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String transitionId = (String) request.get("transitionId");
            String type = (String) request.get("type");
            Double duration = request.containsKey("duration") ? Double.valueOf(request.get("duration").toString()) : null;
            String segmentId = (String) request.get("segmentId");
            Boolean start = request.containsKey("start") ? Boolean.valueOf(request.get("start").toString()) : null;
            Boolean end = request.containsKey("end") ? Boolean.valueOf(request.get("end").toString()) : null;
            Integer layer = request.containsKey("layer") ? Integer.valueOf(request.get("layer").toString()) : null;
            @SuppressWarnings("unchecked")
            Map<String, String> parameters = request.containsKey("parameters") ? (Map<String, String>) request.get("parameters") : null;

            // Validate required parameters
            if (transitionId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: transitionId");
            }
            if (duration != null && duration <= 0) {
                return ResponseEntity.badRequest().body("Duration must be positive");
            }
            if (start != null && end != null && !start && !end) {
                return ResponseEntity.badRequest().body("Transition must be applied at start, end, or both");
            }

            Transition updatedTransition = videoEditingService.updateTransition(
                    sessionId, transitionId, type, duration, segmentId, start, end, layer, parameters);

            return ResponseEntity.ok(Map.of(
                    "transitionId", updatedTransition.getId(),
                    "type", updatedTransition.getType(),
                    "duration", updatedTransition.getDuration(),
                    "segmentId", updatedTransition.getSegmentId(),
                    "start", updatedTransition.isStart(),
                    "end", updatedTransition.isEnd(),
                    "layer", updatedTransition.getLayer(),
                    "timelineStartTime", updatedTransition.getTimelineStartTime(),
                    "parameters", updatedTransition.getParameters()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating transition: " + e.getMessage());
        }
    }

    // NEW: Endpoint to remove a transition
    @DeleteMapping("/{projectId}/remove-transition")
    public ResponseEntity<?> removeTransition(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String transitionId) {
        try {
            User user = getUserFromToken(token);

            if (transitionId == null || transitionId.isEmpty()) {
                return ResponseEntity.badRequest().body("Missing required parameter: transitionId");
            }

            videoEditingService.removeTransition(sessionId, transitionId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing transition: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/transitions")
    public ResponseEntity<List<Transition>> getTransitions(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId) {
        try {
            User user = getUserFromToken(token);
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);
            return ResponseEntity.ok(timelineState.getTransitions());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(null);
        }
    }

    // Delete Video Segment from Timeline
    @DeleteMapping("/timeline/video/{sessionId}/{segmentId}")
    public ResponseEntity<String> deleteVideoFromTimeline(
            @PathVariable String sessionId,
            @PathVariable String segmentId) {
        try {
            videoEditingService.deleteVideoFromTimeline(sessionId, segmentId);
            return ResponseEntity.ok("Video segment deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // Delete Image Segment from Timeline
    @DeleteMapping("/timeline/image/{sessionId}/{imageId}")
    public ResponseEntity<String> deleteImageFromTimeline(
            @PathVariable String sessionId,
            @PathVariable String imageId) {
        try {
            videoEditingService.deleteImageFromTimeline(sessionId, imageId);
            return ResponseEntity.ok("Image segment deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // Delete Audio Segment from Timeline
    @DeleteMapping("/timeline/audio/{sessionId}/{audioId}")
    public ResponseEntity<String> deleteAudioFromTimeline(
            @PathVariable String sessionId,
            @PathVariable String audioId) {
        try {
            videoEditingService.deleteAudioFromTimeline(sessionId, audioId);
            return ResponseEntity.ok("Audio segment deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // Delete Text Segment from Timeline
    @DeleteMapping("/timeline/text/{sessionId}/{textId}")
    public ResponseEntity<String> deleteTextFromTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable String sessionId,
            @PathVariable String textId) {
        try {
            User user = getUserFromToken(token); // Authenticate user
            videoEditingService.deleteTextFromTimeline(sessionId, textId);
            return ResponseEntity.ok("Text segment deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }


    @DeleteMapping("/{projectId}")
    public ResponseEntity<?> deleteProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId) {
        try {
            User user = getUserFromToken(token);
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            if (!project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("Unauthorized to delete this project");
            }

            // Delete associated files
            videoEditingService.deleteProjectFiles(projectId);
            // Delete project from database
            projectRepository.delete(project);
            return ResponseEntity.ok().body("Project deleted successfully");
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error deleting project: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/audio/{filename:.+}")
    public ResponseEntity<Resource> serveAudio(
            @RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable Long projectId,
            @PathVariable String filename) {
        try {
            User user = null;
            if (token != null && !token.isEmpty()) {
                // Authenticate user if token is provided
                user = getUserFromToken(token);
            }

            // Verify project exists
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new RuntimeException("Project not found with ID: " + projectId));

            // If token is provided, verify user has access
            if (user != null && !project.getUser().getId().equals(user.getId())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(null);
            }

            // Define possible audio file paths
            String baseAudioDirectory = "audio/projects/" + projectId + "/";
            String extractedAudioDirectory = baseAudioDirectory + "extracted/";
            File audioFile = new File(baseAudioDirectory, filename);

            // Check if file exists in base directory, if not, try extracted directory
            if (!audioFile.exists() || !audioFile.isFile()) {
                audioFile = new File(extractedAudioDirectory, filename);
            }

            // Verify file existence
            if (!audioFile.exists() || !audioFile.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            // Create resource and determine content type
            Resource resource = new FileSystemResource(audioFile);
            String contentType = determineAudioContentType(filename);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);

        } catch (RuntimeException e) {
            System.err.println("Error serving audio: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        } catch (Exception e) {
            System.err.println("Error serving audio: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
        }
    }

    // Helper method to determine audio content type
    private String determineAudioContentType(String filename) {
        filename = filename.toLowerCase();
        if (filename.endsWith(".mp3")) return "audio/mpeg";
        if (filename.endsWith(".wav")) return "audio/wav";
        if (filename.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream"; // Default fallback
    }

}