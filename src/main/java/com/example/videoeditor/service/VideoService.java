package com.example.videoeditor.service;

import com.example.videoeditor.entity.User;
import com.example.videoeditor.entity.Video;
import com.example.videoeditor.repository.VideoRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class VideoService {
    private final VideoRepository videoRepository;
    private final String uploadDir = "/Users/nimitpatel/Desktop/VideoEditor 2/videos/";

    public VideoService(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
        new File(uploadDir).mkdirs();
    }

    public List<Video> uploadVideos(MultipartFile[] files, String[] titles, User user) throws IOException {
        List<Video> uploadedVideos = new ArrayList<>();

        for (int i = 0; i < files.length; i++) {
            MultipartFile file = files[i];
            String title = (titles != null && i < titles.length && titles[i] != null) ? titles[i] : file.getOriginalFilename();

            String filename = file.getOriginalFilename();
            String filePath = uploadDir + filename;
            file.transferTo(Paths.get(filePath));

            Video video = new Video();
            video.setTitle(title);
            video.setFilePath(filename);
            video.setUser(user);
            uploadedVideos.add(videoRepository.save(video));
        }

        return uploadedVideos;
    }

    public List<Video> getVideosByUser(String email) {
        return videoRepository.findByUserEmail(email);
    }

    public double getVideoDuration(String videoPath) throws IOException, InterruptedException {

        String baseDir = System.getProperty("user.dir");
        String fullPath = Paths.get(baseDir, videoPath).toString();

        ProcessBuilder builder = new ProcessBuilder(
                "/usr/local/bin/ffprobe",
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                fullPath
        );

        System.out.println("Attempting to get duration for video at path: " + fullPath);
        File videoFile = new File(fullPath);
        if (!videoFile.exists()) {
            throw new IOException("Video file not found at path: " + fullPath);
        }


        builder.redirectErrorStream(true);

        Process process = builder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String duration = reader.readLine();
        int exitCode = process.waitFor();

        if (exitCode != 0 || duration == null) {
            throw new IOException("Failed to get video duration");
        }


        return Double.parseDouble(duration);
    }

}