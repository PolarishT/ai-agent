package com.involutionhell.backend.rag.retrieval.persistence.jdbc;

import com.involutionhell.backend.rag.retrieval.persistence.RagUserRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRagUserRepository implements RagUserRepository {

    private final JdbcTemplate jdbc;

    public JdbcRagUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsertSeen(String userId) {
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(upsertSql(connection));
            statement.setString(1, userId);
            return statement;
        });
    }

    private String upsertSql(Connection connection) throws java.sql.SQLException {
        if (isPostgreSql(connection)) {
            return """
                    INSERT INTO rag_users (user_id)
                    VALUES (?)
                    ON CONFLICT (user_id)
                    DO UPDATE SET last_seen_at = now()
                    """;
        }
        return """
                MERGE INTO rag_users (user_id, last_seen_at)
                KEY (user_id)
                VALUES (?, CURRENT_TIMESTAMP)
                """;
    }

    private boolean isPostgreSql(Connection connection) throws java.sql.SQLException {
        return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
    }
}
