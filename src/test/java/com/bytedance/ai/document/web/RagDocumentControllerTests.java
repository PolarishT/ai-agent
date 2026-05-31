package com.bytedance.ai.document.web;

import com.bytedance.ai.document.api.DocumentCommandFacade;
import com.bytedance.ai.document.api.RagDocumentCreateRequest;
import com.bytedance.ai.document.api.RagDocumentUpdateRequest;
import com.bytedance.ai.document.api.RagDocumentView;
import com.bytedance.ai.infrastructure.web.RagExceptionHandler;
import com.bytedance.ai.shared.markdown.MarkdownDocumentParser;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RagDocumentControllerTests {

    @Test
    void multipartCreateAcceptsOnlyMetadataAndMarkdownFile() throws Exception {
        CapturingDocumentCommandFacade commandFacade = new CapturingDocumentCommandFacade();
        RagJsonCodec jsonCodec = new RagJsonCodec(JsonMapper.builder().build());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RagDocumentController(
                        commandFacade,
                        documentId -> null,
                        new MarkdownDocumentParser(jsonCodec),
                        jsonCodec
                ))
                .setControllerAdvice(new RagExceptionHandler())
                .build();

        MockMultipartFile metadata = new MockMultipartFile(
                "metadata",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                """
                        {"tags":["manual-e2e"],"seed":"offline-chain"}
                        """.getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "offline-chain-001.md",
                "text/markdown",
                """
                        ---
                        sourceUri: manual-e2e://offline-chain-001
                        externalRef: manual-offline-e2e-001
                        ---
                        # 防水通勤双肩包
                        适合 14 寸电脑，雨天短途通勤，价格 199 元。
                        """.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/public/rag/documents/create")
                        .file(metadata)
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(1001))
                .andExpect(jsonPath("$.data.title").value("防水通勤双肩包"));

        RagDocumentCreateRequest captured = commandFacade.capturedRequest.get();
        assertThat(captured.sourceType()).isEqualTo("MARKDOWN");
        assertThat(captured.sourceUri()).isEqualTo("manual-e2e://offline-chain-001");
        assertThat(captured.externalRef()).isEqualTo("manual-offline-e2e-001");
        assertThat(captured.title()).isEqualTo("防水通勤双肩包");
        assertThat(captured.content()).contains("适合 14 寸电脑");
        assertThat(captured.metadata())
                .containsEntry("seed", "offline-chain")
                .containsEntry("uploadMode", "multipart")
                .containsEntry("originalFilename", "offline-chain-001.md");
        assertThat(captured.metadata().get("tags")).isEqualTo(java.util.List.of("manual-e2e"));
    }

    private static class CapturingDocumentCommandFacade implements DocumentCommandFacade {

        private final AtomicReference<RagDocumentCreateRequest> capturedRequest = new AtomicReference<>();

        @Override
        public RagDocumentView createDocument(RagDocumentCreateRequest request) {
            capturedRequest.set(request);
            OffsetDateTime now = OffsetDateTime.parse("2026-05-30T10:15:30+10:00");
            return new RagDocumentView(
                    1001L,
                    request.sourceType(),
                    request.sourceUri(),
                    request.externalRef(),
                    request.title(),
                    "PENDING",
                    0,
                    0,
                    null,
                    null,
                    null,
                    now,
                    now
            );
        }

        @Override
        public RagDocumentView updateDocument(Long documentId, RagDocumentUpdateRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RagDocumentView reindexDocument(Long documentId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteDocument(Long documentId) {
            throw new UnsupportedOperationException();
        }
    }
}
