-- Complete PostgreSQL DDL for the RAG.
-- Intended for a fresh PostgreSQL database.
-- Cleaned version:
-- 1. Removed owner binding: ALTER TABLE ... OWNER TO neondb_owner.
-- 2. Fixed exported sequence dependencies by using bigserial.
-- 3. Removed PostgreSQL system columns from agent_turn.
-- 4. No foreign keys: relations are enforced at application layer.
-- 5. Kept pg_trgm and trigram/FTS indexes.

BEGIN;

-- Optional but recommended for hybrid keyword retrieval.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- =========================================================
-- RAG documents
-- =========================================================

CREATE TABLE public.rag_documents
(
    id                 bigserial PRIMARY KEY,
    source_type        varchar(32)                                  NOT NULL,
    source_uri         text,
    external_ref       varchar(255),
    title              varchar(512),
    content            text                                         NOT NULL,
    content_sha256     char(64)                                     NOT NULL,
    indexed_generation bigint,
    status             varchar(16) DEFAULT 'PENDING'                NOT NULL
        CONSTRAINT rag_documents_status_chk
            CHECK (status IN ('PENDING', 'PROCESSING', 'INDEXED', 'FAILED', 'DELETING')),
    chunk_count        integer DEFAULT 0                            NOT NULL,
    attempt_count      integer DEFAULT 0                            NOT NULL,
    metadata           jsonb   DEFAULT '{}'::jsonb                  NOT NULL,
    last_error         text,
    last_attempted_at  timestamp(6) with time zone,
    indexed_at         timestamp(6) with time zone,
    created_at         timestamp(6) with time zone DEFAULT now()    NOT NULL,
    updated_at         timestamp(6) with time zone DEFAULT now()    NOT NULL
);

CREATE INDEX idx_rag_documents_status
    ON public.rag_documents (status);

CREATE INDEX idx_rag_documents_source_type
    ON public.rag_documents (source_type);

CREATE INDEX idx_rag_documents_external_ref
    ON public.rag_documents (external_ref);

CREATE INDEX idx_rag_documents_source_uri
    ON public.rag_documents (source_uri);

CREATE UNIQUE INDEX uq_rag_documents_source_uri_source_type
    ON public.rag_documents (source_uri, source_type)
    WHERE source_uri IS NOT NULL;

CREATE INDEX idx_rag_documents_indexed_generation
    ON public.rag_documents (indexed_generation);

CREATE INDEX idx_rag_documents_title_fts
    ON public.rag_documents
        USING gin (to_tsvector('simple'::regconfig, COALESCE(title, '')::text));

CREATE INDEX idx_rag_documents_title_trgm
    ON public.rag_documents
        USING gin (lower(COALESCE(title, '')::text) public.gin_trgm_ops);

-- =========================================================
-- RAG index jobs
-- =========================================================

CREATE TABLE public.rag_index_jobs
(
    id                bigserial PRIMARY KEY,
    document_id       bigint                                      NOT NULL,
    content_sha256    char(64)                                    NOT NULL,
    status            varchar(16) DEFAULT 'QUEUED'                NOT NULL
        CONSTRAINT rag_index_jobs_status_chk
            CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    stage             varchar(32) DEFAULT 'QUEUED'                NOT NULL
        CONSTRAINT rag_index_jobs_stage_chk
            CHECK (stage IN (
                             'QUEUED',
                             'DISPATCHING',
                             'PREPARING',
                             'CHUNKING',
                             'SAVE_CHUNKS',
                             'VECTOR_INDEXING',
                             'COMMIT_INDEX',
                             'COMPLETED',
                             'SKIPPED'
                )),
    version           bigint  DEFAULT 0                           NOT NULL,
    last_event        varchar(64),
    attempt_count     integer DEFAULT 0                            NOT NULL,
    target_generation bigint,
    message_id        varchar(128),
    last_error        text,
    started_at        timestamp(6) with time zone,
    finished_at       timestamp(6) with time zone,
    created_at        timestamp(6) with time zone DEFAULT now()    NOT NULL,
    updated_at        timestamp(6) with time zone DEFAULT now()    NOT NULL,
    CONSTRAINT uq_rag_index_jobs_document_sha
        UNIQUE (document_id, content_sha256)
);

