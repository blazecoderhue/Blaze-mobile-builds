---
name: weekly-digest
description: The weekly store digest - last week's orders and revenue (from the store's order emails), the top social posts, and the next 3 posts to make. Runs every Monday as a Schedule job (Settings › Seller › Weekly store digest) or on request ("how did the store do this week").
tags: [ecommerce, analytics, reporting, marketing, schedule]
version: 1
---

# Weekly store digest

## 1. Orders (last 7 days)
- gmail_search for the store's order notifications: `newer_than:7d (subject:order OR subject:"new order" OR from:shopify)` (adjust to the store platform: Shopify, WooCommerce, Selar, Paystack, Flutterwave). gmail_read each one to get order number, items, total and currency.
- Also check ~/store/orders.csv if the user keeps one.
- Sum: number of orders, revenue, average order value, best-selling product, compared with the week before when last week's digest is in ~/store/digests/.
- No access to orders (Google not connected)? Say so in one line and offer to connect Google; don't guess numbers.

## 2. Top posts
- If the user is signed in to TikTok / Instagram / Facebook in Blaze's browser, read the analytics pages (browser_open, browser_read): views, likes, comments, shares, link clicks for last week's posts. Otherwise use what the user tells you, or skip this part with one line.
- Name the top 2 posts and why they worked (hook, product, time).

## 3. Next 3 posts
- Based on what sold and what got attention, suggest 3 posts (hook + format + product), in the brand voice (brand_kit get). Offer to schedule them with the content-calendar skill.

## 4. Output (short, for a notification)
```
Store week <dates>: <n> orders · <revenue> (<±% vs last week>) · best seller <product>
Top post: <hook> (<views>)
Next: 1) … 2) … 3) …
```
- Save the full digest to ~/store/digests/<yyyy-mm-dd>.md.

## Rules
- Only numbers you actually read; mark gaps plainly. Read-only: the digest never posts, emails or changes the store.
