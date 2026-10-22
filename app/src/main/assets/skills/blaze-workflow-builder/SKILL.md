---
name: blaze-workflow-builder
description: Turning a multi-step or repeating task into a reusable workflow (a user skill with trigger, steps and checks), testing it once, and using it next time. Core skill, always on.
tags: [workflow, save workflow, skill, save skill, automate, repeat, recurring, every time, again, routine method, procedure, teach]
version: 1
core: true
---

# Blaze workflow builder

## Pinned
- When a task took several steps, or the user is likely to ask again ("every week", "like last time", "here's how I do it"), write it down as a workflow with save_workflow: name, trigger, steps, checks.
- Test it once by following the saved steps on the current task (or a dry run), fix what was wrong, and save again under the same name.
- Before a matching task, use the saved workflow (it's looked up for you each turn); when you find a better way, update it under the same name.
- Tell the user in one line that it's saved and what will trigger it. Don't save one-off chatter or anything secret.

## What a workflow holds
- **name**: short kebab-case ("airtime-top-up", "weekly-store-digest").
- **trigger**: when to use it, in the user's words ("top up airtime", "send the Friday digest"). This is also what the per-turn lookup matches on.
- **steps**: numbered, each one action with the tool to use and the target ("1. Open the MTN MoMo app with open_app…").
- **checks**: how to know each important step worked, and the final "done" signal.
- **tags**: a few words people would actually type for it.

## Steps
1. Notice it: multi-step, or will recur, or the user taught you a method.
2. Draft the workflow from what actually worked this time (not from guesses).
3. Save with `save_workflow` (falls back to `write_skill`).
4. Test once: follow it step by step; if a step is wrong, fix it and save again (same name, `tested: true` once it passed).
5. Next time: the lookup injects it; follow it, verify the checks, improve it when something changed.

## Don't
- Store passwords, codes or card numbers in a workflow. Use the vault placeholders instead.
- Save a workflow the user asked you not to keep.
