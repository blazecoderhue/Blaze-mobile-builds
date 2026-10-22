---
name: wf-call-someone
description: Workflow. Use when: Call a person or number, with the same lookup rules as messaging. Use for "call my brother", "ring Mary", "phone this number".
tags: [workflow, call, phone, contact, dial]
workflow: true
version: 1
---

# Call someone

## Steps
1. Resolve the person as in wf-find-a-person (memory, find_contact, ask_user when several match).
2. call_number with the exact saved name or the number. The user confirms on the card.
3. WhatsApp call wanted: say Blaze can open the chat with message_contact but the user taps the call button.

## Checks
- Only say "Calling X" when the tool said so.
