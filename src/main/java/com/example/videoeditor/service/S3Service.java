package com.example.videoeditor.service;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;

@Service
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    public S3Service(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    // Upload a MultipartFile to S3
    public String uploadFile(String key, MultipartFile file) throws IOException {
        File tempFile = convertMultipartFileToFile(file);
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(file.getContentType()) // Set content type
                    .build();
            s3Client.putObject(request, RequestBody.fromFile(tempFile));
            System.out.println("Uploaded file to S3: " + key);
            return key;
        } catch (Exception e) {
            System.err.println("Failed to upload MultipartFile to S3: " + key + ", error: " + e.getMessage());
            throw new IOException("Failed to upload file to S3: " + key, e);
        } finally {
            if (tempFile.exists()) {
                try {
                    tempFile.delete();
                    System.out.println("Deleted temporary file: " + tempFile.getAbsolutePath());
                } catch (Exception e) {
                    System.err.println("Failed to delete temporary file: " + tempFile.getAbsolutePath() + ", error: " + e.getMessage());
                }
            }
        }
    }

    // Upload a File to S3
    public String uploadFile(String key, File file) throws IOException {
        try {
            String contentType = Files.probeContentType(file.toPath());
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(contentType)
                    .build();
            s3Client.putObject(request, RequestBody.fromFile(file));
            System.out.println("Uploaded file to S3: " + key);
            return key;
        } catch (Exception e) {
            System.err.println("Failed to upload File to S3: " + key + ", error: " + e.getMessage());
            throw new IOException("Failed to upload file to S3: " + key, e);
        }
    }

    // Download an S3 file to a temporary local file
    public File downloadFile(String key) throws IOException {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
            File tempFile = Files.createTempFile("s3_" + UUID.randomUUID(), ".tmp").toFile();
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                response.transferTo(fos);
            } finally {
                response.close(); // Explicitly close the input stream
            }
            System.out.println("Downloaded file from S3: " + key + " to " + tempFile.getAbsolutePath());
            return tempFile;
        } catch (NoSuchKeyException e) {
            System.err.println("File not found in S3: " + key);
            throw new IOException("File not found in S3: " + key, e);
        } catch (Exception e) {
            System.err.println("Failed to download file from S3: " + key + ", error: " + e.getMessage());
            throw new IOException("Failed to download file from S3: " + key, e);
        }
    }

    // Delete an S3 file
    public boolean deleteFile(String key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.deleteObject(request);
            System.out.println("Deleted file from S3: " + key);
            return true;
        } catch (Exception e) {
            System.err.println("Failed to delete file from S3: " + key + ", error: " + e.getMessage());
            return false;
        }
    }

    // Generate a pre-signed URL for S3 file access
    public String generatePresignedUrl(String s3Key) throws IOException {
        try {
            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(r -> r
                    .getObjectRequest(g -> g.bucket(bucketName).key(s3Key))
                    .signatureDuration(Duration.ofMinutes(15)));
            String url = presignedRequest.url().toString();
            System.out.println("Generated pre-signed URL for S3 key: " + s3Key);
            return url;
        } catch (Exception e) {
            System.err.println("Failed to generate pre-signed URL for S3 key: " + s3Key + ", error: " + e.getMessage());
            throw new IOException("Failed to generate pre-signed URL for S3 key: " + s3Key, e);
        }
    }

    // Convert MultipartFile to File
    private File convertMultipartFileToFile(MultipartFile file) throws IOException {
        File tempFile = Files.createTempFile("upload_" + UUID.randomUUID(), ".tmp").toFile();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(file.getBytes());
        }
        System.out.println("Converted MultipartFile to temporary file: " + tempFile.getAbsolutePath());
        return tempFile;
    }
}