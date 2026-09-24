"""
Unit and Integration Tests for Reference Binding and Reference Relation Prompt Builder.

Covers all 8 requirements specified in Section XXII:
- Test 1: Single Character (林默 -> 1 image)
- Test 2: Dual Characters (林默 -> image A, 苏晚 -> image B, distinct roles, no identity swapping)
- Test 3: Character + Scene (林默 + 废弃医院走廊)
- Test 4: Character + Scene + Prop (林默 + 苏晚 + 废弃医院走廊 + 银色怀表)
- Test 5: Same Character Multiple Reference Images (林默 -> 2 images, merged as one character)
- Test 6: Zero References (references = [], prompt untouched, no useless prompt)
- Test 7: First Frame & Last Frame (FIRST_FRAME / LAST_FRAME separated from character reference)
- Test 8: Reference Audio (Audio bound to Character)
- Test 9: MiniMax H3 FL2VA & Ref2VA Workflow Assembly Integration
- Test 10: Request Deserialization (camelCase, snake_case, extra_body parsing)
- Test 11: Backend Adapter Compatibility (MiniMax Commercial API Adapter)
"""

import unittest
from config import load_config
from schemas import ImageGenerationRequest
from reference_prompt import (
    ReferenceBinding,
    ReferenceType,
    ReferenceRelationPromptBuilder
)
from workflow_engine import WorkflowEngine
from adapters import MiniMaxCommercialAdapter, ComfyUIBackendAdapter