CREATE INDEX idx_rag_index_jobs_status
    ON public.rag_index_jobs (status);

CREATE INDEX idx_rag_index_jobs_stage
    ON public.rag_index_jobs (stage);

CREATE INDEX idx_rag_index_jobs_document_id
    ON public.rag_index_jobs (document_id);

CREATE INDEX idx_rag_index_jobs_document_sha_version
    ON public.rag_index_jobs (document_id, content_sha256, version);

-- =========================================================
-- RAG index job transitions
-- =========================================================

CREATE TABLE public.rag_index_job_transitions
(
    id             bigserial PRIMARY KEY,
    document_id    bigint                                      NOT NULL,
    job_id         bigint,
    outbox_id      bigint,
    content_sha256 char(64)                                    NOT NULL,
    from_state     varchar(32),
    to_state       varchar(32)                                 NOT NULL,
    event          varchar(64)                                 NOT NULL,
    trigger_type   varchar(32)                                 NOT NULL,
    triggered_by   varchar(255),
    success        boolean DEFAULT true                        NOT NULL,
    failure_reason varchar(64),
    error_message  text,
    message_id     varchar(128),
    metadata       jsonb   DEFAULT '{}'::jsonb                 NOT NULL,
    created_at     timestamp(6) with time zone DEFAULT now()   NOT NULL
);

CREATE INDEX idx_rag_index_job_transitions_document_created
    ON public.rag_index_job_transitions (document_id, created_at);

CREATE INDEX idx_rag_index_job_transitions_document_sha_created
    ON public.rag_index_job_transitions (document_id, content_sha256, created_at);

-- =========================================================
-- RAG index outbox
-- =========================================================

CREATE TABLE public.rag_index_outbox
(
    id              bigserial PRIMARY KEY,
    document_id     bigint                                    NOT NULL,
    content_sha256  char(64)                                  NOT NULL,
    event_type      varchar(32)                               NOT NULL,
    status          varchar(16) DEFAULT 'NEW'                 NOT NULL
        CONSTRAINT rag_index_outbox_status_chk
            CHECK (status IN ('NEW', 'SENDING', 'SENT', 'FAILED')),
    attempt_count   integer DEFAULT 0                         NOT NULL,
    message_id      varchar(128),
    last_error      text,
    next_attempt_at timestamp(6) with time zone,
    dispatched_at   timestamp(6) with time zone,
    consumed_at     timestamp(6) with time zone,
    created_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_rag_index_outbox_document_sha_event
        UNIQUE (document_id, content_sha256, event_type)
);

CREATE INDEX idx_rag_index_outbox_status_next_attempt
    ON public.rag_index_outbox (status, next_attempt_at);

CREATE INDEX idx_rag_index_outbox_document_id
    ON public.rag_index_outbox (document_id);

CREATE INDEX idx_rag_index_outbox_message_id
    ON public.rag_index_outbox (message_id);

-- =========================================================
-- RAG index message failures
-- =========================================================

