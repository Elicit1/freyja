"""Deployment smoke test using one temporary shot; no external AI calls.
Always deletes only the shot created by this invocation.
"""
import argparse
import copy
import json
import os
import time
import urllib.request
import pymysql
from migrate_key_image_roles import component_settings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    args = parser.parse_args()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def call(path, data=None, method=None, expected=200):
        request = urllib.request.Request(args.base_url + path,
            data=None if data is None else json.dumps(data).encode(),
            headers={'Content-Type': 'application/json'}, method=method or ('POST' if data is not None else 'GET'))
        with opener.open(request, timeout=30) as response:
            result = json.load(response)
        assert result.get('code') == expected, (path, result.get('code'), result.get('msg'))
        return result.get('data')
    settings = component_settings('docs/components.md')['mysql']
    connection = pymysql.connect(host=os.environ.get('MYSQL_HOST', settings[2]), port=int(settings[3]),
        user=os.environ.get('MYSQL_USER', settings[4]), password=os.environ.get('MYSQL_PASSWORD', settings[5]),
        database=os.environ.get('MYSQL_DATABASE', 'freyja'), charset='utf8mb4', autocommit=True,
        cursorclass=pymysql.cursors.DictCursor)
    shot_id = None
    name = '__director_plan_smoke_' + str(time.time_ns())
    checks = []
    try:
        with connection.cursor() as cur:
            cur.execute('SELECT s.scene_id, s.shot_group_id FROM drama_shot s JOIN drama_scene c ON c.id=s.scene_id AND c.deleted=0 '
                        'JOIN drama_shot_group g ON g.id=s.shot_group_id AND g.deleted=0 WHERE s.deleted=0 LIMIT 1')
            context = cur.fetchone()
        assert context, 'An active scene and shot group are required for the smoke test'
        shot_id = call('/drama/shot', {'sceneId': str(context['scene_id']), 'shotGroupId': str(context['shot_group_id']),
            'shotName': name, 'duration': 4, 'scriptContent': 'A person waits quietly.',
            'shotTypeLocked': False, 'cameraMovementLocked': False})
        assert isinstance(shot_id, str), 'Snowflake IDs must serialize as strings'
        state = call('/drama/shot/' + shot_id)
        assert state['directorPlanMode'] == 'AUTO' and state['directorPlanRevision'] == 0
        checks.append('default AUTO with revision 0')
        plan = {'schemaVersion': '1.0', 'duration': 4, 'shotSize': 'MEDIUM_SHOT', 'cameraAngle': 'EYE_LEVEL',
            'narrativeIntent': 'First manual decision', 'cameraBeats': [
                {'startSec': 0, 'endSec': 4, 'movement': 'STATIC', 'speed': 'NONE'}]}
        saved = call('/drama/shot/' + shot_id + '/director-plan',
            {'directorPlanMode': 'MANUAL', 'directorPlanRevision': 0, 'directorPlan': plan}, 'PUT')
        assert saved['directorPlanRevision'] == 1
        state = call('/drama/shot/' + shot_id)
        assert state['directorPlanMode'] == 'MANUAL' and json.loads(state['directorPlanJson'])['narrativeIntent'] == plan['narrativeIntent']
        checks.append('manual save and reopen')
        for mode in ['REFERENCE_MODE', 'FIRST_LAST_FRAME']:
            payload = {'shotId': shot_id, 'generationMode': mode, 'duration': 4, 'scriptContent': 'A person waits quietly.',
                'directorPlanRevision': 1, 'directorPlanMode': 'MANUAL', 'directorPlan': plan}
            package = call('/drama/shot/derive-prompt-package', payload)
            assert plan['narrativeIntent'] in package['userPrompt']
            assert package['directorPlanMode'] == 'MANUAL' and package['directorPlanRevision'] == 1
            assert package['directorPlan']['cameraBeats'][0]['movement'] == 'STATIC'
            if mode == 'REFERENCE_MODE':
                parsed = call('/drama/shot/parse-derived-prompt', {'shotId': shot_id, 'generationMode': mode,
                    'rawResponse': '{"prompt":"A person waits.","videoPrompt":"A person waits."}',
                    'contextFingerprint': package['contextFingerprint'], 'directorPlan': package['directorPlan'],
                    'directorPlanMode': package['directorPlanMode'], 'directorPlanRevision': package['directorPlanRevision'],
                    'exportedContext': payload})
                assert parsed['fingerprintMatched'] is True
                assert parsed['result']['directorPlan']['narrativeIntent'] == plan['narrativeIntent']
            checks.append(mode + ' plan injection/export snapshot')
        invalid = copy.deepcopy(plan)
        invalid['cameraBeats'][0]['startSec'] = 0.1
        call('/drama/shot/derive-prompt-task', {'shotId': shot_id, 'directorPlanMode': 'MANUAL', 'directorPlanRevision': 1,
            'directorPlan': invalid, 'directorPlanFrozen': True, 'duration': 4, 'scriptContent': 'Wait'}, expected=400)
        checks.append('invalid manual rejected before task creation; client cannot bypass freeze')
        plan['narrativeIntent'] = 'Second revised decision'
        plan['duration'] = 6
        plan['cameraBeats'][0]['endSec'] = 6
        call('/drama/shot/' + shot_id + '/director-plan',
            {'directorPlanMode': 'MANUAL', 'directorPlanRevision': 1, 'directorPlan': plan, 'duration': 6}, 'PUT')
        call('/drama/shot/' + shot_id + '/director-plan',
            {'directorPlanMode': 'MANUAL', 'directorPlanRevision': 1, 'directorPlan': plan}, 'PUT', expected=409)
        package = call('/drama/shot/derive-prompt-package', {'shotId': shot_id, 'generationMode': 'REFERENCE_MODE',
            'scriptContent': 'Wait', 'directorPlanRevision': 2})
        assert package['directorPlan']['narrativeIntent'] == 'Second revised decision'
        state = call('/drama/shot/' + shot_id)
        assert float(state['duration']) == 6 and json.loads(state['directorPlanJson'])['duration'] == 6
        checks.append('edited duration and plan persist atomically')
        checks.append('latest manual fallback and stale revision protection')
        call('/drama/shot', {'id': shot_id, 'shotName': name, 'directorPlanMode': 'AUTO',
            'directorPlanJson': '{}', 'directorPlanRevision': 0}, 'PUT')
        state = call('/drama/shot/' + shot_id)
        assert state['directorPlanMode'] == 'MANUAL' and state['directorPlanRevision'] == 2
        assert json.loads(state['directorPlanJson'])['narrativeIntent'] == 'Second revised decision'
        checks.append('ordinary shot save cannot overwrite newer plan')
        reset = call('/drama/shot/' + shot_id + '/director-plan', {'directorPlanMode': 'AUTO', 'directorPlanRevision': 2}, 'PUT')
        assert reset['directorPlanRevision'] == 3
        state = call('/drama/shot/' + shot_id)
        assert state['directorPlanMode'] == 'AUTO' and state.get('directorPlanJson') is None
        checks.append('explicit restore AUTO clears manual content')
        print(json.dumps({'passed': len(checks), 'checks': checks}, ensure_ascii=False))
    finally:
        if shot_id is not None:
            try:
                call('/drama/shot/' + shot_id, method='DELETE')
                with connection.cursor() as cur:
                    cur.execute('DELETE FROM drama_shot WHERE id=%s AND shot_name=%s AND deleted=1', (shot_id, name))
                print(json.dumps({'temporary_shot_cleaned': shot_id}))
            finally:
                connection.close()
        else:
            connection.close()

if __name__ == '__main__':
    main()
