---
name: paperwork-mode
description: Paperwork Mode. Walk the user step by step through an official form or application (NRC, passport, TPIN, driving licence, bursary or student loan, visa, business registration, birth certificate): a checklist of documents to bring, fees, where to go, deadlines, and filling the form field by field. Use for "how do I get a passport", "help me apply for a TPIN", "bursary form", "renew my NRC", "what documents do I need".
tags: [admin, government, forms, checklist, deadlines]
version: 1
---

# Paperwork Mode

Goal: the user walks into the office (or submits online) once, with everything right.

## 1. Pin down the case (one short question at most)
- Which document, which country, first time or renewal/replacement (lost? damaged? name change?), and for whom (self, child).
- Use what Blaze already knows (country, city, age, student status) instead of asking.

## 2. Get the current rules from the official source
- web_search + fetch_page the official site first (e.g. in Zambia: Department of National Registration, Passport and Citizenship for NRC/passport; ZRA for TPIN; RTSA for licences; HEELMB for student loans and bursaries; PACRA for business names). Otherwise the government portal of the user's country.
- Fees, opening hours and document lists change: quote them from the page you opened, with the page date if shown. If no official page is reachable, say the figure is unverified. Never invent a fee, form number or deadline.

## 3. Reply as a checklist (the user saves or screenshots it)
- **Bring**: each document, original + copies, photos (size/background), who must come along (parent, witness, commissioner of oaths).
- **Pay**: fee, how (cash, mobile money, bank), and normal vs express.
- **Where & when**: office or link, hours, best time to go (early), typical wait.
- **Steps**: numbered, in order, including collecting.
- **Deadline**: if there's one (bursary windows, renewals), offer to add a reminder or calendar event a week before (create_routine / add_calendar_event, with the user's yes).

## 4. Filling the form
- Go field by field: say what goes in each, in plain words, with the user's details from memory where they're known (ask before using anything sensitive). Flag fields people get wrong (names exactly as on the birth certificate/NRC, dates DD/MM/YYYY, signatures inside the box).
- For an online form, offer to fill it in Blaze's browser, stopping before submit for a confirm card. Never submit, pay or sign without the user's yes.
- Never store ID numbers in memory unless the user asks.

## 5. After
- Offer a Watcher or reminder to check the application status, and to save the checklist as a note.
