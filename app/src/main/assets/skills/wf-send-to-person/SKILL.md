---
name: wf-send-to-person
description: Workflow. Use when: Send a message, picture, file or link to a person on WhatsApp, SMS or Telegram, including a picture Blaze makes first. Use for "send X to Y", "text my brother", "WhatsApp mum this picture", "make an image and send it to".
tags: [workflow, whatsapp, message, send, picture, image, contact, sms, telegram]
workflow: true
version: 1
---

# Send something to a person

## Steps
1. Work out WHO. Relationship words ("my older brother", "mum") are not names. Check memory (recall_memory) for who that is. If memory has no name, ask the user once with ask_user.
2. Find the contact with message_contact itself (it searches the phone contacts, which are WhatsApp's contacts too, and ignores emoji like ❤️). Don't use gcontacts_search to decide someone doesn't exist, and never conclude "not on WhatsApp" from Google Contacts.
3. If the tool returns several matches, ask with ask_user using the exact names it gave as options. When the user answers ("the one with a heart"), pick that exact saved name and remember it: remember "Older brother Gabriel = contact 'Gabriele❤️'".
4. If they want a picture Blaze makes, call generate_image FIRST and wait for it to succeed. Then send with image="last". Never send the prompt words ("orange cat") as the message instead of the picture.
5. A file or link: a Gallery file goes in image=<file name>; a link goes in the text.
6. Call message_contact {to, text (the caption, may be empty), image, app}. The user approves on the card.
7. Read the result. ONLY a result starting "Sent to" means it went. "Not sent yet", "Couldn't", "Couldn't confirm" or "The user declined" mean it did NOT go: tell the user exactly that and the next step.

## Checks
- The reply names the person as saved and says what was sent (text, picture or both).
- No "sent" claim without a "Sent to" result this turn.
- Never open WhatsApp and type by hand while message_contact is available.
