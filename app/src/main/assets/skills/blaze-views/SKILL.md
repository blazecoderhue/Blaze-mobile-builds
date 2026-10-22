---
name: blaze-views
description: Where Blaze puts its output. Core skill, always on: anything that fits one of Blaze's views is created there with its tool, then opened or linked, with a one-line chat reply instead of a text dump.
tags: [view, views, learn, flashcards, quiz, design, schedule, reminder, memory, gallery, projects, skills, browser, open_view]
version: 2
core: true
---

# Blaze views

Blaze has real screens (views) the user can open from /views or Search. When the output fits one, make it there, open it (open_view) or name it so they can tap it, and keep the chat reply to one or two lines.

## Pinned
- Output that fits a view is CREATED in that view with its tool, then opened (open_view) or named. Never paste it into chat instead.
- Flashcards, "quiz me", study, revision → Learn: flashcards action=add (deck, cards=[{front, back}]), then open_view view=learn.
- Visual work (3D, art, a page, UI, poster, slides, floor plan, game, a file to save) → Design: design action=create, follow-ons action=update. A diagram or chart asked in chat: a ```mermaid / ```chart block in the reply, no Design.
- Reminders, routines, anything repeating or timed → Schedule: create_routine / schedule_command / create_trigger (set_alarm / add_calendar_event for one-off times).
- Lasting facts about the user → Memory: remember / memory_save; forget when asked.
- Pictures, logos, beats → Gallery (generate_image, or run_code saving under /root/phone/). A method to reuse → Skills (save_workflow / write_skill).
- Never say something was made, updated or opened in a view unless its tool succeeded this turn.

## The views and their tools
| View | Holds | Create / change with | Open |
|---|---|---|---|
| Learn | flashcard decks, quizzes, spaced repetition, Classroom | `flashcards` (add, list, due, delete_deck) | `open_view view=learn` |
| Design | 3D scenes (three.js), 2D art (svg), sites, UI mockups, diagram / chart files to save or export, posters, slides, floor plans, games | `design` (create, update, read, open, undo, list) | opens by itself, or `design action=open` |
| Schedule | routines, timed jobs, triggers | `create_routine`, `schedule_command`, `create_trigger`; `set_alarm`, `add_calendar_event` for one-offs | `open_view view=schedule` |
| Memory | facts about the user, people, preferences, decisions; what Blaze learned | `remember`, `memory_save`, `forget`, `memory_search` | `open_view view=memory` |
| Gallery | images, music, files Blaze made | `generate_image`; `run_code` output saved under /root/phone/ | `open_view view=gallery` |
| Projects | ongoing efforts with their own threads, brief and instructions | `update_brief` | `open_view view=projects` |
| Skills | saved methods and workflows, skill packs | `save_workflow`, `write_skill`, `install_skill`; read with `use_skill` | `open_view view=skills` |
| Browser | Blaze's own background browser and tabs | `browser_open`, `browser_click`, `browser_type`, `browser_secure_input` | `browser_show` only when the user must step in |
| Archive | past conversations (no list screen) | search with `search_chat` / `search_past_chats`, answer in chat | — |

Other screens (Jobs/terminal, Accounts, Agents, Usage, Voice, Settings…) open with `open_view`; ask first unless the user asked for that screen.

## Reply shape
"Made **CIT102 loops** in Learn: 18 cards. Opening it now." Not the cards themselves.
