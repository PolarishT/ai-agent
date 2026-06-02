package com.bytedance.ai.indexing.persistence.jdbc;

import com.bytedance.ai.indexing.persistence.RagChunkRepository;
import com.bytedance.ai.indexing.persistence.RagChunkRecord;
import com.bytedance.ai.indexing.model.RagChunkDraft;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基于 Spring JDBC 的 RAG 切片仓储实现。
 */
@Repository
public class JdbcRagChunkRepository implements RagChunkRepository {

    private final JdbcTemplate jdbc;
    private final RagJsonCodec jsonCodec;
    private volatile Boolean postgreSql;

    public JdbcRagChunkRepository(JdbcTemplate jdbc, RagJsonCodec jsonCodec) {
        this.jdbc = jdbc;
        this.jsonCodec = jsonCodec;
    }

    @Override
    @Transactional
    public List<RagChunkRecord> saveAll(Long documentId, List<RagChunkDraft> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        // 批量写切片时保持事务一致性，避免只写入部分 chunk 造成脏 generation。
        if (isPostgreSql()) {
            return insertAllPostgreSql(documentId, chunks);
        }
        insertAllBatch(documentId, chunks);
        return reloadInsertedChunks(documentId, chunks);
    }

    @Override
    public List<String> findVectorIdsByDocumentId(Long documentId) {
        return jdbc.queryForList(
                "SELECT DISTINCT vector_id FROM rag_chunks WHERE document_id = ? AND vector_id IS NOT NULL",
                String.class,
                documentId
        );
    }

    @Override
    public List<String> findVectorIdsByDocumentIdAndGeneration(Long documentId, Long indexGeneration) {
        return jdbc.queryForList(
                """
                SELECT DISTINCT vector_id
                  FROM rag_chunks
                 WHERE document_id = ?
                   AND index_generation = ?
                   AND vector_id IS NOT NULL
                """,
                String.class,
                documentId,
                indexGeneration
        );
    }

    @Override
    public List<RagChunkRecord> findByDocumentIdAndGeneration(Long documentId, Long indexGeneration) {
        return jdbc.query(
                """
                SELECT id, document_id, index_generation, product_id, source_type, chunk_index, chunk_type, heading_path,
                       chunk_text, chunk_hash, char_count, token_count, vector_id, metadata,
                       created_at, updated_at
                  FROM rag_chunks
                 WHERE document_id = ?
                   AND index_generation = ?
                 ORDER BY chunk_index
                """,
                chunkRowMapper(),
                documentId,
                indexGeneration
        );
    }

    @Override
    public List<String> findVectorIdsByDocumentIdExceptGeneration(Long documentId, Long indexGeneration) {
        return jdbc.queryForList(
                """
                SELECT DISTINCT vector_id
                  FROM rag_chunks
                 WHERE document_id = ?
                   AND index_generation <> ?
                   AND vector_id IS NOT NULL
                """,
                String.class,
                documentId,
                indexGeneration
        );
    }

    @Override
    public Integer findMaxChunkIndexByDocumentIdExceptGeneration(Long documentId, Long indexGeneration) {
        return jdbc.queryForObject(
                """
                SELECT MAX(chunk_index)
                  FROM rag_chunks
                 WHERE document_id = ?
                   AND index_generation <> ?
                """,
                Integer.class,
                documentId,
                indexGeneration
        );
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        jdbc.update("DELETE FROM rag_chunks WHERE document_id = ?", documentId);
    }

    @Override
    public void deleteByDocumentIdAndGeneration(Long documentId, Long indexGeneration) {
        jdbc.update(
                "DELETE FROM rag_chunks WHERE document_id = ? AND index_generation = ?",
                documentId,
                indexGeneration
        );
    }

