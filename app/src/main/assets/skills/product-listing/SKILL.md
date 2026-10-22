---
name: product-listing
description: Turn a supplier link (AliExpress, Zendrop, CJ Dropshipping, Alibaba, Temu, etc.) into a ready-to-paste store listing - title, description, bullet benefits, tags, SEO fields, suggested price - plus image prompts. Use for "make a listing", "product description for this", "add this product to my Shopify", a pasted supplier URL.
tags: [ecommerce, shopify, dropshipping, copywriting, listings]
version: 1
---

# Product listing

## 1. Read the supplier page
- fetch_page the URL. If it's blocked or empty (AliExpress often is), open it with browser_open and browser_read. Get: product name, specs (size, material, power, colours/variants), what's in the box, shipping time to the user's market, supplier price and variant prices, and the supplier's own photos.
- Note anything unclear or risky (no CE mark for electronics, vague sizes, long shipping) to tell the user.

## 2. Write the listing (brand voice from brand_kit get)
- Title: ≤ 70 characters, what it is + main benefit + key spec ("Moon Lamp 3D Night Light – 16 Colours, Remote, USB Rechargeable"). No ALL CAPS, no supplier brand names you can't use.
- Short description: 1-2 sentences for the top of the page.
- Benefits: 4-6 bullets, benefit first then the fact behind it.
- Details: specs table, what's in the box, care, shipping time (honest, from the supplier data).
- Tags: 8-15 lowercase tags (product type, use, room, gift occasions).
- SEO: meta title ≤ 60 chars, meta description ≤ 155 chars, URL handle.
- Variants: names and options as the store should show them.
- Price: suggest a price = supplier price + shipping, × 2.5-3, ending in .99, and show the margin. Compare 2-3 competitor prices if the user wants (research-brief skill).

## 3. Image prompts
- 4-6 prompts for generate_image (free) or a designer: hero on a clean background in the brand colours, lifestyle in use, scale/size shot, detail close-up, gift/packaging. Each names the product exactly so it isn't misrepresented.
- Remind the user to use the supplier's real photos for anything that shows what the buyer actually gets.

## 4. Deliver
- Show it ready to copy, and save it to ~/store/listings/<handle>.md (and append a row to ~/store/products.csv: handle, title, price, supplier URL).
- Offer an ad-lab round for the product, or posts for it in the content calendar.

## Rules
- Never claim certifications, materials or results the supplier page doesn't show.
- Blaze doesn't publish to the store by itself; the user pastes it (or asks Blaze to fill the store's admin page in the browser, with their OK before Save).
