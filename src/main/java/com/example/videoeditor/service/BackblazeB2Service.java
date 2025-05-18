package com.example.videoeditor.service;

import com.backblaze.b2.client.B2StorageClient;
import com.backblaze.b2.client.B2StorageClientFactory;
import com.backblaze.b2.client.contentHandlers.B2ContentFileWriter;
import com.backblaze.b2.client.contentSources.B2FileContentSource;
import com.backblaze.b2.client.exceptions.B2Exception;
import com.backblaze.b2.client.structures.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Profile("!test") // Only activate this service in non-test profiles
public class BackblazeB2Service {
    private static final Logger logger = LoggerFactory.getLogger(BackblazeB2Service.class);
    private static final long LARGE_FILE_THRESHOLD = 200 * 1024 * 1024; // 200 MB
    private static final int BUFFER_SIZE = 8192; // 8KB buffer for I/O

    private B2StorageClient b2Client;
    private String bucketId; // Store the bucket ID
    private final ExecutorService executorService;

    @Value("${B2_APPLICATION_KEY_ID}")
    private String applicationKeyId;

    @Value("${B2_APPLICATION_KEY}")
    private String applicationKey;

    @Value("${B2_BUCKET_NAME}")
    private String bucketName;

    public BackblazeB2Service() {
        this.executorService = Executors.newFixedThreadPool(4); // For large file uploads
    }

    @PostConstruct
    public void init() {
        logger.info("Initializing BackblazeB2Service with Application Key ID: {}, Bucket: {}",
                applicationKeyId, bucketName);

        if (applicationKeyId == null || applicationKeyId.isBlank() ||
                applicationKey == null || applicationKey.isBlank() ||
                bucketName == null || bucketName.isBlank()) {
            logger.error("Backblaze B2 credentials or bucket name are missing or blank");
            throw new IllegalStateException("Backblaze B2 configuration cannot be blank");
        }

        // Initialize B2 client
        this.b2Client = B2StorageClientFactory
                .createDefaultFactory()
                .create(applicationKeyId, applicationKey, "video-editor");

        // Get account ID first
        try {
            B2AccountAuthorization accountAuth = b2Client.getAccountAuthorization();
            String accountId = accountAuth.getAccountId();

            // Get bucket ID from bucket name using account ID
            B2ListBucketsRequest request = B2ListBucketsRequest.builder(accountId)  // Add account ID here
                    .setBucketName(bucketName)
                    .build();

            B2ListBucketsResponse response = b2Client.listBuckets(request);
            if (!response.getBuckets().isEmpty()) {
                this.bucketId = response.getBuckets().get(0).getBucketId();
                logger.info("Found bucket ID '{}' for bucket '{}'", bucketId, bucketName);
            } else {
                logger.error("No buckets found with name: {}", bucketName);
                throw new RuntimeException("Bucket not found: " + bucketName);
            }
        } catch (B2Exception e) {
            logger.error("Failed to retrieve bucket ID: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize BackBlaze B2 client", e);
        }
    }

