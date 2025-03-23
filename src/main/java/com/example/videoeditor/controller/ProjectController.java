package com.example.videoeditor.controller;

import com.example.videoeditor.dto.*;
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
    // In your controller class
    @PutMapping("/{projectId}")
    public ResponseEntity<Project> updateProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> request) throws JsonProcessingException {
        User user = getUserFromToken(token);

        String name = (String) request.get("name");
        Integer width = request.get("width") != null ?
                ((Number) request.get("width")).intValue() : null;
        Integer height = request.get("height") != null ?
                ((Number) request.get("height")).intValue() : null;

        try {
            Project updatedProject = videoEditingService.updateProject(projectId, user, name, width, height);
            return ResponseEntity.ok(updatedProject);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<?> deleteProject(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId) {
        User user = getUserFromToken(token);

        try {
            videoEditingService.deleteProject(projectId, user);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
            } else if (e.getMessage().contains("Unauthorized")) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
            } else {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
            }
        }
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

            // Add new parameters
            Double timelineStartTime = request.containsKey("timelineStartTime") ?
                    Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Integer layer = request.containsKey("layer") ?
                    Integer.valueOf(request.get("layer").toString()) : null;

            videoEditingService.updateVideoSegment(sessionId, segmentId, positionX, positionY, scale, timelineStartTime, layer);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating video segment: " + e.getMessage());
        }
    }


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
            videoEditingService.splitVideo(sessionId, request.getVideoPath(), request.getSplitTimeSeconds(), request.getSegmentId());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error splitting video: " + e.getMessage());
        }
    }

    @PutMapping("/{projectId}/split")
    public ResponseEntity<?> updateSplitVideo(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String segmentId = (String) request.get("segmentId");
            double newSplitTime = Double.parseDouble(request.get("newSplitTime").toString());

            videoEditingService.updateSplitVideo(sessionId, segmentId, newSplitTime);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating video split: " + e.getMessage());
        }
    }

    @DeleteMapping("/{projectId}/split")
    public ResponseEntity<?> deleteSplitVideo(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            String firstSegmentId = (String) request.get("firstSegmentId");
            String secondSegmentId = (String) request.get("secondSegmentId");

            videoEditingService.deleteSplitVideo(sessionId, firstSegmentId, secondSegmentId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error deleting video split: " + e.getMessage());
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

//    @PutMapping("/{projectId}/update-segment")
//    public ResponseEntity<?> updateVideoSegment(
//            @RequestHeader("Authorization") String token,
//            @PathVariable Long projectId,
//            @RequestParam String sessionId,
//            @RequestParam String segmentId,
//            @RequestBody Map<String, Object> request) {
//        try {
//            User user = getUserFromToken(token);
//
//            // Extract parameters from the request
//            Integer positionX = request.get("positionX") != null ? ((Number) request.get("positionX")).intValue() : null;
//            Integer positionY = request.get("positionY") != null ? ((Number) request.get("positionY")).intValue() : null;
//            Double scale = request.get("scale") != null ? ((Number) request.get("scale")).doubleValue() : null;
//
//            // Call the service method to update the video segment
//            videoEditingService.updateVideoSegment(
//                    sessionId,
//                    segmentId,
//                    positionX,
//                    positionY,
//                    scale
//            );
//
//            return ResponseEntity.ok().build();
//        } catch (Exception e) {
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
//                    .body("Error updating video segment: " + e.getMessage());
//        }
//    }

    @DeleteMapping("/{projectId}/remove-segment")
    public ResponseEntity<?> removeVideoSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId) {
        try {
            User user = getUserFromToken(token);

            // Call the service method to remove the video segment
            videoEditingService.removeVideoSegment(sessionId, segmentId);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing video segment: " + e.getMessage());
        }
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
    @DeleteMapping("/{projectId}/clear-timeline")
    public ResponseEntity<?> clearTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId) {
        try {
            User user = getUserFromToken(token);

            // Call the service method to clear the timeline
            videoEditingService.clearTimeline(sessionId);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error clearing timeline: " + e.getMessage());
        }
    }

    @PutMapping("/{projectId}/update-segment-timing")
    public ResponseEntity<?> updateSegmentTiming(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestParam String segmentId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Extract parameters from the request
            Double timelineStartTime = request.get("timelineStartTime") != null ? ((Number) request.get("timelineStartTime")).doubleValue() : null;
            Double timelineEndTime = request.get("timelineEndTime") != null ? ((Number) request.get("timelineEndTime")).doubleValue() : null;
            Integer layer = request.get("layer") != null ? ((Number) request.get("layer")).intValue() : null;

            // Call the service method to update the segment timing
            videoEditingService.updateSegmentTiming(
                    sessionId,
                    segmentId,
                    timelineStartTime,
                    timelineEndTime,
                    layer
            );

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating segment timing: " + e.getMessage());
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
            return ResponseEntity.ok(segment);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error getting video segment: " + e.getMessage());
        }
    }
    // Apply a new filter to a video segment (stacking capability)
    @PostMapping("/{projectId}/apply-filter")
    public ResponseEntity<?> applyFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody FilterRequest filterRequest) {
        try {
            User user = getUserFromToken(token);

            // Validate required parameters
            if (filterRequest.getSegmentId() == null || filterRequest.getFilterType() == null) {
                return ResponseEntity.badRequest()
                        .body("Missing required parameters: segmentId and filterType are required");
            }

            // Apply the filter (this stacks a new filter on top of existing ones)
            videoEditingService.applyFilter(
                    sessionId,
                    filterRequest.getSegmentId(),
                    filterRequest.getFilterType(),
                    filterRequest.getFilterParams()
            );

            return ResponseEntity.ok()
                    .body(Map.of("message", "Filter applied successfully"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error applying filter: " + e.getMessage());
        }
    }

    // Get filter details for a specific segment
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

    // Update an existing filter
    @PutMapping("/{projectId}/filter/{filterId}")
    public ResponseEntity<?> updateFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @PathVariable String filterId,
            @RequestParam String sessionId,
            @RequestBody FilterRequest filterRequest) {
        try {
            User user = getUserFromToken(token);

            // Validate required parameters
            if (filterRequest.getSegmentId() == null || filterRequest.getFilterType() == null) {
                return ResponseEntity.badRequest()
                        .body("Missing required parameters: segmentId and filterType are required");
            }

            // Update the existing filter
            boolean filterUpdated = videoEditingService.updateFilter(
                    sessionId,
                    filterId,
                    filterRequest.getSegmentId(),
                    filterRequest.getFilterType(),
                    filterRequest.getFilterParams()
            );

            if (filterUpdated) {
                return ResponseEntity.ok()
                        .body(Map.of(
                                "message", "Filter updated successfully",
                                "filterId", filterId
                        ));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Filter not found with ID: " + filterId);
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating filter: " + e.getMessage());
        }
    }

    // Delete a specific filter
    @DeleteMapping("/{projectId}/filter/{filterId}")
    public ResponseEntity<?> deleteFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @PathVariable String filterId,
            @RequestParam String sessionId) {
        try {
            User user = getUserFromToken(token);

            // Remove the specific filter
            boolean filterRemoved = videoEditingService.removeFilter(sessionId, filterId);

            if (filterRemoved) {
                return ResponseEntity.ok()
                        .body(Map.of(
                                "message", "Filter removed successfully",
                                "filterId", filterId
                        ));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Filter not found with ID: " + filterId);
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error deleting filter: " + e.getMessage());
        }
    }

    // Delete all filters for a specific segment
    @DeleteMapping("/{projectId}/segment/{segmentId}/filters")
    public ResponseEntity<?> deleteAllFiltersForSegment(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @PathVariable String segmentId,
            @RequestParam String sessionId) {
        try {
            User user = getUserFromToken(token);

            // Remove all filters for the segment
            Map<String, Object> result = videoEditingService.removeAllFiltersFromSegment(sessionId, segmentId);

            boolean removed = (boolean) result.get("removed");
            List<String> removedFilterIds = (List<String>) result.get("removedFilterIds");

            if (removed && !removedFilterIds.isEmpty()) {
                return ResponseEntity.ok()
                        .body(Map.of(
                                "message", "All filters removed from segment",
                                "segmentId", segmentId,
                                "removedFilters", removedFilterIds
                        ));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("No filters found for segment: " + segmentId);
            }
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error removing filters: " + e.getMessage());
        }
    }
    @PostMapping("/{projectId}/add-image")
    public ResponseEntity<?> addImageToTimeline(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Extract parameters
            String imagePath = (String) request.get("imagePath");
            Integer layer = request.get("layer") != null ? ((Number) request.get("layer")).intValue() : 0;
            Double timelineStartTime = request.get("timelineStartTime") != null ? ((Number) request.get("timelineStartTime")).doubleValue() : 0.0;
            Double timelineEndTime = request.get("timelineEndTime") != null ? ((Number) request.get("timelineEndTime")).doubleValue() : null;
            Integer positionX = request.get("positionX") != null ? ((Number) request.get("positionX")).intValue() : 0;
            Integer positionY = request.get("positionY") != null ? ((Number) request.get("positionY")).intValue() : 0;
            Double scale = request.get("scale") != null ? ((Number) request.get("scale")).doubleValue() : 1.0;

            Integer customWidth = request.get("customWidth") != null ? ((Number) request.get("customWidth")).intValue() : null;
            Integer customHeight = request.get("customHeight") != null ? ((Number) request.get("customHeight")).intValue() : null;
            Boolean maintainAspectRatio = request.get("maintainAspectRatio") != null ? (Boolean) request.get("maintainAspectRatio") : null;

            @SuppressWarnings("unchecked")
            Map<String, String> filters = request.get("filters") != null ? (Map<String, String>) request.get("filters") : null;

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

            // Validate custom dimensions
            if (customWidth != null && customWidth <= 0) {
                return ResponseEntity.badRequest().body("Custom width must be a positive value");
            }
            if (customHeight != null && customHeight <= 0) {
                return ResponseEntity.badRequest().body("Custom height must be a positive value");
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
                    scale,
                    customWidth,
                    customHeight,
                    maintainAspectRatio,
                    filters
            );

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Error adding image to timeline");
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
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

            // New parameters for image customization
            Integer customWidth = request.containsKey("customWidth") ?
                    Integer.valueOf(request.get("customWidth").toString()) : null;
            Integer customHeight = request.containsKey("customHeight") ?
                    Integer.valueOf(request.get("customHeight").toString()) : null;
            Boolean maintainAspectRatio = request.containsKey("maintainAspectRatio") ?
                    Boolean.valueOf(request.get("maintainAspectRatio").toString()) : null;

            // Filter management
            @SuppressWarnings("unchecked")
            Map<String, String> filters = request.containsKey("filters") ?
                    (Map<String, String>) request.get("filters") : null;

            @SuppressWarnings("unchecked")
            List<String> filtersToRemove = request.containsKey("filtersToRemove") ?
                    (List<String>) request.get("filtersToRemove") : null;

            // Validate parameters
            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }

            // Validate custom dimensions if provided
            if (customWidth != null && customWidth <= 0) {
                return ResponseEntity.badRequest().body("Custom width must be a positive value");
            }
            if (customHeight != null && customHeight <= 0) {
                return ResponseEntity.badRequest().body("Custom height must be a positive value");
            }

            // Validate opacity
            if (opacity != null && (opacity < 0 || opacity > 1)) {
                return ResponseEntity.badRequest().body("Opacity must be between 0 and 1");
            }

            // Call service method
            videoEditingService.updateImageSegment(
                    sessionId,
                    segmentId,
                    positionX,
                    positionY,
                    scale,
                    opacity,
                    layer,
                    customWidth,
                    customHeight,
                    maintainAspectRatio,
                    filters,
                    filtersToRemove
            );
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating image segment: " + e.getMessage());
        }
    }

    // Add a new endpoint specifically for applying filters
    @PutMapping("/{projectId}/apply-image-filter")
    public ResponseEntity<?> applyImageFilter(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam String sessionId,
            @RequestBody Map<String, Object> request) {
        try {
            User user = getUserFromToken(token);

            // Extract parameters
            String segmentId = (String) request.get("segmentId");
            String filterType = (String) request.get("filterType");
            String filterValue = request.get("filterValue") != null ?
                    request.get("filterValue").toString() : null;

            // Validate parameters
            if (segmentId == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: segmentId");
            }
            if (filterType == null) {
                return ResponseEntity.badRequest().body("Missing required parameter: filterType");
            }

            // Get the timeline state
            TimelineState timelineState = videoEditingService.getTimelineState(sessionId);

            // Find the image segment
            ImageSegment targetSegment = null;
            for (ImageSegment segment : timelineState.getImageSegments()) {
                if (segment.getId().equals(segmentId)) {
                    targetSegment = segment;
                    break;
                }
            }

            if (targetSegment == null) {
                return ResponseEntity.badRequest().body("Image segment not found: " + segmentId);
            }

            // Apply or remove filter
            if (filterValue == null) {
                targetSegment.removeFilter(filterType);
            } else {
                targetSegment.addFilter(filterType, filterValue);
            }

            // Create an operation for tracking
//            EditOperation filterOperation = new EditOperation();
//            filterOperation.setOperationType("APPLY_IMAGE_FILTER");
//            filterOperation.setSourceVideoPath(targetSegment.getImagePath());

            Map<String, Object> params = new HashMap<>();
            params.put("imageSegmentId", segmentId);
            params.put("time", System.currentTimeMillis());
            params.put("filterType", filterType);
            params.put("filterValue", filterValue);
//            filterOperation.setParameters(params);

//            timelineState.add(filterOperation);
//
// Save the updated timeline state
            videoEditingService.saveTimelineState(sessionId, timelineState);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error applying image filter: " + e.getMessage());
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

            String text = (String) request.get("text");
            int layer = (int) request.get("layer");
            double timelineStartTime = (double) request.get("timelineStartTime");
            double timelineEndTime = (double) request.get("timelineEndTime");
            String fontFamily = (String) request.get("fontFamily");
            int fontSize = (int) request.get("fontSize");
            String fontColor = (String) request.get("fontColor");
            String backgroundColor = (String) request.get("backgroundColor");
            int positionX = (int) request.get("positionX");
            int positionY = (int) request.get("positionY");

            videoEditingService.addTextToTimeline(sessionId, text, layer, timelineStartTime, timelineEndTime,
                    fontFamily, fontSize, fontColor, backgroundColor, positionX, positionY);

            return ResponseEntity.ok().build();
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

            String segmentId = (String) request.get("segmentId");
            String text = (String) request.get("text");
            String fontFamily = (String) request.get("fontFamily");
            Integer fontSize = request.containsKey("fontSize") ?
                    Integer.valueOf(request.get("fontSize").toString()) : null;
            String fontColor = (String) request.get("fontColor");
            String backgroundColor = (String) request.get("backgroundColor");
            Integer positionX = request.containsKey("positionX") ?
                    Integer.valueOf(request.get("positionX").toString()) : null;
            Integer positionY = request.containsKey("positionY") ?
                    Integer.valueOf(request.get("positionY").toString()) : null;
            Double timelineStartTime = request.containsKey("timelineStartTime") ?
                    Double.valueOf(request.get("timelineStartTime").toString()) : null;
            Double timelineEndTime = request.containsKey("timelineEndTime") ?
                    Double.valueOf(request.get("timelineEndTime").toString()) : null;
            Integer layer = request.containsKey("layer") ?
                    Integer.valueOf(request.get("layer").toString()) : null;

            videoEditingService.updateTextSegment(sessionId, segmentId, text, fontFamily, fontSize,
                    fontColor, backgroundColor, positionX, positionY, timelineStartTime, timelineEndTime, layer);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error updating text segment: " + e.getMessage());
        }
    }

}