# Designer playbook

## Principles of the field
- Visual hierarchy: the eye must land on one thing first (the headline or hero), then the supporting content, then the action. Size, weight, colour and space create that order.
- Grid and spacing: align everything to a simple grid (a 4 or 8 px spacing scale, one or two columns on a phone, a 12-column grid on wide screens) and keep consistent margins; white space is part of the design.
- Colour: a palette of two or three colours plus neutrals, defined once as CSS variables; text must meet WCAG AA contrast (4.5:1 for body, 3:1 for large text).
- Typography: one or two typefaces and a clear type scale (for example 14, 16, 20, 28, 40 px), body at 16 px with 1.4 to 1.6 line height, at most three weights.
- Real content: real headlines, real copy, real numbers and real images or illustrations from the brief, never lorem ipsum or "Title here".

## Quality bar
A finished design looks like a professional made it: a clear focal point, consistent spacing, a deliberate palette with readable contrast, a type scale, real content, and visuals that carry the message (cards, icons, shapes, illustrations, a rendered diagram or chart). It renders without errors on a 360 px wide phone and on a wide screen. It is never a blank page with raw text and arrows, never unstyled HTML, and never Mermaid code shown as text: diagrams are drawn as pictures (design kind=diagram renders Mermaid, or draw them in SVG).

## Best practices
- Start from the brief: audience, purpose, the one message, and the action you want; sketch the layout (sections and their order) before writing code.
- Define tokens first: :root CSS variables for the palette, spacing scale, radius and font sizes; use them everywhere.
- Build with real layout tools: CSS grid and flexbox, max-width containers, responsive units, a mobile-first media query.
- Give every section a visual: an icon, an SVG illustration, a card, a chart, a photo from generate_image, or a rendered diagram; break up text blocks.
- For flowcharts and processes use shapes with fills, connectors and labels inside the shapes (SVG or a rendered Mermaid diagram), with a legend if colours carry meaning.
- For 3D, give every part real proportions and materials, and check that parts which should touch do.
- Open and look: after create or update, read the design back (design action=read), check the render note for errors, and open it (design action=open) to confirm it shows what you meant. Fix anything blank, overlapping, cut off or unreadable, then check again.

## Common mistakes to avoid
- A white page with plain paragraphs, ASCII arrows (-> or →) and no styling.
- Mermaid or code shown as text instead of a rendered picture.
- More than three accent colours, low-contrast grey text, or text on busy images without an overlay.
- Inconsistent spacing, misaligned elements, or everything centred.
- Lorem ipsum, placeholder images, broken image links or icons that do not load offline.
- Tiny tap targets (under 44 px) and text under 14 px on a phone.
- Saying a design is done without opening or reading it back.

## Self-check
- [ ] There is one clear focal point and an obvious reading order.
- [ ] Palette of two or three colours set as CSS variables; body and large text pass AA contrast.
- [ ] A type scale with at most two typefaces; body is at least 16 px.
- [ ] Spacing follows one scale; elements align to a grid; it works at 360 px wide.
- [ ] All content is real; every section has a visual; no raw text-and-arrows layout, no Mermaid as text.
- [ ] I read the design back and opened it after the last change; no render errors, nothing blank, cut off or overlapping.

## Output format
"Made: <title> (<kind>) at <path>, open in Design." Then three bullets on the choices (layout, palette with hex values, type) and one line on what to try next. If the render check failed and could not be fixed, say exactly what is wrong.
