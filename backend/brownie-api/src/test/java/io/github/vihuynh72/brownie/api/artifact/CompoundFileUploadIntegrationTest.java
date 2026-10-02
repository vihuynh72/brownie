package io.github.vihuynh72.brownie.api.artifact;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.ContentInspection;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.prepare.ConvertibleFormat;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Word 97-2003 file, and an encrypted Word file, through the artifact
 * service the application wires, against real storage and the real
 * scanner: the compound-file reader is the one plugged in, not the one
 * that refuses every such file. A compound file is larger than the upload
 * test's deliberately tiny limit, so this runs with the ordinary one.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class CompoundFileUploadIntegrationTest {

    private static final String ISSUER = "https://issuer-compound-file-upload";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> "brownie_api_local_only");
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> "brownie_migration_local_only");
        registry.add("brownie.storage.local-connection", DB::azuriteConnectionString);
        registry.add("brownie.security.clamav.host", SharedContainers::clamAvHost);
        registry.add("brownie.security.clamav.port", SharedContainers::clamAvPort);
    }

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    void aWord97FileIsAcceptedAsADocAndReadAgainAsTheSameFormat() throws Exception {
        long userId = user("word-97");
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();

        Artifact allocated = artifactService.initiateUpload(workspaceId, userId, "Lease.doc");
        Artifact received = artifactService.receiveContent(workspaceId, userId, allocated.id(), new ByteArrayInputStream(wordDocument()));
        assertThat(received.detectedMediaType()).isEqualTo(SupportedMediaType.DOC);

        Artifact ready = artifactService.finalizeUpload(workspaceId, userId, allocated.id());
        assertThat(ready.status()).isEqualTo(ArtifactStatus.READY);
        assertThat(artifactService.inspectContent(workspaceId, userId, allocated.id()))
                .isEqualTo(new ContentInspection(SupportedMediaType.DOC, ConvertibleFormat.WORD_97));
    }

    @Test
    void anEncryptedWordFileIsRefusedAsLockedAndNotKept() throws Exception {
        long userId = user("locked");
        long workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();

        Artifact allocated = artifactService.initiateUpload(workspaceId, userId, "Lease.docx");
        assertThatThrownBy(() -> artifactService.receiveContent(workspaceId, userId, allocated.id(), new ByteArrayInputStream(encryptedDocx())))
                .isInstanceOfSatisfying(UnsupportedArtifactTypeException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(UnsupportedArtifactTypeException.Reason.PASSWORD_PROTECTED));
    }

    private long user(String subject) {
        userIdentityRepository.recordLogin(ISSUER, subject, null, null);
        return userIdentityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow().id();
    }

    /** A container holding a Word document stream whose header says Word 97, as the probe reads it. */
    private static byte[] wordDocument() throws Exception {
        byte[] stream = new byte[4096];
        stream[0] = (byte) 0xEC;
        stream[1] = (byte) 0xA5;
        stream[2] = (byte) 0xC1;
        try (POIFSFileSystem container = new POIFSFileSystem()) {
            container.getRoot().createDocument("WordDocument", new ByteArrayInputStream(stream));
            container.getRoot().createDocument("1Table", new ByteArrayInputStream(new byte[512]));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            container.writeFilesystem(out);
            return out.toByteArray();
        }
    }

    private static byte[] encryptedDocx() throws Exception {
        ByteArrayOutputStream docx = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Tenant: ________");
            document.write(docx);
        }
        try (POIFSFileSystem container = new POIFSFileSystem()) {
            Encryptor encryptor = new EncryptionInfo(EncryptionMode.agile).getEncryptor();
            encryptor.confirmPassword("correct horse");
            try (OPCPackage document = OPCPackage.open(new ByteArrayInputStream(docx.toByteArray()));
                    OutputStream encrypting = encryptor.getDataStream(container)) {
                document.save(encrypting);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            container.writeFilesystem(out);
            return out.toByteArray();
        }
    }
}
