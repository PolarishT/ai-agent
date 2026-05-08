package com.involutionhell.backend.rag.document.application;

import com.involutionhell.backend.rag.document.api.*;
import com.involutionhell.backend.rag.document.persistence.RagDocumentRecord;
import com.involutionhell.backend.rag.document.persistence.RagDocumentRepository;
import com.involutionhell.backend.rag.shared.markdown.MarkdownDocumentParser;
import com.involutionhell.backend.rag.shared.model.RagDocumentStatus;
import com.involutionhell.backend.rag.shared.support.RagLogFields;
import com.involutionhell.backend.rag.shared.support.RagLogHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
class DocumentCommandService implements DocumentCommandFacade {

    private static final Logger log = LoggerFactory.getLogger(DocumentCommandService.class);

    private final RagDocumentRepository documentRepository;
    private final DocumentQueryFacade documentQueryFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final MarkdownDocumentParser markdownDocumentParser;

    DocumentCommandService(
            RagDocumentRepository documentRepository,
            DocumentQueryFacade documentQueryFacade,
            ApplicationEventPublisher eventPublisher,
            MarkdownDocumentParser markdownDocumentParser
    ) {
        this.documentRepository = documentRepository;
        this.documentQueryFacade = documentQueryFacade;
        this.eventPublisher = eventPublisher;
        this.markdownDocumentParser = markdownDocumentParser;
    }

    @Override
    @Transactional
    public RagDocumentView createDocument(RagDocumentCreateRequest request) {
        PreparedDocument prepared = prepareDocument(
                request.sourceType(),
                request.sourceUri(),
                request.externalRef(),
                request.title(),
                request.content(),
                request.metadata()
        );
        RagDocumentRecord record = documentRepository.save(
                prepared.sourceType(),
                prepared.sourceUri(),
                prepared.externalRef(),
                prepared.title(),
                prepared.content(),
                prepared.contentSha256(),
                prepared.metadata()
        );
        logCreate(record, prepared);
        queueAndPublish(record.id(), record.contentSha256());
        return documentQueryFacade.getDocument(record.id());
    }

    @Override
    @Transactional
    public RagDocumentView updateDocument(Long documentId, RagDocumentUpdateRequest request) {
        requireMutableDocument(documentId);
        PreparedDocument prepared = prepareDocument(
                request.sourceType(),
                request.sourceUri(),
                request.externalRef(),
                request.title(),
                request.content(),
                request.metadata()
        );
        documentRepository.update(
                documentId,
                prepared.sourceType(),
                prepared.sourceUri(),
                prepared.externalRef(),
                prepared.title(),
                prepared.content(),
                prepared.contentSha256(),
                prepared.metadata()
        );
        logUpdate(documentId, prepared);
        queueAndPublish(documentId, prepared.contentSha256());
        return documentQueryFacade.getDocument(documentId);
    }

    @Override
    @Transactional
    public RagDocumentView reindexDocument(Long documentId) {
        RagDocumentRecord record = requireMutableDocument(documentId);
        documentRepository.markPending(documentId);
        logReindex(documentId, record.contentSha256());
        queueAndPublish(documentId, record.contentSha256());
        return documentQueryFacade.getDocument(documentId);
    }

