// Submission video regression test (EF-SUBMISSION-VIDEO-WEB-GITHUB-01).
// Guards the official 2:55 demo integration on the landing page and the
// published pre-release APK links on the download page: correct video ID,
// click-to-load embed without autoplay, honest pre-release wording, and
// intact dashboard/navigation references.
// Run from the repository root: node --test 'website/test/*.test.mjs'
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SITE = join(resolve(dirname(fileURLToPath(import.meta.url)), ".."));
const read = (p) => readFileSync(join(SITE, p), "utf8");
const WATCH = "https://youtu.be/KyUwmhIL6SM";
const EMBED = "https://www.youtube.com/embed/KyUwmhIL6SM";
const RELEASE = "https://github.com/Ron-Bee/EdgeFit-AI/releases/tag/v0.2.1-preview.1";
const APK =
  "https://github.com/Ron-Bee/EdgeFit-AI/releases/download/v0.2.1-preview.1/EdgeFit-AI-Android-v0.2.1-preview.1-arm64.apk";
const SUMS =
  "https://github.com/Ron-Bee/EdgeFit-AI/releases/download/v0.2.1-preview.1/SHA256SUMS.txt";

describe("landing page demo video", () => {
  const html = read("index.html");

  it("links the official 2:55 demo in a new tab", () => {
    assert.ok(
      html.includes(`href="${WATCH}" target="_blank"`),
      "missing new-tab official demo link",
    );
  });

  it("marks the official demo published, not in production", () => {
    assert.ok(!html.includes("In production"), "stale in-production label");
    assert.ok(
      /Official 2:55 hackathon demo[\s\S]{0,200}\bpublished\b/.test(html),
      "official demo caption lacks published marker",
    );
  });

  it("adds the See EdgeFit AI in Action section", () => {
    assert.ok(
      html.includes("See EdgeFit AI in Action"),
      "missing video section heading",
    );
  });

  it("click-to-loads the embed without autoplay", () => {
    assert.ok(html.includes(EMBED), "missing embed URL");
    assert.ok(!html.includes("autoplay=1"), "embed must not autoplay");
  });

  it("keeps a direct link if embedding is blocked", () => {
    const direct = (html.match(new RegExp(`href="${WATCH}"`, "g")) || [])
      .length;
    assert.ok(direct >= 2, "need hero/caption link plus section fallback");
  });
});

describe("download page pre-release", () => {
  const html = read("download.html");

  it("links the published pre-release assets", () => {
    for (const u of [RELEASE, APK, SUMS]) {
      assert.ok(html.includes(`href="${u}"`), `missing ${u}`);
    }
  });

  it("drops the no-release-exists claim", () => {
    assert.ok(
      !html.includes("No verified public release exists yet"),
      "stale no-release claim",
    );
  });

  it("states pre-release and unverified install testing honestly", () => {
    assert.ok(html.includes("pre-release"), "missing pre-release label");
    assert.ok(
      /physical-install test (is )?not (yet )?verified/i.test(html),
      "missing physical-test disclaimer",
    );
  });
});

describe("video styles", () => {
  const css = read("style.css");

  it("defines responsive click-to-load video styles", () => {
    assert.ok(css.includes(".video-frame"), "missing .video-frame");
    assert.ok(css.includes(".video-thumb"), "missing .video-thumb");
    assert.ok(css.includes("16 / 9"), "missing 16:9 aspect ratio");
  });
});
