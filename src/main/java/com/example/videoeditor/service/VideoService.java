package com.example.videoeditor.service;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.Video;
import com.example.videoeditor.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Service
public class VideoService {
    private static final Logger logger = LoggerFactory.getLogger(VideoService.class);

    private final VideoRepository videoRepository;
    private final S3Service s3Service;

    @Value("${ffprobe.path:/usr/bin/ffprobe}")
    private String ffprobePath;

    public VideoService(VideoRepository videoRepository, S3Service s3Service) {
        this.videoRepository = videoRepository;
        this.s3Service = s3Service;
    }

    public List<Video> uploadVideos(MultipartFile[] files, String[] titles, User user) throws IOException {
        List<Video> uploadedVideos = new ArrayList<>();

        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            if (file.isEmpty()) {
                logger.warn("Skipping empty file at index {}", i);
                continue;
            }

            String originalFileName = file.getOriginalFilename();
            String title = (titles != null && i < titles.length && titles[i] != null && !titles[i].trim().isEmpty())
                    ? titles[i].trim()
                    : (originalFileName != null ? originalFileName : "Untitled_" + System.currentTimeMillis());

            // Generate unique S3 key
            String uniqueFileName = user.getId() + "_" + System.currentTimeMillis() + "_" + (originalFileName != null ? originalFileName : "video");
            String s3Key = "videos/users/" + user.getId() + "/" + uniqueFileName.replaceAll("[^a-zA-Z0-9.]", "_");

            try {
                // Upload to S3
                s3Service.uploadFile(file, s3Key);
                logger.debug("Uploaded video to S3: user={}, key={}", user.getEmail(), s3Key);

                // Store S3 key in Video entity
                Video video = new Video();
                video.setTitle(title);
                video.setFilePath(s3Key);
                video.setUser(user);
                uploadedVideos.add(videoRepository.save(video));
            } catch (IOException e) {
                logger.error("Failed to upload video to S3: key={}, error={}", s3Key, e.getMessage());
                throw new IOException("Failed to upload video: " + originalFileName, e);
            }
        }

        return uploadedVideos;
    }

    public List<Video> getVideosByUser(String email) {
        return videoRepository.findByUserEmail(email);
    }

    public double getVideoDuration(String videoS3Key) throws IOException, InterruptedException {
        File videoFile = null;
        try {
            // Download video from S3
            videoFile = s3Service.downloadFile(videoS3Key);
            if (!videoFile.exists()) {
                throw new IOException("Downloaded video file not found: " + videoFile.getAbsolutePath());
            }

            // Run ffprobe to get duration
            ProcessBuilder builder = new ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    videoFile.getAbsolutePath()
            );

            builder.redirectErrorStream(true);
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String duration = reader.readLine();
            int exitCode = process.waitFor();

            if (exitCode != 0 || duration == null) {
                logger.error("Failed to get video duration for S3 key: {}", videoS3Key);
                throw new IOException("Failed to get video duration for S3 key: " + videoS3Key);
            }

            logger.debug("Retrieved duration {} seconds for video: {}", duration, videoS3Key);
            return Double.parseDouble(duration);
        } catch (NumberFormatException e) {
            logger.error("Invalid duration format for S3 key: {}", videoS3Key, e);
            throw new IOException("Invalid duration format for video: " + videoS3Key, e);
        } finally {
            // Clean up temporary file
            if (videoFile != null && videoFile.exists()) {
                videoFile.delete();
            }
        }
    }
}