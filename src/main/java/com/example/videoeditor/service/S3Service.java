package com.example.videoeditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class S3Service {
    private static final Logger logger = LoggerFactory.getLogger(S3Service.class);
    private static final Pattern S3_KEY_PATTERN = Pattern.compile("^[a-zA-Z0-9._/-]+$");

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    @Value("${aws.s3.bucket}")
    private String bucketName;

    public S3Service(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    public String uploadFile(String key, MultipartFile file) throws IOException {
        validateS3Key(key);
        File tempFile = null;
        try {
            tempFile = convertMultipartFileToFile(file);
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .contentType(file.getContentType())
                    .build();
            s3Client.putObject(request, RequestBody.fromFile(tempFile));
            logger.info("Successfully uploaded file to S3: bucket={}, key={}", bucketName, key);
            return key;
        } catch (SdkException e) {
            logger.error("Failed to upload file to S3: bucket={}, key={}", bucketName, key, e);
            throw new IOException("Failed to upload file to S3: " + key, e);
        } finally {
            cleanupTempFile(tempFile);
        }
    }

    public String uploadFile(String key, File file) throws IOException {
        validateS3Key(key);
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
            logger.info("Successfully uploaded file to S3: bucket={}, key={}", bucketName, key);
            return key;
        } catch (SdkException e) {
            logger.error("Failed to upload file to S3: bucket={}, key={}", bucketName, key, e);
            throw new IOException("Failed to upload file to S3: " + key, e);
        }
    }

    public File downloadFile(String key) throws IOException {
        validateS3Key(key);
        File tempFile = null;
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            tempFile = Files.createTempFile("s3_" + UUID.randomUUID(), ".tmp").toFile();
            try (var response = s3Client.getObject(request)) {
                Files.copy(response, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            logger.info("Successfully downloaded file from S3: bucket={}, key={}, tempFile={}", bucketName, key, tempFile.getAbsolutePath());
            return tempFile;
        } catch (NoSuchKeyException e) {
            logger.error("File not found in S3: bucket={}, key={}", bucketName, key, e);
            throw new IOException("File not found in S3: " + key, e);
        } catch (SdkException | IOException e) {
            logger.error("Failed to download file from S3: bucket={}, key={}", bucketName, key, e);
            cleanupTempFile(tempFile);
            throw new IOException("Failed to download file from S3: " + key, e);
        }
    }

    public boolean deleteFile(String key) {
        validateS3Key(key);
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.deleteObject(request);
            logger.info("Successfully deleted file from S3: bucket={}, key={}", bucketName, key);
            return true;
        } catch (SdkException e) {
            logger.error("Failed to delete file from S3: bucket={}, key={}", bucketName, key, e);
            return false;
        }
    }

    public boolean fileExists(String key) {
        validateS3Key(key);
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build();
            s3Client.headObject(request);
            logger.debug("S3 object exists: bucket={}, key={}", bucketName, key);
            return true;
        } catch (NoSuchKeyException e) {
            logger.debug("S3 object does not exist: bucket={}, key={}", bucketName, key);
            return false;
        } catch (SdkException e) {
            logger.error("Failed to check if S3 object exists: bucket={}, key={}", bucketName, key, e);
            return false;
        }
    }

    public String generatePresignedUrl(String s3Key) throws IOException {
        validateS3Key(s3Key); // Fixed: Changed 'key' to 's3Key'
        try {
            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(r -> r
                    .getObjectRequest(g -> g.bucket(bucketName).key(s3Key))
                    .signatureDuration(Duration.ofMinutes(15)));
            String url = presignedRequest.url().toString();
            logger.info("Generated pre-signed URL for S3: bucket={}, key={}", bucketName, s3Key);
            return url;
        } catch (SdkException e) {
            logger.error("Failed to generate pre-signed URL for S3: bucket={}, key={}", bucketName, s3Key, e);
            throw new IOException("Failed to generate pre-signed URL for S3: " + s3Key, e);
        }
    }

    private File convertMultipartFileToFile(MultipartFile file) throws IOException {
        File tempFile = Files.createTempFile("upload_" + UUID.randomUUID(), ".tmp").toFile();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(file.getBytes());
        }
        logger.debug("Converted MultipartFile to temporary file: {}", tempFile.getAbsolutePath());
        return tempFile;
    }

    private void validateS3Key(String key) {
        if (key == null || !S3_KEY_PATTERN.matcher(key).matches()) {
            logger.error("Invalid S3 key: {}", key);
            throw new IllegalArgumentException("Invalid S3 key: " + key);
        }
    }

    private void cleanupTempFile(File tempFile) {
        if (tempFile != null && tempFile.exists()) {
            try {
                Files.delete(tempFile.toPath());
                logger.debug("Deleted temporary file: {}", tempFile.getAbsolutePath());
            } catch (IOException e) {
                logger.warn("Failed to delete temporary file: {}", tempFile.getAbsolutePath(), e);
            }
        }
    }
}