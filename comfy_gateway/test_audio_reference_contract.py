import unittest

from pydantic import ValidationError
from reference_prompt import ReferenceBinding


class AudioReferenceContractTest(unittest.TestCase):
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
