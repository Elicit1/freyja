import unittest

from pydantic import ValidationError
from reference_prompt import ReferenceType
from schemas import ImageGenerationRequest


class CharacterLookReferenceContractTest(unittest.TestCase):
    def test_look_alias_preserves_binding_and_media_order(self):
        for source_type in ("CHARACTER_LOOK", "CHARACTER_REFERENCE"):
            with self.subTest(source_type=source_type):
                look = dict(referenceType=source_type, referenceId="look-picture",
                            entityId="2032095628944588801", characterId="2032095628944588802",
                            lookId="2032095628944588801", usageRole="SUBJECT",
                            referenceRole="COMBINED", referenceImages=["look.png"])
                request = ImageGenerationRequest.model_validate(dict(
                    model="minimax-h3-ref", prompt="<Picture 1> <Picture 2>",
                    references=[dict(referenceType="SCENE", referenceImages=["scene.png"]), look],
                    extra_body=dict(references=[
                        dict(referenceType="SCENE", referenceImages=["scene.png"]),
                        {**look, "referenceType": "CHARACTER_REFERENCE"}]),
                ))
                binding = request.references[1]
                self.assertEqual(binding.reference_type, ReferenceType.CHARACTER_REFERENCE)
                self.assertEqual(binding.entity_id, look["entityId"])
                self.assertEqual(binding.character_id, look["characterId"])
                self.assertEqual(binding.look_id, look["lookId"])
                self.assertEqual(binding.usage_role, "SUBJECT")
                self.assertEqual(binding.reference_role, "COMBINED")
                self.assertEqual(request.get_all_ref_images(), ["scene.png", "look.png"])

    def test_unknown_reference_type_remains_invalid(self):
        with self.assertRaises(ValidationError):
            ImageGenerationRequest.model_validate(dict(prompt="test", references=[
                dict(referenceType="UNKNOWN_LOOK", referenceImages=["look.png"])]))


if __name__ == "__main__":
    unittest.main()