CREATE TABLE public.rag_index_message_failures
(
    id               bigserial PRIMARY KEY,
    message_id       varchar(128)                              NOT NULL,
    topic            varchar(255)                              NOT NULL,
    delivery_attempt integer                                   NOT NULL,
    failure_type     varchar(32)                               NOT NULL,
    error_message    text,
    payload_base64   text,
    payload_preview  text,
    properties_json  jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at       timestamp(6) with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_rag_index_message_failures_message_created
    ON public.rag_index_message_failures (message_id, created_at);

-- =========================================================
-- RAG chunks
-- =========================================================

CREATE TABLE public.rag_chunks
(
    id               bigserial PRIMARY KEY,
    document_id      bigint                                    NOT NULL,
    product_id       bigint,
    source_type      varchar(64),
    index_generation bigint  DEFAULT 1                         NOT NULL,
    chunk_index      integer                                   NOT NULL,
    chunk_type       varchar(64),
    heading_path     text,
    chunk_text       text                                      NOT NULL,
    chunk_hash       char(64)                                  NOT NULL,
    char_count       integer DEFAULT 0                         NOT NULL,
    token_count      integer,
    vector_id        varchar(128)                              NOT NULL,
    metadata         jsonb   DEFAULT '{}'::jsonb               NOT NULL,
    created_at       timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at       timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_rag_chunks_document_generation_chunk
        UNIQUE (document_id, index_generation, chunk_index),
    CONSTRAINT uq_rag_chunks_vector_id
        UNIQUE (vector_id)
);

CREATE INDEX idx_rag_chunks_document_id
    ON public.rag_chunks (document_id);

CREATE INDEX idx_rag_chunks_product_id
    ON public.rag_chunks (product_id)
    WHERE product_id IS NOT NULL;

CREATE INDEX idx_rag_chunks_source_type
    ON public.rag_chunks (source_type)
    WHERE source_type IS NOT NULL;

CREATE INDEX idx_rag_chunks_chunk_type
    ON public.rag_chunks (chunk_type)
    WHERE chunk_type IS NOT NULL;

CREATE INDEX idx_rag_chunks_document_generation
    ON public.rag_chunks (document_id, index_generation, chunk_index);

CREATE INDEX idx_rag_chunks_chunk_hash
    ON public.rag_chunks (chunk_hash);

CREATE INDEX idx_rag_chunks_chunk_text_fts
    ON public.rag_chunks
        USING gin (to_tsvector('simple'::regconfig, COALESCE(chunk_text, ''::text)));

CREATE INDEX idx_rag_chunks_heading_path_text_fts
    ON public.rag_chunks
        USING gin (to_tsvector('simple'::regconfig, COALESCE(heading_path, ''::text)));

CREATE INDEX idx_rag_chunks_chunk_text_trgm
    ON public.rag_chunks
        USING gin (lower(COALESCE(chunk_text, ''::text)) public.gin_trgm_ops);

CREATE INDEX idx_rag_chunks_heading_path_text_trgm
    ON public.rag_chunks
        USING gin (lower(COALESCE(heading_path, ''::text)) public.gin_trgm_ops);

-- =========================================================
-- RAG users
-- =========================================================

CREATE TABLE public.rag_users
(
    id            bigserial PRIMARY KEY,
    user_id       varchar(128)                              NOT NULL UNIQUE,
    metadata      jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    first_seen_at timestamp(6) with time zone DEFAULT now() NOT NULL,
    last_seen_at  timestamp(6) with time zone DEFAULT now() NOT NULL
);

-- =========================================================
-- Agent conversations
-- =========================================================

CREATE TABLE public.agent_conversations
(
    id              bigserial PRIMARY KEY,
    conversation_id varchar(128)                              NOT NULL UNIQUE,
    user_id         varchar(128)                              NOT NULL,
    title           varchar(200),
    status          varchar(16) DEFAULT 'ACTIVE'              NOT NULL
        CONSTRAINT rag_conversations_status_chk
            CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    message_count   integer DEFAULT 0                         NOT NULL,
    next_turn_seq   bigint  DEFAULT 0                         NOT NULL,
    metadata        jsonb   DEFAULT '{}'::jsonb               NOT NULL,
    created_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    last_message_at timestamp(6) with time zone
);

CREATE INDEX idx_rag_conversations_user_cursor
    ON public.agent_conversations (user_id ASC, last_message_at DESC, id DESC);

-- =========================================================
-- Agent conversation messages
-- =========================================================

CREATE TABLE public.agent_conversation_messages
(
    id              bigserial PRIMARY KEY,
    message_id      varchar(128)                              NOT NULL UNIQUE,
    conversation_id bigint                                    NOT NULL,
    role            varchar(16)                               NOT NULL
        CONSTRAINT rag_messages_role_chk
            CHECK (role IN ('user', 'assistant', 'system')),
    content         text                                      NOT NULL,
    status          varchar(16) DEFAULT 'SUCCEEDED'           NOT NULL
        CONSTRAINT rag_messages_status_chk
            CHECK (status IN ('PENDING', 'STREAMING', 'SUCCEEDED', 'FAILED')),
    token_count     integer,
    correlation_id  varchar(128),
    sequence_no     integer                                   NOT NULL,
    metadata        jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_rag_messages_conversation_sequence
        UNIQUE (conversation_id, sequence_no)
);

CREATE INDEX idx_rag_messages_conversation_sequence
    ON public.agent_conversation_messages (conversation_id, sequence_no);

-- =========================================================
-- RAG ask runs
-- =========================================================

CREATE TABLE public.rag_ask_runs
(
    id                   bigserial PRIMARY KEY,
    run_id               varchar(128)                              NOT NULL UNIQUE,
    correlation_id       varchar(128)                              NOT NULL UNIQUE,
    user_id              varchar(128)                              NOT NULL,
    conversation_id      bigint                                    NOT NULL,
    user_message_id      bigint,
    assistant_message_id bigint,
    request_id           varchar(128),
    question             text                                      NOT NULL,
    retrieval_question   text,
    top_k                integer,
    filters              jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    retrieval_queries    jsonb DEFAULT '[]'::jsonb                 NOT NULL,
    retrieved_contexts   jsonb DEFAULT '[]'::jsonb                 NOT NULL,
    notices              jsonb DEFAULT '[]'::jsonb                 NOT NULL,
    generated_by_model   boolean DEFAULT false                     NOT NULL,
    degraded             boolean DEFAULT false                     NOT NULL,
    status               varchar(16) DEFAULT 'RUNNING'             NOT NULL
        CONSTRAINT rag_ask_runs_status_chk
            CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error_code           varchar(64),
    error_message        text,
    started_at           timestamp(6) with time zone DEFAULT now() NOT NULL,
    completed_at         timestamp(6) with time zone
);

CREATE INDEX idx_rag_ask_runs_user_started
    ON public.rag_ask_runs (user_id ASC, started_at DESC);

CREATE INDEX idx_rag_ask_runs_conversation_started
    ON public.rag_ask_runs (conversation_id ASC, started_at DESC);

CREATE UNIQUE INDEX uq_rag_ask_runs_request
    ON public.rag_ask_runs (user_id, conversation_id, request_id)
    WHERE request_id IS NOT NULL;

-- =========================================================
-- Shopping cart
-- =========================================================

CREATE TABLE public.shopping_cart
(
    id                    bigserial PRIMARY KEY,
    cart_id               varchar(64)                               NOT NULL UNIQUE,
    user_id               varchar(64)                               NOT NULL,
    conversation_id       varchar(64)                               NOT NULL,
    state                 varchar(32) DEFAULT 'IDLE'                NOT NULL
        CONSTRAINT shopping_cart_state_chk
            CHECK (state IN ('IDLE', 'ITEM_PROPOSED', 'IN_CART', 'CHECKING_OUT', 'PLACED', 'CANCELLED')),
    currency              varchar(8) DEFAULT 'CNY'                  NOT NULL,
    subtotal_amount       numeric(12, 2) DEFAULT 0                  NOT NULL,
    item_count            integer DEFAULT 0                         NOT NULL,
    shipping_address_json jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    version               bigint DEFAULT 0                          NOT NULL,
    created_at            timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at            timestamp(6) with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_shopping_cart_user_conversation
    ON public.shopping_cart (user_id, conversation_id);

-- 同一 (user_id, conversation_id) 至多一辆「活跃」购物车（排除终态 PLACED / CANCELLED，
-- 这两态后允许为同一对话再开新车）。配合 JdbcShoppingCartRepository 的
-- INSERT ... ON CONFLICT DO NOTHING 抢占式创建，消除并发首次请求产生的重复活跃购物车。
CREATE UNIQUE INDEX uq_shopping_cart_active_owner
    ON public.shopping_cart (user_id, conversation_id)
    WHERE state NOT IN ('PLACED', 'CANCELLED');

CREATE INDEX idx_shopping_cart_state
    ON public.shopping_cart (state);

-- =========================================================
-- Cart item
-- =========================================================

CREATE TABLE public.cart_item
(
    id             bigserial PRIMARY KEY,
    cart_id        bigint                                    NOT NULL,
    spu_id         bigint                                    NOT NULL,
    sku_id         bigint,
    external_ref   varchar(64),
    title          varchar(255)                              NOT NULL,
    brand          varchar(64),
    image_url      varchar(512),
    quantity       integer DEFAULT 1                         NOT NULL
        CONSTRAINT cart_item_quantity_chk
            CHECK (quantity > 0),
    unit_price     numeric(10, 2),
    stock_snapshot integer,
    status         varchar(16) DEFAULT 'ACTIVE'              NOT NULL
        CONSTRAINT cart_item_status_chk
            CHECK (status IN ('ACTIVE', 'REMOVED')),
    created_at     timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at     timestamp(6) with time zone DEFAULT now() NOT NULL
);

-- 同一购物车内「同一 SKU 行」唯一，仅约束 ACTIVE 明细。
-- NULLS NOT DISTINCT（PG15+）让「无 SKU 的同一 SPU」也视为同一行，避免无 SKU 商品重复成行。
-- 配合 JdbcCartItemRepository 的 INSERT ... ON CONFLICT DO UPDATE 做到并发加购原子合并，杜绝重复明细。
CREATE UNIQUE INDEX uq_cart_item_active_line
    ON public.cart_item (cart_id, spu_id, sku_id) NULLS NOT DISTINCT
    WHERE status = 'ACTIVE';

CREATE INDEX idx_cart_item_cart_status
    ON public.cart_item (cart_id, status);

CREATE INDEX idx_cart_item_spu
    ON public.cart_item (spu_id);

CREATE INDEX idx_cart_item_sku
    ON public.cart_item (sku_id)
    WHERE sku_id IS NOT NULL;

-- =========================================================
-- Cart transition audit
-- =========================================================

CREATE TABLE public.cart_transition_audit
(
    id               bigserial PRIMARY KEY,
    cart_id          bigint,
    business_cart_id varchar(64),
    from_state       varchar(32),
    to_state         varchar(32)                               NOT NULL,
    event            varchar(32)                               NOT NULL,
    triggered_by     varchar(64),
    success          boolean DEFAULT true                      NOT NULL,
    failure_reason   varchar(64),
    error_message    text,
    metadata         jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at       timestamp(6) with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_cart_transition_audit_cart_created
    ON public.cart_transition_audit (cart_id, created_at);

CREATE INDEX idx_cart_transition_audit_business_cart_created
    ON public.cart_transition_audit (business_cart_id, created_at);

-- =========================================================
-- Delivery address
-- =========================================================

CREATE TABLE public.delivery_address
(
    id            bigserial PRIMARY KEY,
    user_id       varchar(64)                               NOT NULL,
    receiver_name varchar(128)                              NOT NULL,
    phone         varchar(64)                               NOT NULL,
    province      varchar(128),
    city          varchar(128),
    district      varchar(128),
    detail        varchar(512)                              NOT NULL,
    postal_code   varchar(32),
    is_default    boolean DEFAULT false                     NOT NULL,
    created_at    timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at    timestamp(6) with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_delivery_address_user_default
    ON public.delivery_address (user_id, is_default);

-- =========================================================
-- Customer order
-- =========================================================

CREATE TABLE public.customer_order
(
    id                    bigserial PRIMARY KEY,
    order_id              varchar(64)                               NOT NULL UNIQUE,
    cart_id               varchar(64),
    user_id               varchar(64)                               NOT NULL,
    conversation_id       varchar(64)                               NOT NULL,
    status                varchar(32) DEFAULT 'PLACED'              NOT NULL
        CONSTRAINT customer_order_status_chk
            CHECK (status IN ('PLACED', 'CANCELLED')),
    currency              varchar(8) DEFAULT 'CNY'                  NOT NULL,
    subtotal_amount       numeric(12, 2) DEFAULT 0                  NOT NULL,
    item_count            integer DEFAULT 0                         NOT NULL,
    delivery_address_id   bigint,
    delivery_address_json jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    price_change_json     jsonb DEFAULT '[]'::jsonb                 NOT NULL,
    placed_at             timestamp(6) with time zone DEFAULT now() NOT NULL,
    created_at            timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at            timestamp(6) with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_customer_order_user_created
    ON public.customer_order (user_id ASC, created_at DESC);

-- 一个购物车至多对应一笔订单：唯一索引兜底，防止并发 / 重复提交产生重复订单。
-- cart_id 可空，无购物车来源的订单（多 NULL）不受约束。
CREATE UNIQUE INDEX uq_customer_order_cart_id
    ON public.customer_order (cart_id);

-- =========================================================
-- Order item
-- =========================================================

CREATE TABLE public.order_item
(
    id           bigserial PRIMARY KEY,
    order_id     bigint                                    NOT NULL,
    spu_id       bigint                                    NOT NULL,
    sku_id       bigint,
    external_ref varchar(64),
    title        varchar(255)                              NOT NULL,
    brand        varchar(64),
    image_url    varchar(512),
    quantity     integer                                   NOT NULL
        CONSTRAINT order_item_quantity_chk
            CHECK (quantity > 0),
    unit_price   numeric(10, 2),
    line_amount  numeric(12, 2),
    created_at   timestamp(6) with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_order_item_order_id
    ON public.order_item (order_id);

CREATE INDEX idx_order_item_spu
    ON public.order_item (spu_id);

CREATE INDEX idx_order_item_sku
    ON public.order_item (sku_id)
    WHERE sku_id IS NOT NULL;

-- =========================================================
-- Catalog product
-- =========================================================

CREATE TABLE public.catalog_product
(
    id              bigserial PRIMARY KEY,
    title           varchar(512)                              NOT NULL,
    brand           varchar(255),
    category        varchar(128)                              NOT NULL,
    sub_category    varchar(128),
    base_price      numeric(12, 2) DEFAULT 0                  NOT NULL,
    price_min       numeric(12, 2),
    price_max       numeric(12, 2),
    total_stock     integer DEFAULT 0                         NOT NULL,
    image_path      text,
    status          varchar(16) DEFAULT 'ACTIVE'              NOT NULL
        CONSTRAINT catalog_product_status_chk
            CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    attributes_json jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    raw_json        jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at      timestamp(6) with time zone DEFAULT now() NOT NULL
);

CREATE INDEX idx_catalog_product_status
    ON public.catalog_product (status);

CREATE INDEX idx_catalog_product_category
    ON public.catalog_product (category);

CREATE INDEX idx_catalog_product_sub_category
    ON public.catalog_product (sub_category);

CREATE INDEX idx_catalog_product_brand
    ON public.catalog_product (brand);

CREATE INDEX idx_catalog_product_price_min
    ON public.catalog_product (price_min);

CREATE INDEX idx_catalog_product_price_max
    ON public.catalog_product (price_max);

CREATE INDEX idx_catalog_product_total_stock
    ON public.catalog_product (total_stock);

CREATE INDEX idx_catalog_product_attributes_gin
    ON public.catalog_product
        USING gin (attributes_json jsonb_path_ops);

CREATE INDEX idx_catalog_product_title_fts
    ON public.catalog_product
        USING gin (to_tsvector('simple'::regconfig, COALESCE(title, ''::text)));

CREATE INDEX idx_catalog_product_title_trgm
    ON public.catalog_product
        USING gin (lower(COALESCE(title, ''::text)) public.gin_trgm_ops);

-- =========================================================
-- Catalog SKU
-- =========================================================

CREATE TABLE public.catalog_sku
(
    id              bigserial PRIMARY KEY,
    product_id      bigint                                    NOT NULL,
    sku_index       integer DEFAULT 0                         NOT NULL,
    properties_json jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    price           numeric(12, 2) DEFAULT 0                  NOT NULL,
    stock           integer DEFAULT 0                         NOT NULL,
    status          varchar(16) DEFAULT 'ACTIVE'              NOT NULL
        CONSTRAINT catalog_sku_status_chk
            CHECK (status IN ('ACTIVE', 'INACTIVE', 'DELETED')),
    raw_json        jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_catalog_sku_product_index
        UNIQUE (product_id, sku_index)
);

CREATE INDEX idx_catalog_sku_product_id
    ON public.catalog_sku (product_id);

CREATE INDEX idx_catalog_sku_status
    ON public.catalog_sku (status);

CREATE INDEX idx_catalog_sku_properties_gin
    ON public.catalog_sku
        USING gin (properties_json jsonb_path_ops);

-- =========================================================
-- Catalog product knowledge
-- =========================================================

CREATE TABLE public.catalog_product_knowledge
(
    id             bigserial PRIMARY KEY,
    product_id     bigint                                    NOT NULL,
    knowledge_type varchar(64)                               NOT NULL
        CONSTRAINT catalog_product_knowledge_type_chk
            CHECK (knowledge_type IN ('MARKETING_DESCRIPTION', 'USAGE_GUIDE', 'SELLING_POINTS', 'REVIEW_SUMMARY', 'OTHER')),
    title          varchar(512),
    content        text                                      NOT NULL,
    content_sha256 char(64)                                  NOT NULL,
    metadata       jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at     timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at     timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_catalog_product_knowledge_type
        UNIQUE (product_id, knowledge_type)
);

CREATE INDEX idx_catalog_product_knowledge_product_id
    ON public.catalog_product_knowledge (product_id);

-- =========================================================
-- Catalog product FAQ
-- =========================================================

CREATE TABLE public.catalog_product_faq
(
    id             bigserial PRIMARY KEY,
    product_id     bigint                                    NOT NULL,
    faq_index      integer DEFAULT 0                         NOT NULL,
    question       text                                      NOT NULL,
    answer         text                                      NOT NULL,
    content_sha256 char(64)                                  NOT NULL,
    metadata       jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at     timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at     timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_catalog_product_faq_product_index
        UNIQUE (product_id, faq_index)
);

CREATE INDEX idx_catalog_product_faq_product_id
    ON public.catalog_product_faq (product_id);

-- =========================================================
-- Catalog product review
-- =========================================================

CREATE TABLE public.catalog_product_review
(
    id             bigserial PRIMARY KEY,
    product_id     bigint                                    NOT NULL,
    review_index   integer DEFAULT 0                         NOT NULL,
    nickname       varchar(128),
    rating         integer
        CONSTRAINT catalog_product_review_rating_chk
            CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    content        text                                      NOT NULL,
    content_sha256 char(64)                                  NOT NULL,
    sentiment      varchar(16)
        CONSTRAINT catalog_product_review_sentiment_chk
            CHECK (sentiment IS NULL OR sentiment IN ('POSITIVE', 'NEUTRAL', 'NEGATIVE')),
    metadata       jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at     timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at     timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_catalog_product_review_product_index
        UNIQUE (product_id, review_index)
);

CREATE INDEX idx_catalog_product_review_product_id
    ON public.catalog_product_review (product_id);

-- =========================================================
-- Catalog attribute outbox
-- =========================================================

CREATE TABLE public.catalog_attribute_outbox
(
    id              bigserial PRIMARY KEY,
    product_id      bigint                                    NOT NULL,
    event_type      varchar(32) DEFAULT 'EXTRACT_ATTRIBUTES'  NOT NULL
        CONSTRAINT catalog_attr_outbox_event_type_chk
            CHECK (event_type IN ('EXTRACT_ATTRIBUTES', 'REFRESH_ATTRIBUTES')),
    status          varchar(16) DEFAULT 'NEW'                 NOT NULL
        CONSTRAINT catalog_attr_outbox_status_chk
            CHECK (status IN ('NEW', 'SENDING', 'SENT', 'FAILED', 'CONSUMED')),
    attempt_count   integer DEFAULT 0                         NOT NULL,
    message_id      varchar(128),
    last_error      text,
    next_attempt_at timestamp(6) with time zone,
    dispatched_at   timestamp(6) with time zone,
    consumed_at     timestamp(6) with time zone,
    metadata        jsonb DEFAULT '{}'::jsonb                 NOT NULL,
    created_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    updated_at      timestamp(6) with time zone DEFAULT now() NOT NULL,
    CONSTRAINT uq_catalog_attribute_outbox_product_event
        UNIQUE (product_id, event_type)
);

CREATE INDEX idx_catalog_attribute_outbox_product_id
    ON public.catalog_attribute_outbox (product_id);

CREATE INDEX idx_catalog_attribute_outbox_status_next_attempt
    ON public.catalog_attribute_outbox (status, next_attempt_at);

-- =========================================================
-- Agent turn
-- =========================================================

CREATE TABLE public.agent_turn
(
    id              bigserial PRIMARY KEY,
    turn_id         varchar(64)                              NOT NULL,
    request_id      varchar(64),
    conversation_id varchar(64)                              NOT NULL,
    user_id         varchar(64)                              NOT NULL,
    status          varchar(32) DEFAULT 'PENDING'            NOT NULL
        CONSTRAINT agent_turn_status_chk
            CHECK (status IN (
                              'PENDING',
                              'RUNNING',
                              'SUCCEEDED',
                              'FAILED',
                              'CANCELLED',
                              'WAITING_CLARIFICATION',
                              'WAITING_CONFIRMATION'
                )),
    intent          varchar(64),
    target_workflow varchar(64),
    metadata        jsonb DEFAULT '{}'::jsonb                NOT NULL,
    created_at      timestamp with time zone DEFAULT now()   NOT NULL,
    updated_at      timestamp with time zone DEFAULT now()   NOT NULL,
    completed_at    timestamp with time zone
);

CREATE UNIQUE INDEX uq_agent_turn_turn_id
    ON public.agent_turn USING btree (turn_id);

CREATE INDEX idx_agent_turn_conversation_turn
    ON public.agent_turn (conversation_id, turn_id);

CREATE UNIQUE INDEX uq_agent_turn_conversation_request
    ON public.agent_turn (conversation_id, request_id) WHERE request_id IS NOT NULL;

-- ----------------------------------------------------------------------------
-- Central conversation runtime context.
-- Replaces workflow-private pending_* tables. All product/cart/order workflows
-- read their cross-turn context from this table through ConversationContextManager.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.agent_context_items
(
    id                       bigserial PRIMARY KEY,
    conversation_internal_id bigint                              NOT NULL,
    user_id                  varchar(255)                        NOT NULL,
    conversation_id          varchar(255)                        NOT NULL,
    item_type                varchar(64)                         NOT NULL
        CONSTRAINT agent_context_items_type_chk
            CHECK (item_type IN (
                                 'PRODUCT_CANDIDATE',
                                 'FOCUS',
                                 'PENDING_CLARIFICATION',
                                 'CART_SNAPSHOT',
                                 'ORDER_CONTEXT',
                                 'LAST_RESULT',
                                 'MEMORY_SLOT',
                                 'TASK_CHAIN'
                )),
    item_key                 varchar(255)                        NOT NULL,
    source_turn_id           varchar(128),
    source_workflow          varchar(128),
    status                   varchar(32)                         NOT NULL
        CONSTRAINT agent_context_items_status_chk
            CHECK (status IN (
                              'ACTIVE',
                              'CONSUMED',
                              'SUPERSEDED',
                              'CANCELLED',
                              'COMPLETED',
                              'FAILED',
                              'EXPIRED'
                )),
    payload_json             jsonb DEFAULT '{}'::jsonb           NOT NULL,
    created_at               timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at               timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    expires_at               timestamp
);

CREATE INDEX IF NOT EXISTS idx_agent_context_items_user_conv_type_status
    ON public.agent_context_items (user_id, conversation_id, item_type, status);
CREATE INDEX IF NOT EXISTS idx_agent_context_items_conv_type_key
    ON public.agent_context_items (conversation_internal_id, item_type, item_key);
CREATE INDEX IF NOT EXISTS idx_agent_context_items_active_expiry
    ON public.agent_context_items (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_context_items_active_key
    ON public.agent_context_items (conversation_internal_id, item_type, item_key)
    WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS public.mock_orders
(
    id              bigserial PRIMARY KEY,
    order_no        varchar(64)    NOT NULL UNIQUE,
    user_id         varchar(255)   NOT NULL,
    conversation_id varchar(255)   NOT NULL,
    items_json      jsonb          NOT NULL DEFAULT '{}'::jsonb,
    address_json    jsonb          NOT NULL DEFAULT '{}'::jsonb,
    total_amount    numeric(12, 2) NOT NULL,
    status          varchar(32)    NOT NULL
        CONSTRAINT mock_orders_status_chk
            CHECK (status IN ('CREATED')),
    created_at      timestamp      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_mock_orders_user_conv
    ON public.mock_orders (user_id, conversation_id);

COMMIT;
