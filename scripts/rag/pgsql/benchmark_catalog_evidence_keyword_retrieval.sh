#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RUNS="${RUNS:-5}"
RESULT_LIMIT="${RESULT_LIMIT:-20}"

if [[ $# -gt 0 ]]; then
  QUERIES=("$@")
else
  QUERIES=(
    "保质期多久"
    "评价怎么样"
    "适合通勤"
  )
fi

PSQL_BASE=(psql -X -v ON_ERROR_STOP=1)
if [[ -n "${DATABASE_URL:-}" ]]; then
  PSQL_BASE+=("$DATABASE_URL")
fi

extract_total_execution_time() {
  awk -F': ' '
    /Execution Time/ {
      gsub(/ ms/, "", $2);
      sum += $2;
      count += 1;
    }
    END {
      if (count > 0) {
        printf "%.3f\n", sum;
      }
    }'
}

print_summary() {
  local label="$1"
  shift
  printf '%s\n' "$@" | awk -v label="$label" '
    BEGIN { min = -1; max = -1; sum = 0; count = 0; }
    NF {
      value = $1 + 0;
      if (min < 0 || value < min) min = value;
      if (max < 0 || value > max) max = value;
      sum += value;
      count += 1;
    }
    END {
      if (count == 0) {
        printf "%-18s avg=n/a min=n/a max=n/a runs=0\n", label;
      } else {
        printf "%-18s avg=%.3f ms min=%.3f ms max=%.3f ms runs=%d\n", label, sum / count, min, max, count;
      }
    }'
}

run_catalog_evidence() {
  local query="$1"
  local -a times=()

  for ((run = 1; run <= RUNS; run++)); do
    local output
    output="$("${PSQL_BASE[@]}" \
      -v search_text="$query" \
      -v result_limit="$RESULT_LIMIT" \
      -f "$SCRIPT_DIR/explain_catalog_evidence_keyword_retrieval.sql")"
    local execution_time
    execution_time="$(printf '%s\n' "$output" | extract_total_execution_time)"
    if [[ -z "$execution_time" ]]; then
      printf 'Unable to extract Execution Time for query [%s]. Full output:\n%s\n' "$query" "$output" >&2
      exit 1
    fi
    times+=("$execution_time")
  done

  print_summary "catalog-evidence" "${times[@]}"
}

printf 'Catalog evidence PostgreSQL retrieval benchmark\n'
printf 'runs=%s result_limit=%s\n\n' "$RUNS" "$RESULT_LIMIT"

for query in "${QUERIES[@]}"; do
  printf 'Query: %s\n' "$query"
  run_catalog_evidence "$query"
  printf '\n'
done
