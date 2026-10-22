---
name: lecture-notes
description: Turn a lecture recording (audio or video) or a rough transcript into clean notes, a short summary and key terms, plus optional flashcards. Use for "transcribe my lecture", "make notes from this recording", "summarise this class", or when the user shares an audio file from class.
tags: [student, notes, transcription, summary, lectures]
version: 1
---

# Lecture notes

## 1. Get the text
- Recording on the phone (Recorder, WhatsApp voice note, video): find it under /sdcard (Recordings, Music, Download, WhatsApp/Media) or the attachment the user sent.
- Transcribe with free Whisper in Blaze's Linux (no paid APIs):
  `linux_install` if needed, then run_code python: `pip install faster-whisper` and use model "base" (or "tiny" on low storage / low-data mode), `compute_type="int8"`, language auto. Long files: run it in the background (run_shell background=true) and tell the user it's working; check with terminal_jobs.
- Convert video or odd formats first with ffmpeg (`ffmpeg -i in.mp4 -ac 1 -ar 16000 out.wav`).
- If the user pasted a transcript or photos of the board, use that instead (photos: read them as images).
- Save the raw transcript to ~/notes/<course>/<yyyy-mm-dd>-transcript.txt.

## 2. Write the notes (~/notes/<course>/<yyyy-mm-dd>-notes.md)
- Title: course, topic, date.
- Summary: 5 bullet points max, what the lecture was about and the main conclusions.
- Notes: headings in lecture order; definitions in bold; formulas and code in blocks; examples the lecturer gave; anything said to be "on the exam" marked ⚑.
- Key terms: a table term | meaning.
- Questions to ask: what was unclear or cut off in the recording (mark [inaudible] spots honestly, never guess).
- Tasks: homework, deadlines and readings mentioned, each with its date. Offer to add them to Schedule (create_routine with date).

## 3. Offer next steps (one line each)
- Flashcards from the key terms and ⚑ items (tutor skill → flashcards add).
- Add the lecture to a study plan (study-planner).
- Share the notes (export_file, or share_to_app WhatsApp for a class group).

## Rules
- Keep the lecturer's meaning; don't add outside facts unless marked "(added)".
- Delete large audio copies you made, not the user's originals.
