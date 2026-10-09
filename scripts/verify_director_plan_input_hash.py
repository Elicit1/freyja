"""Exercise saved/reopened input checks using one temporary shot, without AI calls."""
import argparse
import copy
import json
import os
import time
import urllib.error
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
        try:
            response = opener.open(request, timeout=30)
        except urllib.error.HTTPError as e:
            response = e
        with response:
            result = json.load(response)
        assert result.get('code') == expected, (path, result.get('code'), result.get('msg'))
        return result.get('data')

    def input_fields(state):
        keys = ['shotNo', 'shotName', 'duration', 'shotTypeLocked', 'shotType', 'cameraMovementLocked',
                'cameraMovement', 'scriptContent', 'actionDescription', 'dialogue', 'dialogueSpeaker', 'voiceover', 'soundEffect']
        return {key: state.get(key) for key in keys}

    settings = component_settings('docs/components.md')['mysql']
    connection = pymysql.connect(host=os.environ.get('MYSQL_HOST', settings[2]), port=int(os.environ.get('MYSQL_PORT', settings[3])),
        user=os.environ.get('MYSQL_USER', settings[4]), password=os.environ.get('MYSQL_PASSWORD', settings[5]),
        database=os.environ.get('MYSQL_DATABASE', 'freyja'), charset='utf8mb4', autocommit=True,
        cursorclass=pymysql.cursors.DictCursor)
    shot_id = None
    name = '__director_hash_smoke_' + str(time.time_ns())
    checks = []
    try:
        with connection.cursor() as cur:
            cur.execute('SELECT s.scene_id, s.shot_group_id FROM drama_shot s JOIN drama_scene c ON c.id=s.scene_id AND c.deleted=0 '
                        'JOIN drama_shot_group g ON g.id=s.shot_group_id AND g.deleted=0 WHERE s.deleted=0 LIMIT 1')
            context = cur.fetchone()
        assert context, 'An active scene and shot group are required'
        shot_id = call('/drama/shot', {'sceneId': str(context['scene_id']), 'shotGroupId': str(context['shot_group_id']),
            'shotName': name, 'duration': 4, 'scriptContent': 'Original shot script.',
            'shotTypeLocked': False, 'cameraMovementLocked': False})
        plan = {'schemaVersion': '1.0', 'duration': 4, 'shotSize': 'MEDIUM_SHOT', 'cameraAngle': 'EYE_LEVEL',
            'narrativeIntent': 'Creator revised plan', 'cameraBeats': [{'startSec': 0, 'endSec': 4, 'movement': 'STATIC', 'speed': 'NONE'}]}
        saved = call('/drama/shot/' + shot_id + '/director-plan',
            {'directorPlanMode': 'MANUAL', 'directorPlanRevision': 0, 'directorPlan': plan}, 'PUT')
        baseline_hash = saved['directorPlanInputHash']
        assert len(baseline_hash) == 64
        state = call('/drama/shot/' + shot_id)
        payload = {'shotId': shot_id, 'directorPlanRevision': 1, 'directorPlanMode': 'MANUAL',
            'directorPlanInput': input_fields(state), 'replanOnInputChange': True}
        checked = call('/drama/shot/director-plan/check', payload)
        assert not checked['inputsChanged'] and not checked['overwriteRequired']
        checks.append('unchanged card with saved manual plan requires no overwrite')
        for mode in ['REFERENCE_MODE', 'FIRST_LAST_FRAME']:
            package = call('/drama/shot/derive-prompt-package', {**payload, 'generationMode': mode})
            assert package['directorPlanMode'] == 'MANUAL'
            assert package['directorPlan']['narrativeIntent'] == plan['narrativeIntent']
            checks.append(mode + ' reuses manual plan')

        call('/drama/shot', {'id': shot_id, 'shotName': name, 'scriptContent': 'Saved modified script.', 'duration': 6}, 'PUT')
        state = call('/drama/shot/' + shot_id)
        assert state['directorPlanInputHash'] == baseline_hash
        assert json.loads(state['directorPlanJson'])['duration'] == 4
        checks.append('card save preserves baseline and allows stale physical plan')
        revised = copy.deepcopy(plan)
        revised['duration'] = 6; revised['cameraBeats'][0]['endSec'] = 6; revised['narrativeIntent'] = 'Saved second manual revision'
        saved = call('/drama/shot/' + shot_id + '/director-plan',
            {'directorPlanMode': 'MANUAL', 'directorPlanRevision': 1, 'directorPlan': revised, 'duration': 6,
             'directorPlanInputHash': '0' * 64}, 'PUT')
        assert saved['directorPlanInputHash'] == baseline_hash
        state = call('/drama/shot/' + shot_id)
        payload = {'shotId': shot_id, 'directorPlanRevision': 2, 'directorPlanMode': 'MANUAL',
            'directorPlanInput': input_fields(state), 'replanOnInputChange': True}
        checked = call('/drama/shot/director-plan/check', payload)
        assert checked['inputsChanged'] and checked['overwriteRequired']
        assert checked['inputHash'] != baseline_hash
        checks.append('saved card and saved plan still require confirmation after reopen')
        call('/drama/shot/derive-prompt-task', payload, expected=409)
        reopened = call('/drama/shot/' + shot_id)
        assert reopened['directorPlanInputHash'] == baseline_hash and reopened['directorPlanRevision'] == 2
        assert json.loads(reopened['directorPlanJson'])['narrativeIntent'] == revised['narrativeIntent']
        checks.append('unconfirmed submission leaves plan hash and revision intact')

        call('/drama/shot/' + shot_id + '/director-plan', {'directorPlanMode': 'AUTO', 'directorPlanRevision': 2}, 'PUT')
        state = call('/drama/shot/' + shot_id)
        checked = call('/drama/shot/director-plan/check', {'shotId': shot_id, 'directorPlanInput': input_fields(state)})
        assert checked['inputsChanged'] and not checked['overwriteRequired']
        assert state['directorPlanInputHash'] == baseline_hash
        checks.append('changed card in AUTO requires replanning without confirmation')
        call('/drama/shot', {'id': shot_id, 'scriptContent': 'Original shot script.', 'duration': 4}, 'PUT')
        state = call('/drama/shot/' + shot_id)
        checked = call('/drama/shot/director-plan/check', {'shotId': shot_id, 'directorPlanInput': input_fields(state)})
        assert not checked['inputsChanged']
        checks.append('restoring original card content restores matching hash')
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
