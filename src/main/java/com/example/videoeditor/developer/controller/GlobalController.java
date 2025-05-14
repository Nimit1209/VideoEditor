package com.example.videoeditor.developer.controller;

import com.example.videoeditor.dto.ElementDto;
import com.example.videoeditor.developer.service.GlobalElementService;
import com.example.videoeditor.service.S3Service; // Add S3Service
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class GlobalController {
    private final GlobalElementService globalElementService;
    private final S3Service s3Service; // Add S3Service

    public GlobalController(GlobalElementService globalElementService, S3Service s3Service) {
        this.globalElementService = globalElementService;
        this.s3Service = s3Service;
    }

    @GetMapping("/global-elements")
    public ResponseEntity<List<ElementDto>> getGlobalElements() {
        List<ElementDto> elements = globalElementService.getGlobalElements();
        return ResponseEntity.ok(elements);
    }

    @GetMapping("/global-elements/{filename:.+}")
    public ResponseEntity<Resource> serveElement(@PathVariable String filename) {
        try {
            String s3Key = "elements/" + filename;
            File tempFile = s3Service.downloadFile(s3Key);
            InputStreamResource resource = new InputStreamResource(new FileInputStream(tempFile));

            String contentType = determineContentType(filename);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (IOException e) {
            System.err.println("Error serving element from S3: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
    }

    private String determineContentType(String filename) {
        filename = filename.toLowerCase();
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".gif")) return "image/gif";
        if (filename.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}