package com.example.videoeditor.developer.service;

import com.example.videoeditor.developer.entity.Developer;
import com.example.videoeditor.developer.repository.DeveloperRepository;
import com.example.videoeditor.developer.entity.GlobalElement;
import com.example.videoeditor.developer.repository.GlobalElementRepository;
import com.example.videoeditor.dto.ElementDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
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

    private String globalElementsDirectory = "/Users/nimitpatel/Desktop/VideoEditor 2/elements";

    public GlobalElementService(GlobalElementRepository globalElementRepository, DeveloperRepository developerRepository, ObjectMapper objectMapper) {
        this.globalElementRepository = globalElementRepository;
        this.developerRepository = developerRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<ElementDto> uploadGlobalElements(MultipartFile[] files, String title, String type, String category, String username) throws IOException {
        Developer developer = developerRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Developer not found"));

        List<ElementDto> elements = new ArrayList<>();
        File directory = new File(globalElementsDirectory);
        System.out.println("Saving to directory: " + directory.getAbsolutePath());
        if (!directory.exists()) {
            System.out.println("Creating directory: " + directory.getAbsolutePath());
            boolean created = directory.mkdirs();
            System.out.println("Directory created: " + created);
        }

        for (MultipartFile file : files) {
            String originalFileName = file.getOriginalFilename();
            if (originalFileName == null || !isValidFileType(originalFileName)) {
                throw new RuntimeException("Invalid file type. Only PNG, JPEG, GIF, or WEBP allowed.");
            }

            // Handle filename conflicts
            String fileName = originalFileName;
            File destFile = new File(directory, fileName);
            int counter = 1;
            while (destFile.exists()) {
                String baseName = originalFileName.substring(0, originalFileName.lastIndexOf('.'));
                String extension = originalFileName.substring(originalFileName.lastIndexOf('.'));
                fileName = baseName + "_" + counter + extension; // e.g., example_1.png
                destFile = new File(directory, fileName);
                counter++;
            }

            System.out.println("Writing file: " + destFile.getAbsolutePath());
            file.transferTo(destFile);

            // Create JSON for globalElement_json
            Map<String, String> elementData = new HashMap<>();
            elementData.put("imagePath", "elements/" + fileName);
            elementData.put("imageFileName", fileName);
            String json = objectMapper.writeValueAsString(elementData); // {"filePath": "elements/fileName", "fileName": "fileName"}

            GlobalElement element = new GlobalElement();
            element.setGlobalElementJson(json);
            globalElementRepository.save(element);

            ElementDto dto = new ElementDto();
            dto.setId(element.getId().toString());
            dto.setFilePath("elements/" + fileName);
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
            // Parse globalElement_json
            Map<String, String> jsonData = objectMapper.readValue(
                    globalElement.getGlobalElementJson(),
                    new TypeReference<Map<String, String>>() {}
            );
            ElementDto dto = new ElementDto();
            dto.setId(globalElement.getId().toString());
            dto.setFilePath(jsonData.get("imagePath")); // elements/fileName
            dto.setFileName(jsonData.get("imageFileName")); // fileName
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