-- Add PostgreSQL text indexes for catalog evidence search paths.
--
-- Use this against an existing PostgreSQL database:
--   psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f scripts/rag/pgsql/enable_catalog_evidence_text_indexes.sql
--
-- The CREATE INDEX CONCURRENTLY statements must not run inside an explicit transaction.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_product_knowledge_text_fts
    ON public.catalog_product_knowledge
        USING gin (to_tsvector('simple'::regconfig, COALESCE(content, ''::text) || ' ' || COALESCE(title, ''::text)));

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_product_knowledge_content_trgm
    ON public.catalog_product_knowledge
        USING gin (lower(COALESCE(content, ''::text)) public.gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_product_knowledge_title_trgm
    ON public.catalog_product_knowledge
        USING gin (lower(COALESCE(title, ''::text)) public.gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_product_faq_question_fts
    ON public.catalog_product_faq
        USING gin (to_tsvector('simple'::regconfig, COALESCE(question, ''::text)));

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_product_faq_answer_fts
    ON public.catalog_product_faq
        USING gin (to_tsvector('simple'::regconfig, COALESCE(answer, ''::text)));

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_product_faq_question_trgm
    ON public.catalog_product_faq
        USING gin (lower(COALESCE(question, ''::text)) public.gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_product_faq_answer_trgm
    ON public.catalog_product_faq
        USING gin (lower(COALESCE(answer, ''::text)) public.gin_trgm_ops);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_product_review_content_fts
    ON public.catalog_product_review
        USING gin (to_tsvector('simple'::regconfig, COALESCE(content, ''::text)));

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_catalog_product_review_content_trgm
    ON public.catalog_product_review
        USING gin (lower(COALESCE(content, ''::text)) public.gin_trgm_ops);

ANALYZE public.catalog_product_knowledge;
ANALYZE public.catalog_product_faq;
ANALYZE public.catalog_product_review;
