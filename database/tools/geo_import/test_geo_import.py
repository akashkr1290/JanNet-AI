"""Tests for geo_import's village / Gram Panchayat -> development block linking.

Run from the repository root (standard library only):
    python3 -m unittest discover -s database/tools/geo_import -p 'test_*.py'
"""
import collections
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import geo_import as gi  # noqa: E402

UP, BIHAR, OTHER = 9, 10, 35


def row(state, village, block, body=0):
    return {"statecode": str(state), "villagecode": str(village),
            "developmentblockcode": str(block), "localbodycode": str(body)}


class SingleMajorityTest(unittest.TestCase):
    def test_clear_winner(self):
        self.assertEqual(gi.single_majority(collections.Counter({10: 2, 11: 1})), 10)

    def test_tie_is_not_guessed(self):
        self.assertIsNone(gi.single_majority(collections.Counter({10: 1, 11: 1})))

    def test_empty(self):
        self.assertIsNone(gi.single_majority(collections.Counter()))


class VillageBlockLinksTest(unittest.TestCase):
    def links(self, rows, villages=(1, 2, 3, 4, 5), bodies=(100, 200), blocks=(10, 11, 12)):
        return gi.village_block_links(rows, {UP, BIHAR}, villages, bodies, blocks)

    def test_village_and_gp_get_their_block(self):
        village, gp, stats = self.links([row(UP, 1, 10, 100)])
        self.assertEqual(village, {1: 10})
        self.assertEqual(gp, {100: 10})
        self.assertEqual(stats, {})

    def test_repeated_rows_with_the_same_block(self):
        village, _, _ = self.links([row(BIHAR, 2, 11, 100), row(BIHAR, 2, 11, 200)])
        self.assertEqual(village, {2: 11})

    def test_other_states_and_unloaded_villages_are_ignored(self):
        village, gp, stats = self.links([row(OTHER, 1, 10, 100), row(UP, 99, 10, 100)])
        self.assertEqual((village, gp, stats), ({}, {}, {}))

    def test_unknown_block_is_skipped_and_counted(self):
        village, _, stats = self.links([row(UP, 1, 999, 100)])
        self.assertEqual(village, {})
        self.assertEqual(stats, {"unknown_block": 1})

    def test_split_village_is_left_without_a_block(self):
        village, _, stats = self.links([row(UP, 3, 10, 100), row(UP, 3, 11, 200)])
        self.assertNotIn(3, village)
        self.assertEqual(stats["split_villages"], 1)

    def test_gp_block_is_the_block_of_most_of_its_villages(self):
        rows = [row(UP, 2, 11, 200), row(UP, 3, 11, 200), row(UP, 4, 12, 200)]
        _, gp, _ = self.links(rows)
        self.assertEqual(gp, {200: 11})

    def test_gp_split_evenly_gets_no_block(self):
        _, gp, stats = self.links([row(UP, 2, 11, 200), row(UP, 4, 12, 200)])
        self.assertNotIn(200, gp)
        self.assertEqual(stats["split_gps"], 1)

    def test_real_lgd_header_names(self):
        header = ("S.No.,State Code,State Name (In English),State Census 2011 Code,District Code,"
                  "District Name (In English),District Census 2011 Code,Subdistrict Code,"
                  "Subdistrict Name (In English),Subdistrict Census 2011 Code,Village Code,"
                  "Village Name (In English),Village Census 2011 Code,Local Body Code,"
                  "Local Body Name (In English),Local Body 2011 Code,Development Block Code,"
                  "Development Block Name (In English),District Code of Development Block,"
                  "District Name of Development Block (In English)")
        line = "1,9,Uttar Pradesh,09,180,Bara Banki,180,915,Nawabganj,915,5,Nasir Nagar,5,100,Naseer Nagar,0,12,Banki,180,Bara Banki"
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "villages_by_blocks.11Oct2026.csv"
            f.write_text(header + "\n" + line + "\n", encoding="utf-8")
            self.assertEqual(gi.find_optional(Path(d), "villages_by_blocks"), f)
            village, gp, _ = self.links(gi.read_csv(f))
        self.assertEqual(village, {5: 12})
        self.assertEqual(gp, {100: 12})

    def test_missing_optional_file(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertIsNone(gi.find_optional(Path(d), "villages_by_blocks"))


if __name__ == "__main__":
    unittest.main()
