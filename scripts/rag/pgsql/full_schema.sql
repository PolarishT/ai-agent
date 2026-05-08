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
    CONSTRAINT fk_rag_conversations_user
        FOREIGN KEY (user_id) REFERENCES rag_users(user_id),
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
    CONSTRAINT fk_rag_messages_conversation
        FOREIGN KEY (conversation_id) REFERENCES rag_conversations(id) ON DELETE CASCADE,
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
    CONSTRAINT fk_rag_ask_runs_conversation
        FOREIGN KEY (conversation_id) REFERENCES rag_conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_rag_ask_runs_user_message
        FOREIGN KEY (user_message_id) REFERENCES rag_conversation_messages(id),
    CONSTRAINT fk_rag_ask_runs_assistant_message
        FOREIGN KEY (assistant_message_id) REFERENCES rag_conversation_messages(id),
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

COMMIT;
