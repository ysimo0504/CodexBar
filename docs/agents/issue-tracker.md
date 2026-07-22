# Issue tracker: GitHub

Issues and PRDs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --comments`, filtering comments by `jq` and also fetching labels.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments` with appropriate label and
  state filters.
- **Comment on an issue**: `gh issue comment <number> --body "..."`.
- **Apply or remove labels**: `gh issue edit <number> --add-label "..."` or `--remove-label "..."`.
- **Close an issue**: `gh issue close <number> --comment "..."`.

Infer the repo from `git remote -v`; `gh` does this automatically inside the clone.

## Pull requests as a triage surface

**PRs as a request surface: no.**

GitHub shares one number space across issues and PRs. Resolve an ambiguous `#42` with `gh pr view 42`, then fall
back to `gh issue view 42`.

## Skill operations

- When a skill says "publish to the issue tracker", create a GitHub issue.
- When a skill says "fetch the relevant ticket", run `gh issue view <number> --comments`.

## Wayfinding operations

The map is one issue labelled `wayfinder:map`; investigation tickets are child issues.

- **Map**: create with `gh issue create --label wayfinder:map`.
- **Child ticket**: use GitHub sub-issues when available. Otherwise add it to a task list in the map and put
  `Part of #<map>` at the top of the child body. Label it `wayfinder:research`, `wayfinder:prototype`,
  `wayfinder:grilling`, or `wayfinder:task`.
- **Blocking**: use GitHub native issue dependencies. The blocker value is the blocker's numeric database ID, not
  its issue number or node ID. If unavailable, add `Blocked by: #<n>, #<n>` to the child body.
- **Frontier**: among open map children, drop assigned tickets and tickets with open blockers. First in map order
  wins.
- **Claim**: `gh issue edit <n> --add-assignee @me` before starting work.
- **Resolve**: add a resolution comment, close the ticket, then append a linked one-line context pointer to the
  map's `Decisions so far` section.
