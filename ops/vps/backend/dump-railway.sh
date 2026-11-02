#!/bin/bash
# Run by the owner on the PC (Git Bash). Dumps the production database from Railway into a private folder outside every repo.
# It only reads Railway. The connection address is asked for without showing it, and is never saved or printed.
#   bash dump-railway.sh
# The address is DATABASE_PUBLIC_URL in Railway: the Postgres service, Variables tab.
set -euo pipefail
PGBIN="/c/Program Files/PostgreSQL/18/bin"
OUT="$HOME/trendysh-db-dumps"
mkdir -p "$OUT"
chmod 700 "$OUT" 2>/dev/null || true
read -rsp "Paste DATABASE_PUBLIC_URL (nothing will show), then press Enter: " URL
echo
[[ "$URL" == postgres* ]] || { echo "That does not look like a postgres:// address."; exit 1; }
STAMP=$(date +%Y%m%d-%H%M)
FILE="$OUT/trendysh-$STAMP.dump"
echo "Server version: $("$PGBIN/psql.exe" "$URL" -At -c 'show server_version')"
"$PGBIN/pg_dump.exe" "$URL" --format=custom --no-owner --no-acl --file="$FILE"
"$PGBIN/pg_restore.exe" --list "$FILE" > /dev/null
sha256sum "$FILE" | tee "$FILE.sha256"
ls -lh "$FILE"
echo "Done. This file holds customer data: keep it in this folder, never in a repo or a chat."
