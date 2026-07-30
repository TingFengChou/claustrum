#!/usr/bin/env python3
"""Post an advisory Gemini code review on a pull request.

Self-contained on purpose: stdlib only (urllib), plus `gh` (preinstalled on
GitHub runners) for the diff and the comment. No marketplace action whose input
schema drifts, no pip install. Failures are non-fatal -- AI review is advisory;
the hard gate is ci.yml.

Env:
    GEMINI_API_KEY   required, from the repo secret
    GEMINI_MODEL     model id (default gemini-2.5-flash)
    GH_TOKEN         github.token, used by `gh`
    PR_NUMBER        the PR to review
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

MAX_DIFF_CHARS = 100_000  # keep the request well within model context

RUBRIC = """\
You are reviewing a pull request for `claustrum`, a safety-adjacent, on-device
perception system. Review the diff below and give concise, actionable feedback.
Prioritise, in order:

1. Correctness and edge cases — especially anything that could produce a wrong
   caption, a missed or false safety alert, or a crash in the pipeline.
2. The project's invariants (do not let them regress):
   - `Actant` is a role slot, never an identity; no face recognition, no
     name/age/gender fields anywhere.
   - `risk.level != none` requires visible in-frame evidence in `risk.reason`.
   - The domain dataclasses and the JSON Schema must stay in agreement.
   - Novelty is pipeline-computed, never model-reported.
3. Testability — is the new logic unit-testable without hardware? Are there
   tests? (project rule: modules ship with tests.)
4. Design-doc hygiene — if a module changed, did its docs/design/<module>/SA.md
   and SD.md change too? Milestones must update README/ROADMAP.
5. Simplicity, readability, and consistency with surrounding code.

Be specific: cite the file and the concern. If the change looks good, say so
briefly. Do not invent issues to seem thorough. Format as short markdown with a
one-line verdict at the top (LGTM / comments / needs work).
"""


def sh(*args: str) -> str:
    return subprocess.run(args, capture_output=True, text=True, check=True).stdout


def main() -> int:
    api_key = os.environ["GEMINI_API_KEY"]
    model = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash")
    pr = os.environ["PR_NUMBER"]

    try:
        diff = sh("gh", "pr", "diff", pr, "--patch")
    except subprocess.CalledProcessError as e:
        print(f"could not get diff: {e.stderr}", file=sys.stderr)
        return 0  # advisory: never block the PR

    if not diff.strip():
        print("empty diff, nothing to review")
        return 0

    truncated = len(diff) > MAX_DIFF_CHARS
    if truncated:
        diff = diff[:MAX_DIFF_CHARS] + "\n\n[diff truncated for length]\n"

    prompt = f"{RUBRIC}\n\n---\n\nPR #{pr} diff:\n\n```diff\n{diff}\n```\n"
    body = {
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0.2},
    }
    url = (
        f"https://generativelanguage.googleapis.com/v1beta/models/"
        f"{model}:generateContent?key={api_key}"
    )
    req = urllib.request.Request(
        url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}
    )

    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            payload = json.loads(resp.read())
    except (urllib.error.URLError, TimeoutError) as e:
        print(f"Gemini request failed: {e}", file=sys.stderr)
        return 0

    try:
        text = payload["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError):
        print(f"unexpected Gemini response: {json.dumps(payload)[:500]}", file=sys.stderr)
        return 0

    note = "\n\n> 🤖 Advisory review by Gemini (`ai-code-review`). Not a merge gate — see CI for the hard checks."
    if truncated:
        note = "\n\n> ⚠️ Diff was truncated for length; review may be partial." + note

    comment = f"## AI code review\n\n{text}{note}"
    proc = subprocess.run(
        ["gh", "pr", "comment", pr, "--body-file", "-"],
        input=comment,
        text=True,
        capture_output=True,
    )
    if proc.returncode != 0:
        print(f"could not post comment: {proc.stderr}", file=sys.stderr)
        return 0

    print("posted AI review comment")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
