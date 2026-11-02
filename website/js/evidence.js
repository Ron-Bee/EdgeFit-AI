// Evidence status live network block. Reuses the dashboard's
// validated provider + networkHtml builder: same counts, same
// honesty rules, no second implementation. Manual refresh only.
"use strict";
import { STATE, EmptyProvider, HttpProvider } from "./provider.js";
import { EVIDENCE_BASE } from "./config.js";
import { networkHtml, stateHtml } from "./dashboard.js";

export function initEvidence(root, provider) {
  const prov = provider ||
    (EVIDENCE_BASE ? new HttpProvider(EVIDENCE_BASE) : new EmptyProvider());
  const $ = (id) => root.querySelector(`#${id}`);
  async function load() {
    const box = $("ev-network"), meta = $("ev-meta");
    try {
      const net = await prov.getNetworkStatus();
      if (box) {
        box.innerHTML = networkHtml(net, new Date().toISOString());
      }
      if (meta) meta.textContent = `Source: ${prov.kind}; live.`;
    } catch (e) {
      const st = (e && e.state) || STATE.SOURCE_ERROR;
      if (box) box.innerHTML = stateHtml(st);
      if (meta) {
        meta.textContent = `Source: ${prov.kind}; ` +
          `${e && e.message ? e.message : "unavailable"}.`;
      }
    }
  }
  const btn = $("ev-refresh");
  if (btn) btn.addEventListener("click", load);
  return { load };
}

if (typeof document !== "undefined" && document.getElementById) {
  document.addEventListener("DOMContentLoaded", () => {
    const root = document.getElementById("ev-network");
    if (root) {
      const scope = root.closest("main") || document;
      initEvidence(scope).load();
    }
  });
}
