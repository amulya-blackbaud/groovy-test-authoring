#!/usr/bin/env python3
"""
harvest_fetch.py — deterministic, read-only ADO scan.

Reads the saved cursor (harvest/state.json), lists COMPLETED pull requests in the
receipt-manager repo newer than that cursor, keeps resolved human review comments
that sit on .groovy files, and writes them to harvest/candidates.json for the
analysis step (Claude) to judge. Standard library only. Never writes to ADO and
never edits the rule files.

Env:
  ADO_PAT       Azure DevOps PAT with Code:Read   (required)
  ADO_ORG       default: blackbaud
  ADO_PROJECT   default: Products
  ADO_REPO      default: receipt-manager
"""
import base64, json, os, sys, urllib.request, urllib.error, urllib.parse
from pathlib import Path

ORG      = os.environ.get("ADO_ORG", "blackbaud")
PROJECT  = os.environ.get("ADO_PROJECT", "Products")
REPO     = os.environ.get("ADO_REPO", "receipt-manager")
API      = "7.1"
RESOLVED = {"fixed", "closed", "byDesign"}          # what "resolved" means in ADO
SOURCE_SETS = {"test", "coreTest", "componentTest", "sharedTest"}

ROOT  = Path(__file__).resolve().parent.parent
STATE = ROOT / "harvest" / "state.json"
OUT   = ROOT / "harvest" / "candidates.json"
BASE  = f"https://dev.azure.com/{ORG}/{PROJECT}/_apis/git/repositories/{REPO}"


def auth_header():
    pat = os.environ.get("ADO_PAT")
    if not pat:
        sys.exit("ERROR: ADO_PAT is not set.")
    return "Basic " + base64.b64encode(f":{pat}".encode()).decode()


def get(url):
    req = urllib.request.Request(
        url, headers={"Authorization": auth_header(), "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        sys.exit(f"ERROR: ADO HTTP {e.code} for {url}\n{e.read().decode()[:300]}")
    except urllib.error.URLError as e:
        sys.exit(f"ERROR: ADO network error for {url}: {e}")


def load_cursor():
    try:
        return int(json.loads(STATE.read_text()).get("last_pr_id", 0))
    except Exception:
        return 0


def classify(path):
    for seg in path.strip("/").split("/"):
        if seg in SOURCE_SETS:
            return seg
    return None


def first_human_comment(thread):
    for c in thread.get("comments", []):
        author  = c.get("author", {}).get("displayName", "?")
        content = (c.get("content") or "").strip()
        if content and c.get("commentType", "text") != "system" \
           and author != "Microsoft.VisualStudio.Services.TFS":
            return author, content
    return None, None


def main():
    last = load_cursor()
    q = urllib.parse.urlencode({
        "searchCriteria.status": "completed", "$top": "100", "api-version": API})
    prs = get(f"{BASE}/pullrequests?{q}").get("value", [])

    candidates, scanned_max = [], last
    for pr in prs:
        pid = pr["pullRequestId"]
        scanned_max = max(scanned_max, pid)
        if pid <= last:
            continue
        for th in get(f"{BASE}/pullrequests/{pid}/threads?api-version={API}").get("value", []):
            path = (th.get("threadContext") or {}).get("filePath") or ""
            if not path.endswith(".groovy") or th.get("status") not in RESOLVED:
                continue
            src = classify(path)
            if not src:
                continue
            author, content = first_human_comment(th)
            if not content:
                continue
            candidates.append({
                "pr": pid, "threadId": th.get("id"),
                "file": path.rsplit("/", 1)[-1], "path": path,
                "source_set": src, "author": author,
                "comment": " ".join(content.split())[:500],
            })

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(
        {"previous_last_pr_id": last, "scanned_max_pr_id": scanned_max,
         "candidates": candidates}, indent=2))
    print(f"Scanned {len(prs)} completed PRs; found {len(candidates)} candidate "
          f"comment(s) above PR {last}. Highest PR seen: {scanned_max}.")


if __name__ == "__main__":
    main()
