package com.modocument.api.security;

import com.modocument.api.IsolatedStorageApiTestSupport;
import com.modocument.domain.entity.AppUser;
import com.modocument.domain.entity.Template;
import com.modocument.domain.model.Role;
import com.modocument.infrastructure.persistence.AppUserJpaRepository;
import com.modocument.infrastructure.persistence.TemplateJpaRepository;
import com.modocument.infrastructure.persistence.UploadedFileJpaRepository;
import com.modocument.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Upload content gates (SEC-030 + C6 duplicate blocking) over real HTTP
 * semantics:
 *
 * <ul>
 *   <li>re-uploading identical content under the same name is a 409 (the
 *       earlier upload already serves every purpose of the new one);</li>
 *   <li>the same content under a different name stays allowed — deliberately,
 *       a renamed copy is a new upload;</li>
 *   <li>content must match the claimed extension: a PNG renamed .xlsx and a
 *       binary disguised as .csv are 400s, on both upload endpoints;</li>
 *   <li>legitimate files of each format family sail through.</li>
 * </ul>
 */
@Transactional
class UploadContentGateSec030Test extends IsolatedStorageApiTestSupport {

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AppUserJpaRepository users;
    @Autowired
    private TemplateJpaRepository templates;
    @Autowired
    private UploadedFileJpaRepository uploadedFiles;
    @Autowired
    private JwtService jwtService;

    private AppUser uploader;
    private String auth;

    @BeforeEach
    void uploaderUser() {
        AppUser entity = new AppUser();
        entity.setEmail("sec030-" + UUID.randomUUID() + "@example.com");
        entity.setPasswordHash("{noop}irrelevant");
        entity.setDisplayName("sec030");
        entity.setBankId(1);
        entity.setRole(Role.READ_WRITE);
        entity.setSettingsJson("{}");
        uploader = users.save(entity);
        auth = "Bearer " + jwtService.issueToken(uploader);
    }

    private MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    private static byte[] text(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------ duplicate blocking ----

    @Test
    void reUploadingIdenticalContentUnderTheSameNameIs409() throws Exception {
        MockMultipartFile upload = file("ledger.csv", "text/csv", text("a,b\n1,2\n"));

        mvc.perform(multipart("/api/fileupload/upload").file(upload).header("Authorization", auth))
                .andExpect(status().isOk());

        mvc.perform(multipart("/api/fileupload/upload").file(upload).header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("already uploaded")));
    }

    @Test
    void sameContentUnderADifferentNameIsDeliberatelyAllowed() throws Exception {
        byte[] content = text("a,b\n1,2\n");

        mvc.perform(multipart("/api/fileupload/upload")
                        .file(file("original.csv", "text/csv", content))
                        .header("Authorization", auth))
                .andExpect(status().isOk());
        // A renamed copy is a new upload on purpose (see UploadOwnership).
        mvc.perform(multipart("/api/fileupload/upload")
                        .file(file("renamed-copy.csv", "text/csv", content))
                        .header("Authorization", auth))
                .andExpect(status().isOk());
    }

    @Test
    void duplicateGateCoversTheIngestionMultipartPathToo() throws Exception {
        MockMultipartFile upload = file("jobs.csv", "text/csv", text("x,y\n1,2\n"));
        int templateId = ownedTemplate();

        mvc.perform(multipart("/api/ingestion/jobs").file(upload)
                        .param("templateId", String.valueOf(templateId))
                        .param("autoRun", "false")
                        .header("Authorization", auth))
                .andExpect(status().isCreated());

        mvc.perform(multipart("/api/ingestion/jobs").file(upload)
                        .param("templateId", String.valueOf(templateId))
                        .param("autoRun", "false")
                        .header("Authorization", auth))
                .andExpect(status().isConflict());
    }

    // ----------------------------------------------------- magic bytes ----

    @Test
    void pngRenamedToXlsxIsRejectedOnBothUploadEndpoints() throws Exception {
        mvc.perform(multipart("/api/fileupload/upload")
                        .file(file("sheet.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                PNG_BYTES))
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("does not match")));

        mvc.perform(multipart("/api/ingestion/jobs")
                        .file(file("sheet.xlsx", "application/octet-stream", PNG_BYTES))
                        .param("templateId", String.valueOf(ownedTemplate()))
                        .param("autoRun", "false")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
    }

    @Test
    void binaryDisguisedAsCsvIsRejected() throws Exception {
        byte[] exe = new byte[256]; // MZ header + NULs: binary, not text
        exe[0] = 'M';
        exe[1] = 'Z';
        mvc.perform(multipart("/api/fileupload/upload")
                        .file(file("innocent.csv", "text/csv", exe))
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString(".csv")));
    }

    @Test
    void legitimateFilesOfEachFamilyPass() throws Exception {
        record Sample(String name, byte[] content) {
        }
        Sample[] samples = {
                new Sample("fine.csv", text("col\nval\n")),
                new Sample("fine.txt", text("notes")),
                new Sample("fine.xlsx", new byte[] {'P', 'K', 3, 4, 0, 0}),
                new Sample("fine.xls", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1}),
                new Sample("fine.pdf", text("%PDF-1.7\n%%EOF")),
                new Sample("fine.png", PNG_BYTES),
        };
        for (Sample sample : samples) {
            mvc.perform(multipart("/api/fileupload/upload")
                            .file(file(sample.name(), "application/octet-stream", sample.content()))
                            .header("Authorization", auth))
                    .andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------ hash ----

    @Test
    void everyStoredUploadRecordsItsSha256() throws Exception {
        mvc.perform(multipart("/api/fileupload/upload")
                        .file(file("hashed.csv", "text/csv", text("a\n1\n")))
                        .header("Authorization", auth))
                .andExpect(status().isOk());

        var row = uploadedFiles.findFirstByOwnerUserIdAndSha256HexAndOriginalName(
                uploader.getAppUserId(),
                com.modocument.domain.model.Hashing.sha256(text("a\n1\n")),
                "hashed.csv");
        org.assertj.core.api.Assertions.assertThat(row).isPresent();
    }

    private int ownedTemplate() {
        Template template = new Template(0, "SEC030-" + UUID.randomUUID(), "d");
        template.setOwnerUserId(uploader.getAppUserId());
        template.setBankId(uploader.getBankId());
        return templates.save(template).getTemplateId();
    }
}
