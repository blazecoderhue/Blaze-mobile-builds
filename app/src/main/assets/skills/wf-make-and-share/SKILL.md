---
name: wf-make-and-share
description: Workflow. Use when: Make something (image, document, design, audio) and then share or save it outside Blaze. Use for "make a poster and share it", "generate a picture and save it to my phone", "create a PDF and send it".
tags: [workflow, generate, image, share, save, gallery, export]
workflow: true
version: 1
---

# Make it, then share it

## Steps
1. Make it with the right tool (generate_image, design, run_code) and wait for success. Check the result is what was asked (look at it if needed).
2. To a person: message_contact with image="last" (pictures) and follow wf-send-to-person.
3. To the phone: gallery action=save.
4. To any app: gallery action=share (opens the share sheet; the user picks the app).

## Checks
- Each step is done only when its tool succeeded. Say which step failed if one did.