    public File downloadFile(String b2Path, String destinationPath) throws IOException, B2Exception {
        try {
            File destinationFile = new File(destinationPath);
            // Ensure parent directory exists
            File parentDir = destinationFile.getParentFile();
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                throw new IOException("Failed to create parent directories for: " + destinationPath);
            }

            B2ContentFileWriter writer = B2ContentFileWriter.builder(destinationFile).build();
            b2Client.downloadByName(bucketName, b2Path, writer);

            logger.info("Downloaded file from B2: {}/{} to {}", bucketName, b2Path, destinationPath);
            return destinationFile;
        } catch (B2Exception e) {
            logger.error("Failed to download file from B2: {}/{}, error: {}", bucketName, b2Path, e.getMessage());
            throw e; // Re-throw B2Exception for VideoEditingService
        }
    }

    public File saveMultipartFileToTemp(MultipartFile file, String tempPath) throws IOException {
        try {
            File tempFile = new File(tempPath);
            // Ensure parent directory exists
            File parentDir = tempFile.getParentFile();
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                throw new IOException("Failed to create parent directories for: " + tempPath);
            }
            // Transfer MultipartFile to temporary file
            file.transferTo(tempFile);
            logger.debug("Saved MultipartFile to temp: {}", tempPath);
            return tempFile;
        } catch (IOException e) {
            logger.error("Failed to save MultipartFile to temp: {}, error: {}", tempPath, e.getMessage());
            throw e;
        }
    }

    public void uploadFile(String b2Path, File file) throws IOException, B2Exception {
        try {
            // Check if bucketId is available
            if (bucketId == null || bucketId.isBlank()) {
                throw new IllegalStateException("Bucket ID not initialized");
            }

            String contentType = Files.probeContentType(file.toPath());
            if (contentType == null) {
                contentType = "video/mp4"; // Default for video files
            }

            B2FileContentSource source = B2FileContentSource.build(file);
            B2UploadFileRequest request = B2UploadFileRequest
                    .builder(bucketId, b2Path, contentType, source) // Using bucketId instead of bucketName
                    .build();

            if (file.length() > LARGE_FILE_THRESHOLD) {
                logger.debug("Using large file upload for file: {}", file.getName());
                b2Client.uploadLargeFile(request, executorService);
            } else {
                b2Client.uploadSmallFile(request);
            }

            logger.info("Uploaded file to B2: {}/{}", bucketName, b2Path);
        } catch (B2Exception e) {
            logger.error("Failed to upload file to B2: {}/{}, error: {}", bucketName, b2Path, e.getMessage(), e);
            throw e;
        }
    }

    public String uploadFile(MultipartFile file, String b2Path) throws IOException, B2Exception {
        try {
            // Check if bucketId is available
            if (bucketId == null || bucketId.isBlank()) {
                throw new IllegalStateException("Bucket ID not initialized");
            }

            File tempFile = File.createTempFile("b2-upload-", file.getOriginalFilename());
            file.transferTo(tempFile);

            String contentType = file.getContentType() != null ? file.getContentType() : "video/mp4";
            B2FileContentSource source = B2FileContentSource.build(tempFile);
            B2UploadFileRequest request = B2UploadFileRequest
                    .builder(bucketId, b2Path, contentType, source) // Using bucketId instead of bucketName
                    .build();

            if (tempFile.length() > LARGE_FILE_THRESHOLD) {
                logger.debug("Using large file upload for file: {}", file.getOriginalFilename());
                b2Client.uploadLargeFile(request, executorService);
            } else {
                b2Client.uploadSmallFile(request);
            }

            logger.debug("Uploaded MultipartFile to B2: bucket={}, path={}", bucketName, b2Path);
            tempFile.delete();
            return b2Path;
        } catch (B2Exception e) {
            logger.error("Failed to upload MultipartFile to B2: path={}, error={}", b2Path, e.getMessage(), e);
            throw e;
        }
    }

    public String uploadFile(File file, String b2Path) throws IOException, B2Exception {
        uploadFile(b2Path, file);
        return b2Path;
    }

    public void deleteFile(String b2Path) throws B2Exception {
        try {
            B2ListFileVersionsRequest request = B2ListFileVersionsRequest
                    .builder(bucketName)
                    .setStartFileName(b2Path)
                    .build();

            for (B2FileVersion version : b2Client.fileVersions(request)) {
                if (version.getFileName().equals(b2Path)) {
                    B2DeleteFileVersionRequest deleteRequest = B2DeleteFileVersionRequest
                            .builder(version.getFileName(), version.getFileId())
                            .build();
                    b2Client.deleteFileVersion(deleteRequest);
                    logger.debug("Deleted file version from B2: {}/{}, ID: {}",
                            bucketName, b2Path, version.getFileId());
                }
            }
            logger.debug("Deleted file from B2: bucket={}, path={}", bucketName, b2Path);
        } catch (B2Exception e) {
            logger.error("Failed to delete file from B2: path={}, error={}", b2Path, e.getMessage());
            throw e;
        }
    }

    public void deleteDirectory(String prefix) throws B2Exception {
        try {
            B2ListFileVersionsRequest request = B2ListFileVersionsRequest
                    .builder(bucketName)
                    .setPrefix(prefix)
                    .build();

            for (B2FileVersion version : b2Client.fileVersions(request)) {
                B2DeleteFileVersionRequest deleteRequest = B2DeleteFileVersionRequest
                        .builder(version.getFileName(), version.getFileId())
                        .build();
                b2Client.deleteFileVersion(deleteRequest);
                logger.debug("Deleted file version from B2: {}/{}, ID: {}",
                        bucketName, version.getFileName(), version.getFileId());
            }
            logger.debug("Deleted B2 directory: bucket={}, prefix={}", bucketName, prefix);
        } catch (B2Exception e) {
            logger.error("Failed to delete B2 directory: prefix={}, error={}", prefix, e.getMessage());
            throw e;
        }
    }

    public boolean fileExists(String b2Path) {
        try {
            B2ListFileVersionsRequest request = B2ListFileVersionsRequest
                    .builder(bucketName)
                    .setStartFileName(b2Path)
                    .setMaxFileCount(1)
                    .build();
            for (B2FileVersion version : b2Client.fileVersions(request)) {
                if (version.getFileName().equals(b2Path)) {
                    logger.debug("File exists in B2: bucket={}, path={}", bucketName, b2Path);
                    return true;
                }
            }
            return false;
        } catch (B2Exception e) {
            logger.error("Error checking file existence in B2: path={}, error={}", b2Path, e.getMessage());
            return false;
        }
    }
}