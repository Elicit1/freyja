import json
import unittest

from migrate_key_image_roles import migrate_asset_role, migrate_slots


class KeyImageMigrationTest(unittest.TestCase):
    def test_generic_old_slot_inherits_asset_and_preserves_ids_order_and_media(self):
        original = [
            {'id': 'character', 'sourceType': 'CHARACTER_REFERENCE',
             'sourceId': '2032095628944588801', 'usageRole': 'IDENTITY', 'imageUrl': 'https://assets/a.png'},
            {'id': 'key-image', 'sourceType': 'KEYFRAME', 'sourceId': 2032095628944588802,
             'usageRole': 'MOTION_KEYFRAME', 'imageUrl': 'https://assets/b.png', 'weight': 0.85},
        ]
        actual = json.loads(migrate_slots(json.dumps(original),
                                         {'2032095628944588802': 'COMPOSITION_ANCHOR'}))
        expected = json.loads(json.dumps(original))
        expected[1]['usageRole'] = 'COMPOSITION_ANCHOR'
        self.assertEqual(expected, actual)
        self.assertEqual(2032095628944588802, actual[1]['sourceId'])
        self.assertIsInstance(actual[0]['sourceId'], str)

    def test_concrete_aliases_and_generic_slot_without_asset(self):
        slots = [{'sourceType': 'KEYFRAME', 'usageRole': role}
                 for role in ('END_FRAME', 'ACTION_BEAT', 'MOTION_KEYFRAME')]
        actual = json.loads(migrate_slots(json.dumps(slots), {}))
        self.assertEqual(['LAST_FRAME', 'KEYFRAME', 'KEYFRAME'],
                         [slot['usageRole'] for slot in actual])
        self.assertEqual('LAST_FRAME', migrate_asset_role('END_FRAME'))

    def test_valid_data_is_idempotent_and_missing_usage_still_inherits(self):
        raw = '[ {"sourceType": "KEYFRAME", "sourceId": "1"} ]'
        self.assertEqual(raw, migrate_slots(raw, {'1': 'LAST_FRAME'}))
        raw = '[ {"sourceType": "KEYFRAME", "usageRole": "COMPOSITION_ANCHOR"} ]'
        self.assertEqual(raw, migrate_slots(raw, {}))

    def test_invalid_data_blocks_migration(self):
        for raw in ('not-json', '{}', '[null]',
                    '[{"sourceType":"KEYFRAME","usageRole":"UNKNOWN"}]',
                    '[{"sourceType":"KEYFRAME"}]'):
            with self.subTest(raw=raw), self.assertRaises((ValueError, TypeError)):
                migrate_slots(raw, {})
        with self.assertRaises(ValueError):
            migrate_asset_role('UNKNOWN')


if __name__ == '__main__':
    unittest.main()
