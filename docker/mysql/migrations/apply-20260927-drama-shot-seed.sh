#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(CDPATH= cd -- "$script_dir/../../.." && pwd)

docker compose --project-directory "$project_dir" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot freyja' \
  < "$script_dir/20260927_drama_shot_seed.sql"

docker compose --project-directory "$project_dir" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot freyja -e "SHOW COLUMNS FROM drama_shot LIKE '\''seed'\''"'
