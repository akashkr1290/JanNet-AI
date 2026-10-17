"""Tests for location_accuracy.py (standard library only).

    python3 -m unittest discover -s database/tools/accuracy -p 'test_*.py'
"""
import csv
import http.server
import json
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

import location_accuracy as la

GAYA = {"code": 196, "name": "Gaya", "stateCode": 10, "stateName": "Bihar", "served": True}


def boundary(district=GAYA, block="Gurua", body="Pakri", village="Jolah Bigha", pin="824205"):
    return {"areaSource": "BOUNDARY", "district": district, "block": {"code": 1129, "name": block} if block else None,
            "localBody": {"code": 95600, "name": body} if body else None,
            "village": {"code": 255609, "name": village} if village else None, "pincode": pin}


class CompareTest(unittest.TestCase):
    def test_boundary_answer_is_checked_by_district_code_and_pin(self):
        p = la.Point("Gurua SO", 24.6, 84.7, 10, 196, "Gaya", "824205")
        r = la.compare(p, boundary())
        self.assertTrue(r.district_ok)
        self.assertTrue(r.pincode_ok)
        self.assertEqual(r.block_name, "Gurua")

    def test_wrong_district_and_pin_are_mismatches(self):
        p = la.Point("X", 24.6, 84.7, 10, 188, "Patna", "800001")
        r = la.compare(p, boundary())
        self.assertFalse(r.district_ok)
        self.assertFalse(r.pincode_ok)
        self.assertTrue(la.is_mismatch(r))

    def test_post_office_fallback_is_never_counted(self):
        p = la.Point("X", 24.6, 84.7, 10, 196, "Gaya", "824205")
        r = la.compare(p, {"areaSource": "POST_OFFICE", "district": GAYA, "pincode": "824205"})
        self.assertIsNone(r.district_ok)
        self.assertIsNone(r.pincode_ok)
        self.assertFalse(la.is_mismatch(r))

    def test_no_pin_polygon_means_pin_not_compared(self):
        r = la.compare(la.Point("X", 1, 1, 10, 196, "Gaya", "824205"), boundary(pin=None))
        self.assertIsNone(r.pincode_ok)

    def test_known_place_block_is_matched_by_name(self):
        p = la.Point("Gurua block office", 24.6, 84.7, district_name="Gaya", block_name="gurua")
        r = la.compare(p, boundary())
        self.assertTrue(r.district_ok)
        self.assertTrue(r.block_ok)

    def test_known_place_without_boundary_fails_its_block(self):
        p = la.Point("Somewhere", 24.6, 84.7, district_name="Gaya", block_name="Gurua")
        r = la.compare(p, {"areaSource": "NONE"})
        self.assertFalse(r.block_ok)

    def test_names_ignore_spaces_and_case(self):
        self.assertTrue(la.same_name("Bara Banki", "Barabanki"))
        self.assertFalse(la.same_name("", "Gaya"))
        self.assertFalse(la.same_name("Gaya", "Patna"))


