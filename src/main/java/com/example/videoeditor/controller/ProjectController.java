package com.example.videoeditor.controller;

import com.example.videoeditor.dto.FilterRequest;
import com.example.videoeditor.dto.VideoSegment;
import com.example.videoeditor.entity.Project;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.repository.ProjectRepository;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.security.JwtUtil;
import com.example.videoeditor.service.VideoEditingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        Project project = videoEditingService.createProject(user, name, width, height);
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


    @PutMapping("/{projectId}/update-segment")
    public ResponseEntity<?> updateVideoSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String segmentId = (String) request.get("segmentId");
            Integer positionX = request.containsKey("positionX") ?
                    Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.containsKey("positionY") ?
                    Integer.valueOf(request.get("positionY").toString()) : null;
            Double scale = request.containsKey("scale") ?
                    Double.valueOf(request.get("scale").toString()) : null;

            videoEditingService.updateVideoSegment(sessionId, segmentId, positionX, positionY, scale);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating video segment: " + e.getMessage());
        }
    }

//    @GetMapping("/{projectId}/get-segment")
//    public ResponseEntity<?> getVideoSegment(
//            @RequestHeader("Authorization") String token,
//            @PathVariable Long projectId,
//            @RequestParam String sessionId,
//            @RequestParam String segmentId) {
//        try {
//            User user = getUserFromToken(token);
//
//            VideoSegment segment = videoEditingService.getVideoSegment(sessionId, segmentId);
//            return ResponseEntity.ok(segment);
//        } catch (Exception e) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body("Error getting video segment: " + e.getMessage());
//        }
//    }

    @PostMapping("/{projectId}/save")
    public ResponseEntity<?> saveProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId) throws JsonProcessingException {
        videoEditingService.saveProject(sessionId);
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


    @PostMapping("/{projectId}/split")
    public ResponseEntity<?> splitVideo(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody VideoController.SplitRequest request) {
        try {
            User user = getUserFromToken(token);
            videoEditingService.splitVideo(sessionId, request.getVideoPath(), request.getSplitTimeSeconds(),request.getSegmentId());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error splitting video: " + e.getMessage());
        }
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

            // Extract required parameters from the request
            String videoPath = (String) request.get("videoPath");
            Integer layer = (Integer) request.get("layer"); // Layer (optional, default to 0)
            Double timelineStartTime = request.get("timelineStartTime") != null ? ((Number) request.get("timelineStartTime")).doubleValue() : null;
            Double timelineEndTime = request.get("timelineEndTime") != null ? ((Number) request.get("timelineEndTime")).doubleValue() : null;

            // Validate required parameters
            if (videoPath == null) {
                return ResponseEntity.badRequest().body("Missing required parameters: videoPath");
            }

            // Call the service method to add the video to the timeline
            videoEditingService.addVideoToTimeline(
                    sessionId,
                    videoPath,
                    layer != null ? layer : 0, // Default to layer 0 if not provided
                    timelineStartTime,
                    timelineEndTime
            );

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error adding video to timeline: " + e.getMessage());
        }
    }


    @PostMapping("/{projectId}/apply-filter")
    public ResponseEntity<?> applyFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody FilterRequest filterRequest) {
        try {
            User user = getUserFromToken(token);
            videoEditingService.applyFilter(
                    sessionId,
                    filterRequest.getVideoPath(),
                    filterRequest.getSegmentId(),
                    filterRequest.getFilterType(),
                    filterRequest.getFilterParams()
            );
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error applying filter: " + e.getMessage());
        }
    }

    @GetMapping("/{projectId}/segment/{segmentId}/filters")
    public ResponseEntity<?> getFilterDetailsForSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @PathVariable String segmentId,
            @RequestParam String sessionId) {
        try {
            User user = getUserFromToken(token);
            List<Map<String, Object>> filterDetails = videoEditingService.getFilterDetailsForSegment(sessionId, segmentId);
            return ResponseEntity.ok(filterDetails);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error retrieving filter details: " + e.getMessage());
        }
    }


    @PostMapping("/{projectId}/add-image")
    public ResponseEntity<?> addImageToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            // Retrieve user from token (if needed)
            User user = getUserFromToken(token);

            // Extract parameters
            String imagePath = (String) request.get("imagePath");
            Integer layer = request.get("layer") != null ?
                    ((Number) request.get("layer")).intValue() : 0;
            Double timelineStartTime = request.get("timelineStartTime") != null ?
                    ((Number) request.get("timelineStartTime")).doubleValue() : 0.0;
            Double timelineEndTime = request.get("timelineEndTime") != null ?
                    ((Number) request.get("timelineEndTime")).doubleValue() : null;
            Integer positionX = request.get("positionX") != null ?
                    ((Number) request.get("positionX")).intValue() : 0;
            Integer positionY = request.get("positionY") != null ?
                    ((Number) request.get("positionY")).intValue() : 0;
            Double scale = request.get("scale") != null ?
                    ((Number) request.get("scale")).doubleValue() : 1.0;

            // Validate required parameters
            if (imagePath == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: imagePath");
            }

            // Validate parameter ranges
            if (layer < 0) {
                return ResponseEntity.badRequest().body("Layer must be a non-negative integer");
            }
            if (timelineStartTime < 0) {
                return ResponseEntity.badRequest().body("Timeline start time must be a non-negative value");
            }
            if (timelineEndTime != null && timelineEndTime <= timelineStartTime) {
                return ResponseEntity.badRequest().body("Timeline end time must be greater than start time");
            }
            if (scale <= 0) {
                return ResponseEntity.badRequest().body("Scale must be a positive value");
            }

            // Call service method
            videoEditingService.addImageToTimeline(
                    sessionId,
                    imagePath,
                    layer,
                    timelineStartTime,
                    timelineEndTime,
                    positionX,
                    positionY,
                    scale
            );

            return ResponseEntity.ok().build();
        } catch (Exception e) {

            // Return a structured error response
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error adding image to timeline");
            errorResponse.put("message", e.getMessage());

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
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

            // Extract parameters
            String segmentId = (String) request.get("segmentId");
            Integer positionX = request.containsKey("positionX") ?
                    Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.containsKey("positionY") ?
                    Integer.valueOf(request.get("positionY").toString()) : null;
            Double scale = request.containsKey("scale") ?
                    Double.valueOf(request.get("scale").toString()) : null;
            Double opacity = request.containsKey("opacity") ?
                    Double.valueOf(request.get("opacity").toString()) : null;
            Integer layer = request.containsKey("layer") ?
                    Integer.valueOf(request.get("layer").toString()) : null;

            // Validate parameters
            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }

            // Call service method
            videoEditingService.updateImageSegment(
                    sessionId,
                    segmentId,
                    positionX,
                    positionY,
                    scale,
                    opacity,
                    layer
            );
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating image segment: " + e.getMessage());
        }
    }
}