---
name: wf-reply-to-latest
description: Workflow. Use when: Answer the latest message from someone (WhatsApp, SMS, Telegram). Use for "reply to Gabriel", "answer that message", "what did mum say and reply yes".
tags: [workflow, reply, message, whatsapp, notification, sms]
workflow: true
version: 1
---

# Reply to the latest message

## Steps
1. recent_notifications (or read_messages for SMS) to read what they actually said. Quote it back in one line.
2. Draft the reply in the user's voice, short.
3. Send with message_contact {to: the sender's name as shown, text}. It replies in the background through the notification when it can.
4. Report from the tool's result only ("Sent to" = sent).

## Checks
- Never invent what the other person said.
