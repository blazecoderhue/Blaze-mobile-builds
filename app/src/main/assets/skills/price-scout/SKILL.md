---
name: price-scout
description: Price Scout. Find what something costs right now near the user (groceries like mealie meal, phones, building materials, transport fares, rent) by comparing live listings and shop pages, and flag the cheapest trustworthy option. Use for "how much is…", "cheapest place to buy…", "price of… in Kitwe", "is this a good price".
tags: [shopping, prices, compare, local]
version: 1
---

# Price Scout

## 1. Make the item exact
Brand, size or weight (a 25 kg vs 50 kg bag), new or used, and the town. Use the user's town from memory or get_location; ask one short question only if the size/model really changes the price.

## 2. Gather live prices (at least 3 sources when they exist)
- web_search with the town and currency ("50kg mealie meal price Kitwe ZMW", "Shoprite Zambia breakfast meal 25kg"), then fetch_page the shop or listing pages: supermarket online catalogues, Jumia/Facebook-Marketplace-style listings that are public, classifieds, news reports of current prices, official fare tables.
- Record for each: seller, price, size, location, date seen, link. Skip results older than about 3 months, unless nothing newer exists (then say so).
- Never invent a price. If only one source exists, say it's a single data point.

## 3. Compare fairly
- Normalise to the same unit (per kg, per litre, per GB) and note delivery or transport costs.
- Flag the cheapest trustworthy option, and anything that looks like a scam (price far below the rest, pay-before-viewing, new account).

## 4. Reply
One line with the cheapest and the typical range, then a small table: Seller · Price · Size · Unit price · Where · Date · Link. End with an offer (a watch on the price with create_trigger/watch, a reminder, or the route there).
