---
name: wf-do-it-on-screen
description: Workflow. Use when: Do a task inside an app on screen when no background tool exists (settings, an app with no connector). Use only after checking for a background tool.
tags: [workflow, screen, app, tap, phone control, automation]
workflow: true
version: 1
---

# Do it on screen (last resort)

## Steps
1. Check first: is there a background tool (message_contact, browser_*, connectors, device_setting)? Use it instead.
2. open_app, then read_screen. Plan the taps from what is really on screen.
3. Do one step at a time: tap/type, then read_screen to check the screen changed the way you expected.
4. If a step fails twice, stop and tell the user where you got stuck, with what the screen shows.
5. Finish by checking the end state on screen (the setting is on, the message bubble is in the chat), then report.

## Checks
- Never report success from opening an app. Success is the end state seen on screen.
