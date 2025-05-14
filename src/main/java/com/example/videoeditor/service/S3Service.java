package com.example.videoeditor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

@Service
public class S3Service {
    private static final Logger logger = LoggerFactory.getLogger(S3Service.class);

    private final S3Client s3Client;
    private final String bucketName;

    public S3Service(
            @Value("${aws.access-key-id}") String accessKeyId,
            @Value("${aws.secret-access-key}") String secretAccessKey,
            @Value("${aws.region}") String region,
            @Value("${aws.s3.bucket}") String bucketName) {
        this.bucketName = bucketName;
        logger.info("Initializing S3Service with Access Key ID: {}, Region: {}, Bucket: {}",
                accessKeyId, region, bucketName);
        if (accessKeyId == null || accessKeyId.isBlank() || secretAccessKey == null || secretAccessKey.isBlank()) {
            logger.error("AWS credentials are missing or blank");
            throw new IllegalStateException("AWS credentials cannot be blank");
        }
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .build();
    }

    @PostConstruct
    public void init() {
        logger.info("S3Service initialized with bucket: {}", bucketName);
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            logger.info("S3 bucket {} is accessible", bucketName);
        } catch (Exception e) {
            logger.error("Failed to access S3 bucket {}: {}", bucketName, e.getMessage());
            throw new IllegalStateException("Cannot access S3 bucket: " + bucketName, e);
        }
    }

    public File downloadFile(String s3Key) throws IOException {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            Path tempFile = Files.createTempFile("s3-", s3Key.replaceAll("[^a-zA-Z0-9.]", "-"));
            s3Client.getObject(getObjectRequest, tempFile);
            return tempFile.toFile();
        } catch (Exception e) {
            logger.error("Failed to download file from S3: {}/{}, error: {}", bucketName, s3Key, e.getMessage());
            throw new IOException("Failed to download file from S3: " + s3Key, e);
        }
    }

    public void uploadFile(String s3Key, File file) throws IOException {
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            s3Client.putObject(putObjectRequest, RequestBody.fromFile(file));
            logger.info("Uploaded file to S3: {}/{}", bucketName, s3Key);
        } catch (Exception e) {
            logger.error("Failed to upload file to S3: {}/{}, error: {}", bucketName, s3Key, e.getMessage());
            throw new IOException("Failed to upload file to S3: " + s3Key, e);
        }
    }

    public String uploadFile(MultipartFile file, String s3Key) throws IOException {
        try {
            File tempFile = File.createTempFile("s3-upload-", file.getOriginalFilename());
            file.transferTo(tempFile);

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(file.getContentType())
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromFile(tempFile));
            logger.debug("Uploaded MultipartFile to S3: bucket={}, key={}", bucketName, s3Key);

            tempFile.delete();
            return s3Key;
        } catch (S3Exception e) {
            logger.error("Failed to upload MultipartFile to S3: key={}, error={}", s3Key, e.getMessage());
            throw new IOException("Failed to upload file to S3", e);
        }
    }

    public String uploadFile(File file, String s3Key) throws IOException {
        try {
            String contentType = Files.probeContentType(file.toPath());
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .contentType(contentType)
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromFile(file));
            logger.debug("Uploaded File to S3: bucket={}, key={}", bucketName, s3Key);
            return s3Key;
        } catch (S3Exception e) {
            logger.error("Failed to upload File to S3: key={}, error={}", s3Key, e.getMessage());
            throw new IOException("Failed to upload file to S3", e);
        }
    }

    public void deleteFile(String s3Key) {
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();

            s3Client.deleteObject(deleteRequest);
            logger.debug("Deleted file from S3: bucket={}, key={}", bucketName, s3Key);
        } catch (S3Exception e) {
            logger.error("Failed to delete file from S3: key={}, error={}", s3Key, e.getMessage());
            throw new RuntimeException("Failed to delete file from S3", e);
        }
    }

    public void deleteDirectory(String prefix) {
        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .build();

            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
            for (S3Object s3Object : listResponse.contents()) {
                deleteFile(s3Object.key());
            }
            logger.debug("Deleted S3 directory: bucket={}, key={}", bucketName, prefix);
        } catch (S3Exception e) {
            logger.error("Failed to delete S3 directory: prefix={}, error={}", prefix, e.getMessage());
            throw new RuntimeException("Failed to delete S3 directory", e);
        }
    }

    public boolean fileExists(String s3Key) {
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(s3Key)
                    .build();
            s3Client.headObject(headRequest);
            logger.debug("File exists in S3: bucket={}, key={}", bucketName, s3Key);
            return true;
        } catch (S3Exception e) {
            logger.error("Error checking file existence in S3: key={}, error={}", s3Key, e.getMessage());
            return false;
        }
    }
}