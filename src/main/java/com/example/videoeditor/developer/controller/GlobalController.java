// src/main/java/com/example/videoeditor/developer/controller/GlobalController.java
package com.example.videoeditor.developer.controller;

import com.example.videoeditor.dto.ElementDto;
import com.example.videoeditor.developer.service.GlobalElementService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.util.List;

@RestController
@RequestMapping("/api")
public class GlobalController {
    private final GlobalElementService globalElementService;

    private String globalElementsDirectory = "elements/";

    public GlobalController(GlobalElementService globalElementService) {
        this.globalElementService = globalElementService;
    }

    @GetMapping("/global-elements")
    public ResponseEntity<List<ElementDto>> getGlobalElements() {
        List<ElementDto> elements = globalElementService.getGlobalElements();
        return ResponseEntity.ok(elements);
    }

    @GetMapping("/global-elements/{filename:.+}")
    public ResponseEntity<Resource> serveElement(@PathVariable String filename) {
        try {
            File file = new File(globalElementsDirectory, filename);
            if (!file.exists() || !file.isFile()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
            }

            Resource resource = new FileSystemResource(file);
            String contentType = determineContentType(filename);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(resource);
        } catch (Exception e) {
            System.err.println("Error serving element: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null);
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