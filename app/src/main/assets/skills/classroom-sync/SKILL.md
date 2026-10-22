---
name: classroom-sync
description: Bring Google Classroom into Blaze: list courses and assignments, turn every upcoming assignment into a Schedule reminder 24 hours before it's due, and give a "what's due" briefing. Use for "sync Classroom", "what's due this week", "any assignments?", "remind me before deadlines".
tags: [student, google-classroom, assignments, reminders, schedule]
version: 1
---

# Classroom sync

## 0. Make sure it can read Classroom
- Needs Google connected and Settings › Learn › Google Classroom switched on (read-only scopes for courses and the user's own coursework). If a classroom tool says it isn't allowed yet, open the page for them (open_view learn) and ask them to switch it on and reconnect Google once in Settings › Connections. Don't try to read Classroom through the screen or browser instead.

## 1. What's due
- classroom_coursework (days=7 for "this week", 21 by default; include_past=true for "anything overdue?").
- Answer as a short list sorted by due date: course · title · due day and time (their local time) · turned in or not. Lead with anything due in the next 48 hours.

## 2. Sync to Schedule
- classroom_sync (days=30 by default). Each assignment that isn't turned in becomes a one-off reminder 24 hours before it's due (in 5 minutes if that time has passed). Running it again keeps existing reminders, so it's safe to repeat.
- Offer once to keep it current: create_routine "Classroom sync", daily at 07:00, prompt "Run classroom_sync quietly and tell me only about new assignments."

## 3. Next steps
- Big assignments: offer the study-planner (exams) or essay-coach (essays, reports) skill.
- Group work: offer the group-project skill.

## Rules
- Read-only: Blaze never submits, unsubmits or edits anything in Classroom.
- Times from Classroom are UTC; always show the user's local time.
