# Blaze Link: messages drafted on the PC, sent from the phone (v0.17.0)

Blaze Desktop drafts a WhatsApp (or Telegram) reply and asks the paired phone to send it. The phone polls the PC's
inbox (`GET /v1/inbox?wait=25`, unchanged) and answers each request with `POST /v1/inbox/<id>/reply {"text": ...}`.

A send is an inbox request with `kind`:

```json
{"id": "r42", "kind": "message", "to": "Chanda (HomeGlow customer)", "text": "Hi Chanda, your moon lamp ships today ...", "app": "whatsapp"}
```

- `kind`: `message` (also accepted: `send_message`, `whatsapp_reply`). Requests without `kind` are plain text tasks, as before.
- `to`: a contact name as saved on the phone, or a number (local numbers get the country code added).
- `text` (or `message`): the exact text to send, at most 4,000 characters.
- `app`: `whatsapp` (default), `telegram` or `any` (reply through the chat's live notification when there is one). SMS is refused from the PC.

The phone shows its confirm card for every send, titled "Message <name> on WhatsApp?" with "Drafted on <PC name>." and the
text, even in Autopilot and even with a standing permission. No model runs on the phone for this. The reply text is one line:
`Sent to <name> on WhatsApp.`, `Not sent: the user declined on the phone.` or `Not sent: <reason>`.
