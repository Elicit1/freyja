import unittest

from pydantic import ValidationError
from reference_prompt import ReferenceBinding


class AudioReferenceContractTest(unittest.TestCase):
    def test_library_audio_preserves_snowflake_identity_without_a_character(self):
        for kind in ("VOICE", "DIALOGUE", "LYRICS", "MUSIC", "SOUND_EFFECT", "RHYTHM", "CONTINUITY", "FULL_TRACK"):
            for relation in ("fully_copy", "partially_copy", "reference", "weak_reference"):
                binding = ReferenceBinding.model_validate({
                    "referenceType": "REFERENCE_AUDIO", "referenceAudio": "https://media/music.flac",
                    "resAudioId": "2032095628944590001", "audioKind": kind,
                    "audioReferenceType": relation, "referenceDescription": "仅参考明确指定的特征",
                })
                data = binding.model_dump(by_alias=True)
                self.assertEqual(data["resAudioId"], "2032095628944590001")
                self.assertEqual(data["audioKind"], kind)
                self.assertEqual(data["audioReferenceType"], relation)
                self.assertIsNone(data["audioId"])
                self.assertIsNone(data["characterId"])

    def test_all_four_relations_and_center_metadata_survive_transport(self):
        for relation in ("fully_copy", "partially_copy", "reference", "weak_reference"):
            binding = ReferenceBinding.model_validate({
                "referenceType": "REFERENCE_AUDIO",
                "referenceAudio": "https://media/clone.wav",
                "audioReferenceType": relation,
                "referenceDescription": "参考指定声音层",
                "audioId": "2032095628944590001",
                "characterId": "2032095628944588802",
            })
            data = binding.model_dump(by_alias=True)
            self.assertEqual(data["audioReferenceType"], relation)
            self.assertEqual(data["referenceDescription"], "参考指定声音层")
            self.assertEqual(data["audioId"], "2032095628944590001")
            self.assertEqual(data["characterId"], "2032095628944588802")

    def test_legacy_relations_are_not_mapped(self):
        for relation in ("VOICE_TIMBRE", "DIALOGUE_REUSE", "REFERENCE_AUDIO"):
            with self.assertRaises(ValidationError):
                ReferenceBinding.model_validate({
                    "referenceType": "REFERENCE_AUDIO", "audioReferenceType": relation,
                })


if __name__ == "__main__":
    unittest.main()
