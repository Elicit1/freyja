"""Add director plan provenance/version columns without changing existing plans.
Dry run by default. --apply backs up existing plans and schema before additive ALTER.
"""
import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import pymysql
from migrate_key_image_roles import component_settings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--local-components', default='docs/components.md')
    args = parser.parse_args()
    cells = component_settings(args.local_components)['mysql']
    connection = pymysql.connect(host=os.environ.get('MYSQL_HOST', cells[2]),
        port=int(os.environ.get('MYSQL_PORT', cells[3])), user=os.environ.get('MYSQL_USER', cells[4]),
        password=os.environ.get('MYSQL_PASSWORD', cells[5]), database=os.environ.get('MYSQL_DATABASE', 'freyja'),
        charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor, connect_timeout=5, autocommit=True)
    try:
        with connection.cursor() as cur:
            cur.execute('SHOW COLUMNS FROM drama_shot')
            columns = {row['Field'] for row in cur.fetchall()}
            additions = []
            if 'director_plan_mode' not in columns:
                additions.append("ADD COLUMN director_plan_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO' AFTER director_plan_json")
            if 'director_plan_revision' not in columns:
                additions.append('ADD COLUMN director_plan_revision INT NOT NULL DEFAULT 0 AFTER director_plan_mode')
            print(json.dumps({'missing_columns': len(additions), 'apply': args.apply}))
            if not args.apply or not additions:
                return
            cur.execute('SHOW CREATE TABLE drama_shot')
            schema = cur.fetchone()
            cur.execute('SELECT id, director_plan_json FROM drama_shot')
            plans = cur.fetchall()
            stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
            backup = Path('logs/migrations') / ('director-plan-editing-' + stamp + '.json')
            backup.parent.mkdir(parents=True, exist_ok=True)
            backup.write_text(json.dumps({'schema': schema, 'plans': plans}, ensure_ascii=False, indent=2), encoding='utf-8')
            cur.execute('ALTER TABLE drama_shot ' + ', '.join(additions))
            print(json.dumps({'added_columns': len(additions), 'backed_up_shots': len(plans), 'backup': str(backup)}))
    finally:
        connection.close()

if __name__ == '__main__':
    main()
