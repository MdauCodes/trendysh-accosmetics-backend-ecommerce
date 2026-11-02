#!/bin/bash
# Run ON the server. Restores a Railway dump into the shop's own Coolify database, and nothing else.
#   restore-dump.sh <db container> <dump file> [--rehearsal]
# It refuses any container whose database is not named "trendysh", so it cannot touch another project's database, and any
# database that already has tables (delete and recreate the Coolify database to start again).
# --rehearsal also removes the keys saved in the dashboard from THIS copy (integration_setting: Gemini, Cloudinary, photo
# bucket, deploy hook), so a copy running beside the live backend cannot spend money, write to the real bucket or rebuild the site.
set -euo pipefail
C="${1:?db container}"; DUMP="${2:?dump file}"; MODE="${3:-}"
[ -s "$DUMP" ] || { echo "No such dump: $DUMP"; exit 1; }
DB=$(docker exec "$C" sh -c 'echo "$POSTGRES_DB"')
[ "$DB" = "trendysh" ] || { echo "Refusing: $C holds database '$DB', not 'trendysh'."; exit 1; }
sql() { docker exec -i "$C" sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -c "$1"' sh "$1"; }
TABLES=$(sql "select count(*) from information_schema.tables where table_schema='public'")
[ "$TABLES" = "0" ] || { echo "Refusing: the database already has $TABLES tables."; exit 1; }
docker exec -i "$C" sh -c 'pg_restore --exit-on-error --no-owner --no-acl -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$DUMP"
sql "analyze"
if [ "$MODE" = "--rehearsal" ]; then
  echo "Removed saved keys from this copy: $(sql "with d as (delete from integration_setting returning 1) select count(*) from d")"
fi
for t in flyway_schema_history staff_user product product_image customer_order photo_url_move; do
  printf '%-24s %s\n' "$t" "$(sql "select count(*) from $t")"
done
echo "latest migration: $(sql "select version from flyway_schema_history where success order by installed_rank desc limit 1")"
