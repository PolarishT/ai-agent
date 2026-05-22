#!/usr/bin/env bash
# 一键导入 demo catalog 数据并实时追踪索引状态。
#
# 用法：
#   scripts/rag/import-catalog.sh [BASE_URL]
#
# 默认 BASE_URL=http://localhost:8080/api/v1
#
# 依赖：curl、jq、psql（PG 校验步骤可选；未安装 psql 会跳过）。

set -euo pipefail

BASE_URL="${1:-${RAG_BASE_URL:-http://localhost:8080/api/v1}}"
SAMPLE_FILE="${2:-src/test/resources/sample-catalog.json}"

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SAMPLE_PATH="${REPO_ROOT}/${SAMPLE_FILE}"

if [[ ! -f "${SAMPLE_PATH}" ]]; then
  echo "[ERROR] 样例文件不存在：${SAMPLE_PATH}" >&2
  exit 1
fi

echo "[1/4] 导入 catalog 样例数据 → ${BASE_URL}/admin/catalog/import"
RESPONSE=$(curl -sf -X POST \
  -H 'Content-Type: application/json' \
  --data @"${SAMPLE_PATH}" \
  "${BASE_URL}/admin/catalog/import")
echo "${RESPONSE}" | jq .

TOTAL=$(echo "${RESPONSE}" | jq -r '.data.total')
SUCCEEDED=$(echo "${RESPONSE}" | jq -r '.data.succeeded')
FAILED=$(echo "${RESPONSE}" | jq -r '.data.failed')
FIRST_ID=$(echo "${RESPONSE}" | jq -r '.data.succeededIds[0] // empty')

echo
echo "[2/4] 导入汇总：total=${TOTAL} succeeded=${SUCCEEDED} failed=${FAILED}"
if [[ "${FAILED}" -gt 0 ]]; then
  echo "  失败明细：" && echo "${RESPONSE}" | jq -r '.data.failures'
fi

if [[ -z "${FIRST_ID}" ]]; then
  echo "[ERROR] 没有成功导入的 SPU，跳过后续步骤" >&2
  exit 2
fi

echo
echo "[3/4] 拉取第一个 SPU 落地页 → GET /public/catalog/spu/${FIRST_ID}"
curl -sf "${BASE_URL}/public/catalog/spu/${FIRST_ID}" | jq .

echo
echo "[4/4] 索引状态追踪（最多 60 秒）"
if command -v psql >/dev/null 2>&1 && [[ -n "${PGURL:-}" ]]; then
  ATTEMPTS=0
  while [[ ${ATTEMPTS} -lt 12 ]]; do
    INDEXED=$(psql "${PGURL}" -tA -c "SELECT count(*) FROM rag_documents WHERE source_type='catalog-spu' AND status='INDEXED';")
    ATTR_DONE=$(psql "${PGURL}" -tA -c "SELECT count(*) FROM catalog_spu WHERE attributes_status='DONE';")
    echo "  t+${ATTEMPTS}*5s  rag_documents.INDEXED=${INDEXED}  catalog_spu.attributes_status=DONE=${ATTR_DONE}"
    if [[ "${INDEXED}" == "${SUCCEEDED}" ]] && [[ "${ATTR_DONE}" -ge "${SUCCEEDED}" ]]; then
      echo "[DONE] 全部条目已被索引并完成属性抽取。"
      exit 0
    fi
    ATTEMPTS=$((ATTEMPTS + 1))
    sleep 5
  done
  echo "[WARN] 60 秒内未全部完成；可能 Milvus / Doubao 速率较慢，请稍后再查："
  echo "       psql \"\$PGURL\" -c \"SELECT status, count(*) FROM rag_documents WHERE source_type='catalog-spu' GROUP BY status\""
else
  echo "  跳过 psql 追踪（未设置 PGURL 或未安装 psql）。"
  echo "  手动验证："
  echo "    SELECT status, count(*) FROM rag_documents WHERE source_type='catalog-spu' GROUP BY status;"
  echo "    SELECT attributes_status, count(*) FROM catalog_spu GROUP BY 1;"
fi
