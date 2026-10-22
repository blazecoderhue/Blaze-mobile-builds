---
name: group-project
description: Run a group assignment as a Blaze project: a task list with owners and dates, a pinned brief, meeting notes, and a ready-to-paste WhatsApp summary for the group. Use for "group project", "group assignment", "split the work", "what has everyone done", "update the group".
tags: [student, teamwork, projects, whatsapp, tasks]
version: 1
---

# Group project

## 1. Set it up (once)
- Ask for: the assignment (or read it from Classroom / a file), due date, group members' names, and how the group talks (usually a WhatsApp group).
- Suggest creating a Blaze project for it (Projects tab) and put this in its brief with update_brief:
  Purpose, due date, deliverables, members and roles, rules (e.g. "drafts in Google Docs by Friday"), links.
- Break the work down with the planning-task-breakdown skill: tasks ≤ 1 day each, one owner each, a due date at least 2 days before the real deadline for review.
- Save the task list to ~/projects/<name>/tasks.md as a checklist: `- [ ] Task — Owner — due Day d Mon`.

## 2. Keep it moving
- "X finished the intro": tick it in tasks.md and update the brief's "in progress" line.
- Meeting notes: when the user shares notes or a voice note (lecture-notes skill for transcription), add decisions and new tasks to tasks.md.
- Before each internal deadline, offer a reminder (create_routine with date) for the user.

## 3. WhatsApp summary (the "update the group" button)
Write a short message the user can paste or share (share_to_app WhatsApp, or message_contact for one person, always with their approval):
```
*<Project> update* (<date>)
✅ Done: …
⏳ In progress: … (owner, due)
❗ Needs someone: …
📅 Next: meeting <day time> / final due <date>
```
- Under 120 words, names exactly as the group uses them, no blame. Never send to the group without the user's OK.

## Rules
- The user is one member: Blaze helps them coordinate and do their own part; it doesn't do other members' graded sections for them (see essay-coach integrity rule).
