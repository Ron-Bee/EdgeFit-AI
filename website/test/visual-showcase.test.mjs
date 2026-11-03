// Visual showcase regression test (EF-DOC-VISUAL-SHOWCASE-12).
// Guards the "How EdgeFit AI Works" landing section and the
// "How Evidence Becomes Public" dashboard explainer: section copy,
// resolvable SVG assets, accessible <img> attributes, and untouched
// live dashboard regions.
// Run from the repository root: node --test 'website/test/*.test.mjs'
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const SITE = join(ROOT, "website");
const read = (p) => readFileSync(join(SITE, p), "utf8");

function imgRefs(html) {
  const refs = [];
  for (const m of html.matchAll(/<img\s[^>]*src="([^"]+)"[^>]*>/g)) {
    refs.push({ tag: m[0], src: m[1] });
  }
  return refs;
}

describe("visual showcase assets", () => {
  it("ships the two walkthrough SVG masters", () => {
    for (const f of [
      "assets/visual-showcase/v1.1/edgefit-fig-02-system-architecture.svg",
      "assets/visual-showcase/v1.1/edgefit-fig-48-global-network.svg",
    ]) {
      const p = join(SITE, f);
      assert.ok(existsSync(p), `missing ${f}`);
      const svg = readFileSync(p, "utf8");
      assert.ok(
        svg.includes('viewBox="0 0 1600 1000"'),
        `${f} keeps its 16:10 master viewBox`,
      );
      assert.ok(!/<script/i.test(svg), `${f} contains no script`);
      const noNs = svg.replace(/\sxmlns(?::\w+)?="[^"]*"/g, "");
      assert.ok(
        !/https?:\/\//.test(noNs),
        `${f} is self-contained (no remote refs)`,
      );
    }
  });

  it("defines showcase figure styles", () => {
    const css = read("style.css");
    assert.ok(css.includes(".figure-showcase"), "missing .figure-showcase");
    assert.ok(
      css.includes(".figure-showcase img"),
      "missing .figure-showcase img",
    );
  });
});

describe("landing page showcase", () => {
  const html = read("index.html");

  it("adds the How EdgeFit AI Works section", () => {
    assert.ok(html.includes("<h2>How EdgeFit AI Works</h2>"));
    assert.ok(html.includes("Walkthrough v1.1, Fig 02"));
    assert.ok(
      html.includes("https://ronbeesoftware.com/edgefit/walkthrough/"),
    );
  });

  it("references a resolvable, accessible architecture diagram", () => {
    const shots = imgRefs(html).filter((r) =>
      r.src.includes("edgefit-fig-02-system-architecture.svg"),
    );
    assert.equal(shots.length, 1, "exactly one Fig 02 image");
    assert.ok(existsSync(join(SITE, shots[0].src)), "Fig 02 src resolves");
    assert.ok(shots[0].tag.includes('width="1600"'), "explicit width");
    assert.ok(shots[0].tag.includes('height="1000"'), "explicit height");
    assert.ok(shots[0].tag.includes('loading="lazy"'), "lazy loading");
    const alt = shots[0].tag.match(/alt="([^"]*)"/);
    assert.ok(alt && alt[1].length > 20, "useful alt text");
  });

  it("preserves the hero, demo, and download flows", () => {
    assert.ok(html.includes('<div class="hero">'), "hero intact");
    assert.ok(html.includes('href="download.html"'), "download link intact");
    assert.ok(html.includes('id="demo"'), "fit-check demo intact");
    assert.ok(html.includes('src="demo.js"'), "demo script intact");
  });
});

describe("dashboard explainer", () => {
  const html = read("dashboard.html");

  it("adds the How Evidence Becomes Public explainer", () => {
    assert.ok(html.includes("<h2>How Evidence Becomes Public</h2>"));
    assert.ok(html.includes("Accepted does not mean qualified"));
    assert.ok(html.includes("Qualified does not mean published"));
    assert.ok(html.includes("Walkthrough v1.1, Fig 50"));
  });

  it("references a resolvable, accessible pipeline diagram", () => {
    const shots = imgRefs(html).filter((r) =>
      r.src.includes("edgefit-fig-48-global-network.svg"),
    );
    assert.equal(shots.length, 1, "exactly one Fig 50 image");
    assert.ok(existsSync(join(SITE, shots[0].src)), "Fig 50 src resolves");
    const alt = shots[0].tag.match(/alt="([^"]*)"/);
    assert.ok(alt && alt[1].length > 20, "useful alt text");
  });

  it("leaves every live dashboard region untouched", () => {
    for (const id of [
      "dash-network",
      "dash-kpis",
      "dash-filters",
      "dash-charts",
      "dash-refresh",
      "dash-meta",
    ]) {
      assert.ok(html.includes(`id="${id}"`), `live region #${id} intact`);
    }
    assert.ok(
      html.includes('src="js/dashboard.js"'),
      "dashboard script intact",
    );
  });
});