class TestReferenceRelationPrompt(unittest.TestCase):

    def setUp(self):
        self.config = load_config()
        self.engine = WorkflowEngine(self.config)

    def test_01_single_character(self):
        """
        Test 1: 单人物 (林默 -> 一张参考图)
        Prompt 必须明确表达: 参考图 -> 林默, 保持视觉特征一致
        """
        bindings = [
            ReferenceBinding(
                reference_id="img_001",
                reference_type=ReferenceType.CHARACTER,
                entity_id="char_001",
                entity_name="林默",
                images=["path/to/lin_mo.png"]
            )
        ]
        prompt = ReferenceRelationPromptBuilder.build_relation_prompt(bindings)
        
        self.assertIn("【人物参考】", prompt)
        self.assertIn("林默", prompt)
        self.assertIn("提供的人物参考图对应角色【林默】", prompt)
        self.assertIn("保持林默的人物身份和主要视觉特征一致", prompt)

        # Test assembly
        final_prompt = ReferenceRelationPromptBuilder.assemble_final_prompt(
            original_prompt="林默缓缓走入房间。",
            bindings=bindings
        )
        self.assertIn("【参考素材关系】", final_prompt)
        self.assertIn("【当前镜头】\n\n林默缓缓走入房间。", final_prompt)
        self.assertIn("【连续性】\n\n保持林默的人物身份稳定。", final_prompt)

    def test_02_dual_characters(self):
        """
        Test 2: 双人物 (林默 -> image A, 苏晚 -> image B)
        检查不会出现林默与苏晚无法区分或角色交换的问题。
        """
        bindings = [
            ReferenceBinding(
                reference_id="img_001",
                reference_type=ReferenceType.CHARACTER,
                entity_id="char_001",
                entity_name="林默",
                images=["path/to/lin_mo.png"]
            ),
            ReferenceBinding(
                reference_id="img_002",
                reference_type=ReferenceType.CHARACTER,
                entity_id="char_002",
                entity_name="苏晚",
                images=["path/to/su_wan.png"]
            )
        ]
        prompt = ReferenceRelationPromptBuilder.build_relation_prompt(bindings)

        self.assertIn("【人物参考关系】", prompt)
        self.assertIn("- 林默：对应其提供的人物参考图。", prompt)
        self.assertIn("- 苏晚：对应其提供的人物参考图。", prompt)
        self.assertIn("视频中的林默应保持与林默参考人物一致。", prompt)
        self.assertIn("视频中的苏晚应保持与苏晚参考人物一致。", prompt)
        self.assertIn("不要交换两个角色的身份。", prompt)

        final_prompt = ReferenceRelationPromptBuilder.assemble_final_prompt(
            original_prompt="林默转过头看着苏晚。",
            bindings=bindings
        )
        self.assertIn("不要交换不同角色与其参考素材之间的对应关系。", final_prompt)
        self.assertIn("保持林默和苏晚的人物身份稳定。", final_prompt)

    def test_03_character_and_scene(self):
        """
        Test 3: 人物 + 场景 (检查人物和场景是否被正确区分)
        """
        bindings = [
            ReferenceBinding(
                reference_id="img_001",
                reference_type=ReferenceType.CHARACTER,
                entity_name="林默",
                images=["lin_mo.png"]
            ),
            ReferenceBinding(
                reference_id="img_002",
                reference_type=ReferenceType.SCENE,
                entity_name="废弃医院走廊",
                images=["corridor.png"]
            )
        ]
        prompt = ReferenceRelationPromptBuilder.build_relation_prompt(bindings)

        self.assertIn("【人物参考】", prompt)
        self.assertIn("林默", prompt)
        self.assertIn("【场景参考】", prompt)
        self.assertIn("废弃医院走廊", prompt)
        self.assertIn("保持主要空间结构、环境特征和整体视觉身份一致", prompt)

    def test_04_character_scene_prop(self):
        """
        Test 4: 人物 + 场景 + 道具 (检查三种类型全部正确区分)
        """
        bindings = [
            ReferenceBinding(
                reference_id="img_001",
                reference_type=ReferenceType.CHARACTER,
                entity_name="林默",
                images=["lin_mo.png"]
            ),
            ReferenceBinding(
                reference_id="img_002",
                reference_type=ReferenceType.CHARACTER,
                entity_name="苏晚",
                images=["su_wan.png"]
            ),
            ReferenceBinding(
                reference_id="img_003",
                reference_type=ReferenceType.SCENE,
                entity_name="废弃医院走廊",
                images=["corridor.png"]
            ),
            ReferenceBinding(
                reference_id="img_004",
                reference_type=ReferenceType.PROP,
                entity_name="银色怀表",
                images=["watch.png"]
            )
        ]
        final_prompt = ReferenceRelationPromptBuilder.assemble_final_prompt(
            original_prompt="林默拿起银色怀表。苏晚站在林默身后。林默低声说道：“我们走。”",
            bindings=bindings
        )

        self.assertIn("【参考素材关系】", final_prompt)
        self.assertIn("【人物参考关系】", final_prompt)
        self.assertIn("【场景参考】", final_prompt)
        self.assertIn("【道具参考】", final_prompt)
        self.assertIn("银色怀表", final_prompt)
        self.assertIn("【当前镜头】\n\n林默拿起银色怀表。苏晚站在林默身后。林默低声说道：“我们走。”", final_prompt)
        self.assertIn("【连续性】", final_prompt)
        self.assertIn("保持场景和道具的视觉身份稳定", final_prompt)

    def test_05_same_character_multiple_images(self):
        """
        Test 5: 同一人物多参考图 (检查多张图片是否合并为同一人物，而不是产生两个角色)
        """
        # Form A: multiple bindings with the same entity_name
        bindings_a = [
            ReferenceBinding(
                reference_id="img_001",
                reference_type=ReferenceType.CHARACTER,
                entity_name="林默",
                images=["lin_mo_front.png"]
            ),
            ReferenceBinding(
                reference_id="img_002",
                reference_type=ReferenceType.CHARACTER,
                entity_name="林默",
                images=["lin_mo_side.png"]
            )
        ]
        prompt_a = ReferenceRelationPromptBuilder.build_relation_prompt(bindings_a)
        self.assertIn("提供的多张人物参考图共同对应角色【林默】", prompt_a)
        self.assertIn("它们不是不同角色，而是同一角色的多个视觉参考", prompt_a)
        self.assertNotIn("分别对应以下角色", prompt_a)  # Not dual characters!

        # Form B: single binding with multiple image URLs in images list
        bindings_b = [
            ReferenceBinding(
                reference_id="img_001",
                reference_type=ReferenceType.CHARACTER,
                entity_name="林默",
                images=["lin_mo_front.png", "lin_mo_side.png"]
            )
        ]
        prompt_b = ReferenceRelationPromptBuilder.build_relation_prompt(bindings_b)
        self.assertIn("提供的多张人物参考图共同对应角色【林默】", prompt_b)
        self.assertIn("它们不是不同角色，而是同一角色的多个视觉参考", prompt_b)

    def test_06_zero_references(self):
        """
        Test 6: 无参考图 (检查不会增加无意义 Prompt)
        """
        self.assertEqual(ReferenceRelationPromptBuilder.build_relation_prompt([]), "")
        self.assertEqual(ReferenceRelationPromptBuilder.build_relation_prompt(None), "")

        original = "夜色如墨，大雪纷飞。"
        final = ReferenceRelationPromptBuilder.assemble_final_prompt(original, [])
        self.assertEqual(final, original)
        self.assertNotIn("没有参考图", final)
        self.assertNotIn("【参考素材关系】", final)

    def test_07_first_and_last_frame(self):
        """
        Test 7: 首帧 / 尾帧 (检查 FIRST_FRAME / LAST_FRAME 不会被当成普通人物参考)
        """
        bindings = [
            ReferenceBinding(
                reference_id="frame_001",
                reference_type=ReferenceType.FIRST_FRAME,
                images=["first.png"]
            ),
            ReferenceBinding(
                reference_id="frame_002",
                reference_type=ReferenceType.LAST_FRAME,
                images=["last.png"]
            )
        ]
        prompt = ReferenceRelationPromptBuilder.build_relation_prompt(bindings)

        self.assertIn("【首帧参考】", prompt)
        self.assertIn("提供的首帧参考图定义当前视频的初始画面状态", prompt)
        self.assertIn("【尾帧参考】", prompt)
        self.assertIn("提供的尾帧参考图定义当前视频的结束画面状态", prompt)
        self.assertNotIn("【人物参考】", prompt)

    def test_08_reference_audio(self):
        """
        Test 8: 参考音频 (检查 audio reference 能正确绑定 Character)
        """
        bindings = [
            ReferenceBinding(
                reference_id="aud_001",
                reference_type=ReferenceType.REFERENCE_AUDIO,
                entity_name="林默",
                audio_url="voice_lin_mo.wav"
            ),
            ReferenceBinding(
                reference_id="aud_002",
                reference_type=ReferenceType.REFERENCE_AUDIO,
                entity_name="苏晚",
                audio_url="voice_su_wan.wav"
            )
        ]
        prompt = ReferenceRelationPromptBuilder.build_relation_prompt(bindings)

        self.assertIn("【声音参考】", prompt)
        self.assertIn("提供的参考音频对应角色【林默】", prompt)
        self.assertIn("当前镜头中属于林默的对白应使用该声音作为声音身份参考", prompt)
        self.assertIn("提供的参考音频对应角色【苏晚】", prompt)
        self.assertIn("当前镜头中属于苏晚的对白应使用该声音作为声音身份参考", prompt)

    def test_09_minimax_workflow_assembly_integration(self):
        """
        Test 9: MiniMax H3 FL2VA / Ref2VA 端到端离线工作流装配测试
        验证最终 Prompt 会被正确组装并注入 Node 136 inputs.prompt
        """
        req = ImageGenerationRequest(
            model="minimax-h3-ref2va",
            prompt="林默与苏晚并肩走出医院。",
            shot_id="shot_101",
            references=[
                ReferenceBinding(
                    reference_type=ReferenceType.CHARACTER,
                    entity_name="林默",
                    images=["lin_mo.png"]
                ),
                ReferenceBinding(
                    reference_type=ReferenceType.CHARACTER,
                    entity_name="苏晚",
                    images=["su_wan.png"]
                ),
                ReferenceBinding(
                    reference_type=ReferenceType.PROP,
                    entity_name="银色怀表",
                    images=["watch.png"]
                )
            ]
        )
        # Verify images auto-collected by get_all_ref_images()
        all_imgs = req.get_all_ref_images()
        self.assertIn("lin_mo.png", all_imgs)
        self.assertIn("su_wan.png", all_imgs)
        self.assertIn("watch.png", all_imgs)

        # Assemble workflow
        wf = self.engine.assemble_workflow(req, uploaded_ref_images=all_imgs)

        # Verify Node 136 prompt contains structured reference relations
        prompt_val = wf["136"]["inputs"]["prompt"]
        self.assertIn("【参考素材关系】", prompt_val)
        self.assertIn("【人物参考关系】", prompt_val)
        self.assertIn("- 林默：对应其提供的人物参考图。", prompt_val)
        self.assertIn("- 苏晚：对应其提供的人物参考图。", prompt_val)
        self.assertIn("【道具参考】", prompt_val)
        self.assertIn("银色怀表", prompt_val)
        self.assertIn("【当前镜头】\n\n林默与苏晚并肩走出医院。", prompt_val)
        self.assertIn("【连续性】", prompt_val)
        self.assertIn("不要交换不同角色与其参考素材之间的对应关系。", prompt_val)

        # Verify revised_prompt cached on request
        self.assertEqual(getattr(req, "_revised_prompt"), prompt_val)

    def test_10_request_deserialization_from_extra_body(self):
        """
        Test 10: 验证从 extra_body 解析 camelCase / snake_case 的 references
        """
        payload = {
            "model": "minimax-h3-fl2va",
            "prompt": "镜头缓缓推进。",
            "extra_body": {
                "shot_id": "shot_999",
                "references": [
                    {
                        "referenceId": "ref_1",
                        "type": "CHARACTER",
                        "entityId": "char_1",
                        "entityName": "林默",
                        "referenceImages": ["http://example.com/linmo.jpg"]
                    },
                    {
                        "reference_id": "ref_2",
                        "reference_type": "FIRST_FRAME",
                        "images": ["http://example.com/first.png"]
                    }
                ]
            }
        }
        req = ImageGenerationRequest.model_validate(payload)
        self.assertIsNotNone(req.references)
        self.assertEqual(len(req.references), 2)
        self.assertEqual(req.references[0].entity_name, "林默")
        self.assertEqual(req.references[0].reference_type, ReferenceType.CHARACTER)
        self.assertEqual(req.references[1].reference_type, ReferenceType.FIRST_FRAME)
        self.assertEqual(req.first_frame, "http://example.com/first.png")
        self.assertEqual(req.shot_id, "shot_999")

    def test_11_minimax_commercial_adapter(self):
        """
        Test 11: 商业 MiniMax 适配器测试
        验证从 ImageGenerationRequest 成功转换为商业 API 的 Subject Reference 载荷
        """
        req = ImageGenerationRequest(
            model="video-01",
            prompt="苏晚在雨中撑伞回眸。",
            references=[
                ReferenceBinding(
                    reference_type=ReferenceType.CHARACTER,
                    entity_id="char_002",
                    entity_name="苏晚",
                    images=["https://oss.example.com/suwan.png"]
                )
            ]
        )
        adapter = MiniMaxCommercialAdapter()
        payload = adapter.prepare_payload(req)

        self.assertIn("prompt", payload)
        self.assertIn("【人物参考】", payload["prompt"])
        self.assertIn("subject_reference", payload)
        self.assertEqual(len(payload["subject_reference"]), 1)
        self.assertEqual(payload["subject_reference"][0]["entity_name"], "苏晚")
        self.assertEqual(payload["subject_reference"][0]["type"], "character")

    def test_12_minimax_h3_dedicated_format_bypass(self):
        """
        Test 12: MiniMax H3 专用格式跳过旧版中文关系组装测试
        当 prompt_format='MINIMAX_H3_REF2VA_V1' 时，原样透传官方六段式 Prompt，不再包裹【参考素材关系】
        """
        h3_prompt = (
            "subject_definitions:\n"
            "<Subject 1> is character in <Picture 1>.\n\n"
            "summary:\n"
            "Shot showing <Subject 1>.\n\n"
            "retention_analysis:\n"
            "<Subject 1>: fully_preserved.\n\n"
            "detailed_description:\n"
            "[Shot 1] <Subject 1> speaks, <d>[Chinese] 决不后退。</d>.\n\n"
            "overall_soundscape:\n"
            "Wind blowing.\n\n"
            "non_diegetic_music:\n"
            "N/A."
        )
        req = ImageGenerationRequest(
            model="minimax-h3-ref2va",
            prompt=h3_prompt,
            prompt_format="MINIMAX_H3_REF2VA_V1",
            shot_id="shot_102",
            references=[
                ReferenceBinding(
                    reference_type=ReferenceType.CHARACTER,
                    entity_name="林默",
                    images=["lin_mo.png"]
                )
            ]
        )
        wf = self.engine.assemble_workflow(req, uploaded_ref_images=["lin_mo.png"])
        submitted_prompt = wf["136"]["inputs"]["prompt"]

        self.assertEqual(submitted_prompt, h3_prompt)
        self.assertNotIn("【参考素材关系】", submitted_prompt)
        self.assertNotIn("【当前镜头】", submitted_prompt)

    def test_13_minimax_h3_multi_audio_and_limits(self):
        """
        Test 13: MiniMax H3 多参考音频提取、多插槽连接与限制校验测试
        """
        req = ImageGenerationRequest(
            model="minimax-h3-ref2va",
            prompt="测试多音频输入",
            references=[
                ReferenceBinding(
                    reference_type=ReferenceType.CHARACTER,
                    entity_name="林默",
                    images=["lin_mo.png"]
                ),
                ReferenceBinding(
                    reference_type=ReferenceType.REFERENCE_AUDIO,
                    entity_name="林默",
                    audio_url="lin_mo_timbre.wav"
                ),
                ReferenceBinding(
                    reference_type=ReferenceType.REFERENCE_AUDIO,
                    entity_name="苏晚",
                    audio_url="su_wan_timbre.wav"
                ),
                ReferenceBinding(
                    reference_type=ReferenceType.REFERENCE_AUDIO,
                    entity_name="背景音乐",
                    audio_url="bgm_reference.wav"
                )
            ]
        )
        all_audios = req.get_all_ref_audios()
        self.assertEqual(len(all_audios), 3)
        self.assertEqual(all_audios[0], "lin_mo_timbre.wav")
        self.assertEqual(all_audios[1], "su_wan_timbre.wav")
        self.assertEqual(all_audios[2], "bgm_reference.wav")

        # 校验插槽连接
        wf = self.engine.assemble_workflow(
            req,
            uploaded_ref_images=["lin_mo.png"],
            uploaded_ref_audios=["audio_0.wav", "audio_1.wav", "audio_2.wav"]
        )
        cond_inputs = wf["136"]["inputs"]
        self.assertEqual(cond_inputs["ref_audios.ref_audio_0"], ["147", 0])
        self.assertEqual(cond_inputs["ref_audios.ref_audio_1"], ["1471", 0])
        self.assertEqual(cond_inputs["ref_audios.ref_audio_2"], ["1472", 0])

        # 校验限制: 超过 9 张图片
        invalid_imgs_req = ImageGenerationRequest(
            model="minimax-h3-ref2va",
            prompt="test",
            ref_images=[f"img_{i}.png" for i in range(10)]
        )
        with self.assertRaises(ValueError):
            invalid_imgs_req.validate_minimax_h3_limits()

        # 校验限制: 只有音频没有视觉参考图/视频
        only_audio_req = ImageGenerationRequest(
            model="minimax-h3-ref2va",
            prompt="test",
            audio_url="test.wav"
        )
        with self.assertRaises(ValueError):
            only_audio_req.validate_minimax_h3_limits()

    def test_14_system_character_reference_type_is_accepted(self):
        """角色模块的 CHARACTER_REFERENCE 类型由网关直接接收，不做别名转换。"""
        binding = ReferenceBinding(
            referenceType="CHARACTER_REFERENCE",
            referenceId="look_ref_1",
            characterId="char_1",
            lookId="look_1",
            entityName="林默",
            referenceRole="COMBINED",
            usageRole="SUBJECT",
            referenceImages=["look.png"]
        )
        self.assertEqual(binding.reference_type, ReferenceType.CHARACTER_REFERENCE)
        self.assertEqual(binding.character_id, "char_1")
        self.assertEqual(binding.look_id, "look_1")
        prompt = ReferenceRelationPromptBuilder.build_relation_prompt([binding])
        self.assertIn("林默", prompt)

    def test_15_system_upload_type_is_accepted(self):
        """系统上传参考图保持 UPLOAD 类型，并由明确用途驱动提示词。"""
        binding = ReferenceBinding(
            referenceType="UPLOAD",
            referenceId="upload_1",
            usageRole="SCENE",
            referenceImages=["uploaded-scene.png"]
        )
        self.assertEqual(binding.reference_type, ReferenceType.UPLOAD)
        prompt = ReferenceRelationPromptBuilder.build_relation_prompt([binding])
        self.assertIn("上传参考", prompt)
        self.assertIn("SCENE", prompt)


if __name__ == "__main__":
    unittest.main()
