---
name: wf-find-a-person
description: Workflow. Use when: Find who someone is and how to reach them (number, chat app) when the user names them loosely. Use for "find my brother", "who is Gabriel", "get me Mary's number", "is X on WhatsApp".
tags: [workflow, contact, find, person, number, phone, family]
workflow: true
version: 1
---

# Find a person

## Steps
1. Memory first: recall_memory for the name or relationship ("older brother").
2. find_contact with the shortest distinctive part of the name ("Gabriel", not "my older brother Gabriel Kapapa"). It matches emoji names and small typos.
3. Nothing? Try recent_notifications and recent_calls for that name, then gcontacts_search.
4. Several results: list them (name + number) with ask_user. Never pick one silently.
5. When the user confirms, remember who it is (relationship → exact saved name) so next time is instant.
6. Still nothing after steps 2–3: say so plainly and ask for the number. Don't keep searching.

## Checks
- At most 3 lookups before asking the user.
- Report numbers exactly as stored.
