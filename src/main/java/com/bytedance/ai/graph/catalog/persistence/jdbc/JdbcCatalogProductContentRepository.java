package com.bytedance.ai.graph.catalog.persistence.jdbc;

import com.bytedance.ai.graph.catalog.persistence.CatalogProductContentRepository;
import com.bytedance.ai.shared.support.RagJsonCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcCatalogProductContentRepository implements CatalogProductContentRepository {

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;

    public JdbcCatalogProductContentRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public void saveKnowledge(Long productId, List<KnowledgeDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        for (KnowledgeDraft draft : drafts) {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(knowledgeSql(connection));
                statement.setLong(1, productId);
                statement.setString(2, draft.knowledgeType());
                statement.setString(3, draft.title());
                statement.setString(4, draft.content());
                statement.setString(5, draft.contentSha256());
                bindJson(statement, 6, draft.metadata());
                return statement;
            });
        }
    }

    @Override
    public void saveFaqs(Long productId, List<FaqDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        for (FaqDraft draft : drafts) {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(faqSql(connection));
                statement.setLong(1, productId);
                statement.setInt(2, draft.faqIndex());
                statement.setString(3, draft.question());
                statement.setString(4, draft.answer());
                statement.setString(5, draft.contentSha256());
                bindJson(statement, 6, draft.metadata());
                return statement;
            });
        }
    }

    @Override
    public void saveReviews(Long productId, List<ReviewDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return;
        }
        for (ReviewDraft draft : drafts) {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(reviewSql(connection));
                statement.setLong(1, productId);
                statement.setInt(2, draft.reviewIndex());
                statement.setString(3, draft.nickname());
                if (draft.rating() == null) {
                    statement.setObject(4, null);
                } else {
                    statement.setInt(4, draft.rating());
                }
                statement.setString(5, draft.content());
                statement.setString(6, draft.contentSha256());
                statement.setString(7, draft.sentiment());
                bindJson(statement, 8, draft.metadata());
                return statement;
            });
        }
    }

    private String knowledgeSql(Connection connection) throws SQLException {
        String metadata = isPostgreSql(connection) ? "CAST(? AS jsonb)" : "?";
        return """
                INSERT INTO catalog_product_knowledge (
                    product_id, knowledge_type, title, content, content_sha256, metadata
                ) VALUES (?, ?, ?, ?, ?, %s)
                """.formatted(metadata);
    }

    private String faqSql(Connection connection) throws SQLException {
        String metadata = isPostgreSql(connection) ? "CAST(? AS jsonb)" : "?";
        return """
                INSERT INTO catalog_product_faq (
                    product_id, faq_index, question, answer, content_sha256, metadata
                ) VALUES (?, ?, ?, ?, ?, %s)
                """.formatted(metadata);
    }

    private String reviewSql(Connection connection) throws SQLException {
        String metadata = isPostgreSql(connection) ? "CAST(? AS jsonb)" : "?";
        return """
                INSERT INTO catalog_product_review (
                    product_id, review_index, nickname, rating, content, content_sha256, sentiment, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, %s)
                """.formatted(metadata);
    }

    private void bindJson(PreparedStatement statement, int index, Map<String, Object> metadata) throws SQLException {
        statement.setString(index, jsonCodec.write(metadata == null ? Map.of() : metadata));
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }
}
