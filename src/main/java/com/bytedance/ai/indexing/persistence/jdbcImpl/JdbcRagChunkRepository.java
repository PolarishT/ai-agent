package com.bytedance.ai.indexing.persistence.jdbcImpl;

import com.bytedance.ai.indexing.persistence.RagChunkRepository;
import com.bytedance.ai.indexing.persistence.RagChunkRecord;
import com.bytedance.ai.indexing.persistence.RagChunkSearchRecord;
import com.bytedance.ai.indexing.model.RagChunkDraft;
import com.bytedance.ai.shared.support.RagJsonCodec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private volatile Boolean pgTrgmEnabled;

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
                SELECT id, document_id, index_generation, chunk_index, chunk_text, chunk_hash, char_count, token_count, vector_id, metadata,
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
    public Integer findMaxChunkIndexByDocumentIdAndGeneration(Long documentId, Long indexGeneration) {
        return jdbc.queryForObject(
                """
                SELECT MAX(chunk_index)
                  FROM rag_chunks
                 WHERE document_id = ?
                   AND index_generation = ?
                """,
                Integer.class,
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

    @Override
    public List<RagChunkSearchRecord> findKeywordCandidates(Set<String> tokens, int limit) {
        // 先做轻量归一化，避免把空 token 或超长 token 列表直接带进 SQL。
        List<String> normalizedTokens = tokens == null ? List.of() : tokens.stream()
                .filter(token -> token != null && !token.isBlank())
                .map(token -> token.toLowerCase(Locale.ROOT))
                .limit(8)
                .toList();
        if (normalizedTokens.isEmpty() || limit <= 0) {
            return List.of();
        }

        if (isPostgreSql()) {
            // PostgreSQL 优先走 FTS + trigram 组合排序，命中质量更稳定。
            return findKeywordCandidatesPostgreSql(normalizedTokens, limit, isPgTrgmEnabled());
        }
        // 其他数据库退化为 LIKE 检索，保证开发和测试环境仍可运行。
        return findKeywordCandidatesFallback(normalizedTokens, limit);
    }

    private List<RagChunkSearchRecord> findKeywordCandidatesPostgreSql(
            List<String> normalizedTokens,
            int limit,
            boolean trigramEnabled
    ) {
        String searchText = String.join(" ", normalizedTokens);
        String likePattern = likePattern(searchText);
        String ftsVector = """
                (
                    setweight(to_tsvector('simple', COALESCE(c.metadata->>'headingPathText', '')), 'A')
                    || setweight(to_tsvector('simple', COALESCE(d.title, '')), 'B')
                    || setweight(to_tsvector('simple', COALESCE(c.chunk_text, '')), 'C')
                )
                """;
        String tsQuery = "plainto_tsquery('simple', ?)";
        List<Object> args = new ArrayList<>();
        args.add(searchText);

        // 用子查询包一层：PostgreSQL 允许 ORDER BY 单独引用 SELECT 别名，
        // 但禁止把别名嵌进表达式（如 ORDER BY (fts_score * 10.0 + ...)）。
        // 包一层后外层 ORDER BY 直接拿别名做算术，避免 "column fts_score does not exist"。
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM (")
                .append(searchableSelectSql())
                .append(", ts_rank_cd(")
                .append(ftsVector)
                .append(", ")
                .append(tsQuery)
                .append(") AS fts_score");

        if (trigramEnabled) {
            // pg_trgm 可用时，把标题、正文和 heading path 的模糊相似度一起纳入排序。
            sql.append("""
                    , GREATEST(
                        similarity(LOWER(COALESCE(c.metadata->>'headingPathText', '')), ?),
                        similarity(LOWER(COALESCE(d.title, '')), ?),
                        similarity(LOWER(COALESCE(c.chunk_text, '')), ?)
                    ) AS trigram_score
                    """);
            args.add(searchText);
            args.add(searchText);
            args.add(searchText);
        } else {
            sql.append(", 0.0 AS trigram_score");
        }

        sql.append(searchableFromSql())
                .append(" AND (")
                .append(ftsVector)
                .append(" @@ ")
                .append(tsQuery);
        args.add(searchText);

        if (trigramEnabled) {
            sql.append("""
                    OR LOWER(COALESCE(c.metadata->>'headingPathText', '')) % ?
                    OR LOWER(COALESCE(d.title, '')) % ?
                    OR LOWER(COALESCE(c.chunk_text, '')) % ?
                    """);
            args.add(searchText);
            args.add(searchText);
            args.add(searchText);
        }

        sql.append("""
                OR LOWER(COALESCE(c.metadata->>'headingPathText', '')) LIKE ? ESCAPE '\\'
                OR LOWER(COALESCE(d.title, '')) LIKE ? ESCAPE '\\'
                OR LOWER(COALESCE(c.chunk_text, '')) LIKE ? ESCAPE '\\'
                )
                ) ranked
                ORDER BY (fts_score * 10.0 + trigram_score * 3.0) DESC,
                         document_id,
                         chunk_index
                LIMIT ?
                """);
        args.add(likePattern);
        args.add(likePattern);
        args.add(likePattern);
        args.add(limit);

        return jdbc.query(sql.toString(), searchRowMapper(), args.toArray());
    }

    private List<RagChunkSearchRecord> findKeywordCandidatesFallback(List<String> normalizedTokens, int limit) {
        String metadataText = "LOWER(CAST(c.metadata AS TEXT))";
        List<String> scoreTerms = new ArrayList<>();
        List<String> matchClauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        for (String token : normalizedTokens) {
            String pattern = likePattern(token);
            scoreTerms.add("""
                    (CASE WHEN LOWER(c.chunk_text) LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END
                     + CASE WHEN LOWER(COALESCE(d.title, '')) LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END
                     + CASE WHEN %s LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END)
                    """.formatted(metadataText));
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        for (String token : normalizedTokens) {
            String pattern = likePattern(token);
            matchClauses.add("""
                    (LOWER(c.chunk_text) LIKE ? ESCAPE '\\'
                     OR LOWER(COALESCE(d.title, '')) LIKE ? ESCAPE '\\'
                     OR %s LIKE ? ESCAPE '\\')
                    """.formatted(metadataText));
            args.add(pattern);
            args.add(pattern);
            args.add(pattern);
        }

        String sql = searchableSelectSql()
                + ", (" + String.join(" + ", scoreTerms) + ") AS candidate_score"
                + searchableFromSql()
                + " AND (" + String.join(" OR ", matchClauses) + ")"
                + " ORDER BY candidate_score DESC, c.document_id, c.chunk_index"
                + " LIMIT ?";
        args.add(limit);

        return jdbc.query(sql, searchRowMapper(), args.toArray());
    }

    @Override
    public List<RagChunkSearchRecord> findActiveChunksByDocumentIdAndRange(Long documentId, int startChunkIndex, int endChunkIndex) {
        return jdbc.query(
                searchableSelectSql()
                        + searchableFromSql()
                        + " AND c.document_id = ? AND c.chunk_index BETWEEN ? AND ?"
                        + " ORDER BY c.chunk_index",
                searchRowMapper(),
                documentId,
                startChunkIndex,
                endChunkIndex
        );
    }

    @Override
    public List<RagChunkSearchRecord> findSearchableByVectorIds(List<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(vectorIds.size(), "?"));
        List<RagChunkSearchRecord> rows = jdbc.query(
                searchableSelectSql() + searchableFromSql() + " AND c.vector_id IN (" + placeholders + ")",
                searchRowMapper(),
                vectorIds.toArray()
        );

        Map<String, RagChunkSearchRecord> byVectorId = rows.stream()
                .collect(Collectors.toMap(RagChunkSearchRecord::vectorId, row -> row, (left, right) -> left));
        List<RagChunkSearchRecord> ordered = new ArrayList<>();
        // 按输入向量顺序回放结果，避免数据库 IN 查询打乱向量召回的相关性顺序。
        for (String vectorId : vectorIds) {
            RagChunkSearchRecord row = byVectorId.get(vectorId);
            if (row != null) {
                ordered.add(row);
            }
        }
        return ordered;
    }

    private String searchableSelectSql() {
        return """
                SELECT c.id AS chunk_id,
                       c.document_id,
                       d.title,
                       d.source_type,
                       d.source_uri,
                       d.external_ref,
                       c.index_generation,
                       c.chunk_index,
                       c.chunk_text,
                       c.vector_id,
                       c.metadata
                """;
    }

    private String searchableFromSql() {
        return """
                  FROM rag_chunks c
                 JOIN rag_documents d ON d.id = c.document_id
                 -- 只暴露当前 active generation 的切片给检索链路，避免命中旧版本内容。
                 WHERE d.indexed_generation IS NOT NULL
                   AND d.status <> 'DELETING'
                   AND c.index_generation = d.indexed_generation
                """;
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }

    private String insertSql(Connection connection) throws java.sql.SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO rag_chunks (
                        document_id, index_generation, chunk_index, chunk_text, chunk_hash, char_count, token_count, vector_id, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """;
        }
        return """
                INSERT INTO rag_chunks (
                    document_id, index_generation, chunk_index, chunk_text, chunk_hash, char_count, token_count, vector_id, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
    }

    private List<RagChunkRecord> insertAllPostgreSql(Long documentId, List<RagChunkDraft> chunks) {
        String placeholders = chunks.stream()
                .map(chunk -> "(?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))")
                .collect(Collectors.joining(", "));
        String sql = """
                INSERT INTO rag_chunks (
                    document_id, index_generation, chunk_index, chunk_text, chunk_hash, char_count, token_count, vector_id, metadata
                ) VALUES
                """
                + placeholders
                + """
                
                RETURNING id, document_id, index_generation, chunk_index, chunk_text, chunk_hash, char_count, token_count, vector_id, metadata,
                          created_at, updated_at
                """;

        return jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql);
            int parameterIndex = 1;
            for (RagChunkDraft chunk : chunks) {
                statement.setLong(parameterIndex++, documentId);
                statement.setLong(parameterIndex++, chunk.indexGeneration());
                statement.setInt(parameterIndex++, chunk.chunkIndex());
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
                    document_id, index_generation, chunk_index, chunk_text, chunk_hash, char_count, token_count, vector_id, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                chunks,
                chunks.size(),
                (statement, chunk) -> {
                    statement.setLong(1, documentId);
                    statement.setLong(2, chunk.indexGeneration());
                    statement.setInt(3, chunk.chunkIndex());
                    statement.setString(4, chunk.chunkText());
                    statement.setString(5, chunk.chunkHash());
                    statement.setInt(6, chunk.charCount());
                    if (chunk.tokenCount() == null) {
                        statement.setObject(7, null);
                    } else {
                        statement.setInt(7, chunk.tokenCount());
                    }
                    statement.setString(8, chunk.vectorId());
                    statement.setString(9, chunk.metadata() == null ? null : jsonCodec.write(chunk.metadata()));
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
                rs.getInt("chunk_index"),
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

    private RowMapper<RagChunkSearchRecord> searchRowMapper() {
        return (rs, rowNum) -> new RagChunkSearchRecord(
                rs.getLong("chunk_id"),
                rs.getLong("document_id"),
                rs.getString("title"),
                rs.getString("source_type"),
                rs.getString("source_uri"),
                rs.getString("external_ref"),
                rs.getLong("index_generation"),
                rs.getInt("chunk_index"),
                rs.getString("chunk_text"),
                rs.getString("vector_id"),
                jsonCodec.readMap(rs.getString("metadata"))
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

    private boolean isPgTrgmEnabled() {
        Boolean cached = pgTrgmEnabled;
        if (cached != null) {
            return cached;
        }
        if (!isPostgreSql()) {
            pgTrgmEnabled = false;
            return false;
        }
        synchronized (this) {
            cached = pgTrgmEnabled;
            if (cached == null) {
                cached = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm')",
                        Boolean.class
                ));
                pgTrgmEnabled = cached;
            }
        }
        return cached;
    }

    private String likePattern(String token) {
        return "%" + token
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }
}
