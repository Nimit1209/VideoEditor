package com.example.videoeditor.service;

import com.example.videoeditor.PathConfig;
import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.Video;
import com.example.videoeditor.repository.VideoRepository;
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
    private final VideoRepository videoRepository;
    private final S3Service s3Service; // Add S3Service dependency

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
            String originalFileName = file.getOriginalFilename();
            String title = (titles != null && i < titles.length && titles[i] != null) ? titles[i] : originalFileName;

            // Generate unique S3 key
            String uniqueFileName = user.getId() + "_" + System.currentTimeMillis() + "_" + originalFileName;
            String s3Key = "videos/users/" + user.getId() + "/" + uniqueFileName;

            // Upload to S3
            s3Service.uploadFile(s3Key, file);

            // Store S3 key in Video entity
            Video video = new Video();
            video.setTitle(title);
            video.setFilePath(s3Key); // Store S3 key instead of local path
            video.setUser(user);
            uploadedVideos.add(videoRepository.save(video));
        }

        return uploadedVideos;
    }

    public List<Video> getVideosByUser(String email) {
        return videoRepository.findByUserEmail(email);
    }

    public double getVideoDuration(String videoS3Key) throws IOException, InterruptedException {
        // Download video from S3 to a temporary file
        File videoFile = s3Service.downloadFile(videoS3Key);
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    ffprobePath,
                    "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    videoFile.getAbsolutePath()
            );

            System.out.println("Attempting to get duration for video at S3 key: " + videoS3Key);
            if (!videoFile.exists()) {
                throw new IOException("Downloaded video file not found: " + videoFile.getAbsolutePath());
            }

            builder.redirectErrorStream(true);

            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String duration = reader.readLine();
            int exitCode = process.waitFor();

            if (exitCode != 0 || duration == null) {
                throw new IOException("Failed to get video duration for S3 key: " + videoS3Key);
            }

            return Double.parseDouble(duration);
        } finally {
            // Clean up temporary file
            if (videoFile.exists()) {
                videoFile.delete();
            }
        }
    }
}