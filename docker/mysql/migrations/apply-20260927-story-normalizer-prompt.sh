#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)

docker compose --project-directory "$project_dir" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot freyja' \
  < "$script_dir/20260927_story_normalizer_prompt.sql"

docker compose --project-directory "$project_dir" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 -uroot freyja -e "SELECT config_key, status, LEFT(config_value, 28) AS prompt_start FROM sys_config WHERE config_key = '\''ai.prompt.story_normalizer_system'\''"'
