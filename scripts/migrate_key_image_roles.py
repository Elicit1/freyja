"""One-time key-image migration. Dry-run by default; requires PyMySQL.

Run while backend writers are stopped. Historical video-take/task snapshots are
intentionally excluded. Credentials come from environment or --local-components.
"""
import argparse
import copy
import json
import os
import re
import socket
from datetime import datetime, timezone
from pathlib import Path

ROLES = {
    'FIRST_FRAME', 'KEYFRAME', 'LAST_FRAME', 'EDITED_KEYFRAME',
    'COMPOSITION_ANCHOR', 'STORYBOARD_REFERENCE', 'SHOT_PLANNING_REFERENCE',
}
OLD_ROLES = {'END_FRAME': 'LAST_FRAME', 'ACTION_BEAT': 'KEYFRAME', 'MOTION_KEYFRAME': 'KEYFRAME'}
PROMPT_KEYS = (
    'ai.prompt.shot_visual_plan_system', 'ai.prompt.minimax_h3_ref2va_system',
    'ai.prompt.minimax_h3_ref2va_user', 'ai.prompt.minimax_h3_fl2va_system',
    'ai.prompt.minimax_h3_fl2va_user',
)


def migrate_asset_role(value):
    role = (value or '').strip().upper()
    role = OLD_ROLES.get(role, role) or 'COMPOSITION_ANCHOR'
    if role not in ROLES:
        raise ValueError(f'Unknown asset role: {value!r}')
    return role


def migrate_slots(raw, asset_roles):
    if raw is None or not raw.strip():
        return raw
    slots = json.loads(raw)
    if not isinstance(slots, list):
        raise ValueError('Reference images must be an array')
    migrated = copy.deepcopy(slots)
    for slot in migrated:
        if not isinstance(slot, dict):
            raise ValueError('Reference image slot must be an object')
        if str(slot.get('sourceType', '')).upper() != 'KEYFRAME':
            continue
        role = str(slot.get('usageRole') or '').strip().upper()
        asset_role = asset_roles.get(str(slot.get('sourceId')))
        if role == 'MOTION_KEYFRAME':
            role = asset_role or 'KEYFRAME'
        elif role:
            role = OLD_ROLES.get(role, role)
        else:
            # Missing usage remains missing; runtime will inherit the asset.
            if asset_role is None:
                raise ValueError('Missing slot role and no usable linked asset')
            continue
        if role not in ROLES:
            raise ValueError(f'Unknown slot role: {role!r}')
        slot['usageRole'] = role
    if migrated == slots:
        return raw
    return json.dumps(migrated, ensure_ascii=False, separators=(',', ':'))


def migrate_prompt(value):
    value = value.replace('KEYFRAME / MOTION_KEYFRAME', 'KEYFRAME')
    return re.sub(r'\b(END_FRAME|ACTION_BEAT|MOTION_KEYFRAME)\b',
                  lambda match: OLD_ROLES[match.group()], value)


def component_settings(path):
    settings = {}
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        if line.startswith('| MySQL') or line.startswith('| Redis'):
            cells = [cell.strip() for cell in line.split('|')]
            settings['mysql' if line.startswith('| MySQL') else 'redis'] = cells
    return settings


