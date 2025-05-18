package com.example.videoeditor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VideoEditorApplication {
	private static final Logger logger = LoggerFactory.getLogger(VideoEditorApplication.class);

	public static void main(String[] args) {
		logger.info("Starting VideoEditorApplication with B2 Bucket: {}", System.getenv("B2_BUCKET_NAME"));
		SpringApplication.run(VideoEditorApplication.class, args);
	}
}