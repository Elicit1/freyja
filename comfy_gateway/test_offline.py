"""
Offline Unit & Assembly Tests for ComfyUI Gateway.
Verifies configuration loading, schema validation, and dynamic workflow node graph assembly.
"""

import unittest
import json
from config import load_config
from schemas import ImageGenerationRequest
from workflow_engine import WorkflowEngine


class TestGatewayOffline(unittest.TestCase):

    def setUp(self):
        self.config = load_config()
        self.engine = WorkflowEngine(self.config)

    def test_config_loading(self):
        """Test workflows.yaml loaded properly"""
        self.assertEqual(self.config.server.port, 8000)
        self.assertGreaterEqual(len(self.config.models), 3)

        flux_model = self.config.get_model("flux-2-klein-9b")
        self.assertIsNotNone(flux_model)
        self.assertEqual(flux_model.handler, "flux2_reference_chain")
        self.assertEqual(flux_model.default_steps, 4)

        fl2va_model = self.config.get_model("minimax-h3-fl2va")
        self.assertIsNotNone(fl2va_model)
        self.assertEqual(fl2va_model.handler, "minimax_h3_fl2va")
        self.assertEqual(fl2va_model.default_steps, 20)
        self.assertEqual(fl2va_model.timeout_seconds, 1800)

        ref2va_model = self.config.get_model("minimax-h3-ref2va")
        self.assertIsNotNone(ref2va_model)
        self.assertEqual(ref2va_model.handler, "minimax_h3_ref2va")
        self.assertEqual(ref2va_model.default_steps, 20)
        self.assertEqual(ref2va_model.timeout_seconds, 1800)

    def test_schema_parsing(self):
        """Test size resolution parsing and ref_images extraction from extra_body"""
        req1 = ImageGenerationRequest(
            prompt="cyberpunk street",
            size="1280x720",
            extra_body={"ref_images": ["http://example.com/img1.png", "path/to/img2.jpg"]}
        )
        w, h = req1.parse_dimensions()
        self.assertEqual(w, 1280)
        self.assertEqual(h, 720)
        self.assertEqual(len(req1.get_all_ref_images()), 2)
        self.assertEqual(req1.get_all_ref_images()[0], "http://example.com/img1.png")

        # Invalid dimensions must be rejected instead of silently rounded.
        req2 = ImageGenerationRequest(prompt="test", size="1275x725")
        with self.assertRaisesRegex(ValueError, "multiples of 16"):
            req2.parse_dimensions()

        # MiniMax H3's 960x544 tier is already aligned and must remain exact.
        req3 = ImageGenerationRequest(model="minimax-h3-fl2va", prompt="test", size="960x544")
        self.assertEqual(req3.parse_dimensions(multiple=32), (960, 544))

    def test_workflow_zero_reference_images(self):
        """
        Test FLUX.2 Klein 9B with 0 reference images.
        Must keep base guidance connected directly to sampler without generating extra nodes.
        """
        req = ImageGenerationRequest(
            model="flux-2-klein-9b",
            prompt="a glowing futuristic crystal",
            negative_prompt="blurry, distorted",
            size="1024x1024",
            seed=12345,
            steps=4
        )

        wf = self.engine.assemble_workflow(req, uploaded_ref_images=[])

        # Verify prompt injected in node 4
        self.assertEqual(wf["4"]["inputs"]["text"], "a glowing futuristic crystal")
        # Verify negative prompt injected in node 5
        self.assertEqual(wf["5"]["inputs"]["text"], "blurry, distorted")
        # Verify dimensions in node 6
        self.assertEqual(wf["6"]["inputs"]["width"], 1024)
        self.assertEqual(wf["6"]["inputs"]["height"], 1024)
        # Verify seed and steps in node 7
        self.assertEqual(wf["7"]["inputs"]["seed"], 12345)
        self.assertEqual(wf["7"]["inputs"]["steps"], 4)
        # Verify sampler positive conditioning is connected directly to node 4
        self.assertEqual(wf["7"]["inputs"]["positive"], ["4", 0])
        # Ensure no reference nodes exist
        self.assertNotIn("1011", wf)
        self.assertNotIn("1012", wf)
        self.assertNotIn("1013", wf)

    def test_workflow_single_reference_image(self):
        """
        Test FLUX.2 Klein 9B with 1 reference image.
        Must dynamically add LoadImage -> VAEEncode -> ReferenceLatent and wire to sampler.
        """
        req = ImageGenerationRequest(
            model="flux-2-klein-9b",
            prompt="character in space suit",
            size="1024x1024"
        )

        wf = self.engine.assemble_workflow(req, uploaded_ref_images=["char_ref_01.png"])

        # Check LoadImage node 1011
        self.assertIn("1011", wf)
        self.assertEqual(wf["1011"]["class_type"], "LoadImage")
        self.assertEqual(wf["1011"]["inputs"]["image"], "char_ref_01.png")

        # Check VAEEncode node 1012 (VAE node is 3)
        self.assertIn("1012", wf)
        self.assertEqual(wf["1012"]["class_type"], "VAEEncode")
        self.assertEqual(wf["1012"]["inputs"]["pixels"], ["1011", 0])
        self.assertEqual(wf["1012"]["inputs"]["vae"], ["3", 0])

        # Check ReferenceLatent node 1013
        self.assertIn("1013", wf)
        self.assertEqual(wf["1013"]["class_type"], "ReferenceLatent")
        self.assertEqual(wf["1013"]["inputs"]["conditioning"], ["4", 0])
        self.assertEqual(wf["1013"]["inputs"]["latent"], ["1012", 0])

        # Check KSampler connected to ReferenceLatent
        self.assertEqual(wf["7"]["inputs"]["positive"], ["1013", 0])

    def test_workflow_multiple_reference_images(self):
        """
        Test FLUX.2 Klein 9B with multiple reference images (chaining).
        Must link ReferenceLatent #1 output into ReferenceLatent #2 input,
        and ReferenceLatent #2 output into KSampler.
        """
        req = ImageGenerationRequest(
            model="flux-2-klein-9b",
            prompt="character with weapon in sci-fi hallway",
            size="1280x720"
        )

        uploaded = ["face_ref.png", "weapon_ref.png", "pose_ref.png"]
        wf = self.engine.assemble_workflow(req, uploaded_ref_images=uploaded)

        # 3 images -> 3 chains
        # Ref 1:
        self.assertEqual(wf["1011"]["inputs"]["image"], "face_ref.png")
        self.assertEqual(wf["1013"]["inputs"]["conditioning"], ["4", 0])

        # Ref 2:
        self.assertEqual(wf["1021"]["inputs"]["image"], "weapon_ref.png")
        self.assertEqual(wf["1023"]["inputs"]["conditioning"], ["1013", 0])

        # Ref 3:
        self.assertEqual(wf["1031"]["inputs"]["image"], "pose_ref.png")
        self.assertEqual(wf["1033"]["inputs"]["conditioning"], ["1023", 0])

        # Final connection to sampler
        self.assertEqual(wf["7"]["inputs"]["positive"], ["1033", 0])

    def test_minimax_length_calculation(self):
        """Test MiniMax H3 frame length formula alignment to (frames - 5) % 17 == 0"""
        req_15s = ImageGenerationRequest(prompt="test", duration=15.0)
        # 15.0s -> 360 -> aligned to 362
        self.assertEqual(req_15s.calculate_minimax_length(), 362)
        self.assertEqual((362 - 5) % 17, 0)

        req_5s = ImageGenerationRequest(prompt="test", duration=5.0)
        # 5.0s -> 120 -> aligned to 124
        self.assertEqual(req_5s.calculate_minimax_length(), 124)
        self.assertEqual((124 - 5) % 17, 0)

        # Explicit length override
        req_explicit = ImageGenerationRequest(prompt="test", length=200)
        self.assertEqual(req_explicit.calculate_minimax_length(), 200)

    def test_minimax_fl2va_t2v_zero_images(self):
        """Test MiniMax H3 FL2VA pure text-to-video mode (0 images)"""
        req = ImageGenerationRequest(
            model="minimax-h3-fl2va",
            prompt="A majestic eagle soaring through storm clouds with roaring thunder",
            size="16:9",
            duration=15.0,
            seed=8888,
            steps=20
        )
        wf = self.engine.assemble_workflow(req, uploaded_ref_images=[])

        # Conditioning node 136 inputs
        cond_inputs = wf["136"]["inputs"]
        self.assertEqual(cond_inputs["prompt"], "A majestic eagle soaring through storm clouds with roaring thunder")
        self.assertEqual(cond_inputs["width"], 1344)
        self.assertEqual(cond_inputs["height"], 768)
        self.assertEqual(cond_inputs["length"], 362)
        # Verify first_frame and last_frame are omitted for pure T2VA
        self.assertNotIn("first_frame", cond_inputs)
        self.assertNotIn("last_frame", cond_inputs)
        self.assertNotIn("137", wf)
        self.assertNotIn("139", wf)

        # Sampler & noise seed
        self.assertEqual(wf["129"]["inputs"]["noise_seed"], 8888)
        self.assertEqual(wf["124"]["inputs"]["steps"], 20)

    def test_minimax_fl2va_first_frame(self):
        """Test MiniMax H3 FL2VA image-to-video mode with 1 first frame"""
        req = ImageGenerationRequest(
            model="minimax-h3-fl2va",
            prompt="The character begins running forward",
            size="1344x768",
            duration=5.0
        )
        wf = self.engine.assemble_workflow(req, uploaded_ref_images=["start_frame.png"])

        cond_inputs = wf["136"]["inputs"]
        self.assertEqual(cond_inputs["length"], 124)
        self.assertIn("137", wf)
        self.assertEqual(wf["137"]["class_type"], "LoadImage")
        self.assertEqual(wf["137"]["inputs"]["image"], "start_frame.png")
        self.assertEqual(cond_inputs["first_frame"], ["137", 0])
        self.assertNotIn("last_frame", cond_inputs)
        self.assertNotIn("139", wf)

    def test_minimax_fl2va_first_and_last_frame(self):
        """Test MiniMax H3 FL2VA transition mode with first and last frames"""
        req = ImageGenerationRequest(
            model="minimax-h3-fl2va",
            prompt="Smooth transition from day to night",
            size="16:9"
        )
        wf = self.engine.assemble_workflow(req, uploaded_ref_images=["day.png", "night.png"])

        cond_inputs = wf["136"]["inputs"]
        self.assertIn("137", wf)
        self.assertEqual(wf["137"]["inputs"]["image"], "day.png")
        self.assertEqual(cond_inputs["first_frame"], ["137", 0])

        self.assertIn("139", wf)
        self.assertEqual(wf["139"]["inputs"]["image"], "night.png")
        self.assertEqual(cond_inputs["last_frame"], ["139", 0])

    def test_minimax_ref2va_multiple_references(self):
        """Test MiniMax H3 Ref2VA with multiple reference images"""
        req = ImageGenerationRequest(
            model="minimax-h3-ref2va",
            prompt="Character wielding magical sword in front of ancient castle",
            size="16:9",
            duration=15.0
        )
        refs = ["char_costume.png", "magic_sword.png", "castle_bg.png"]
        wf = self.engine.assemble_workflow(req, uploaded_ref_images=refs)

        cond_inputs = wf["136"]["inputs"]
        self.assertEqual(cond_inputs["length"], 362)
        self.assertEqual(cond_inputs["ref_image_size"], "match")

        # Check ref slots
        self.assertIn("1370", wf)
        self.assertEqual(wf["1370"]["inputs"]["image"], "char_costume.png")
        self.assertEqual(cond_inputs["ref_images.ref_image_0"], ["1370", 0])

        self.assertIn("1371", wf)
        self.assertEqual(wf["1371"]["inputs"]["image"], "magic_sword.png")
        self.assertEqual(cond_inputs["ref_images.ref_image_1"], ["1371", 0])

        self.assertIn("1372", wf)
        self.assertEqual(wf["1372"]["inputs"]["image"], "castle_bg.png")
        self.assertEqual(cond_inputs["ref_images.ref_image_2"], ["1372", 0])

        # Verify DiT model name
        self.assertEqual(wf["127"]["inputs"]["unet_name"], "minimax_h3_ref2va_dit_16g.safetensors")
        # Verify SaveVideo node prefix
        self.assertEqual(wf["92"]["inputs"]["filename_prefix"], "MiniMax-H3-16GB-HiFi/Ref2VA-Fast")

    def test_negative_seed_replaced_by_valid_random_seed(self):
        """Test that negative seed (-1 from frontend) is replaced with a positive integer"""
        req = ImageGenerationRequest(
            model="minimax-h3-ref2va",
            prompt="A dramatic cinematic scene",
            seed=-1
        )
        self.assertIsNone(req.seed)
        wf = self.engine.assemble_workflow(req)
        noise_seed = wf["129"]["inputs"]["noise_seed"]
        self.assertGreater(noise_seed, 0)


if __name__ == "__main__":
    unittest.main()