class InputTest(unittest.TestCase):
    def test_offices_export_skips_rows_without_coordinates(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "p.tsv"
            f.write_text("pincode\toffice_name\tdistrict_code\tdistrict_name\tstate_code\tlatitude\tlongitude\n"
                         "824205\tGurua SO\t196\tGAYA\t10\t24.6\t84.7\n"
                         "824206\tNo GPS BO\t196\tGAYA\t10\tNULL\tNULL\n", encoding="utf-8")
            pts = la.read_offices(f)
        self.assertEqual(len(pts), 1)
        self.assertEqual((pts[0].district_code, pts[0].state_code, pts[0].pincode), (196, 10, "824205"))

    def test_known_places_skip_comment_rows(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "k.csv"
            f.write_text("name,latitude,longitude,district,block,local_body,note\n"
                         "# example,0,0,,,,\n"
                         "Gurua block office,24.6,84.7,Gaya,Gurua,,\n", encoding="utf-8")
            pts = la.read_places(f)
        self.assertEqual([p.ref for p in pts], ["Gurua block office"])
        self.assertEqual(pts[0].block_name, "Gurua")

    def test_sample_is_spread_over_states_and_repeatable(self):
        pts = [la.Point(str(i), 0, 0, 9 if i < 90 else 10) for i in range(100)]
        a = la.sample(pts, 20, 1)
        self.assertEqual(sum(1 for p in a if p.state_code == 9), 10)
        self.assertEqual(sum(1 for p in a if p.state_code == 10), 10)
        self.assertEqual([p.ref for p in a], [p.ref for p in la.sample(pts, 20, 1)])


class _FakeApi(http.server.BaseHTTPRequestHandler):
    calls = []

    def log_message(self, *a):
        pass

    def _send(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = json.loads(self.rfile.read(length))
        _FakeApi.calls.append(("login", body["identifier"]))
        if body["password"] != "secret":
            self._send(401, {"message": "bad credentials"})
        else:
            self._send(200, {"accessToken": "t0k", "refreshToken": "r", "expiresInSeconds": 900, "user": {}})

    def do_GET(self):
        _FakeApi.calls.append(("locate", self.headers.get("Authorization")))
        if "lat=24.600000" in self.path:
            self._send(200, boundary())
        else:
            self._send(200, {"areaSource": "POST_OFFICE", "district": GAYA})


class EndToEndTest(unittest.TestCase):
    def test_run_writes_report_results_and_mismatches(self):
        server = http.server.HTTPServer(("127.0.0.1", 0), _FakeApi)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as d:
                pts = Path(d) / "p.tsv"
                pts.write_text("pincode\toffice_name\tdistrict_code\tdistrict_name\tstate_code\tlatitude\tlongitude\n"
                               "824205\tGurua SO\t196\tGAYA\t10\t24.6\t84.7\n"
                               "800001\tWrong PIN SO\t196\tGAYA\t10\t24.6\t84.7\n"
                               "824999\tFar BO\t196\tGAYA\t10\t25.1\t85.1\n", encoding="utf-8")
                out = Path(d) / "out"
                with mock.patch("getpass.getpass", return_value="secret"):
                    la.main(["offices", str(pts), "--base-url", f"http://127.0.0.1:{server.server_port}/api/v1",
                             "--user", "9999999999", "--per-minute", "6000", "--out", str(out)])
                report = (out / "report.md").read_text()
                rows = list(csv.DictReader((out / "results.csv").open()))
                mism = list(csv.DictReader((out / "mismatches.csv").open()))
        finally:
            server.shutdown()
        self.assertEqual(len(rows), 3)
        self.assertEqual([r["ref"] for r in mism], ["Wrong PIN SO"])
        self.assertIn("| District | 2 | 2 | 100.0 % |", report)
        self.assertIn("| PIN code | 1 | 2 | 50.0 % |", report)
        self.assertIn("| POST_OFFICE | 1 |", report)
        self.assertIn(("locate", "Bearer t0k"), _FakeApi.calls)
        self.assertNotIn("secret", report)

    def test_wrong_password_stops_with_a_message(self):
        server = http.server.HTTPServer(("127.0.0.1", 0), _FakeApi)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as d:
                pts = Path(d) / "p.tsv"
                pts.write_text("pincode\toffice_name\tdistrict_code\tdistrict_name\tstate_code\tlatitude\tlongitude\n"
                               "824205\tGurua SO\t196\tGAYA\t10\t24.6\t84.7\n", encoding="utf-8")
                api = la.Api(f"http://127.0.0.1:{server.server_port}/api/v1", 6000)
                with self.assertRaises(la.ApiError) as e:
                    api.login("9999999999", "wrong")
        finally:
            server.shutdown()
        self.assertIn("HTTP 401", str(e.exception))


if __name__ == "__main__":
    unittest.main()
