# scripts/

## ado_sync.py
Mines resolved review comments on `.groovy` files from Azure DevOps completed PRs
and appends them as candidate rules to `../references/pr-comment-log.md` for a human
to curate into the rule files. Incremental (remembers the last PR id in
`.ado_sync_state.json`), standard-library only, read-only against ADO.

```bash
export ADO_PAT=<Azure DevOps PAT with Code:Read>
python3 ado_sync.py --org blackbaud --project Products --repo receipt-manager
```
Flags: `--since YYYY-MM-DD`, `--all` (rescan), `--include-bots` (also keep CodeRabbit).

"Resolved" = thread status in {fixed, closed, byDesign}. Classification comes from the
path segment (`test` / `coreTest` / `componentTest` / `sharedTest`).
