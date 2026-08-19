#!/usr/bin/env python3
"""
harvest_finalize.py — deterministically raise the pull request.

The Claude step edits the rule files (references/*.md) and records what it did in
harvest/decisions.json. This step does the git + gh work so a PR is reliably opened
whenever a rule file actually changed — no reliance on the model to run git.

Source of truth for "what to commit" is the git working tree (the reference files
Claude modified). decisions.json is used for nice commit messages / PR body, and
harvest/candidates.json provides the cursor value.

Behaviour:
  * no references/*.md changed  -> advance the cursor on develop, no PR
  * references/*.md changed      -> new branch, ONE commit per changed rule file,
                                   advance the cursor, push, open ONE PR to develop
Requires: gh (GH_TOKEN in env) and a checkout with push credentials.
"""
import json, subprocess, sys, datetime as dt
from pathlib import Path

ROOT  = Path(__file__).resolve().parent.parent
STATE = ROOT / "harvest" / "state.json"
CAND  = ROOT / "harvest" / "candidates.json"
DEC   = ROOT / "harvest" / "decisions.json"
BASE  = "develop"
ADO   = "https://dev.azure.com/blackbaud/Products/_git/receipt-manager/pullrequest/"


def git(*a, check=True):
    r = subprocess.run(["git", *a], text=True, capture_output=True)
    if r.stdout.strip(): print(r.stdout.strip())
    if r.stderr.strip(): print(r.stderr.strip(), file=sys.stderr)
    if check and r.returncode:
        sys.exit(f"ERROR: git {' '.join(a)} failed ({r.returncode})")
    return r


def load(p, default):
    try: return json.loads(p.read_text())
    except Exception: return default


def changed_rule_files():
    r = subprocess.run(["git", "diff", "--name-only", "--", "references"],
                       text=True, capture_output=True)
    return [f for f in r.stdout.split() if f.endswith(".md")]


def advance_cursor(scanned_max):
    st = load(STATE, {"last_pr_id": 0})
    if scanned_max and int(scanned_max) > int(st.get("last_pr_id", 0)):
        st["last_pr_id"] = int(scanned_max)
        st["updated"] = dt.datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")
        STATE.write_text(json.dumps(st, indent=2) + "\n")
        return True
    return False


def main():
    scanned_max = load(CAND, {}).get("scanned_max_pr_id")
    accepted = load(DEC, {}).get("accepted") or []
    by_file = {}
    for a in accepted:
        by_file.setdefault(a.get("ref_file", ""), []).append(a)

    changed = changed_rule_files()

    # --- no new rules: just move the cursor forward on develop ---
    if not changed:
        if advance_cursor(scanned_max):
            git("add", "harvest/state.json")
            git("commit", "-m", f"chore(harvest): advance cursor to PR {scanned_max}")
            git("push", "origin", BASE)
        print("No new rules this run.")
        return

    # --- new rules: branch, one commit per changed file, PR to develop ---
    branch = "rules/harvest-" + dt.datetime.utcnow().strftime("%Y%m%d-%H%M%S")
    git("checkout", "-b", branch)

    for f in changed:
        git("add", f)
        rules = by_file.get(f, [])
        prs = ", ".join(sorted({str(r["pr"]) for r in rules if r.get("pr")})) or "n/a"
        if len(rules) == 1 and rules[0].get("title"):
            msg = f"{Path(f).stem}: {rules[0]['title']} (ADO PR {prs})"
        else:
            msg = f"{Path(f).stem}: add {max(len(rules), 1)} rule(s) (ADO PR {prs})"
        git("commit", "-m", msg)

    if advance_cursor(scanned_max):
        git("add", "harvest/state.json")
        git("commit", "-m", f"chore(harvest): advance cursor to PR {scanned_max}")

    git("push", "-u", "origin", branch)

    all_prs = ", ".join(sorted({str(r["pr"]) for r in accepted if r.get("pr")}))
    n = len(accepted) if accepted else len(changed)
    title = f"Harvest: {n} new Groovy test rule(s)" + (f" (ADO PRs {all_prs})" if all_prs else "")
    body = ["Proposed Groovy test-authoring rules mined from resolved ADO review comments.", ""]
    if accepted:
        for r in accepted:
            fn = Path(r.get("ref_file", "")).name or "?"
            pr = r.get("pr")
            src = f" ([ADO PR {pr}]({ADO}{pr}))" if pr else ""
            body.append(f"- **{fn}** — {r.get('title', 'new rule')}{src}")
    else:
        for f in changed:
            body.append(f"- **{Path(f).name}** — new rule(s)")
    body += ["", "Review and merge to `develop` to make these rules active."]

    r = subprocess.run(["gh", "pr", "create", "--base", BASE, "--head", branch,
                        "--title", title, "--body", "\n".join(body)],
                       text=True, capture_output=True)
    print(r.stdout.strip())
    if r.stderr.strip(): print(r.stderr.strip(), file=sys.stderr)
    if r.returncode:
        sys.exit("ERROR: `gh pr create` failed. Confirm Settings -> Actions -> General "
                 "allows GitHub Actions to create pull requests (and org policy permits it).")


if __name__ == "__main__":
    main()
