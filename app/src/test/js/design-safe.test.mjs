// v0.23.5: reproduces the Design view's "Cannot read properties of null (reading 'morphAttributes')" and checks
// that the three.js safety net (assets/design/three-safe.js) and the 3D starter (three-d-design mockup.html)
// survive bad objects. Run from the repo root: node app/src/test/js/design-safe.test.mjs (CI runs it).
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

const root = process.cwd();
const assets = path.join(root, "app/src/main/assets");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "bz3d-"));
fs.writeFileSync(path.join(tmp, "package.json"), '{"type":"module"}');
let failures = 0;
async function test(name, fn) {
  try { await fn(); console.log("ok   " + name); } catch (e) { failures++; console.log("FAIL " + name + "\n     " + (e && e.stack || e)); }
}

for (const [build, dir] of [["r170", "three"], ["r162", "three-r162"]]) {
  const d = path.join(tmp, build); fs.mkdirSync(d);
  fs.copyFileSync(path.join(assets, "design", dir, "three.module.min.js"), path.join(d, "three.module.min.js"));
  fs.copyFileSync(path.join(assets, "design/three-safe.js"), path.join(d, "three.safe.js"));
}

const RAW = await import(pathToFileURL(path.join(tmp, "r170/three.module.min.js")));

await test("raw three.js r170 throws the test phone error for a null-geometry mesh (the bug)", () => {
  assert.equal(RAW.REVISION, "170");
  assert.throws(() => new RAW.Mesh(null, new RAW.MeshBasicMaterial()), /Cannot read properties of null \(reading 'morphAttributes'\)/);
});

// The safety net patches the shared module's prototypes, so it's loaded only after the repro above.
const SAFE = await import(pathToFileURL(path.join(tmp, "r170/three.safe.js")));

for (const build of ["r170", "r162"]) {
  await test(`safety net (${build}): null geometry / material meshes, lines and points build and are hidden`, async () => {
    const T = await import(pathToFileURL(path.join(tmp, build, "three.safe.js")));
    const m = new T.Mesh(null, null), l = new T.Line(null), pt = new T.Points(undefined, null);
    for (const o of [m, l, pt]) { assert.ok(o.geometry.isBufferGeometry); assert.ok(o.material.isMaterial); }
    assert.equal(m.visible, false);
    assert.ok(T.WebGLRenderer.prototype instanceof RAW.WebGLRenderer || T.WebGLRenderer.name === "WebGLRenderer");
  });
}

await test("sanitizer fixes objects nulled after build and Box3 skips them", () => {
  const T = SAFE;
  const s = new T.Scene();
  const good = new T.Mesh(new T.BoxGeometry(1, 1, 1), new T.MeshBasicMaterial());
  const bad = new T.Mesh(new T.BoxGeometry(1, 1, 1), new T.MeshBasicMaterial());
  s.add(good, bad);
  bad.geometry = null; good.material = null;
  assert.ok(T.sanitizeScene(s) >= 2);
  assert.ok(bad.geometry.isBufferGeometry && bad.visible === false);
  assert.ok(good.material.isMaterial && good.visible === true);
  bad.geometry = undefined;
  assert.equal(new T.Box3().setFromObject(s).isEmpty(), false);
});

await test("render wrapper sanitizes before each frame and reports a repeating error once", () => {
  const T = SAFE;
  const s = new T.Scene(); const m = new T.Mesh(new T.BoxGeometry(), new T.MeshBasicMaterial()); s.add(m);
  let calls = 0;
  const r = { render(scene) { calls++; scene.traverse(o => { if (o.isMesh) o.geometry.attributes; }); throw new Error("boom"); } };
  T.wrapRender(r);
  m.geometry = null;
  assert.throws(() => r.render(s, null), /boom/);       // first time it surfaces (window.onerror sees it)
  assert.doesNotThrow(() => r.render(s, null));          // the same error again is swallowed
  assert.equal(calls, 2);
  assert.ok(m.geometry.isBufferGeometry);
});

