package com.involutionhell.backend.rag.retrieval;

import com.involutionhell.backend.rag.retrieval.api.RagAskFacade;
import com.involutionhell.backend.rag.retrieval.api.RagAskRequest;
import com.involutionhell.backend.rag.retrieval.api.RagConversationUpdateRequest;
import com.involutionhell.backend.rag.retrieval.application.RagConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ApplicationModuleTest(
        module = "rag.retrieval",
        mode = ApplicationModuleTest.BootstrapMode.ALL_DEPENDENCIES,
        extraIncludes = "rag.infrastructure",
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-modulith.sql"
})
class RetrievalModuleTests {

    @Autowired
    private RagAskFacade ragAskFacade;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RagConversationService conversationService;

    @BeforeEach
    void setUpConversation() {
        jdbc.update("DELETE FROM rag_ask_runs");
        jdbc.update("DELETE FROM rag_conversation_messages");
        jdbc.update("DELETE FROM rag_conversations");
        jdbc.update("DELETE FROM rag_users");
        jdbc.update("INSERT INTO rag_users (user_id) VALUES (?)", "user-001");
        jdbc.update(
                "INSERT INTO rag_conversations (conversation_id, user_id, title) VALUES (?, ?, ?)",
                "conv-001",
                "user-001",
                "测试会话"
        );
    }

    @Test
    void exposesRetrievalFacade() {
        assertThat(ragAskFacade).isNotNull();
    }

