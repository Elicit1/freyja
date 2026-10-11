"""Create the six Agent foundation tables without changing existing business data.

Dry-run by default. Uses the same PyMySQL/local-components convention as other
migrations. Apply the standalone SQL only; full init.sql recreates business tables.
"""
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
    sql_path = Path(__file__).resolve().parents[1] / 'src/main/resources/sql/agent-foundation.sql'
    sql = re.sub(r'^--[^\n]*', '', sql_path.read_text(encoding='utf-8'), flags=re.MULTILINE)
    statements = [statement.strip() for statement in sql.split(';') if statement.strip()]
    definitions = {}
    for statement in statements:
        match = re.match(r'CREATE TABLE IF NOT EXISTS `([a-z_]+)`', statement)
        if match:
            definitions[match[1]] = (statement, set(re.findall(r'^\s*`([a-z_]+)`', statement, re.MULTILINE)))
        elif statement != 'SET NAMES utf8mb4':
            raise ValueError('Only additive CREATE TABLE statements are permitted')
    if len(definitions) != 6:
        raise ValueError('Expected exactly six Agent tables')
    cells = component_settings(args.local_components)['mysql']
    connection = pymysql.connect(host=os.environ.get('MYSQL_HOST', cells[2]),
        port=int(os.environ.get('MYSQL_PORT', cells[3])), user=os.environ.get('MYSQL_USER', cells[4]),
        password=os.environ.get('MYSQL_PASSWORD', cells[5]), database=os.environ.get('MYSQL_DATABASE', 'freyja'),
        charset='utf8mb4', cursorclass=pymysql.cursors.DictCursor, connect_timeout=5, autocommit=True)
    try:
        with connection.cursor() as cur:
            cur.execute('SHOW TABLES')
            existing = {next(iter(row.values())) for row in cur.fetchall()}
            missing = []
            # Preflight every existing table before any DDL. Never silently accept an old partial schema.
            for table, (definition, columns) in definitions.items():
                if table not in existing:
                    missing.append(table)
                    continue
                cur.execute('SHOW COLUMNS FROM `' + table + '`')
                absent = columns - {row['Field'] for row in cur.fetchall()}
                if absent:
                    raise ValueError('Existing ' + table + ' requires a reviewed upgrade: ' + ', '.join(sorted(absent)))
            print(json.dumps({'missing_tables': missing, 'apply': args.apply}))
            if args.apply:
                for table in missing:
                    cur.execute(definitions[table][0])
                print(json.dumps({'created_tables': missing, 'business_data_changed': False}))
    finally:
        connection.close()


if __name__ == '__main__':
    main()
