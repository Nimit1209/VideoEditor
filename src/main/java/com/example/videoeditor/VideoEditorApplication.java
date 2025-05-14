package com.example.videoeditor;

import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VideoEditorApplication {
	private static final Logger logger = LoggerFactory.getLogger(VideoEditorApplication.class);

	public static void main(String[] args) {
		Dotenv dotenv = Dotenv.configure().load();
		logger.info("AWS_ACCESS_KEY_ID: {}", dotenv.get("AWS_ACCESS_KEY_ID"));
		logger.info("AWS_SECRET_ACCESS_KEY: {}", dotenv.get("AWS_SECRET_ACCESS_KEY"));
		SpringApplication.run(VideoEditorApplication.class, args);
	}

}
