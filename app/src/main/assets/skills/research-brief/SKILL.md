---
name: research-brief
description: A one-page competitor and pricing brief for a product, niche or store, built with the deep-research skill - who sells it, at what price, how they position it, gaps to use, and a recommended price. Use for "research my competitors", "what do others charge", "is this product worth selling", "pricing brief".
tags: [marketing, research, competitors, pricing, ecommerce]
version: 1
---

# Research brief (competitors and pricing)

## 1. Research (use_skill deep-research for the method)
- Scope: the product or niche, the user's market (country and currency; check memory), and their store link if any.
- Find 5-8 real competitors: web_search for the product name + "buy", marketplaces (Amazon, Jumia, Takealot, Etsy, local Facebook/Instagram shops), and TikTok Shop / Instagram sellers. Open each with fetch_page (browser for pages that need it).
- For each: name, link, price (and shipping), positioning (cheap / premium / gift / niche), main claims, review count and rating, offer (bundles, free shipping threshold), and how they advertise if visible.
- Record the date: prices change.

## 2. One page (~/store/research/<topic>-<yyyy-mm-dd>.md)
```
# <Product/niche> - competitor and pricing brief (<date>, <market>)
Bottom line: <2 sentences: is it worth it, at what price, with what angle>
| Competitor | Price (+ship) | Positioning | Rating (n) | Notes |
Price range: low … median … high (currency)
Gaps: 3 bullets (angles, bundles, audiences nobody serves)
Recommended price: X (why; margin at supplier cost Y)
Risks: saturation, shipping times, returns, ad costs
Next steps: 3 bullets (listing, ad-lab angles, content ideas)
```
- Sources: every competitor with its link.

## Rules
- Only real competitors and prices you actually saw; mark anything estimated as an estimate.
- One page: if it doesn't fit, cut detail, not the bottom line.
