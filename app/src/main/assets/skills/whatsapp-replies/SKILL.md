---
name: whatsapp-replies
description: Draft replies to store customers on WhatsApp (order status, delivery, returns, product questions, price questions) in the brand voice, and send them only after the user approves each one. On Blaze Desktop, replies are drafted on the PC and sent through the paired phone over Blaze Link. Use for "reply to customers", "answer this WhatsApp", "customer asked…".
tags: [customer-service, whatsapp, ecommerce, messaging]
version: 1
---

# WhatsApp replies

## 1. Read the conversation
- On the phone: recent_notifications (WhatsApp) or the message the user forwards/quotes. On the PC: the text the user pastes.
- Find the order if it's about one: order emails in Gmail (gmail_search "order #1234"), ~/store/orders.csv, or ask the user.

## 2. Draft (brand voice from brand_kit get)
- Greeting with their name, answer in 1-3 short sentences, one clear next step, sign-off with the store name. No more than ~60 words.
- Order status: say what you actually know (status, tracking link, expected days) and nothing invented.
- Delays: apologise once, give the new estimate and what you're doing.
- Returns/refunds: follow the store's policy (ask the user for it once and save it in the project brief or memory); never promise more than the policy.
- Price questions: the listed price; discounts only if the user approves.
- Show each draft to the user before sending.

## 3. Send (always with approval)
- On the phone: message_contact {to, text, app: whatsapp}. The confirm card shows; it only sends when the user taps Send.
- From Blaze Desktop: the PC sends the draft to the paired phone over Blaze Link as a message request ({"kind": "message", "to", "text", "app": "whatsapp"}). The phone shows a confirm card marked "Drafted on <PC>" every time, even in Autopilot, and tells the PC whether it was sent or declined.
- Several customers: one card per message, never a bulk send.

## Rules
- Never send without the user's tap. Never share another customer's details. No pushy follow-ups more than once.
