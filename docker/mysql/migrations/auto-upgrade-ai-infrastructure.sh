#!/bin/sh
# Runs in the one-shot db-upgrade container before the backend starts.
set -eu

SQL_DIR=${SQL_DIR:-/upgrade/sql}
MYSQL_HOST=${MYSQL_HOST:-mysql}
MYSQL_PORT=${MYSQL_PORT:-3306}
MYSQL_USER=${MYSQL_USER:-root}
MYSQL_DATABASE=${MYSQL_DATABASE:-freyja}

log() { printf '[db-upgrade] %s\n' "$*"; }
fail() { log "ERROR: $*" >&2; exit 1; }

# MYSQL_PWD is supplied by Compose; never put the password in command arguments.
mysql_query() {
    mysql --protocol=TCP --host="$MYSQL_HOST" --port="$MYSQL_PORT" \
        --user="$MYSQL_USER" --database="$MYSQL_DATABASE" \
        --default-character-set=utf8mb4 --connect-timeout=10 \
        --batch --skip-column-names "$@"
}

for file in agent-foundation.sql agent-production-preparation.sql mcp-business.sql upgrade_unified_capabilities.sql migrate_unified_shot_script.sql video-render-batch.sql shot-video-export.sql reference-video-generation.sql; do
    [ -r "$SQL_DIR/$file" ] || fail "Missing SQL file: $SQL_DIR/$file. Update the deployment files together with the images."
done

log "Checking database $MYSQL_DATABASE at $MYSQL_HOST:$MYSQL_PORT"
frame_schema=$(mysql_query --execute="SELECT CONCAT(DATA_TYPE, ':', CHARACTER_MAXIMUM_LENGTH)
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'res_keyframe' AND column_name = 'frame_url';") \
    || fail "Cannot inspect the database. Check the MySQL connection and credentials."
[ "$frame_schema" = 'varchar:512' ] \
    || fail "Expected res_keyframe.frame_url VARCHAR(512). Initialize the base business schema or review its custom definition first."

mysql_query --execute="CREATE TABLE IF NOT EXISTS freyja_schema_migration (
    migration_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;" \
    || fail "Cannot create migration journal. The upgrade account needs CREATE permission."

apply_migration() {
    migration_id=$1
    sql_file=$2
    applied=$(mysql_query --execute="SELECT COUNT(*) FROM freyja_schema_migration WHERE migration_id = '$migration_id';") \
        || fail "Cannot read migration journal for $migration_id."
    if [ "$applied" = '1' ]; then
        log "Already applied: $migration_id"
        return
    fi

    log "Applying $migration_id ($sql_file)"
    # mysql stops on SQL errors. Record success only after every statement completes.
    # DDL commits independently in MySQL; these additive scripts can be retried after a partial failure.
    if ! mysql_query < "$SQL_DIR/$sql_file"; then
        fail "$migration_id failed. Check the SQL error and CREATE/ALTER permissions, then rerun db-upgrade. The backend will not start."
    fi
    mysql_query --execute="INSERT IGNORE INTO freyja_schema_migration (migration_id) VALUES ('$migration_id');" \
        || fail "Cannot record $migration_id. Fix the journal permissions and rerun; the SQL is repeatable."
    log "Applied: $migration_id"
}

apply_migration 20261014-agent-foundation-v1 agent-foundation.sql
apply_migration 20261014-agent-production-v1 agent-production-preparation.sql
apply_migration 20261014-mcp-business-v1 mcp-business.sql
apply_migration 20261014-unified-capabilities-v1 upgrade_unified_capabilities.sql
apply_migration 20261014-unified-shot-script-v1 migrate_unified_shot_script.sql
apply_migration 20261016-video-render-batch-v1 video-render-batch.sql
apply_migration 20261016-shot-video-export-v1 shot-video-export.sql
apply_migration 20261023-reference-video-generation-v1 reference-video-generation.sql

log 'Upgrade complete. Existing business data, Agent settings and MCP credentials are preserved.'
