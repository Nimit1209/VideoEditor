package com.example.videoeditor.controller;

import com.example.videoeditor.entity.EditedVideo;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.Video;
import com.example.videoeditor.repository.EditedVideoRepository;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.service.VideoService;
import com.example.videoeditor.security.JwtUtil;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/videos")
public class VideoController {
    private final VideoService videoService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final EditedVideoRepository editedVideoRepository;

    public VideoController(VideoService videoService, UserRepository userRepository, JwtUtil jwtUtil, EditedVideoRepository editedVideoRepository) {
        this.videoService = videoService;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.editedVideoRepository = editedVideoRepository;
    }

    private final String uploadDir = "videos"; // Change to your actual folder

    @GetMapping("/edited-videos/{fileName}")
    public ResponseEntity<Resource> getEditedVideo(@PathVariable String fileName) {
        try {
            Path videoPath = Paths.get("edited_videos").resolve(fileName).normalize();
            Resource resource = new UrlResource(videoPath.toUri());

            if (!resource.exists() || !resource.isReadable()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }

            return ResponseEntity.ok()
                    .contentType(MediaTypeFactory.getMediaType(resource).orElse(MediaType.APPLICATION_OCTET_STREAM))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);

        } catch (MalformedURLException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }



    @GetMapping("/{filename}")
    public ResponseEntity<Resource> getVideo(@PathVariable String filename) throws MalformedURLException {
        Path filePath = Paths.get(uploadDir).resolve(filename).normalize();
        Resource resource = new UrlResource(filePath.toUri());

        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .body(resource);
    }

    @PostMapping("/upload/{projectId}")
    public ResponseEntity<?> uploadVideo(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "titles", required = false) String[] titles
    ) throws IOException {
        try {
            String email = jwtUtil.extractEmail(token.substring(7)); // Extract user email from JWT
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            List<Video> videos = videoService.uploadVideos(files, titles, user); // Updated call
            return ResponseEntity.ok(videos);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error uploading videos: " + e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(e.getMessage());
        }
    }

    @GetMapping("/my-videos")
    public ResponseEntity<List<Video>> getMyVideos(@RequestHeader("Authorization") String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<Video> videos = videoService.getVideosByUser(email);
        return ResponseEntity.ok(videos);
    }


    @GetMapping("/edited-videos")
    public ResponseEntity<List<EditedVideo>> getUserEditedVideos(@RequestHeader("Authorization") String token) {
        String email = jwtUtil.extractEmail(token.substring(7));
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        List<EditedVideo> editedVideos = editedVideoRepository.findByUser(user);
        return ResponseEntity.ok(editedVideos);
    }


    // Add this class at the end of VideoController
    public static class SplitRequest {
        private String videoPath;
        private double splitTimeSeconds;
        private String segmentId;

        public String getSegmentId() {
            return segmentId;
        }

        public void setSegmentId(String segmentId) {
            this.segmentId = segmentId;
        }

        // Getters and setters
        public String getVideoPath() { return videoPath; }
        public void setVideoPath(String videoPath) { this.videoPath = videoPath; }
        public double getSplitTimeSeconds() { return splitTimeSeconds; }
        public void setSplitTimeSeconds(double splitTimeSeconds) {
            this.splitTimeSeconds = splitTimeSeconds;
        }
    }

    // Also add this method to get video duration
    @GetMapping("/duration/{filename}")
    public ResponseEntity<Double> getVideoDuration(@RequestHeader("Authorization") String token,
                                                   @PathVariable String filename) {
        try {
            String email = jwtUtil.extractEmail(token.substring(7));
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            String videoPath = filename.startsWith("edited_")
                    ? "edited_videos/" + filename
                    : "videos/" + filename;

            double duration = videoService.getVideoDuration(videoPath);
            return ResponseEntity.ok(duration);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

}