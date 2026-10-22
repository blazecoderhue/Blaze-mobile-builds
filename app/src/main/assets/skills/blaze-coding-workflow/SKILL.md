---
name: blaze-coding-workflow
description: How Blaze writes and changes code in the terminal, in repos and in apps. Core skill, always on for code work: read first, small diffs, build and test, commit cleanly.
tags: [code, coding, programming, bug, fix, script, repo, git, build, compile, test, refactor, python, kotlin, javascript]
version: 1
core: true
---

# Blaze coding workflow

## Pinned
- Read the code before changing it: the files involved, how they're called, the project's conventions. Keep the diff as small as the fix allows.
- Match the existing style and structure. Don't invent APIs, flags or library functions: look them up (docs, source, --help) first.
- After every change, run, compile or test it, then fix until it's green. Don't hand over code you never ran when you could have.
- Never break user data or leak secrets: back up before migrations, keep formats backward compatible, keep keys out of code, logs and commits.
- Commit in logical chunks with clear messages. Tell the user what changed and why in two or three lines.
- Examples you give are small and runnable as written.

## Steps
1. **Understand**: reproduce the problem or restate the feature; find the entry point (file_search, grep); read the surrounding code and tests.
2. **Plan**: the smallest change that solves it; note files you will touch and how you'll check it.
3. **Change**: edit with file_edit (not shell heredocs); keep names, formatting and patterns consistent with the file.
4. **Check**: run the build, tests or the script (run_code / run_shell); read errors fully, fix the cause, rerun. Three honest attempts before asking for help.
5. **Commit**: git add only what belongs together; message = what + why in the imperative ("Fix crash when list is empty").
6. **Report**: what changed, why, how you verified it, anything left.

## Safety
- Destructive commands (rm -rf, force-push, dropping tables, rewriting history) need the user's yes.
- Data files, prefs and databases keep loading after your change; add fields, don't rename or drop them.
- Tokens and passwords come from the vault or environment, never pasted into files.
