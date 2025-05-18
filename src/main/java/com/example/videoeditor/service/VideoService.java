package com.example.videoeditor.service;

import com.backblaze.b2.client.exceptions.B2Exception;
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@Service
public class VideoService {
    private static final Logger logger = LoggerFactory.getLogger(VideoService.class);

    private final VideoRepository videoRepository;
    private final BackblazeB2Service backblazeB2Service;

    @Value("${ffprobe.path:/usr/bin/ffprobe}")
    private String ffprobePath;

    @Value("${app.base-dir:/tmp}")
    private String baseDir;

    public VideoService(VideoRepository videoRepository, BackblazeB2Service backblazeB2Service) {
        this.videoRepository = videoRepository;
        this.backblazeB2Service = backblazeB2Service;
    }

    public List<Video> uploadVideos(MultipartFile[] files, String[] titles, User user) throws IOException, B2Exception {
        List<Video> uploadedVideos = new ArrayList<>();

        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            String originalFilename = file.getOriginalFilename();
            String title = (titles != null && i < titles.length && titles[i] != null)
                    ? titles[i]
                    : originalFilename;

            // Save to temporary file
            String tempPath = baseDir + "/temp/" + System.currentTimeMillis() + "_" + originalFilename;
            File tempFile = backblazeB2Service.saveMultipartFileToTemp(file, tempPath);

            // Upload to Backblaze B2
            String b2Path = "videos/users/" + user.getId() + "/" + originalFilename;
            backblazeB2Service.uploadFile(tempFile, b2Path);

            // Clean up temporary file
            Files.deleteIfExists(tempFile.toPath());

            // Save video metadata
            Video video = new Video();
            video.setTitle(title);
            video.setFilePath(b2Path); // Store B2 path
            video.setUser(user);
            uploadedVideos.add(videoRepository.save(video));
        }

        logger.info("Uploaded {} videos for user {}", uploadedVideos.size(), user.getEmail());
        return uploadedVideos;
    }

    public List<Video> getVideosByUser(String email) {
        return videoRepository.findByUserEmail(email);
    }

    public double getVideoDuration(String videoPath) throws IOException, InterruptedException, B2Exception {
        // Download video from Backblaze B2
        String tempPath = baseDir + "/temp/video_" + System.currentTimeMillis() + ".mp4";
        File tempFile = backblazeB2Service.downloadFile(videoPath, tempPath);

        if (!tempFile.exists()) {
            throw new IOException("Video file not found at path: " + tempPath);
        }

        ProcessBuilder builder = new ProcessBuilder(
                ffprobePath,
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                tempFile.getAbsolutePath()
        );

        logger.debug("Running ffprobe on file: {}", tempFile.getAbsolutePath());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String duration = reader.readLine();
        int exitCode = process.waitFor();

        // Clean up temporary file
        Files.deleteIfExists(tempFile.toPath());

        if (exitCode != 0 || duration == null) {
            logger.error("Failed to get video duration for path: {}", videoPath);
            throw new IOException("Failed to get video duration");
        }

        double durationSeconds = Double.parseDouble(duration);
        logger.debug("Video duration for {}: {} seconds", videoPath, durationSeconds);
        return durationSeconds;
    }
}