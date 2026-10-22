---
name: diagrams
description: Diagram FILES - flowcharts, mind maps, org charts, sequence and architecture diagrams, timelines, ER and state diagrams - as Mermaid or hand-placed SVG saved to a file, previewed and exported as PNG, SVG or PDF. Use only when the user asks to save, export, download, edit or make a diagram file. A diagram asked for in chat needs no skill - reply with a ```mermaid block.
tags: [diagrams, flowchart, mermaid, svg, visual, design]
version: 2
---

> **In chat: just reply with a ```mermaid block.** When someone asks in chat for a diagram, flowchart, mind map or
> "how X works" picture, don't use this skill, don't write a file and don't open a preview: put a ```mermaid block in the
> reply with 2-3 sentences around it, and the chat draws it inline in seconds ("how do solar panels work" →
> `flowchart LR` Sunlight → Solar cells → DC power → Inverter → AC power → Home). Numbers go in a ```chart block.
> Everything below is only for when they ask to **save, export, download, edit or make a file** (a PNG for a report,
> an SVG for slides, a README diagram).

> **On the phone:** files go under `~` (/root/phone). Preview HTML/SVG with `preview_server` (there is no Design tab here); install missing tools in Blaze's Alpine Linux with run_shell (`apk add …`, `pip install …`).

# Diagrams

Finish line (file route only): a diagram a stranger understands in ten seconds, saved and exported as the
user needs (PNG for sharing, SVG for docs, PDF for print).

## 1. Pick the kind and the engine

| They want | Kind | Engine |
| --- | --- | --- |
| steps, decisions, a process | flowchart (top-down; left-right when wide and shallow) | Mermaid `flowchart` |
| ideas around one topic | mind map | Mermaid `mindmap`, or SVG for a designed one |
| who reports to whom | org chart | Mermaid `flowchart TB` |
| messages between systems over time | sequence | Mermaid `sequenceDiagram` |
| services, data stores, flows | architecture | SVG by hand (boxes in lanes) or Mermaid `flowchart` with subgraphs |
| events on dates | timeline | Mermaid `timeline` or SVG |
| tables and keys | ER | Mermaid `erDiagram` |
| statuses and transitions | state | Mermaid `stateDiagram-v2` |
| a customer's steps and feelings | journey | Mermaid `journey` or SVG |

Mermaid when structure matters more than looks; hand-placed SVG when it will be presented or
branded. In a Markdown doc or a GitHub README, write a ```mermaid block straight into the file.

## 2. Make it readable

1. Write the content first: the nodes and edges as a list. Merge anything that says the same thing;
   more than ~15 nodes means split it into an overview plus detail diagrams.
2. Labels: 1-4 words on nodes, verbs on edges ("sends invoice", "if paid"). Decisions are questions.
3. One direction of flow. No crossing edges if a reorder avoids them. Group related nodes in subgraphs or lanes.
4. Colour carries meaning only (one accent for the main path or the thing that changed, grey for
   external systems); add a small legend when colour means something.

## 3. Preview and export

- The preview shows HTML, so wrap Mermaid in a page: `design/<name>-diagram.html` with
  `<pre class="mermaid">…</pre>` and
  `<script type="module">import m from "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs"; m.initialize({startOnLoad:true, theme:"neutral"});</script>`.
- Files: `npx -y @mermaid-js/mermaid-cli -i d.mmd -o d.svg` (or `-o d.png -w 2000`, `-o d.pdf`).
  No Node: keep the HTML page and export from it with a headless browser, or hand-draw the SVG.
- Hand-placed SVG: a 1200-wide viewBox, 8 px grid, rounded rects, 2 px strokes, arrowheads from one
  `<marker>`, each node a `<g id>` so Design can retype or recolour it.
- Render to PNG and look at it before handing it over.

## Mixing with other skills

- Hand-drawn whiteboard look: structure from here, paper and pens from `paper-sketch`.
- In a deck: export SVG and place it with `slide-deck`. In a printed one-pager: `print-design`.
- From real data (an org export, a schema dump): read it with `data-analysis` first, then generate.

## Checklist

- [ ] Right kind for the question; one flow direction; no avoidable crossings.
- [ ] Short labels; decisions phrased as questions; ~15 nodes or fewer per diagram.
- [ ] Rendered and checked; exported in the format the user needs.
