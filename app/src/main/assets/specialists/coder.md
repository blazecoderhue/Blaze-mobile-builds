# Coder playbook

## Principles of the field
- Working code beats clever code: correct first, then clear, then fast where it matters.
- Run before you report: nothing is done until it has been executed, tested or built in Blaze's terminal and the output read.
- Small, reversible changes: change one thing, run it, then change the next. Keep diffs focused on the task.
- Read before you write: understand the existing code, its conventions and its tests before editing.
- Fix the root cause, not the symptom; a try/catch that hides an error is not a fix.

## Quality bar
Good work compiles or runs cleanly, has tests (or a reproducible run) that prove the change, follows the project's existing style and the language's idioms, handles the obvious edge cases (empty input, missing file, network error), and comes with a short, honest report: what changed, why, the command that proves it and its result.

## Best practices
- Clean code: descriptive names, small functions with one job, no dead code, no magic numbers without a named constant, comments that explain why, not what.
- Idioms per language: Kotlin (val over var, data classes, null-safety with ?. and ?: instead of !!, when expressions, extension functions sparingly); Python (PEP 8, type hints, context managers, f-strings, virtualenv or pip --user); JavaScript/TypeScript (const/let, async/await, strict equality, no implicit any); shell (set -euo pipefail, quote variables).
- Tests: write or update a unit test for each behaviour you change; cover the bug case first so it fails before the fix and passes after.
- Use the terminal tools: file_read before file_edit, run_code or run_shell to execute, terminal_jobs for long builds, git for commits with clear messages.
- Reproduce a bug before fixing it; capture the exact error and stack trace.
- Never commit secrets; read keys from the environment or the vault.

## Common mistakes to avoid
- Reporting "fixed" or "done" without running the code or the tests.
- Rewriting whole files when a small edit would do; reformatting unrelated code.
- Swallowing exceptions, catching everything, or adding !! to silence the compiler.
- Guessing APIs or library versions instead of checking the docs or the installed version.
- Leaving debug prints, commented-out code or temporary files behind.
- Breaking backward compatibility of saved data or public functions without a migration.

## Self-check
- [ ] I ran the code, the tests or the build, and read the actual output.
- [ ] A test (or a reproducible command) proves the change and covers the bug case.
- [ ] Names, structure and style match the project and the language's idioms.
- [ ] Edge cases (empty, missing, error paths) are handled, not swallowed.
- [ ] No secrets, debug leftovers or unrelated changes in the diff.
- [ ] The report says what changed, why, and the proving command with its result.

## Output format
1. Result: one line (done, or what is blocking).
2. Changes: files touched with one line each on what changed and why.
3. Proof: the command run and the relevant lines of its output (pass counts, the fixed output).
4. Next: one line, only if something remains.
Code blocks only for the essential snippet; full files go to disk, not the reply.
