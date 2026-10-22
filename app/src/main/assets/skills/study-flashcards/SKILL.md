---
name: study-flashcards
description: Make and study flashcards in Blaze's Learn view (spaced repetition) instead of writing questions and answers in chat. Use for "make flashcards", "flashcards on…", "quiz me", "test me on…", "help me study / revise / memorise", "turn my notes into cards", or exam prep.
tags: [flashcards, flash cards, quiz, quiz me, test me, study, studying, revise, revision, memorise, memorize, exam, deck, learn, cards]
version: 1
---

# Study flashcards (Learn view)

Flashcards live in the **Learn** view, never in chat. Luke's rule: when output fits a view, make it there, open it, and answer in one or two lines.

## Make a deck
1. Find the material: what the user pasted, a file (file_read), a PDF, slides, a Drive or Classroom file, or the topic they named. If they only gave a topic, write the cards from solid, well-known facts; never invent.
2. Write 10-25 cards, one fact each:
   - front: a real question or a cloze ("The powerhouse of the cell is the ____"), never a bare heading;
   - back: the short answer plus the one detail that makes it stick, under ~40 words.
3. Deck name = course code + topic when you know it ("CIT102 loops"), else the topic ("Photosynthesis").
4. Save with `flashcards` action=add, deck, cards=[{front, back}].
5. Open it: `open_view` view=learn.
6. Reply in one or two lines: the deck name, how many cards, and that cards come back just before they'd forget them. Do NOT list the cards in chat.

## Quiz me
- From an existing deck: `flashcards` action=list, then action=due for that deck; ask one card at a time (ask_user with options for multiple choice), mark each answer, end with the score.
- On a new topic: make the deck first (steps above), then offer to quiz from it. Never paste a whole quiz with its answers into chat.

## Add to a deck
- Same deck name with action=add; duplicates (same front) are skipped automatically.

## Pitfalls
- A reply full of "Q: … A: …" lines is wrong: those belong in a deck.
- Graded homework: explain the method with a similar example; don't make cards that hand over the answers to their exact assignment.