// ---- the 3D starter, run headless with a fake renderer that touches what WebGLRenderer.projectObject touches ----
function runStarter(spec) {
  const html = fs.readFileSync(path.join(assets, "skills/three-d-design/assets/mockup.html"), "utf8");
  let code = html.match(/<script type="module">([\s\S]*?)<\/script>/)[1];
  code = code
    .replace('import * as THREE from "three";', 'import * as THREE0 from "./r170/three.safe.js";\nconst THREE = { ...THREE0, WebGLRenderer: globalThis.__FakeRenderer };')
    .replace(/import \{ OrbitControls \}[^\n]*\n/, "const OrbitControls = globalThis.__Stub;\n")
    .replace(/import \{ GLTFExporter \}[^\n]*\n/, "const GLTFExporter = globalThis.__Stub;\n");
  const els = {};
  const el = id => els[id] || (els[id] = { id, textContent: id === "blaze-parts" ? JSON.stringify(spec) : "", innerHTML: "", style: {} });
  globalThis.window = globalThis;
  globalThis.document = { title: "", getElementById: el, body: { prepend() {} }, createElement: () => ({ click() {} }) };
  globalThis.matchMedia = () => ({ matches: false });
  globalThis.devicePixelRatio = 2; globalThis.innerWidth = 360; globalThis.innerHeight = 640;
  globalThis.addEventListener = () => {};
  globalThis.__Stub = class { constructor(cam) { this.cam = cam; this.target = new SAFE.Vector3(); } update() { if (this.cam && this.cam.lookAt) this.cam.lookAt(this.target); } parse() {} };
  const frames = [];
  globalThis.__FakeRenderer = class {
    constructor() {
      this.domElement = {}; this.shadowMap = {}; this.drawn = 0;
      this.render = function (scene, camera) {
        scene.updateMatrixWorld(); camera.updateMatrixWorld();
        const f = new SAFE.Frustum().setFromProjectionMatrix(new SAFE.Matrix4().multiplyMatrices(camera.projectionMatrix, camera.matrixWorldInverse));
        let n = 0;
        scene.traverseVisible(o => {
          if (!(o.isMesh || o.isLine || o.isPoints)) return;
          if (o.frustumCulled && !f.intersectsObject(o)) return;
          if (!o.geometry.attributes.position) throw new Error("no position attribute on " + o.name);
          if (!o.material.visible) return;
          n++;
        });
        this.drawn = n;
      };
      SAFE.wrapRender(this);
    }
    setPixelRatio() {} setSize() {} setAnimationLoop(cb) { frames.push(cb); }
  };
  delete globalThis.__blazeScene;
  const file = path.join(tmp, "starter-" + Math.random().toString(36).slice(2) + ".js");
  fs.writeFileSync(file, code);
  return import(pathToFileURL(file)).then(() => ({ s: globalThis.__blazeScene, frames, els }));
}

const BAD_ROCKET = {
  name: "Simple rocket", units: "mm", scale: 0.001, nudge: 10,
  parts: [
    { id: "body", label: "Body", shape: "Cylinder", radius: 60, height: 600, position: [0, 300, 0], color: "#eeeeee" },
    { id: "nose", label: "Nose", shape: "nosecone", radius: 60, height: 180, position: [0, 690, 0], color: "#d33" },
    { id: "nozzle", label: "Nozzle", shape: "nozzle", radius: 40, height: 80, position: [0, -40, 0] },
    { id: "fin-1", label: "Fin", shape: "triangle", size: 50, position: [70, 60, 0] },
    { id: "window", label: "Window", shape: "CircleGeometry", radius: "abc", position: [0, 450, 60] },
    null, "not a part",
    { id: "loop", label: "Loop", shape: "box", parent: "loop", position: ["x", 1, 2] },
    { id: "wing", label: "Wing", shape: "custom" },
  ],
};

await test("starter builds and renders a rocket full of bad parts (null-geometry repro)", async () => {
  const { s, frames } = await runStarter(BAD_ROCKET);
  assert.ok(s, "window.__blazeScene was set");
  assert.equal(s.parts.size, 7);
  let meshes = 0;
  s.scene.traverse(o => { if (o.isMesh) { meshes++; assert.ok(o.geometry && o.geometry.attributes, "mesh " + o.name + " has geometry"); assert.ok(o.material && o.material.isMaterial); } });
  assert.ok(meshes >= 6);
  assert.equal(frames.length, 1);
  frames[0]();
  assert.ok(s.renderer.drawn >= 5, "drew " + s.renderer.drawn + " objects");
  // A model-written edit that nulls a geometry after build: the next frame still renders.
  s.parts.get("body").children[0].geometry = null;
  assert.doesNotThrow(() => frames[0]());
  assert.ok(s.renderer.drawn >= 4);
  const c = s.camera.position; assert.ok([c.x, c.y, c.z].every(Number.isFinite));
});

await test("starter survives a spec with no parts array", async () => {
  const { s, frames } = await runStarter({ name: "Empty" });
  assert.ok(s); assert.equal(s.parts.size, 0); frames[0]();
});

console.log(failures ? `${failures} test(s) failed` : "all design JS tests passed");
process.exit(failures ? 1 : 0);