    @Test
    void askFallsBackGracefullyWhenNoContextIsFound() {
        var events = ragAskFacade.askStream(new RagAskRequest(
                "user-001",
                "conv-001",
                "当前知识库里有关于 Modulith 的内容吗？",
                3,
                null,
                List.of(),
                null,
                List.of()
        )).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting("event")
                .containsSequence("started", "query_transformed", "query_expanded", "contexts", "answer_delta");
        assertThat(events).extracting("event").contains("notice");
        assertThat(events.getLast().event()).isEqualTo("completed");
        assertThat(events).anySatisfy(event -> {
            assertThat(event.event()).isEqualTo("answer_delta");
            assertThat(event.data().toString()).contains("我没有检索到可用的知识片段");
        });
        assertThat(count("rag_conversation_messages")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM rag_ask_runs", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                "SELECT message_count FROM rag_conversations WHERE conversation_id = ?",
                Integer.class,
                "conv-001"
        )).isEqualTo(2);
    }

    @Test
    void askRequestIdMakesRepeatedSubscriptionIdempotent() {
        RagAskRequest request = new RagAskRequest(
                "user-001",
                "conv-001",
                "同一个 requestId 只能入库一次",
                3,
                null,
                List.of(),
                null,
                List.of(),
                "client-request-001"
        );

        var firstEvents = ragAskFacade.askStream(request).collectList().block();
        var secondEvents = ragAskFacade.askStream(request).collectList().block();

        assertThat(firstEvents).isNotNull();
        assertThat(secondEvents).isNotNull();
        assertThat(secondEvents).extracting("event").containsExactly("started", "answer_delta", "completed");
        assertThat(count("rag_ask_runs")).isEqualTo(1);
        assertThat(count("rag_conversation_messages")).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_ask_runs WHERE request_id = ?",
                Integer.class,
                "client-request-001"
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT message_count FROM rag_conversations WHERE conversation_id = ?",
                Integer.class,
                "conv-001"
        )).isEqualTo(2);
    }

    @Test
    void askCancellationMarksRunningRunFailed() {
        var events = ragAskFacade.askStream(new RagAskRequest(
                "user-001",
                "conv-001",
                "取消订阅时不应留下 RUNNING run",
                3,
                null,
                List.of(),
                null,
                List.of()
        )).take(1).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting("event").containsExactly("started");
        assertThat(jdbc.queryForObject("SELECT status FROM rag_ask_runs", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM rag_ask_runs", String.class)).isEqualTo("CancellationException");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_conversation_messages WHERE role = 'assistant'",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM rag_conversation_messages WHERE role = 'assistant'",
                String.class
        )).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT content FROM rag_conversation_messages WHERE role = 'assistant'",
                String.class
        )).contains("RAG ask stream cancelled by client");
        assertThat(jdbc.queryForObject(
                "SELECT message_count FROM rag_conversations WHERE conversation_id = ?",
                Integer.class,
                "conv-001"
        )).isEqualTo(2);
    }

    @Test
    void failedAskWritesFailedAssistantMessageVisibleInMessages() {
        var state = conversationService.beginAsk(
                "run:failed",
                "ask:failed",
                "user-001",
                "conv-001",
                "会失败的问题",
                null,
                3,
                null
        );

        conversationService.failAsk(state, new IllegalStateException("retrieval broke"), List.of());

        assertThat(jdbc.queryForObject("SELECT status FROM rag_ask_runs", String.class)).isEqualTo("FAILED");
        var messages = conversationService.getMessages("user-001", "conv-001").messages();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo("user");
        assertThat(messages.get(1).role()).isEqualTo("assistant");
        assertThat(messages.get(1).status()).isEqualTo("FAILED");
        assertThat(messages.get(1).content()).contains("retrieval broke");
    }

    @Test
    void concurrentAsksKeepQuestionAnswerPairsInStartOrder() {
        var first = conversationService.beginAsk(
                "run:first",
                "ask:first",
                "user-001",
                "conv-001",
                "问题一",
                null,
                3,
                null
        );
        var second = conversationService.beginAsk(
                "run:second",
                "ask:second",
                "user-001",
                "conv-001",
                "问题二",
                null,
                3,
                null
        );

        conversationService.completeAsk(second, "答案二", "问题二", List.of("问题二"), List.of(), List.of(), false, false, "ask:second");
        conversationService.completeAsk(first, "答案一", "问题一", List.of("问题一"), List.of(), List.of(), false, false, "ask:first");

        List<String> messages = jdbc.query(
                """
                SELECT role || ':' || content || ':' || status
                  FROM rag_conversation_messages
                 WHERE conversation_id = (
                         SELECT id FROM rag_conversations WHERE conversation_id = ?
                       )
                 ORDER BY sequence_no ASC
                """,
                (rs, _) -> rs.getString(1),
                "conv-001"
        );
        assertThat(messages).containsExactly(
                "user:问题一:SUCCEEDED",
                "assistant:答案一:SUCCEEDED",
                "user:问题二:SUCCEEDED",
                "assistant:答案二:SUCCEEDED"
        );
        assertThat(jdbc.queryForObject(
                "SELECT message_count FROM rag_conversations WHERE conversation_id = ?",
                Integer.class,
                "conv-001"
        )).isEqualTo(4);
    }

    @Test
    void askRejectsMissingConversation() {
        assertThatThrownBy(() -> ragAskFacade.askStream(new RagAskRequest(
                "user-001",
                "missing-conv",
                "问题",
                3,
                null,
                List.of(),
                null,
                List.of()
        )).collectList().block())
                .hasMessageContaining("404 NOT_FOUND");
    }

    @Test
    void askRejectsConversationOwnedByAnotherUser() {
        assertThatThrownBy(() -> ragAskFacade.askStream(new RagAskRequest(
                "user-002",
                "conv-001",
                "问题",
                3,
                null,
                List.of(),
                null,
                List.of()
        )).collectList().block())
                .hasMessageContaining("409 CONFLICT");
    }

    @Test
    void conversationQueryAndUpdateUseUserAndConversationId() {
        var updated = conversationService.updateConversation(
                "conv-001",
                new RagConversationUpdateRequest("user-001", "RAG 检索链路讨论", "ARCHIVED")
        );

        assertThat(updated.title()).isEqualTo("RAG 检索链路讨论");
        assertThat(updated.status()).isEqualTo("ARCHIVED");
        assertThat(conversationService.getMessages("user-001", "conv-001").messages()).isEmpty();

        var page = conversationService.listConversations("user-001", 20, null);
        assertThat(page.items()).extracting("conversationId").containsExactly("conv-001");
        assertThat(page.nextCursor()).isNull();
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}
