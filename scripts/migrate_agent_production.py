"""Add preparation tables and make keyframe image URL optional. Never run full init.sql on existing data."""
import argparse
import json
import os
import re
from pathlib import Path
import pymysql
from migrate_key_image_roles import component_settings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--local-components', default='docs/components.md')
    args = parser.parse_args()
    sql_path = Path(__file__).resolve().parents[1] / 'src/main/resources/sql/agent-production-preparation.sql'
    sql = re.sub(r'^--[^\n]*', '', sql_path.read_text(encoding='utf-8'), flags=re.MULTILINE)
    definitions = {}
    alter = None
    for statement in (s.strip() for s in sql.split(';') if s.strip()):
        match = re.match(r'CREATE TABLE IF NOT EXISTS `([a-z_]+)`', statement)
        if match:
            definitions[match[1]] = (statement, set(re.findall(r'^\s*`([a-z_]+)`', statement, re.MULTILINE)))
        elif statement.startswith('ALTER TABLE `res_keyframe` MODIFY COLUMN `frame_url` VARCHAR(512) NULL'):
            alter = statement
        elif statement != 'SET NAMES utf8mb4':
            raise ValueError('Unexpected migration statement')
    if len(definitions) != 3 or not alter:
        raise ValueError('Expected exactly three preparation tables and nullable frame_url')
    cells = component_settings(args.local_components)['mysql']
    connection = pymysql.connect(host=os.environ.get('MYSQL_HOST', cells[2]), port=int(os.environ.get('MYSQL_PORT', cells[3])),
        user=os.environ.get('MYSQL_USER', cells[4]), password=os.environ.get('MYSQL_PASSWORD', cells[5]),
        database=os.environ.get('MYSQL_DATABASE', 'freyja'), charset='utf8mb4',
        cursorclass=pymysql.cursors.DictCursor, connect_timeout=5, autocommit=True)
    try:
        with connection.cursor() as cur:
            cur.execute('SHOW TABLES')
            existing = {next(iter(row.values())) for row in cur.fetchall()}
            missing = []
            for table, (_, columns) in definitions.items():
                if table not in existing:
                    missing.append(table)
                else:
                    cur.execute('SHOW COLUMNS FROM `' + table + '`')
                    absent = columns - {row['Field'] for row in cur.fetchall()}
                    if absent:
                        raise ValueError('Existing ' + table + ' requires explicit upgrade: ' + ', '.join(sorted(absent)))
            cur.execute("SHOW COLUMNS FROM res_keyframe LIKE 'frame_url'")
            frame = cur.fetchone()
            if not frame or frame['Type'].lower() != 'varchar(512)':
                raise ValueError('Unexpected keyframe schema')
            change_url = frame['Null'] == 'NO'
            print(json.dumps({'missing_tables': missing, 'make_frame_url_nullable': change_url, 'apply': args.apply}))
            if args.apply:
                for table in missing:
                    cur.execute(definitions[table][0])
                if change_url:
                    cur.execute(alter)
                print(json.dumps({'created_tables': missing, 'existing_records_preserved': True}))
    finally:
        connection.close()


if __name__ == '__main__':
    main()
