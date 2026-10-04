package com.modocument.infrastructure.transform;

import com.modocument.application.dto.TransformRequest;
import com.modocument.application.port.AiFilesService;
import com.modocument.application.port.DocumentParser;
import com.modocument.application.privacy.PiiTokenizer;
import com.modocument.application.privacy.PrivacyProperties;
import com.modocument.infrastructure.file.StorageProperties;
import com.modocument.infrastructure.file.UploadStorage;
import com.modocument.infrastructure.prompt.PromptFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The transform path resolves its file through UploadStorage.resolve, which
 * confines every name to the uploads folder — a "../" name must die with a
 * clean 400-shaped IllegalArgumentException before any byte is read or any
 * LLM call is made (the old raw uploadsDir.resolve(fileName) happily walked
 * out of the folder).
 */
class TransformServiceImplTest {

    @TempDir
    Path tempDir;

    private static TransformRequest request(String fileName) {
        TransformRequest request = new TransformRequest();
        request.setFileName(fileName);
        return request;
    }

    @Test
    void traversalFileNamesAreRejectedBeforeAnyReadOrLlmCall() throws Exception {
        // A juicy target outside uploads/ that the old code would have read.
        Files.writeString(tempDir.resolve("secrets.yml"), "jwt-secret: oops");
        Files.createDirectories(tempDir.resolve("uploads"));

        PromptFactory prompts = mock(PromptFactory.class);
        AiFilesService ai = mock(AiFilesService.class);
        DocumentParser parser = mock(DocumentParser.class);
        TransformServiceImpl service = new TransformServiceImpl(prompts, ai, parser,
                new UploadStorage(new StorageProperties(tempDir.toString())),
                new PiiTokenizer(new PrivacyProperties()));

        for (String name : new String[] {"../secrets.yml", "a/../../secrets.yml",
                tempDir.resolve("secrets.yml").toAbsolutePath().toString()}) {
            assertThatThrownBy(() -> service.transform(request(name), "[]"))
                    .as("transform(%s)", name)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.getTemplateId(request(name)))
                    .as("getTemplateId(%s)", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(ai, parser, prompts);
    }

    @Test
    void missingUploadIsNotFoundNotAnError() {
        TransformServiceImpl service = new TransformServiceImpl(
                mock(PromptFactory.class), mock(AiFilesService.class), mock(DocumentParser.class),
                new UploadStorage(new StorageProperties(tempDir.toString())),
                new PiiTokenizer(new PrivacyProperties()));

        assertThatThrownBy(() -> service.transform(request("nope.csv"), "[]"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
