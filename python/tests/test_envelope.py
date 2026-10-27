"""Desktop unit tests for the shared bridge envelope (no Android/Chaquopy).

Run: python -m unittest discover -s python/tests
"""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "src", "main", "python")
))

from common.envelope import (  # noqa: E402
    AUTH_WALL,
    NETWORK,
    RATE_LIMITED,
    UNAVAILABLE,
    classify,
    fail,
    succeed,
)


class EnvelopeTest(unittest.TestCase):

    def test_success_is_flat(self):
        payload = {"shortcode": "abc", "items": []}
        self.assertEqual(json.loads(succeed(payload)), payload)

    def test_fail_omits_empty_tail(self):
        envelope = json.loads(fail("unavailable", "gone"))
        self.assertEqual(envelope, {"error": "unavailable", "detail": "gone"})

    def test_fail_trims_tail_to_last_forty_lines_and_merges_extra(self):
        lines = ["line%d" % i for i in range(50)]
        envelope = json.loads(fail(
            "auth_wall", "login", tail=lines,
            extra={"js_diagnostics": {"runtime": None}},
        ))
        self.assertEqual(envelope["error"], "auth_wall")
        self.assertEqual(envelope["detail"], "login")
        self.assertEqual(len(envelope["debug_log"].splitlines()), 40)
        self.assertEqual(envelope["debug_log"].splitlines()[-1], "line49")
        self.assertIn("js_diagnostics", envelope)

    def test_classify_order_and_defaults(self):
        self.assertEqual(
            classify("Sign in to confirm your age", ("sign in",)), AUTH_WALL
        )
        self.assertEqual(
            classify("HTTP Error 429: Too Many Requests"), RATE_LIMITED
        )
        self.assertEqual(classify("getaddrinfo failed"), NETWORK)
        self.assertEqual(classify("video unavailable"), UNAVAILABLE)
        # yt-dlp wraps permanent 404s in "Unable to download webpage"; the
        # transport marker must not mask the deleted/private classification.
        self.assertEqual(
            classify("Unable to download webpage: HTTP Error 404: Not Found"),
            UNAVAILABLE,
        )
        # Auth markers are bridge-supplied domain knowledge, never shared defaults.
        self.assertEqual(classify("login required"), UNAVAILABLE)


if __name__ == "__main__":
    unittest.main()
