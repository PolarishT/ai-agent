-- Check whether catalog evidence keyword search paths use PostgreSQL text indexes.
--
-- Usage:
--   psql "$DATABASE_URL" -v search_text='shelf life' -v result_limit=20 \
--     -f scripts/rag/pgsql/explain_catalog_evidence_keyword_retrieval.sql
--
-- Expected index families:
--   - idx_catalog_product_faq_question_fts / idx_catalog_product_faq_question_trgm
--   - idx_catalog_product_faq_answer_fts / idx_catalog_product_faq_answer_trgm
--   - idx_catalog_product_review_content_fts / idx_catalog_product_review_content_trgm
--   - idx_catalog_product_knowledge_text_fts
--   - idx_catalog_product_knowledge_content_trgm / idx_catalog_product_knowledge_title_trgm

\if :{?search_text}
\else
\set search_text 'shelf life'
\endif

\if :{?result_limit}
\else
\set result_limit 20
\endif

\echo search_text = :search_text
\echo result_limit = :result_limit

\echo
\echo [FAQ question evidence]
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
WITH params AS (
    SELECT :'search_text'::text AS search_text,
           ('%' || lower(:'search_text') || '%')::text AS like_pattern
)
SELECT p.id           AS product_id,
       p.title        AS title,
       p.brand        AS brand,
       p.category     AS category,
       p.sub_category AS sub_category,
       MAX(
            ts_rank(
                to_tsvector('simple'::regconfig, coalesce(f.question, ''::text)),
                plainto_tsquery('simple'::regconfig, q.search_text)
            ) * 1.6
            + CASE WHEN lower(coalesce(f.question, ''::text)) LIKE q.like_pattern THEN 0.8 ELSE 0 END
       ) AS score
  FROM catalog_product p
  JOIN catalog_product_faq f ON f.product_id = p.id
 CROSS JOIN params q
 WHERE p.status = 'ACTIVE'
   AND (
        to_tsvector('simple'::regconfig, coalesce(f.question, ''::text))
            @@ plainto_tsquery('simple'::regconfig, q.search_text)
     OR lower(coalesce(f.question, ''::text)) LIKE q.like_pattern
   )
 GROUP BY p.id, p.title, p.brand, p.category, p.sub_category
 ORDER BY score DESC, p.id
 LIMIT :result_limit;

\echo
\echo [FAQ answer evidence]
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
WITH params AS (
    SELECT :'search_text'::text AS search_text,
           ('%' || lower(:'search_text') || '%')::text AS like_pattern
)
SELECT p.id           AS product_id,
       p.title        AS title,
       p.brand        AS brand,
       p.category     AS category,
       p.sub_category AS sub_category,
       MAX(
            ts_rank(
                to_tsvector('simple'::regconfig, coalesce(f.answer, ''::text)),
                plainto_tsquery('simple'::regconfig, q.search_text)
            ) * 1.6
            + CASE WHEN lower(coalesce(f.answer, ''::text)) LIKE q.like_pattern THEN 0.8 ELSE 0 END
       ) AS score
  FROM catalog_product p
  JOIN catalog_product_faq f ON f.product_id = p.id
 CROSS JOIN params q
 WHERE p.status = 'ACTIVE'
   AND (
        to_tsvector('simple'::regconfig, coalesce(f.answer, ''::text))
            @@ plainto_tsquery('simple'::regconfig, q.search_text)
     OR lower(coalesce(f.answer, ''::text)) LIKE q.like_pattern
   )
 GROUP BY p.id, p.title, p.brand, p.category, p.sub_category
 ORDER BY score DESC, p.id
 LIMIT :result_limit;

\echo
\echo [Review evidence]
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
WITH params AS (
    SELECT :'search_text'::text AS search_text,
           ('%' || lower(:'search_text') || '%')::text AS like_pattern
)
SELECT p.id           AS product_id,
       p.title        AS title,
       p.brand        AS brand,
       p.category     AS category,
       p.sub_category AS sub_category,
       MAX(
            ts_rank(
                to_tsvector('simple'::regconfig, coalesce(r.content, ''::text)),
                plainto_tsquery('simple'::regconfig, q.search_text)
            )
            + CASE WHEN lower(coalesce(r.content, ''::text)) LIKE q.like_pattern THEN 0.6 ELSE 0 END
       ) AS score
  FROM catalog_product p
  JOIN catalog_product_review r ON r.product_id = p.id
 CROSS JOIN params q
 WHERE p.status = 'ACTIVE'
   AND (
        to_tsvector('simple'::regconfig, coalesce(r.content, ''::text))
            @@ plainto_tsquery('simple'::regconfig, q.search_text)
     OR lower(coalesce(r.content, ''::text)) LIKE q.like_pattern
   )
 GROUP BY p.id, p.title, p.brand, p.category, p.sub_category
 ORDER BY score DESC, p.id
 LIMIT :result_limit;

\echo
\echo [Marketing evidence]
EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
WITH params AS (
    SELECT :'search_text'::text AS search_text,
           ('%' || lower(:'search_text') || '%')::text AS like_pattern
)
SELECT p.id           AS product_id,
       p.title        AS title,
       p.brand        AS brand,
       p.category     AS category,
       p.sub_category AS sub_category,
       MAX(
            ts_rank(
                to_tsvector('simple'::regconfig, coalesce(k.content, ''::text)),
                plainto_tsquery('simple'::regconfig, q.search_text)
            ) * 1.0
            + ts_rank(
                to_tsvector('simple'::regconfig, coalesce(k.title, ''::text)),
                plainto_tsquery('simple'::regconfig, q.search_text)
            ) * 0.6
            + CASE WHEN lower(coalesce(k.content, ''::text)) LIKE q.like_pattern THEN 0.6 ELSE 0 END
            + CASE WHEN lower(coalesce(k.title, ''::text)) LIKE q.like_pattern THEN 0.4 ELSE 0 END
       ) AS score
  FROM catalog_product p
  JOIN catalog_product_knowledge k ON k.product_id = p.id
 CROSS JOIN params q
 WHERE p.status = 'ACTIVE'
   AND EXISTS (
        SELECT 1 FROM catalog_sku s_mk
         WHERE s_mk.product_id = p.id
           AND s_mk.status = 'ACTIVE'
   )
   AND (
        to_tsvector('simple'::regconfig, coalesce(k.content, ''::text) || ' ' || coalesce(k.title, ''::text))
            @@ plainto_tsquery('simple'::regconfig, q.search_text)
     OR lower(coalesce(k.content, ''::text)) LIKE q.like_pattern
     OR lower(coalesce(k.title, ''::text)) LIKE q.like_pattern
   )
 GROUP BY p.id, p.title, p.brand, p.category, p.sub_category
 ORDER BY score DESC, p.id
 LIMIT :result_limit;
