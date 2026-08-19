#!/usr/bin/env python3
"""
harvest_finalize.py — open ONE pull request per new rule.

The Claude step wrote harvest/decisions.json: a list of new rules, each with its
target reference file and the exact markdown bullet to add. For each rule this script
branches off develop, appends the bullet to that reference file, commits, pushes, and
opens a pull request to develop — one PR per rule. It skips a rule if an open PR with
the same title already exists, then advances the scan cursor.

No step relies on the model to run git. Requires gh (GH_TOKEN) + push credentials.
"""
import json, re, subprocess, sys, datetime as dt
from pathlib import Path

ROOT  = Path(__file__).resolve().parent.parent
STATE = ROOT / "harvest" / "state.json"
CAND  = ROOT / "harvest" / "candidates.json"
DEC   = ROOT / "harvest" / "decisions.json"
BASE  = "develop"
ADO   = "https://dev.azure.com/blackbaud/Products/_git/receipt-manager/pullrequest/"


def run(cmd, check=True):
    r = subprocess.run(cmd, text=True, capture_output=True)
    if r.stdout.strip(): print(r.stdout.strip())
    if r.stderr.strip(): print(r.stderr.strip(), file=sys.stderr)
    if check and r.returncode:
        sys.exit(f"ERROR: {' '.join(cmd)} failed ({r.returncode})")
    return r


def git(*a, check=True):
    return run(["git", *a], check=check)


def load(p, default):
    try: return json.loads(p.read_text())
    except Exception: return default


def slug(s, n=40):
    s = re.sub(r"[^a-zA-Z0-9]+", "-", s.lower()).strip("-")
    return s[:n].strip("-") or "rule"


def open_pr_titles():
    r = subprocess.run(["gh", "pr", "list", "--state", "open", "--base", BASE,
                        "--json", "title", "--jq", ".[].title"],
                       text=True, capture_output=True)
    return {t.strip() for t in r.stdout.splitlines() if t.strip()}


def append_bullet(ref_file, bullet):
    p = ROOT / ref_file
    text = p.read_text()
    if text and not text.endswith("\n"):
        text += "\n"
    p.write_text(text + bullet.rstrip("\n") + "\n")


def raise_pr(rule, idx):
    ref_file, bullet = rule.get("ref_file"), rule.get("bullet")
    pr, title = rule.get("pr"), rule.get("title", "new rule")
    if not ref_file or not bullet:
        print(f"Skipping malformed decision: {rule}")
        return
    tag = f" (ADO PR {pr})" if pr else ""

    # each rule starts from a clean develop -> its own branch -> its own PR
    git("checkout", BASE)
    git("checkout", "--", ".", check=False)   # discard any stray tracked edits
    branch = f"rules/harvest-{dt.datetime.utcnow():%Y%m%d-%H%M%S}-{idx}-{slug(title)}"
    git("checkout", "-b", branch)
    append_bullet(ref_file, bullet)
    git("add", ref_file)
    git("commit", "-m", f"{Path(ref_file).stem}: {title}{tag}")
    git("push", "-u", "origin", branch)

    src = f"\n\nSource: [ADO PR {pr}]({ADO}{pr})" if pr else ""
    body = (f"Proposed Groovy test rule mined from a resolved ADO review comment.\n\n"
            f"Adds to **{Path(ref_file).name}**:\n\n{bullet}{src}\n\n"
            f"Review and merge to `{BASE}` to make this rule active.")
    run(["gh", "pr", "create", "--base", BASE, "--head", branch,
         "--title", f"Harvest: {title}{tag}", "--body", body])


def advance_cursor(scanned_max):
    st = load(STATE, {"last_pr_id": 0})
    if not (scanned_max and int(scanned_max) > int(st.get("last_pr_id", 0))):
        return
    git("checkout", BASE)
    st["last_pr_id"] = int(scanned_max)
    st["updated"] = dt.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")
    STATE.write_text(json.dumps(st, indent=2) + "\n")
    git("add", "harvest/state.json")
    if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode != 0:
        git("commit", "-m", f"chore(harvest): advance cursor to PR {scanned_max}")
        # develop may be protected — don't fail the run if the cursor push is rejected
        git("push", "origin", BASE, check=False)


def main():
    scanned_max = load(CAND, {}).get("scanned_max_pr_id")
    accepted = load(DEC, {}).get("accepted") or []

    if not accepted:
        print("No new rules this run.")
        advance_cursor(scanned_max)
        return

    existing = open_pr_titles()
    opened = 0
    for i, rule in enumerate(accepted, 1):
        tag = f" (ADO PR {rule.get('pr')})" if rule.get("pr") else ""
        if f"Harvest: {rule.get('title', 'new rule')}{tag}" in existing:
            print(f"Skipping (open PR already exists): {rule.get('title')}")
            continue
        raise_pr(rule, i)
        opened += 1

    advance_cursor(scanned_max)
    print(f"Opened {opened} pull request(s) for {len(accepted)} decision(s).")


if __name__ == "__main__":
    main()