    @Override
    @Transactional
    public void deleteDocument(Long documentId) {
        RagDocumentRecord document = requireDocument(documentId);
        if (RagDocumentStatus.DELETING.name().equals(document.status())) {
            log.atInfo()
                    .addKeyValue(RagLogFields.EVENT_NAME, "rag.document.delete.ignored")
                    .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SKIPPED)
                    .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, document.contentSha256()))
                    .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                    .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(document.contentSha256()))
                    .addKeyValue(RagLogFields.EVENT_REASON, "already_deleting")
                    .log("RAG delete ignored because document is already deleting");
            return;
        }
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.document.delete.requested")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_STARTED)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, document.contentSha256()))
                .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(document.contentSha256()))
                .log("RAG document marked deleting");
        documentRepository.markDeleting(documentId, "文档删除已受理，等待完成向量与切片清理");
        eventPublisher.publishEvent(new DocumentIndexCleanupRequestedEvent(documentId));
    }

    private RagDocumentRecord requireDocument(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("RAG 文档不存在: " + documentId));
    }

    private RagDocumentRecord requireMutableDocument(Long documentId) {
        RagDocumentRecord record = requireDocument(documentId);
        if (RagDocumentStatus.DELETING.name().equals(record.status())) {
            throw new IllegalStateException("RAG 文档正在删除，暂时不能修改或重建索引: " + documentId);
        }
        return record;
    }

    private void queueAndPublish(Long documentId, String contentSha256) {
        eventPublisher.publishEvent(new DocumentIndexRequestedEvent(
                documentId,
                contentSha256,
                "document-command-service"
        ));
    }

    private PreparedDocument prepareDocument(
            String sourceType,
            String sourceUri,
            String externalRef,
            String title,
            String content,
            Map<String, Object> metadata
    ) {
        String normalizedContent = content == null ? null : content.trim();
        if (!StringUtils.hasText(normalizedContent)) {
            throw new IllegalArgumentException("文档内容不能为空");
        }

        return new PreparedDocument(
                normalize(sourceType),
                trimToNull(sourceUri),
                trimToNull(externalRef),
                trimToNull(title),
                normalizedContent,
                sha256Hex(normalizedContent),
                enrichMetadata(metadata, normalizedContent)
        );
    }

    private Map<String, Object> enrichMetadata(Map<String, Object> metadata, String content) {
        Map<String, Object> enriched = new LinkedHashMap<>();
        if (metadata != null) {
            enriched.putAll(metadata);
        }

        MarkdownDocumentParser.MarkdownDocument markdownDocument = markdownDocumentParser.parse(content);
        if (!enriched.containsKey("format")) {
            enriched.put("format", "markdown");
        }
        if (!markdownDocument.frontmatter().isEmpty()) {
            enriched.put("frontmatter", markdownDocument.frontmatter());
        }
        return enriched;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        return value.trim().toLowerCase();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private void logCreate(RagDocumentRecord record, PreparedDocument prepared) {
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.document.created")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(record.id(), prepared.contentSha256()))
                .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, record.id())
                .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(prepared.contentSha256()))
                .addKeyValue("rag.source_type", prepared.sourceType())
                .addKeyValue("rag.source_uri", prepared.sourceUri())
                .addKeyValue("rag.title", prepared.title())
                .addKeyValue("rag.content_length", prepared.content().length())
                .log("RAG document created");
    }

    private void logUpdate(Long documentId, PreparedDocument prepared) {
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.document.updated")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_SUCCESS)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, prepared.contentSha256()))
                .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(prepared.contentSha256()))
                .addKeyValue("rag.source_type", prepared.sourceType())
                .addKeyValue("rag.source_uri", prepared.sourceUri())
                .addKeyValue("rag.title", prepared.title())
                .addKeyValue("rag.content_length", prepared.content().length())
                .log("RAG document updated");
    }

    private void logReindex(Long documentId, String contentSha256) {
        log.atInfo()
                .addKeyValue(RagLogFields.EVENT_NAME, "rag.document.reindex.requested")
                .addKeyValue(RagLogFields.EVENT_OUTCOME, RagLogFields.OUTCOME_STARTED)
                .addKeyValue(RagLogFields.RAG_CORRELATION_ID, RagLogFields.documentCorrelationId(documentId, contentSha256))
                .addKeyValue(RagLogFields.RAG_DOCUMENT_ID, documentId)
                .addKeyValue(RagLogFields.RAG_CONTENT_SHA, RagLogHelper.shortSha(contentSha256))
                .log("RAG reindex requested");
    }

    private record PreparedDocument(
            String sourceType,
            String sourceUri,
            String externalRef,
            String title,
            String content,
            String contentSha256,
            Map<String, Object> metadata
    ) {
    }
}
