import copy
import unittest

from dynamic_workflow import refinement_schedule


class RefinementScheduleTests(unittest.TestCase):
    def setUp(self):
        self.source = {"class_type": "ManualSigmas", "inputs": {
            "sigmas": "1.0, 0.972973, 0.923077, 0.8, 0.571429, 0.333333, 0.0"}}

    def values(self, steps, denoise):
        node = refinement_schedule(self.source, steps, denoise)
        self.assertEqual(set(node["inputs"]), {"sigmas"}, "ManualSigmas must receive only supported inputs")
        values = [float(value) for value in node["inputs"]["sigmas"].split(",")]
        self.assertEqual(steps + 1, len(values))
        self.assertEqual(0, values[-1])
        self.assertTrue(all(left > right for left, right in zip(values, values[1:])))
        return values

    def test_full_six_step_schedule_preserves_original_evaluation_points(self):
        self.assertEqual([1, .972973, .923077, .8, .571429, .333333, 0], self.values(6, 1))

    def test_half_trajectory_uses_the_original_three_step_tail(self):
        self.assertEqual([.8, .571429, .333333, 0], self.values(3, .5))

    def test_steps_change_evaluation_count_without_changing_initial_noise_level(self):
        two = self.values(2, .25)
        four = self.values(4, .25)
        self.assertAlmostEqual(.452381, two[0])
        self.assertEqual(two[0], four[0])
        self.assertEqual(two[1], four[2])
        self.assertLess(self.values(2, .1)[0], two[0])
        self.assertGreater(self.values(2, .6)[0], two[0])

    def test_every_supported_step_count_has_a_valid_low_noise_schedule(self):
        before = copy.deepcopy(self.source)
        for steps in range(1, 7):
            for denoise in (.05, .25, .4, .6):
                with self.subTest(steps=steps, denoise=denoise):
                    self.assertLess(self.values(steps, denoise)[0], 1)
        self.assertEqual(before, self.source, "base generation must keep its fixed schedule")

    def test_invalid_manual_schedules_are_rejected(self):
        for text in ("", "1", "1, nan, 0", "1, inf, 0", "1, .5, .6, 0", "1, .5, .5, 0", "1, -.1, 0", "1, .5", "2, .5, 0"):
            with self.subTest(sigmas=text), self.assertRaisesRegex(ValueError, "MANUAL_SIGMAS_INVALID"):
                refinement_schedule({"class_type": "ManualSigmas", "inputs": {"sigmas": text}}, 2, .25)

    def test_standard_scheduler_keeps_its_policy_and_uses_the_refinement_model(self):
        source = {"class_type": "BasicScheduler", "inputs": {
            "model": ["base", 0], "scheduler": "simple", "steps": 8, "denoise": 1}}
        before = copy.deepcopy(source)
        result = refinement_schedule(source, 4, .4, ["face", 2])
        self.assertEqual({"model": ["face", 2], "scheduler": "simple", "steps": 4, "denoise": .4}, result["inputs"])
        self.assertEqual(before, source)

    def test_invalid_refinement_options_and_unknown_scheduler_are_rejected(self):
        for steps, denoise in ((0, .25), (True, .25), (2, 0), (2, float("nan")), (2, 1.1)):
            with self.subTest(steps=steps, denoise=denoise), self.assertRaisesRegex(ValueError, "SCHEDULE_INVALID"):
                refinement_schedule(self.source, steps, denoise)
        with self.assertRaisesRegex(ValueError, "SCHEDULE_UNSUPPORTED"):
            refinement_schedule({"class_type": "UnknownScheduler"}, 2, .25)


if __name__ == "__main__":
    unittest.main()
