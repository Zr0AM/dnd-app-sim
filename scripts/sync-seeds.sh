#!/usr/bin/env bash
# Copies the SQLite schema and seed data from a Zr0AM/dnd-app checkout into src/main/resources/db and records the
# source commit and checksums. Seeds are copied (not a submodule) so Gradle needs no cross-repo access.
#
#   DND_APP_DIR=/path/to/dnd-app scripts/sync-seeds.sh
set -euo pipefail

SRC="${DND_APP_DIR:?set DND_APP_DIR to a dnd-app checkout}/docs/db"
DEST="$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/db"

[ -f "$SRC/schema-draft.sql" ] || { echo "no schema-draft.sql under $SRC" >&2; exit 1; }
mkdir -p "$DEST/seed"
rm -f "$DEST"/seed/*.sql
cp "$SRC/schema-draft.sql" "$DEST/schema-draft.sql"
cp "$SRC"/seed/*.sql "$DEST/seed/"

{
  echo "source: Zr0AM/dnd-app"
  echo "commit: $(git -C "$DND_APP_DIR" rev-parse HEAD)"
  echo "files:"
  (cd "$DEST" && sha256sum schema-draft.sql seed/*.sql | sed 's/^/  /')
} > "$DEST/SOURCE"
echo "synced $(ls "$DEST"/seed | wc -l) seed files from $(git -C "$DND_APP_DIR" rev-parse --short HEAD)"
