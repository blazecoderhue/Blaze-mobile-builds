---
name: blaze-specialists
description: How Blaze uses its specialists and background agents. Core skill, always on: pick the matching specialist, hand independent parts out in parallel without blocking, keep small work, steer instead of duplicating, follow up as each result lands.
tags: [specialist, specialists, delegate, parallel, background agent, compare, split, team, shopper, booker, researcher, coder, tutor, designer]
version: 2
core: true
---

# Blaze specialists

Blaze has specialists (Settings › Agents): Researcher, Shopper, Booker, Coder, Tutor, Designer, Writer, Planner, Inbox manager and Phone operator, plus any the user made. Each has its own prompt, a field playbook it checks its work against, only the tools it needs, and its own skill packs. `delegate` starts several at once in the background (each with a card: Steer / Stop) and returns right away; each result arrives on its own. `start_agent` runs other long work in the background. `create_specialist` adds a new one when no specialist covers a field.

## Pinned
- Pick the specialist(s) that fit the task and follow their skill packs; they're matched and attached for you each turn ("Specialist: X" on the work card).
- Delegate only when it helps: independent parts that can run in parallel, or big research. Small or step-by-step work stays with you.
- One delegate call, one task per target (compare 3 shops = 3 tasks); never two specialists on the same target. Give scope: one line for the user ("Sending Researcher and Shopper to compare…").
- Every brief is tight and self-contained (they can't see the chat): the goal, the exact thing to return, the limits, and "stop and report after two failed tries".
- delegate doesn't block: keep helping. When a result lands, write a short follow-up from it; merge into one pick when the last is in. Steer with message_agent, stop with cancel_agent; never start a duplicate.
- No specialist for the field? create_specialist with a real field playbook (all six sections, field-specific). Check the numbers before you hand anything over.

## Writing a brief
```
Goal: price of a Samsung Galaxy A15 128 GB at Game Zambia, delivered to Ndola.
Return: price in ZMW, in stock yes/no, delivery fee and days, product link.
Limits: new only; ignore marketplace resellers; don't buy or sign in.
Stop: if the site fails twice, report what you saw and stop.
```

## When to split
| Request | Plan |
|---|---|
| "Compare the A15 at Game, Shoprite and Jumia" | delegate: 3 Shopper tasks, one per store; follow up as each lands, then merge |
| "Find me a bus to Lusaka Friday and a cheap lodge" | delegate: 1 Booker (bus) + 1 Booker (lodge) |
| "Research solar options and write a one-page summary" | Researcher first, then write it yourself from the result |
| "Text Gloria I'm late" | do it yourself |
| "Fix this bug in my repo" | do it yourself with blaze-coding-workflow (step by step) |

## Merging
1. Line the results up on the same fields.
2. Drop any that broke the limits.
3. Lead with the pick and the reason; keep the rest to a short list or a card.
4. Say plainly which pieces failed and what you'd try next.
