package com.example.videoeditor.controller;

import com.example.videoeditor.entity.EditedVideo;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.Video;
import com.example.videoeditor.repository.EditedVideoRepository;
import com.example.videoeditor.repository.UserRepository;
import com.example.videoeditor.service.S3Service; // Add S3Service
import com.example.videoeditor.service.VideoService;
import com.example.videoeditor.security.JwtUtil;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/videos")
public class VideoController {
    private final VideoService videoService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final EditedVideoRepository editedVideoRepository;
    private final S3Service s3Service; // Add S3Service

    public VideoController(VideoService videoService, UserRepository userRepository, JwtUtil jwtUtil, EditedVideoRepository editedVideoRepository, S3Service s3Service) {
        this.videoService = videoService;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.editedVideoRepository = editedVideoRepository;
        this.s3Service = s3Service;
    }

    @GetMapping("/edited-videos/{fileName}")
    public ResponseEntity<Resource> getEditedVideo(@PathVariable String fileName) {
        try {
            String s3Key = "edited_videos/" + fileName;
            File tempFile = s3Service.downloadFile(s3Key);
            InputStreamResource resource = new InputStreamResource(new FileInputStream(tempFile));

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } finally {
            // Clean up temporary file (handled in S3Service or ensure cleanup here if needed)
        }
    }

    @GetMapping("/{filename}")
    public ResponseEntity<Resource> getVideo(@PathVariable String filename) {
        try {
            // Assume filename is the S3 key or part of it
            String s3Key = "videos/users/" + filename; // Adjust based on stored Video.filePath
            File tempFile = s3Service.downloadFile(s3Key);
            InputStreamResource resource = new InputStreamResource(new FileInputStream(tempFile));

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/upload/{projectId}")
    public ResponseEntity<?> uploadVideo(
            @RequestHeader("Authorization") String token,
            @PathVariable Long projectId,
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "titles", required = false) String[] titles
    ) throws IOException {
        try {
            String email = jwtUtil.extractEmail(token.substring(7));
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            List<Video> videos = videoService.uploadVideos(files, titles, user);
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

        public String getVideoPath() { return videoPath; }
        public void setVideoPath(String videoPath) { this.videoPath = videoPath; }
        public double getSplitTimeSeconds() { return splitTimeSeconds; }
        public void setSplitTimeSeconds(double splitTimeSeconds) { this.splitTimeSeconds = splitTimeSeconds; }
    }

    @GetMapping("/duration/{filename}")
    public ResponseEntity<Double> getVideoDuration(@RequestHeader("Authorization") String token,
                                                   @PathVariable String filename) {
        try {
            String email = jwtUtil.extractEmail(token.substring(7));
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            String videoS3Key = filename.startsWith("edited_")
                    ? "edited_videos/" + filename
                    : "videos/users/" + filename; // Adjust based on Video.filePath

            double duration = videoService.getVideoDuration(videoS3Key);
            return ResponseEntity.ok(duration);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}