    @Override
    public void deleteByDocumentIdExceptGeneration(Long documentId, Long indexGeneration) {
        jdbc.update(
                "DELETE FROM rag_chunks WHERE document_id = ? AND index_generation <> ?",
                documentId,
                indexGeneration
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    private String insertSql(Connection connection) throws java.sql.SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO rag_chunks (
                        document_id, index_generation, product_id, source_type, chunk_index, chunk_type, heading_path,
                        chunk_text, chunk_hash, char_count, token_count, vector_id, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """;
        }
        return """
                INSERT INTO rag_chunks (
                    document_id, index_generation, product_id, source_type, chunk_index, chunk_type, heading_path,
                    chunk_text, chunk_hash, char_count, token_count, vector_id, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    private List<RagChunkRecord> insertAllPostgreSql(Long documentId, List<RagChunkDraft> chunks) {
        String placeholders = chunks.stream()
                .map(chunk -> "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))")
                .collect(Collectors.joining(", "));
        String sql = """
                INSERT INTO rag_chunks (
                    document_id, index_generation, product_id, source_type, chunk_index, chunk_type, heading_path,
                    chunk_text, chunk_hash, char_count, token_count, vector_id, metadata
                ) VALUES
                """
                + placeholders
                + """
                
                RETURNING id, document_id, index_generation, product_id, source_type, chunk_index, chunk_type, heading_path,
                          chunk_text, chunk_hash, char_count, token_count, vector_id, metadata,
                          created_at, updated_at
                """;

        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            int parameterIndex = 1;
            for (RagChunkDraft chunk : chunks) {
                statement.setLong(parameterIndex++, documentId);
                statement.setLong(parameterIndex++, chunk.indexGeneration());
                if (chunk.productId() == null) {
                    statement.setObject(parameterIndex++, null);
                } else {
                    statement.setLong(parameterIndex++, chunk.productId());
                }
                statement.setString(parameterIndex++, chunk.sourceType());
                statement.setInt(parameterIndex++, chunk.chunkIndex());
                statement.setString(parameterIndex++, chunk.chunkType());
                statement.setString(parameterIndex++, chunk.headingPath());
                statement.setString(parameterIndex++, chunk.chunkText());
                statement.setString(parameterIndex++, chunk.chunkHash());
                statement.setInt(parameterIndex++, chunk.charCount());
                if (chunk.tokenCount() == null) {
                    statement.setObject(parameterIndex++, null);
                } else {
                    statement.setInt(parameterIndex++, chunk.tokenCount());
                }
                statement.setString(parameterIndex++, chunk.vectorId());
                bindMetadata(statement, connection, parameterIndex++, chunk.metadata());
            }
            return statement;
        }, chunkRowMapper());
    }

    private void insertAllBatch(Long documentId, List<RagChunkDraft> chunks) {
        jdbc.batchUpdate(
                """
                INSERT INTO rag_chunks (
                    document_id, index_generation, product_id, source_type, chunk_index, chunk_type, heading_path,
                    chunk_text, chunk_hash, char_count, token_count, vector_id, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                chunks,
                chunks.size(),
                (statement, chunk) -> {
                    statement.setLong(1, documentId);
                    statement.setLong(2, chunk.indexGeneration());
                    if (chunk.productId() == null) {
                        statement.setObject(3, null);
                    } else {
                        statement.setLong(3, chunk.productId());
                    }
                    statement.setString(4, chunk.sourceType());
                    statement.setInt(5, chunk.chunkIndex());
                    statement.setString(6, chunk.chunkType());
                    statement.setString(7, chunk.headingPath());
                    statement.setString(8, chunk.chunkText());
                    statement.setString(9, chunk.chunkHash());
                    statement.setInt(10, chunk.charCount());
                    if (chunk.tokenCount() == null) {
                        statement.setObject(11, null);
                    } else {
                        statement.setInt(11, chunk.tokenCount());
                    }
                    statement.setString(12, chunk.vectorId());
                    statement.setString(13, chunk.metadata() == null ? null : jsonCodec.write(chunk.metadata()));
                }
        );
    }

    private List<RagChunkRecord> reloadInsertedChunks(Long documentId, List<RagChunkDraft> chunks) {
        Long indexGeneration = chunks.getFirst().indexGeneration();
        List<RagChunkRecord> inserted = findByDocumentIdAndGeneration(documentId, indexGeneration);
        Set<Integer> expectedChunkIndexes = chunks.stream()
                .map(RagChunkDraft::chunkIndex)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<RagChunkRecord> filtered = inserted.stream()
                .filter(record -> expectedChunkIndexes.contains(record.chunkIndex()))
                .toList();
        if (filtered.size() != chunks.size()) {
            throw new IllegalStateException(
                    "批量创建 RAG 切片后回查数量不一致: expected=" + chunks.size() + ", actual=" + filtered.size()
            );
        }
        return filtered;
    }

    private void bindMetadata(PreparedStatement statement, Connection connection, int index, Map<String, Object> metadataJson)
            throws java.sql.SQLException {
        // PostgreSQL 使用 jsonb，H2 测试环境退化为普通字符串列。
        if (metadataJson == null) {
            if (isPostgreSql(connection)) {
                statement.setNull(index, Types.OTHER);
            } else {
                statement.setNull(index, Types.VARCHAR);
            }
            return;
        }
        statement.setString(index, jsonCodec.write(metadataJson));
    }

    private RowMapper<RagChunkRecord> chunkRowMapper() {
        return (rs, rowNum) -> new RagChunkRecord(
                rs.getLong("id"),
                rs.getLong("document_id"),
                rs.getLong("index_generation"),
                (Long) rs.getObject("product_id"),
                rs.getString("source_type"),
                rs.getInt("chunk_index"),
                rs.getString("chunk_type"),
                rs.getString("heading_path"),
                rs.getString("chunk_text"),
                rs.getString("chunk_hash"),
                rs.getInt("char_count"),
                (Integer) rs.getObject("token_count"),
                rs.getString("vector_id"),
                jsonCodec.readMap(rs.getString("metadata")),
                toOffsetDateTime(rs.getTimestamp("created_at")),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private boolean isPostgreSql(Connection connection) throws java.sql.SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }

    private boolean isPostgreSql() {
        Boolean cached = postgreSql;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = postgreSql;
            if (cached == null) {
                cached = Boolean.TRUE.equals(jdbc.execute((Connection connection) -> isPostgreSql(connection)));
                postgreSql = cached;
            }
        }
        return cached;
    }

}
