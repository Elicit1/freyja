#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)

docker compose --project-directory "$project_dir" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot freyja' \
  < "$script_dir/20260926_res_scene_optional_scene_type.sql"

docker compose --project-directory "$project_dir" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot freyja -e "SHOW COLUMNS FROM res_scene"' \
  | grep '^scene_type'
