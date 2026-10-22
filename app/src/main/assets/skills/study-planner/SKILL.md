---
name: study-planner
description: Build a day-by-day revision plan from an exam date and a list of topics, put each session in Schedule as a reminder, and re-plan when a day is missed. Use for "plan my revision", "I have an exam on…", "make a study timetable", or when the user says they skipped a session.
tags: [student, study, planning, exams, schedule]
version: 1
---

# Study planner

## 1. Get the inputs (ask only what you can't find)
- Exam(s): course, date and time. Check memory, the calendar (gcal_events / calendar_events) and, if Classroom is on, classroom_coursework before asking.
- Topics per exam. If the user has notes, slides or a course outline (file, PDF, Drive), read them and propose the topic list instead of asking for it.
- Time available: hours per weekday and weekend day, and blocked times (classes, meals, work). Default to 2 h on weekdays and 4 h on weekend days if they don't say, and say you assumed it.
- How confident they are per topic (weak / ok / strong). Weak topics get more and earlier sessions.

## 2. Build the plan
- Count the days from today to the day before the exam. Keep the last day for a light review and a full past paper, never new material.
- Sessions of 45-50 minutes with 10-minute breaks; at most 4 sessions a day.
- Spread each topic over at least two sessions a few days apart (spacing beats cramming), and end each topic with active recall: past-paper questions or a quiz with the tutor skill.
- Interleave subjects when there are several exams; the nearest exam gets priority.
- Leave one buffer slot every 3-4 days for catch-up.

## 3. Show it, then schedule it
- Show the plan as a compact table: Day | Time | Topic | Task (read / practise / past paper / flashcards).
- After the user says OK, add each session with create_routine using `date` (yyyy-MM-dd), `time`, `days: once`, a short name ("CIT102: loops, practice") and a prompt that tells Blaze to remind them and offer a 5-question quiz on that topic.
- Offer to make flashcards for weak topics (tutor skill, flashcards tool) so the Learn view drills them between sessions.
- Save the plan to ~/study/<course>-plan.md so it can be re-planned later.

## 4. Re-plan a missed day
- When the user says they missed a session (or a reminder went unanswered), read ~/study/<course>-plan.md, move the missed topics into the next buffer slot or the lightest days, never past the exam, and drop the least important practice first.
- Delete the old reminders for moved sessions (processes, then process delete) and add the new ones. Tell them in one line what moved.

## Rules
- Never schedule sessions during their classes, sleep (23:00-06:00) or campus meal times they've told you about.
- Keep it realistic: if there isn't enough time, say so and suggest what to cut, rather than overfilling days.