class RedisConnection:
    """Small RESP client for targeted cache deletion; no additional dependency."""
    def __init__(self, host, port, password, database):
        self.socket = socket.create_connection((host, port), timeout=5)
        self.stream = self.socket.makefile('rb')
        if password:
            self.command('AUTH', password)
        self.command('SELECT', str(database))

    def command(self, *args):
        values = [str(arg).encode('utf-8') for arg in args]
        data = f'*{len(values)}\r\n'.encode()
        for value in values:
            data += f'${len(value)}\r\n'.encode() + value + b'\r\n'
        self.socket.sendall(data)
        return self.read()

    def read(self):
        line = self.stream.readline()
        if not line:
            raise RuntimeError('Redis closed the connection')
        kind, value = line[:1], line[1:-2]
        if kind == b'-':
            raise RuntimeError('Redis command failed')
        if kind == b':':
            return int(value)
        if kind == b'$':
            size = int(value)
            if size < 0:
                return None
            data = self.stream.read(size)
            self.stream.read(2)
            return data
        if kind == b'*':
            return [self.read() for _ in range(int(value))]
        return value

    def close(self):
        self.stream.close()
        self.socket.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--deleted-only', action='store_true',
                        help='Only migrate deleted shots; refuse any asset/config/dictionary changes')
    parser.add_argument('--local-components', type=Path)
    parser.add_argument('--dict-type', action='append', default=[],
                        help='Exact verified obsolete key-image dictionary code; repeatable')
    parser.add_argument('--backup-dir', type=Path, default=Path('logs/migrations'))
    args = parser.parse_args()
    local = component_settings(args.local_components) if args.local_components else {}
    mysql = local.get('mysql', ['', '', '127.0.0.1', '3306', 'root', ''])
    password = os.environ.get('MYSQL_PASSWORD', mysql[5])
    if not password:
        parser.error('Set MYSQL_PASSWORD or use --local-components')
    import pymysql
    connection = pymysql.connect(
        host=os.environ.get('MYSQL_HOST', mysql[2]), port=int(os.environ.get('MYSQL_PORT', mysql[3])),
        user=os.environ.get('MYSQL_USER', mysql[4]), password=password,
        database=os.environ.get('MYSQL_DATABASE', 'freyja'), charset='utf8mb4',
        connect_timeout=5, autocommit=False, cursorclass=pymysql.cursors.DictCursor,
    )
    redis = None
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT COUNT(*) AS n FROM render_task WHERE deleted=0 "
                           "AND status IN ('QUEUED','RUNNING','RENDERING')")
            if cursor.fetchone()['n']:
                raise RuntimeError('Wait for active renders to finish before migrating')
            cursor.execute("SELECT COUNT(*) AS n FROM ai_task WHERE deleted=0 "
                           "AND task_type='SHOT_PROMPT_DERIVE' "
                           "AND status IN ('PENDING','QUEUED','RUNNING','RETRYING')")
            if cursor.fetchone()['n']:
                raise RuntimeError('Wait for active prompt tasks to finish before migrating')

            lock = ' FOR UPDATE' if args.apply else ''
            cursor.execute('SELECT * FROM res_keyframe' + ('' if args.deleted_only else lock))
            assets = cursor.fetchall()
            cursor.execute('SELECT * FROM drama_shot WHERE ref_images_json IS NOT NULL' +
                           (' AND deleted<>0' if args.deleted_only else '') + lock)
            shots = cursor.fetchall()
            cursor.execute('SELECT * FROM sys_dict_type' + ('' if args.deleted_only else lock))
            types = cursor.fetchall()
            cursor.execute('SELECT * FROM sys_dict_data' + ('' if args.deleted_only else lock))
            entries = cursor.fetchall()
            cursor.execute('SELECT * FROM sys_config WHERE config_key IN (' +
                           ','.join(['%s'] * len(PROMPT_KEYS)) + ')' +
                           ('' if args.deleted_only else lock), PROMPT_KEYS)
            configs = cursor.fetchall()

            patches, issues = [], []
            asset_roles = {}
            for asset in assets:
                try:
                    role = migrate_asset_role(asset['frame_type'])
                    asset_roles[str(asset['id'])] = role
                    if role != asset['frame_type']:
                        patches.append(('res_keyframe', asset, 'frame_type', role))
                except ValueError as error:
                    issues.append(f"asset {asset['id']}: {error}")
            for shot in shots:
                try:
                    value = migrate_slots(shot['ref_images_json'], asset_roles)
                    if value != shot['ref_images_json']:
                        patches.append(('drama_shot', shot, 'ref_images_json', value))
                except (ValueError, TypeError) as error:
                    issues.append(f"shot {shot['id']}: {error}")
            for config in configs:
                if config['config_value'] is None:
                    continue
                value = migrate_prompt(config['config_value'] or '')
                if value != config['config_value']:
                    patches.append(('sys_config', config, 'config_value', value))

            selected = set(args.dict_type)
            if args.deleted_only and (selected or any(p[0] != 'drama_shot' for p in patches)):
                issues.append('Deleted-only mode refuses asset, config or dictionary changes')
            obsolete_entries = [entry for entry in entries if entry['dict_type'] in selected]
            obsolete_types = [row for row in types if row['dict_type'] in selected]
            candidates = {row['dict_type'] for row in entries if row['dict_value'] in OLD_ROLES}
            candidates.update(row['dict_type'] for row in types
                              if any(word in (row['dict_type'] + ' ' + row['dict_name']).lower()
                                     for word in ('keyframe', 'key_frame', '关键帧', '关键图')))
            if args.apply and candidates - selected:
                issues.append('Verify obsolete dictionary candidates and pass --dict-type: ' +
                              ', '.join(sorted(candidates - selected)))
            # Selection alone is insufficient: require key-image metadata or old enum values.
            if selected - candidates:
                issues.append('Selected dictionary codes are not verified key-image candidates: ' +
                              ', '.join(sorted(selected - candidates)))
            if any(entry['dict_value'] not in ROLES | OLD_ROLES.keys() for entry in obsolete_entries):
                issues.append('Selected dictionary contains unrelated values; review it before deletion')
            summary = {
                'apply': args.apply, 'assetUpdates': sum(p[0] == 'res_keyframe' for p in patches),
                'shotUpdates': sum(p[0] == 'drama_shot' for p in patches),
                'configUpdates': sum(p[0] == 'sys_config' for p in patches),
                'dictionaryCandidates': sorted(candidates),
                'dictionaryTypeDeletes': len(obsolete_types), 'dictionaryEntryDeletes': len(obsolete_entries),
                'issues': issues,
            }
            print(json.dumps(summary, ensure_ascii=False))
            if issues:
                raise RuntimeError('Preflight failed; no data changed')
            if not args.apply:
                connection.rollback()
                return

            cache_keys = ['freyja:dict:' + code for code in sorted(selected)]
            cache_keys += ['freyja:config:' + row['config_key'] for table, row, _, _ in patches
                           if table == 'sys_config']
            if cache_keys:
                redis_settings = local.get('redis', ['', '', '127.0.0.1', '6379', '', ''])
                redis = RedisConnection(os.environ.get('REDIS_HOST', redis_settings[2]),
                                        int(os.environ.get('REDIS_PORT', redis_settings[3])),
                                        os.environ.get('REDIS_PASSWORD', redis_settings[5]),
                                        int(os.environ.get('REDIS_DATABASE', '0')))

            args.backup_dir.mkdir(parents=True, exist_ok=True)
            backup_path = args.backup_dir / ('key-image-roles-' +
                                           datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '.json')
            backup = {
                'database': os.environ.get('MYSQL_DATABASE', 'freyja'),
                'updates': [{'table': table, 'before': row, 'column': column, 'after': value}
                            for table, row, column, value in patches],
                'deletedDictionaryTypes': obsolete_types, 'deletedDictionaryEntries': obsolete_entries,
                'cacheKeys': cache_keys,
            }
            with backup_path.open('x', encoding='utf-8') as output:
                json.dump(backup, output, ensure_ascii=False, indent=2, default=str)
                output.flush()
                os.fsync(output.fileno())
            for table, row, column, value in patches:
                cursor.execute(f'UPDATE `{table}` SET `{column}`=%s, update_time=update_time WHERE id=%s',
                               (value, row['id']))
                if cursor.rowcount != 1:
                    raise RuntimeError('Unexpected update count; transaction rolled back')
            for code in sorted(selected):
                cursor.execute('DELETE FROM sys_dict_data WHERE dict_type=%s', (code,))
                cursor.execute('DELETE FROM sys_dict_type WHERE dict_type=%s', (code,))
            for table, row, column, value in patches:
                cursor.execute(f'SELECT `{column}` FROM `{table}` WHERE id=%s', (row['id'],))
                if cursor.fetchone()[column] != value:
                    raise RuntimeError('Post-update verification failed')
            # Delete cache before commit while writers are stopped; a failure rolls DB back.
            if redis and cache_keys:
                redis.command('DEL', *cache_keys)
            connection.commit()
            print(json.dumps({'committed': True, 'backup': str(backup_path),
                              'cacheKeysCleared': cache_keys}, ensure_ascii=False))
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()
        if redis:
            redis.close()


if __name__ == '__main__':
    main()
