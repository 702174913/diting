"""Offline tests: never invokes OpenCode or sends data."""
import json
import unittest

from test_longcat_compliance import CASES, extract_text, grade, locate, prompt_for


def perfect_answer():
    return {"cases": [{"id": c["id"], "roles": c["expected_roles"],
                       "findings": [{"rule_code": rule, "turn_id": tid,
                                     "quote": c["turns"][tid - 1]["text"],
                                     "reason": "合成预期"}
                                    for rule, tid in c["expected"]]} for c in CASES]}


class ComplianceTest(unittest.TestCase):
    def test_perfect(self):
        r = grade(perfect_answer(), CASES)
        self.assertEqual(r["passed"], len(CASES))
        self.assertEqual((r["fp"], r["fn"]), (0, 0))

    def test_false_negative(self):
        a = perfect_answer()
        a["cases"][0]["findings"] = []
        r = grade(a, CASES)
        self.assertEqual(r["fn"], 1)
        self.assertFalse(r["details"][0]["passed"])

    def test_false_positive(self):
        a = perfect_answer()
        a["cases"][1]["findings"] = [{"rule_code": "THREAT", "turn_id": 1,
                                       "quote": CASES[1]["turns"][0]["text"], "reason": "错误命中"}]
        self.assertEqual(grade(a, CASES)["fp"], 1)

    def test_fabricated_quote_fails_with_fallback(self):
        a = perfect_answer()
        a["cases"][0]["findings"][0]["quote"] = "不存在的原文"
        row = grade(a, CASES)["details"][0]
        self.assertFalse(row["passed"])
        self.assertEqual(row["evidence"][0]["display"]["mode"], "whole_turn")

    def test_duplicate_ids_and_missing_cases(self):
        a = perfect_answer()
        a["cases"].append(a["cases"][0])
        with self.assertRaises(ValueError):
            grade(a, CASES)
        with self.assertRaises(ValueError):
            grade({"cases": []}, CASES)

    def test_ambiguous_quote_fallback(self):
        self.assertEqual(locate("还钱，还钱", "还钱")["mode"], "whole_turn")
        self.assertEqual(locate("今天还钱", "还钱")["start"], 2)

    def test_stream_parse(self):
        raw = json.dumps({"type": "text", "part": {"text": "```json\n{}\n```"}})
        self.assertEqual(extract_text(raw), "{}")
        with self.assertRaises(ValueError):
            extract_text(json.dumps({"type": "error", "error": "unavailable"}))

    def test_labels_not_in_prompt(self):
        prompt = prompt_for(CASES)
        self.assertNotIn("expected_roles", prompt)
        self.assertNotIn('"expected"', prompt)


if __name__ == "__main__":
    unittest.main()
