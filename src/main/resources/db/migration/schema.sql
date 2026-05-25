-- Complete PostgreSQL DDL for the RAG backend.
-- Intended for a fresh PostgreSQL database.

BEGIN;

-- Optional but recommended for hybrid keyword retrieval.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Java-managed users for Sa-Token authentication.
CREATE TABLE IF NOT EXISTS user_accounts (
                                             id            BIGSERIAL PRIMARY KEY,
                                             username      VARCHAR(255) NOT NULL UNIQUE,
                                             password_hash VARCHAR(255) NOT NULL,
                                             display_name  VARCHAR(255),
                                             enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
                                             roles         TEXT         NOT NULL DEFAULT '',
                                             permissions   TEXT         NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS rag_documents (
                                             id                  BIGSERIAL PRIMARY KEY,
                                             source_type         VARCHAR(32)    NOT NULL,
                                             source_uri          TEXT,
                                             external_ref        VARCHAR(255),
                                             title               VARCHAR(512),
                                             content             TEXT           NOT NULL,
                                             content_sha256      CHAR(64)       NOT NULL,
                                             indexed_generation  BIGINT,
                                             status              VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
                                             chunk_count         INTEGER        NOT NULL DEFAULT 0,
                                             attempt_count       INTEGER        NOT NULL DEFAULT 0,
                                             metadata            JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                             last_error          TEXT,
                                             last_attempted_at   TIMESTAMPTZ(6),
                                             indexed_at          TIMESTAMPTZ(6),
                                             created_at          TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                             updated_at          TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                             CONSTRAINT rag_documents_status_chk
                                                 CHECK (status IN ('PENDING', 'PROCESSING', 'INDEXED', 'FAILED', 'DELETING'))
);

CREATE INDEX IF NOT EXISTS idx_rag_documents_status
    ON rag_documents(status);
CREATE INDEX IF NOT EXISTS idx_rag_documents_source_type
    ON rag_documents(source_type);
CREATE INDEX IF NOT EXISTS idx_rag_documents_external_ref
    ON rag_documents(external_ref);
CREATE INDEX IF NOT EXISTS idx_rag_documents_source_uri
    ON rag_documents(source_uri);
CREATE INDEX IF NOT EXISTS idx_rag_documents_indexed_generation
    ON rag_documents(indexed_generation);
CREATE INDEX IF NOT EXISTS idx_rag_documents_title_fts
    ON rag_documents USING GIN (to_tsvector('simple', COALESCE(title, '')));
CREATE INDEX IF NOT EXISTS idx_rag_documents_title_trgm
    ON rag_documents USING GIN (LOWER(COALESCE(title, '')) gin_trgm_ops);

CREATE TABLE IF NOT EXISTS rag_index_jobs (
                                              id                BIGSERIAL PRIMARY KEY,
                                              document_id       BIGINT         NOT NULL,
                                              content_sha256    CHAR(64)       NOT NULL,
                                              status            VARCHAR(16)    NOT NULL DEFAULT 'QUEUED',
                                              stage             VARCHAR(32)    NOT NULL DEFAULT 'QUEUED',
                                              version           BIGINT         NOT NULL DEFAULT 0,
                                              last_event        VARCHAR(64),
                                              attempt_count     INTEGER        NOT NULL DEFAULT 0,
                                              target_generation BIGINT,
                                              message_id        VARCHAR(128),
                                              last_error        TEXT,
                                              started_at        TIMESTAMPTZ(6),
                                              finished_at       TIMESTAMPTZ(6),
                                              created_at        TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                              updated_at        TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                              CONSTRAINT uq_rag_index_jobs_document_sha
                                                  UNIQUE (document_id, content_sha256),
                                              CONSTRAINT rag_index_jobs_status_chk
                                                  CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
                                              CONSTRAINT rag_index_jobs_stage_chk
                                                  CHECK (stage IN (
                                                                   'QUEUED', 'DISPATCHING', 'PREPARING', 'CHUNKING', 'SAVE_CHUNKS',
                                                                   'VECTOR_INDEXING', 'COMMIT_INDEX', 'COMPLETED', 'SKIPPED'
                                                      ))
);

CREATE INDEX IF NOT EXISTS idx_rag_index_jobs_status
    ON rag_index_jobs(status);
CREATE INDEX IF NOT EXISTS idx_rag_index_jobs_stage
    ON rag_index_jobs(stage);
CREATE INDEX IF NOT EXISTS idx_rag_index_jobs_document_id
    ON rag_index_jobs(document_id);
CREATE INDEX IF NOT EXISTS idx_rag_index_jobs_document_sha_version
    ON rag_index_jobs(document_id, content_sha256, version);

CREATE TABLE IF NOT EXISTS rag_index_job_transitions (
                                                         id             BIGSERIAL PRIMARY KEY,
                                                         document_id    BIGINT         NOT NULL,
                                                         job_id         BIGINT,
                                                         outbox_id      BIGINT,
                                                         content_sha256 CHAR(64)       NOT NULL,
                                                         from_state     VARCHAR(32),
                                                         to_state       VARCHAR(32)    NOT NULL,
                                                         event          VARCHAR(64)    NOT NULL,
                                                         trigger_type   VARCHAR(32)    NOT NULL,
                                                         triggered_by   VARCHAR(255),
                                                         success        BOOLEAN        NOT NULL DEFAULT TRUE,
                                                         failure_reason VARCHAR(64),
                                                         error_message  TEXT,
                                                         message_id     VARCHAR(128),
                                                         metadata       JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                                         created_at     TIMESTAMPTZ(6) NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rag_index_job_transitions_document_created
    ON rag_index_job_transitions(document_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rag_index_job_transitions_document_sha_created
    ON rag_index_job_transitions(document_id, content_sha256, created_at);

CREATE TABLE IF NOT EXISTS rag_index_outbox (
                                                id              BIGSERIAL PRIMARY KEY,
                                                document_id     BIGINT         NOT NULL,
                                                content_sha256  CHAR(64)       NOT NULL,
                                                event_type      VARCHAR(32)    NOT NULL,
                                                status          VARCHAR(16)    NOT NULL DEFAULT 'NEW',
                                                attempt_count   INTEGER        NOT NULL DEFAULT 0,
                                                message_id      VARCHAR(128),
                                                last_error      TEXT,
                                                next_attempt_at TIMESTAMPTZ(6),
                                                dispatched_at   TIMESTAMPTZ(6),
                                                consumed_at     TIMESTAMPTZ(6),
                                                created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                                updated_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                                CONSTRAINT uq_rag_index_outbox_document_sha_event
                                                    UNIQUE (document_id, content_sha256, event_type),
                                                CONSTRAINT rag_index_outbox_status_chk
                                                    CHECK (status IN ('NEW', 'SENDING', 'SENT', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_rag_index_outbox_status_next_attempt
    ON rag_index_outbox(status, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_rag_index_outbox_document_id
    ON rag_index_outbox(document_id);
CREATE INDEX IF NOT EXISTS idx_rag_index_outbox_message_id
    ON rag_index_outbox(message_id);

CREATE TABLE IF NOT EXISTS rag_index_message_failures (
                                                          id               BIGSERIAL PRIMARY KEY,
                                                          message_id       VARCHAR(128)    NOT NULL,
                                                          topic            VARCHAR(255)    NOT NULL,
                                                          delivery_attempt INTEGER         NOT NULL,
                                                          failure_type     VARCHAR(32)     NOT NULL,
                                                          error_message    TEXT,
                                                          payload_base64   TEXT,
                                                          payload_preview  TEXT,
                                                          properties_json  JSONB           NOT NULL DEFAULT '{}'::jsonb,
                                                          created_at       TIMESTAMPTZ(6)  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rag_index_message_failures_message_created
    ON rag_index_message_failures(message_id, created_at);

CREATE TABLE IF NOT EXISTS rag_chunks (
                                          id               BIGSERIAL PRIMARY KEY,
                                          document_id      BIGINT         NOT NULL,
                                          index_generation BIGINT         NOT NULL DEFAULT 1,
                                          chunk_index      INTEGER        NOT NULL,
                                          chunk_text       TEXT           NOT NULL,
                                          chunk_hash       CHAR(64)       NOT NULL,
                                          char_count       INTEGER        NOT NULL DEFAULT 0,
                                          token_count      INTEGER,
                                          vector_id        VARCHAR(128),
                                          metadata         JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                          created_at       TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                          updated_at       TIMESTAMPTZ(6) NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_chunks_document_generation_chunk_unique
    ON rag_chunks(document_id, index_generation, chunk_index);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_document_id
    ON rag_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_document_generation
    ON rag_chunks(document_id, index_generation, chunk_index);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_vector_id
    ON rag_chunks(vector_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_chunk_hash
    ON rag_chunks(chunk_hash);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_chunk_text_fts
    ON rag_chunks USING GIN (to_tsvector('simple', COALESCE(chunk_text, '')));
CREATE INDEX IF NOT EXISTS idx_rag_chunks_heading_path_text_fts
    ON rag_chunks USING GIN (to_tsvector('simple', COALESCE(metadata->>'headingPathText', '')));
CREATE INDEX IF NOT EXISTS idx_rag_chunks_chunk_text_trgm
    ON rag_chunks USING GIN (LOWER(COALESCE(chunk_text, '')) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_heading_path_text_trgm
    ON rag_chunks USING GIN (LOWER(COALESCE(metadata->>'headingPathText', '')) gin_trgm_ops);

CREATE TABLE IF NOT EXISTS rag_embedding_cache (
                                                   chunk_hash          CHAR(64)       NOT NULL,
                                                   embedding_model     VARCHAR(255)   NOT NULL,
                                                   embedding_dimension INTEGER        NOT NULL,
                                                   embedding_json      TEXT           NOT NULL,
                                                   created_at          TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                                   updated_at          TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                                   PRIMARY KEY (chunk_hash, embedding_model, embedding_dimension)
);

CREATE INDEX IF NOT EXISTS idx_rag_embedding_cache_model
    ON rag_embedding_cache(embedding_model, embedding_dimension);

CREATE TABLE IF NOT EXISTS rag_users (
                                         id            BIGSERIAL PRIMARY KEY,
                                         user_id       VARCHAR(128)   NOT NULL UNIQUE,
                                         metadata      JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                         first_seen_at TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                         last_seen_at  TIMESTAMPTZ(6) NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS rag_conversations (
                                                 id              BIGSERIAL PRIMARY KEY,
                                                 conversation_id VARCHAR(128)   NOT NULL UNIQUE,
                                                 user_id         VARCHAR(128)   NOT NULL,
                                                 title           VARCHAR(200),
                                                 status          VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
                                                 message_count   INTEGER        NOT NULL DEFAULT 0,
                                                 metadata        JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                                 created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                                 updated_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                                 last_message_at TIMESTAMPTZ(6),
                                                 CONSTRAINT rag_conversations_status_chk
                                                     CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED'))
);

CREATE INDEX IF NOT EXISTS idx_rag_conversations_user_cursor
    ON rag_conversations(user_id, last_message_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS rag_conversation_messages (
                                                         id              BIGSERIAL PRIMARY KEY,
                                                         message_id      VARCHAR(128)   NOT NULL UNIQUE,
                                                         conversation_id BIGINT         NOT NULL,
                                                         role            VARCHAR(16)    NOT NULL,
                                                         content         TEXT           NOT NULL,
                                                         status          VARCHAR(16)    NOT NULL DEFAULT 'SUCCEEDED',
                                                         token_count     INTEGER,
                                                         correlation_id  VARCHAR(128),
                                                         sequence_no     INTEGER        NOT NULL,
                                                         metadata        JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                                         created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                                         CONSTRAINT rag_messages_role_chk
                                                             CHECK (role IN ('user', 'assistant', 'system')),
                                                         CONSTRAINT rag_messages_status_chk
                                                             CHECK (status IN ('PENDING', 'STREAMING', 'SUCCEEDED', 'FAILED')),
                                                         CONSTRAINT uq_rag_messages_conversation_sequence
                                                             UNIQUE (conversation_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS idx_rag_messages_conversation_sequence
    ON rag_conversation_messages(conversation_id, sequence_no);

CREATE TABLE IF NOT EXISTS rag_ask_runs (
                                            id                   BIGSERIAL PRIMARY KEY,
                                            run_id               VARCHAR(128)   NOT NULL UNIQUE,
                                            correlation_id       VARCHAR(128)   NOT NULL UNIQUE,
                                            user_id              VARCHAR(128)   NOT NULL,
                                            conversation_id      BIGINT         NOT NULL,
                                            user_message_id      BIGINT,
                                            assistant_message_id BIGINT,
                                            request_id           VARCHAR(128),
                                            question             TEXT           NOT NULL,
                                            retrieval_question   TEXT,
                                            top_k                INTEGER,
                                            filters              JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                            retrieval_queries    JSONB          NOT NULL DEFAULT '[]'::jsonb,
                                            retrieved_contexts   JSONB          NOT NULL DEFAULT '[]'::jsonb,
                                            notices              JSONB          NOT NULL DEFAULT '[]'::jsonb,
                                            generated_by_model   BOOLEAN        NOT NULL DEFAULT FALSE,
                                            degraded             BOOLEAN        NOT NULL DEFAULT FALSE,
                                            status               VARCHAR(16)    NOT NULL DEFAULT 'RUNNING',
                                            error_code           VARCHAR(64),
                                            error_message        TEXT,
                                            started_at           TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                            completed_at         TIMESTAMPTZ(6),
                                            CONSTRAINT rag_ask_runs_status_chk
                                                CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_rag_ask_runs_user_started
    ON rag_ask_runs(user_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_rag_ask_runs_conversation_started
    ON rag_ask_runs(conversation_id, started_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_rag_ask_runs_request
    ON rag_ask_runs(user_id, conversation_id, request_id)
    WHERE request_id IS NOT NULL;

-- ============== Agent 模块 ==============
CREATE TABLE IF NOT EXISTS agent_turn (
                                          id                   BIGSERIAL PRIMARY KEY,
                                          turn_id              VARCHAR(64)    NOT NULL UNIQUE,
                                          correlation_id       VARCHAR(64)    NOT NULL UNIQUE,
                                          user_id              VARCHAR(64)    NOT NULL,
                                          conversation_id      VARCHAR(64)    NOT NULL,
                                          request_id           VARCHAR(64),
                                          user_message_id      VARCHAR(64),
                                          assistant_message_id VARCHAR(64),
                                          status               VARCHAR(16)    NOT NULL DEFAULT 'RUNNING',
                                          user_message         TEXT           NOT NULL,
                                          intent               VARCHAR(32),
                                          intent_source        VARCHAR(16),
                                          intent_confidence    NUMERIC(4, 3),
                                          slots_json           JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                          tools_called         JSONB          NOT NULL DEFAULT '[]'::jsonb,
                                          cards_emitted        JSONB          NOT NULL DEFAULT '[]'::jsonb,
                                          generated_by_model   BOOLEAN,
                                          answer_text          TEXT,
                                          memory_summary       TEXT,
                                          memory_summary_message_count INTEGER,
                                          memory_summary_model VARCHAR(64),
                                          tokens_in            INTEGER,
                                          tokens_out           INTEGER,
                                          latency_ms           INTEGER,
                                          error_code           VARCHAR(64),
                                          error_message        TEXT,
                                          started_at           TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                          completed_at         TIMESTAMPTZ(6),
                                          CONSTRAINT agent_turn_status_chk
                                              CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
                                          CONSTRAINT agent_turn_intent_source_chk
                                              CHECK (intent_source IN ('rule_l1', 'rule_l2', 'llm', 'fallback', 'workflow'))
);
-- 老库迁移：CREATE TABLE IF NOT EXISTS 不会更新已存在表的 CHECK，
-- 这里幂等地放宽 intent_source 白名单以接纳 workflow turn。
ALTER TABLE agent_turn DROP CONSTRAINT IF EXISTS agent_turn_intent_source_chk;
ALTER TABLE agent_turn ADD CONSTRAINT agent_turn_intent_source_chk
    CHECK (intent_source IN ('rule_l1', 'rule_l2', 'llm', 'fallback', 'workflow'));
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_turn_idempotency
    ON agent_turn(user_id, conversation_id, request_id)
    WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_agent_turn_conv_started
    ON agent_turn(conversation_id, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_turn_user_started
    ON agent_turn(user_id, started_at DESC);

-- workflow runtime state for resume across processes (WAITING_SELECTION / WAITING_CONFIRMATION / WAITING_SLOT)
CREATE TABLE IF NOT EXISTS agent_workflow_state (
                                          id              BIGSERIAL PRIMARY KEY,
                                          conversation_id VARCHAR(64)    NOT NULL UNIQUE,
                                          user_id         VARCHAR(64)    NOT NULL DEFAULT '',
                                          workflow_key    VARCHAR(64)    NOT NULL,
                                          version         INTEGER        NOT NULL DEFAULT 1,
                                          current_node    VARCHAR(64),
                                          status          VARCHAR(32)    NOT NULL,
                                          state_json      JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                          created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                          updated_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                          CONSTRAINT agent_workflow_state_status_chk
                                              CHECK (status IN ('RUNNING', 'END', 'WAITING_CONFIRMATION', 'WAITING_SELECTION', 'WAITING_SLOT', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_agent_workflow_state_user
    ON agent_workflow_state(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_workflow_state_status
    ON agent_workflow_state(status);

-- ============== Cart 模块 ==============
CREATE TABLE IF NOT EXISTS shopping_cart (
                                             id                    BIGSERIAL PRIMARY KEY,
                                             cart_id               VARCHAR(64)    NOT NULL UNIQUE,
                                             user_id               VARCHAR(64)    NOT NULL,
                                             conversation_id       VARCHAR(64)    NOT NULL,
                                             state                 VARCHAR(32)    NOT NULL DEFAULT 'IDLE',
                                             currency              VARCHAR(8)     NOT NULL DEFAULT 'CNY',
                                             subtotal_amount       NUMERIC(12, 2) NOT NULL DEFAULT 0,
                                             item_count            INTEGER        NOT NULL DEFAULT 0,
                                             shipping_address_json JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                             version               BIGINT         NOT NULL DEFAULT 0,
                                             created_at            TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                             updated_at            TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                             CONSTRAINT shopping_cart_state_chk
                                                 CHECK (state IN ('IDLE', 'ITEM_PROPOSED', 'IN_CART', 'CHECKING_OUT', 'PLACED', 'CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_shopping_cart_user_conversation
    ON shopping_cart(user_id, conversation_id);
CREATE INDEX IF NOT EXISTS idx_shopping_cart_state
    ON shopping_cart(state);

CREATE TABLE IF NOT EXISTS cart_item (
                                         id             BIGSERIAL PRIMARY KEY,
                                         cart_id        BIGINT         NOT NULL,
                                         spu_id         BIGINT         NOT NULL,
                                         external_ref   VARCHAR(64),
                                         title          VARCHAR(255)   NOT NULL,
                                         brand          VARCHAR(64),
                                         image_url      VARCHAR(512),
                                         quantity       INTEGER        NOT NULL DEFAULT 1,
                                         unit_price     NUMERIC(10, 2),
                                         stock_snapshot INTEGER,
                                         status         VARCHAR(16)    NOT NULL DEFAULT 'ACTIVE',
                                         created_at     TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                         updated_at     TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                         CONSTRAINT cart_item_status_chk CHECK (status IN ('ACTIVE', 'REMOVED')),
                                         CONSTRAINT cart_item_quantity_chk CHECK (quantity > 0)
);
CREATE INDEX IF NOT EXISTS idx_cart_item_cart_status
    ON cart_item(cart_id, status);
CREATE INDEX IF NOT EXISTS idx_cart_item_spu
    ON cart_item(spu_id);

CREATE TABLE IF NOT EXISTS cart_transition_audit (
                                                     id             BIGSERIAL PRIMARY KEY,
                                                     cart_id        BIGINT,
                                                     business_cart_id VARCHAR(64),
                                                     from_state     VARCHAR(32),
                                                     to_state       VARCHAR(32)    NOT NULL,
                                                     event          VARCHAR(32)    NOT NULL,
                                                     triggered_by   VARCHAR(64),
                                                     success        BOOLEAN        NOT NULL DEFAULT TRUE,
                                                     failure_reason VARCHAR(64),
                                                     error_message  TEXT,
                                                     metadata       JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                                     created_at     TIMESTAMPTZ(6) NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cart_transition_audit_cart_created
    ON cart_transition_audit(cart_id, created_at);
CREATE INDEX IF NOT EXISTS idx_cart_transition_audit_business_cart_created
    ON cart_transition_audit(business_cart_id, created_at);

-- ============== Order 模块 ==============
CREATE TABLE IF NOT EXISTS delivery_address (
                                                id              BIGSERIAL PRIMARY KEY,
                                                user_id         VARCHAR(64)    NOT NULL,
                                                receiver_name   VARCHAR(128)   NOT NULL,
                                                phone           VARCHAR(64)    NOT NULL,
                                                province        VARCHAR(128),
                                                city            VARCHAR(128),
                                                district        VARCHAR(128),
                                                detail          VARCHAR(512)   NOT NULL,
                                                postal_code     VARCHAR(32),
                                                is_default      BOOLEAN        NOT NULL DEFAULT FALSE,
                                                created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                                updated_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_delivery_address_user_default
    ON delivery_address(user_id, is_default);

CREATE TABLE IF NOT EXISTS customer_order (
                                              id                    BIGSERIAL PRIMARY KEY,
                                              order_id              VARCHAR(64)    NOT NULL UNIQUE,
                                              cart_id               VARCHAR(64),
                                              user_id               VARCHAR(64)    NOT NULL,
                                              conversation_id       VARCHAR(64)    NOT NULL,
                                              status                VARCHAR(32)    NOT NULL DEFAULT 'PLACED',
                                              currency              VARCHAR(8)     NOT NULL DEFAULT 'CNY',
                                              subtotal_amount       NUMERIC(12, 2) NOT NULL DEFAULT 0,
                                              item_count            INTEGER        NOT NULL DEFAULT 0,
                                              delivery_address_id   BIGINT,
                                              delivery_address_json JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                              price_change_json     JSONB          NOT NULL DEFAULT '[]'::jsonb,
                                              placed_at             TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                              created_at            TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                              updated_at            TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                              CONSTRAINT customer_order_status_chk
                                                  CHECK (status IN ('PLACED', 'CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_customer_order_user_created
    ON customer_order(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_customer_order_cart_id
    ON customer_order(cart_id);

CREATE TABLE IF NOT EXISTS order_item (
                                          id             BIGSERIAL PRIMARY KEY,
                                          order_id       BIGINT         NOT NULL,
                                          spu_id         BIGINT         NOT NULL,
                                          external_ref   VARCHAR(64),
                                          title          VARCHAR(255)   NOT NULL,
                                          brand          VARCHAR(64),
                                          image_url      VARCHAR(512),
                                          quantity       INTEGER        NOT NULL,
                                          unit_price     NUMERIC(10, 2),
                                          line_amount    NUMERIC(12, 2),
                                          created_at     TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                          CONSTRAINT order_item_quantity_chk CHECK (quantity > 0)
);
CREATE INDEX IF NOT EXISTS idx_order_item_order_id
    ON order_item(order_id);
CREATE INDEX IF NOT EXISTS idx_order_item_spu
    ON order_item(spu_id);

-- 数据库层禁止使用外键约束；兼容已初始化过的环境，显式移除历史外键。
ALTER TABLE rag_conversations
    DROP CONSTRAINT IF EXISTS fk_rag_conversations_user;
ALTER TABLE rag_conversation_messages
    DROP CONSTRAINT IF EXISTS fk_rag_messages_conversation;
ALTER TABLE rag_ask_runs
    DROP CONSTRAINT IF EXISTS fk_rag_ask_runs_conversation;
ALTER TABLE rag_ask_runs
    DROP CONSTRAINT IF EXISTS fk_rag_ask_runs_user_message;
ALTER TABLE rag_ask_runs
    DROP CONSTRAINT IF EXISTS fk_rag_ask_runs_assistant_message;

-- ============== Catalog 模块 ==============
-- 电商商品主数据：SPU 业务字段独立于 rag_documents；导入时通过 document 模块对外暴露的
-- DocumentCommandFacade.createDocument(...) 双写一行 rag_documents（source_type='catalog-spu'），
-- 由 catalog_spu.document_id 1:1 回填，触发既有 indexing 链路。indexing 模块无需感知 catalog。
-- 项目约定数据库层不使用外键约束，所有关联在应用层维护（参考其它表的 DROP CONSTRAINT 语句）。
CREATE TABLE IF NOT EXISTS catalog_spu (
                                           id                       BIGSERIAL PRIMARY KEY,
                                           external_ref             VARCHAR(64)  NOT NULL UNIQUE,
                                           title                    VARCHAR(255) NOT NULL,
                                           brand                    VARCHAR(64),
                                           category_path            VARCHAR(255),
                                           price_min                NUMERIC(10, 2),
                                           price_max                NUMERIC(10, 2),
                                           stock                    INTEGER      NOT NULL DEFAULT 0,
                                           description_md           TEXT,
                                           images                   JSONB        NOT NULL DEFAULT '[]'::jsonb,
                                           video_url                VARCHAR(512),
                                           attributes_json          JSONB        NOT NULL DEFAULT '{}'::jsonb,
                                           attributes_status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
                                           attributes_attempt_count INTEGER      NOT NULL DEFAULT 0,
                                           attributes_last_error    TEXT,
                                           attributes_attempted_at  TIMESTAMPTZ(6),
                                           status                   VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
                                           version                  BIGINT       NOT NULL DEFAULT 0,
                                           document_id              BIGINT,
                                           created_at               TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                           updated_at               TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                           CONSTRAINT catalog_spu_status_chk
                                               CHECK (status IN ('ACTIVE', 'DRAFT', 'REMOVED')),
                                           CONSTRAINT catalog_spu_attr_status_chk
                                               CHECK (attributes_status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'SKIPPED'))
);
CREATE INDEX IF NOT EXISTS idx_catalog_spu_attr_status ON catalog_spu (attributes_status);
CREATE INDEX IF NOT EXISTS idx_catalog_spu_category    ON catalog_spu (category_path);
CREATE INDEX IF NOT EXISTS idx_catalog_spu_brand       ON catalog_spu (brand);
CREATE INDEX IF NOT EXISTS idx_catalog_spu_document_id ON catalog_spu (document_id);

CREATE TABLE IF NOT EXISTS catalog_sku (
                                           id          BIGSERIAL PRIMARY KEY,
                                           spu_id      BIGINT        NOT NULL,
                                           sku_code    VARCHAR(64)   NOT NULL,
                                           spec_json   JSONB         NOT NULL DEFAULT '{}'::jsonb,
                                           price       NUMERIC(10, 2) NOT NULL,
                                           stock       INTEGER       NOT NULL DEFAULT 0,
                                           status      VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',
                                           created_at  TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                           updated_at  TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                           CONSTRAINT catalog_sku_status_chk CHECK (status IN ('ACTIVE', 'REMOVED')),
                                           CONSTRAINT uq_catalog_sku_code UNIQUE (spu_id, sku_code)
);
CREATE INDEX IF NOT EXISTS idx_catalog_sku_spu_id ON catalog_sku (spu_id);

-- catalog 抽属性 Outbox：导入事务内同步写一行，dispatcher 扫描 PENDING 发 RocketMQ；
-- 与 rag_index_outbox 等价的可靠性级别，但只跨 catalog 侧的属性抽取链路使用。
CREATE TABLE IF NOT EXISTS catalog_attribute_outbox (
                                                        id              BIGSERIAL PRIMARY KEY,
                                                        spu_id          BIGINT         NOT NULL,
                                                        external_ref    VARCHAR(64)    NOT NULL,
                                                        payload_json    JSONB          NOT NULL DEFAULT '{}'::jsonb,
                                                        status          VARCHAR(16)    NOT NULL DEFAULT 'PENDING',
                                                        attempt_count   INTEGER        NOT NULL DEFAULT 0,
                                                        last_error      TEXT,
                                                        next_send_after TIMESTAMPTZ(6),
                                                        message_id      VARCHAR(128),
                                                        created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                                        updated_at      TIMESTAMPTZ(6) NOT NULL DEFAULT now(),
                                                        CONSTRAINT catalog_attr_outbox_status_chk
                                                            CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_catalog_attr_outbox_status_next
    ON catalog_attribute_outbox(status, next_send_after);
CREATE INDEX IF NOT EXISTS idx_catalog_attr_outbox_spu_id
    ON catalog_attribute_outbox(spu_id);

COMMIT;
