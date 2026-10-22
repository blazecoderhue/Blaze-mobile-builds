/*
 * Blaze v0.23.5: three.js with a safety net (served as /three/three.safe.js and /three/r162/three.safe.js).
 * The Design view's importmap points "three" here; this re-exports the bundled build next to it and:
 *  - lets a Mesh / Line / Points be built with a null or missing geometry or material (three.js throws
 *    "Cannot read properties of null (reading 'morphAttributes')" in the constructor): it gets an empty
 *    geometry / grey material and is hidden, so one bad object doesn't stop the whole scene;
 *  - sanitizes the scene before every render (objects whose geometry or material was nulled or disposed
 *    later are hidden the same way), and Box3.setFromObject skips them;
 *  - reports what it fixed once (console.warn "Blaze fixed …") and a render error only once, not every frame.
 */
import * as T from "./three.module.min.js";
export * from "./three.module.min.js";

const EMPTY = new T.BufferGeometry();
let fallbackMat = null;
const mat = () => fallbackMat || (fallbackMat = new T.MeshStandardMaterial({ color: 0xb8b8b8 }));
const fixed = [];
function note(o, what) {
  if (fixed.length < 50) fixed.push((o && (o.name || o.type)) + ": " + what);
  if (fixed.length === 1) setTimeout(() => console.warn("Blaze fixed " + fixed.length + " object(s) in this scene: " + fixed.slice(0, 5).join("; ")), 0);
}
function badGeometry(g) { return !g || typeof g !== "object" || !g.isBufferGeometry || !g.attributes; }
function badMaterial(m) { return !m || (Array.isArray(m) ? m.length === 0 || m.some(x => !x || !x.isMaterial) : !m.isMaterial); }
function renderable(o) { return !!(o && (o.isMesh || o.isLine || o.isPoints)); }

/** Makes one object safe to build, measure and render. Returns true when something was replaced. */
export function fixObject(o) {
  if (!renderable(o)) return false;
  let changed = false;
  if (badGeometry(o.geometry)) { o.geometry = EMPTY; o.visible = false; note(o, "no geometry"); changed = true; }
  if (badMaterial(o.material)) {
    o.material = Array.isArray(o.material) && o.material.some(x => x && x.isMaterial) ? o.material.map(x => (x && x.isMaterial ? x : mat())) : mat();
    note(o, "no material"); changed = true;
  }
  return changed;
}

/** Walks [root] (without trusting children arrays) and fixes every object. Returns how many were fixed. */
export function sanitizeScene(root) {
  let n = 0;
  const stack = [root], seen = new Set();
  while (stack.length) {
    const o = stack.pop();
    if (!o || typeof o !== "object" || seen.has(o)) continue;
    seen.add(o);
    if (fixObject(o)) n++;
    const kids = o.children;
    if (Array.isArray(kids)) {
      for (let i = kids.length - 1; i >= 0; i--) {
        const k = kids[i];
        if (!k || !k.isObject3D) { kids.splice(i, 1); n++; continue; }
        stack.push(k);
      }
    }
  }
  return n;
}
export const blazeFixed = fixed;

// Construction: three.js calls updateMorphTargets() in the constructor, right after setting geometry.
for (const C of [T.Mesh, T.Line, T.Points]) {
  const proto = C && C.prototype;
  if (!proto || typeof proto.updateMorphTargets !== "function" || proto.__bzSafe) continue;
  const orig = proto.updateMorphTargets;
  proto.updateMorphTargets = function () { fixObject(this); return orig.apply(this, arguments); };
  proto.__bzSafe = true;
}

// Measuring: Box3.setFromObject / expandByObject read object.geometry.boundingBox.
if (T.Box3 && !T.Box3.prototype.__bzSafe) {
  const origExpand = T.Box3.prototype.expandByObject;
  T.Box3.prototype.expandByObject = function (o, precise) { fixObject(o); return origExpand.call(this, o, precise); };
  T.Box3.prototype.__bzSafe = true;
}

// Rendering: sanitize before each frame; a render that still throws is reported once, not 60 times a second.
/**
 * v0.36.1: what the Design view's "did it draw?" check reads. A WebGL canvas without preserveDrawingBuffer is cleared
 * once the frame is shown, so sampling it later from a timer always looked blank and every 3D design fell back to
 * the simple drawing. Now each frame records its draw calls, and the pixel sample is taken right after a render,
 * while the frame is still in the buffer.
 */
function noteFrame(r) {
  try {
    const w = window;
    w.__bzFrames = (w.__bzFrames || 0) + 1;
    const calls = (r.info && r.info.render && r.info.render.calls) || 0;
    if (calls > (w.__bzDrawCalls || 0)) w.__bzDrawCalls = calls;
    if (w.__bzWantSample && typeof w.__bzBlank === "function") { w.__bzWantSample = false; w.__bzSampleBlank = !!w.__bzBlank(r.domElement); }
  } catch (e) { /* never block the frame */ }
}

/** Wraps renderer.render (an own property in three.js) with the sanitizer and the once-only error report. */
export function wrapRender(renderer) {
  const render = renderer.render;
  if (typeof render !== "function" || render.__bzSafe) return renderer;
  let lastErr = "";
  const safe = function (scene, camera) {
    try { if (scene) sanitizeScene(scene); } catch (e) { /* never block the frame */ }
    // v0.45.6: what the Design view's never-blank rescue needs (any three.js page, not only the starter).
    try { const w = window; w.__bzR = this; if (scene && scene.isScene) w.__bzS = scene; if (camera && camera.isCamera) w.__bzC = camera; } catch (e) { }
    try { const out = render.call(this, scene, camera); noteFrame(this); return out; }
    catch (e) {
      const m = String((e && e.message) || e);
      if (m !== lastErr) { lastErr = m; throw e; }
    }
  };
  safe.__bzSafe = true;
  renderer.render = safe;
  return renderer;
}
class WebGLRenderer extends T.WebGLRenderer {
  constructor(...args) { super(...args); wrapRender(this); try { window.__bzR = window.__bzR || this; } catch (e) { } }
}
try { window.__bzTHREE = T; } catch (e) { }
export { WebGLRenderer };
