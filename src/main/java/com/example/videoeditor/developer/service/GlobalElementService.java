package com.example.videoeditor.developer.service;

import com.example.videoeditor.developer.entity.Developer;
import com.example.videoeditor.developer.repository.DeveloperRepository;
import com.example.videoeditor.developer.entity.GlobalElement;
import com.example.videoeditor.developer.repository.GlobalElementRepository;
import com.example.videoeditor.dto.ElementDto;
import com.example.videoeditor.service.S3Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GlobalElementService {
    private final GlobalElementRepository globalElementRepository;
    private final DeveloperRepository developerRepository;
    private final ObjectMapper objectMapper;
    private final S3Service s3Service;

    public GlobalElementService(GlobalElementRepository globalElementRepository, DeveloperRepository developerRepository, ObjectMapper objectMapper, S3Service s3Service) {
        this.globalElementRepository = globalElementRepository;
        this.developerRepository = developerRepository;
        this.objectMapper = objectMapper;
        this.s3Service = s3Service;
    }

    @Transactional
    public List<ElementDto> uploadGlobalElements(MultipartFile[] files, String title, String type, String category, String username) throws IOException {
        Developer developer = developerRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Developer not found"));

        List<ElementDto> elements = new ArrayList<>();

        for (MultipartFile file : files) {
            String originalFileName = file.getOriginalFilename();
            if (originalFileName == null || !isValidFileType(originalFileName)) {
                throw new RuntimeException("Invalid file type. Only PNG, JPEG, GIF, or WEBP allowed.");
            }

            // Handle filename conflicts
            String fileName = originalFileName;
            String s3Key = "elements/" + fileName;
            int counter = 1;
            while (s3Service.fileExists(s3Key)) {
                String baseName = originalFileName.substring(0, originalFileName.lastIndexOf('.'));
                String extension = originalFileName.substring(originalFileName.lastIndexOf('.'));
                fileName = baseName + "_" + counter + extension;
                s3Key = "elements/" + fileName;
                counter++;
            }

            // Upload to S3 (fixed parameter order)
            s3Service.uploadFile(file, s3Key);

            // Create JSON for globalElement_json
            Map<String, String> elementData = new HashMap<>();
            elementData.put("imagePath", s3Key);
            elementData.put("imageFileName", fileName);
            String json = objectMapper.writeValueAsString(elementData);

            GlobalElement element = new GlobalElement();
            element.setGlobalElementJson(json);
            globalElementRepository.save(element);

            ElementDto dto = new ElementDto();
            dto.setId(element.getId().toString());
            dto.setFilePath(s3Key);
            dto.setFileName(fileName);
            elements.add(dto);
        }

        return elements;
    }

    public List<ElementDto> getGlobalElements() {
        return globalElementRepository.findAll().stream()
                .map(this::toElementDto)
                .collect(Collectors.toList());
    }

    private ElementDto toElementDto(GlobalElement globalElement) {
        try {
            Map<String, String> jsonData = objectMapper.readValue(
                    globalElement.getGlobalElementJson(),
                    new TypeReference<Map<String, String>>() {}
            );
            ElementDto dto = new ElementDto();
            dto.setId(globalElement.getId().toString());
            dto.setFilePath(jsonData.get("imagePath"));
            dto.setFileName(jsonData.get("imageFileName"));
            return dto;
        } catch (IOException e) {
            throw new RuntimeException("Error parsing globalElement_json: " + e.getMessage());
        }
    }

    private boolean isValidFileType(String fileName) {
        String lowerCase = fileName.toLowerCase();
        return lowerCase.endsWith(".png") || lowerCase.endsWith(".jpg") ||
                lowerCase.endsWith(".jpeg") || lowerCase.endsWith(".gif") ||
                lowerCase.endsWith(".webp");
    }
}