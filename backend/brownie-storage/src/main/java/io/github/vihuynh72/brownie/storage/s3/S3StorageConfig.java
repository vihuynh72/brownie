package io.github.vihuynh72.brownie.storage.s3;

import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.retention.DeletionLedgerArchive;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.*;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import java.net.URI;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "brownie.storage.provider", havingValue = "s3")
public class S3StorageConfig {
    @Bean(destroyMethod = "close")
    S3Client filesS3Client(@Value("${BROWNIE_S3_ENDPOINT}") String endpoint,
            @Value("${BROWNIE_S3_REGION:auto}") String region,
            @Value("${BROWNIE_S3_ACCESS_KEY_ID}") String key,
            @Value("${BROWNIE_S3_SECRET_ACCESS_KEY}") String secret) {
        return client(endpoint, region, key, secret);
    }

    @Bean(destroyMethod = "close")
    S3Client archiveS3Client(@Value("${BROWNIE_ARCHIVE_S3_ENDPOINT}") String endpoint,
            @Value("${BROWNIE_ARCHIVE_S3_REGION:auto}") String region,
            @Value("${BROWNIE_ARCHIVE_S3_ACCESS_KEY_ID}") String key,
            @Value("${BROWNIE_ARCHIVE_S3_SECRET_ACCESS_KEY}") String secret,
            @Value("${BROWNIE_S3_ACCESS_KEY_ID}") String filesKey,
            @Value("${BROWNIE_S3_BUCKET}") String filesBucket,
            @Value("${BROWNIE_ARCHIVE_S3_BUCKET}") String archiveBucket) {
        if (key.equals(filesKey) || archiveBucket.equals(filesBucket)) {
            throw new IllegalStateException("Files and the deletion archive need separate buckets and credentials.");
        }
        return client(endpoint, region, key, secret);
    }

    static S3Client client(String endpoint, String region, String key, String secret) {
        URI uri = URI.create(endpoint);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("S3 requires an HTTPS endpoint without credentials in its URL.");
        }
        if (key.isBlank() || secret.isBlank()) throw new IllegalArgumentException("S3 credentials are required.");
        return S3Client.builder().endpointOverride(uri).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30)))
                .build();
    }

    @Bean
    BlobStore blobStore(@Qualifier("filesS3Client") S3Client client, @Value("${BROWNIE_S3_BUCKET}") String bucket) {
        return new S3BlobStore(client, bucket);
    }

    @Bean
    DeletionLedgerArchive deletionLedgerArchive(@Qualifier("archiveS3Client") S3Client client,
            @Value("${BROWNIE_ARCHIVE_S3_BUCKET}") String bucket) {
        return new S3DeletionLedgerArchive(new S3BlobStore(client, bucket));
    }

    @Bean
    HealthIndicator fileStorageHealthIndicator(@Qualifier("filesS3Client") S3Client client,
            @Value("${BROWNIE_S3_BUCKET}") String bucket) { return health(client, bucket); }

    @Bean
    HealthIndicator deletionRecordStorageHealthIndicator(@Qualifier("archiveS3Client") S3Client client,
            @Value("${BROWNIE_ARCHIVE_S3_BUCKET}") String bucket) { return health(client, bucket); }

    private static HealthIndicator health(S3Client client, String bucket) {
        return () -> {
            try {
                client.headBucket(r -> r.bucket(bucket));
                return Health.up().build();
            } catch (RuntimeException e) { return Health.down().build(); }
        };
    }
}
