---
name: content-calendar
description: Plan and write a week of social posts for the user's store or brand (TikTok, Instagram, Facebook, WhatsApp Status), with visuals that follow the brand kit, and save each post as a Schedule item that reminds them to publish. Use for "content calendar", "posts for this week", "what should I post", "plan my TikToks".
tags: [marketing, social-media, content, schedule, brand]
version: 1
---

# Content calendar

## 1. Inputs (look before you ask)
- Brand kit: brand_kit get. If empty, ask for the store name, 2-3 colours, fonts and voice (or read them from the store's website with fetch_page) and save with brand_kit set.
- Products to push this week: the user's store page, ~/store/products.csv, or ask for 3-5.
- Platforms and how many posts a day (default: 1 a day on TikTok + Instagram, 7 posts).
- Anything happening: payday weekend, holidays, a sale, new stock.

## 2. Plan the week
Mix the post types so it isn't all selling:
- 3 product posts (problem → product → result; one per product),
- 1 behind-the-scenes or unboxing,
- 1 customer review / social proof (only real reviews; never invent one),
- 1 tip or how-to related to the niche,
- 1 trend or question post to start comments.
Each post: day, time (evening 18:00-21:00 local works best for most stores), platform, hook (first 2 seconds / first line), caption (≤ 150 words, brand voice), 3-8 hashtags (mix niche and broad), call to action, and the visual.

## 3. Visuals (brand kit, free tools)
- Short videos: a shot list the user can film on the phone (5 shots × 2 s) plus on-screen text.
- Images: generate_image with a prompt that names the product, setting and the brand colours; or a poster in HTML using the brand colours and fonts, opened in Design (open_view design) and exported as PNG with run_code.
- Save visuals and the plan in ~/store/content/<yyyy-mm-dd>/ (plan.md + images).

## 4. Save to Schedule
- After the user approves the plan (show it as a table first), add each post with create_routine: date (yyyy-MM-dd), time, days "once", name "Post: <platform> · <hook first words>", prompt "Remind the user to publish this post now, show the caption and hashtags ready to copy, and the visual's path: …".
- Say how many were scheduled and that they can edit or delete them in Schedule.

## Rules
- Never post or send anything by itself: Blaze prepares, the user publishes.
- No fake reviews, fake scarcity or medical/health claims; follow the platform's rules.
- Free tools only.
