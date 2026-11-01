// EdgeFit evidence dashboard renderer. Pure builders return HTML
// strings (unit-tested in node); initDashboard wires the live DOM.
// Cohorts are NEVER merged: every statistic is labeled with its
// exact cohort identity, sample size, and qualification state.
// No measurement literals, no endpoint URLs, no credentials here.
"use strict";
import { STATE, QUORUM_RUNS, QUORUM_INSTALLS, summarizeModels,
  countPublishedGroups, facetValues, applyFacets,
  EmptyProvider } from "./provider.js";

export function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;",
    "'": "&#39;",
  }[c]));
}

export function shortHash(sha) {
  return typeof sha === "string" ? sha.slice(0, 12) : "unknown";
}

export function formatTps(v) {
  if (typeof v !== "number" || !Number.isFinite(v)) return "n/a";
  const r = Math.round(v * 10) / 10;
  return `${r} tok/s`;
}

export function formatInt(v) {
  if (!Number.isInteger(v)) return "n/a";
  return String(v);
}

const STATE_COPY = {
  [STATE.NO_SOURCE]: {
    title: "No public evidence source",
    body: "No qualified evidence endpoint is configured, so this " +
      "dashboard shows structure only. It will populate automatically " +
      "once a genuine public source is authorized — nothing here is " +
      "estimated or filled in.",
  },
  [STATE.BELOW_QUORUM]: {
    title: "Insufficient evidence",
    body: "The source reports cohorts below the publication quorum " +
      "(5 runs from 3 installations). Counts may be shown; statistics " +
      "are withheld by the source, never zeros.",
  },
  [STATE.FILTERED_EMPTY]: {
    title: "No matching cohorts",
    body: "Qualified evidence exists, but no exact cohort matches the " +
      "current filters. Loosen a filter to see what is published.",
  },
  [STATE.SOURCE_ERROR]: {
    title: "Source temporarily unavailable",
    body: "The authorized public source could not be reached or " +
      "returned an incompatible response. Previously shown data is " +
      "not repeated as current.",
  },
};

export function stateHtml(state) {
  const c = STATE_COPY[state] || STATE_COPY[STATE.NO_SOURCE];
  return `<div class="card dash-state" role="status">` +
    `<h3>${escapeHtml(c.title)}</h3><p>${escapeHtml(c.body)}</p></div>`;
}

// KPI cards over validated models/candidates data, or null when the
// inputs cannot honestly support them.
export function kpiHtml(summary, qualifyingCohorts) {
  if (summary === null) return stateHtml(STATE.NO_SOURCE);
  const cards = [
    ["Published qualified runs", formatInt(summary.publishedRuns),
      "Sum of published_runs across listed models."],
    ["Models with published evidence",
      formatInt(summary.modelsWithEvidence),
      "Listed models holding at least one published run."],
    ["Qualifying exact cohorts",
      qualifyingCohorts === null ? "unavailable"
        : formatInt(qualifyingCohorts),
      "Exact cohorts currently passing quorum."],
    ["Latest evidence collected",
      summary.latestCollectedAt === null ? "unavailable"
        : escapeHtml(summary.latestCollectedAt),
      "Newest collection timestamp in the listing."],
  ];
  return `<div class="grid-2">` + cards.map(([k, v, note]) =>
    `<div class="card kpi"><div class="k">${escapeHtml(k)}</div>` +
    `<div class="v">${v}</div><div class="note">${note}</div></div>`
  ).join("") + `</div>` +
    `<p class="caption">Installation counts are per model only: the ` +
    `public API cannot honestly deduplicate contributors across models.</p>`;
}

function cohortLabel(g) {
  const c = g.cohort;
  return `${shortHash(c.model_sha)} · ${c.device_class || "?"} · ` +
    `${c.workload_id || "?"}`;
}

// Median-throughput bars (published cohorts only) with min/max
// whiskers. Below-quorum groups are counted in prose, never plotted.
export function throughputChart(groups) {
  const pub = groups.filter((g) => g.status === "published" &&
    g.aggregates !== null &&
    typeof g.aggregates.median_gen_tps === "number");
  const thin = groups.length - pub.length;
  if (pub.length === 0) return stateHtml(STATE.BELOW_QUORUM);
  const maxV = Math.max(...pub.map((g) => g.aggregates.max_gen_tps ??
    g.aggregates.median_gen_tps));
  const W = 620, H = 56 + pub.length * 64, top = 12;
  const scale = (v) => Math.round((v / maxV) * (W - 260));
  let svg = `<svg viewBox="0 0 ${W} ${H}" role="img" ` +
    `aria-label="Median generation throughput for ${pub.length} ` +
    `published cohorts, tokens per second">`;
  pub.forEach((g, i) => {
    const y = top + i * 64;
    const med = g.aggregates.median_gen_tps;
    const lo = g.aggregates.min_gen_tps ?? med;
    const hi = g.aggregates.max_gen_tps ?? med;
    const x0 = 200 + scale(lo), x1 = 200 + scale(hi);
    svg += `<text x="0" y="${y + 22}" class="chart-label">` +
      `${escapeHtml(cohortLabel(g))}</text>`;
    svg += `<line x1="${x0}" y1="${y + 18}" x2="${x1}" y2="${y + 18}" ` +
      `class="whisker"/>`;
    svg += `<rect x="200" y="${y}" width="${scale(med)}" height="22" ` +
      `class="bar"/>`;
    svg += `<text x="${204 + scale(med)}" y="${y + 17}" ` +
      `class="chart-value">${escapeHtml(formatTps(med))} ` +
      `(n=${g.n}, i=${g.installs})</text>`;
  });
  svg += "</svg>";
  const rows = pub.map((g) =>
    `<tr><td>${escapeHtml(cohortLabel(g))}</td>` +
    `<td>${escapeHtml(formatTps(g.aggregates.median_gen_tps))}</td>` +
    `<td>${g.n}</td><td>${g.installs}</td></tr>`).join("");
  return `<div class="card chart"><h3>Median generation throughput ` +
    `<span class="demo-tag">tok/s</span></h3>${svg}` +
    `<details class="chart-data"><summary>Chart data table</summary>` +
    `<table><thead><tr><th>Cohort</th><th>Median</th><th>Runs</th>` +
    `<th>Installs</th></tr></thead><tbody>${rows}</tbody></table>` +
    `</details><p class="caption">One bar per published exact ` +
    `cohort; whiskers span min–max. ${thin} below-quorum ` +
    `cohort${thin === 1 ? " is" : "s are"} counted, not plotted.</p></div>`;
}

// Sample-size bars with quorum thresholds. Counts are public even
// for thin cohorts, so every group appears here.
export function coverageChart(groups) {
  if (groups.length === 0) return stateHtml(STATE.BELOW_QUORUM);
  const maxN = Math.max(...groups.map((g) => g.n), QUORUM_RUNS);
  const W = 620, H = 56 + groups.length * 52, top = 12;
  const scale = (v) => Math.round((v / maxN) * (W - 260));
  let svg = `<svg viewBox="0 0 ${W} ${H}" role="img" ` +
    `aria-label="Sample sizes for ${groups.length} cohorts with ` +
    `quorum thresholds">`;
  groups.forEach((g, i) => {
    const y = top + i * 52;
    const ok = g.status === "published";
    svg += `<text x="0" y="${y + 20}" class="chart-label">` +
      `${escapeHtml(cohortLabel(g))}</text>`;
    svg += `<rect x="200" y="${y}" width="${scale(g.n)}" height="18" ` +
      `class="${ok ? "bar" : "bar thin"}"/>`;
    svg += `<text x="${204 + scale(g.n)}" y="${y + 15}" ` +
      `class="chart-value">n=${g.n} i=${g.installs}` +
      `${ok ? "" : " (thin)"}</text>`;
  });
  const qx = 200 + scale(QUORUM_RUNS);
  svg += `<line x1="${qx}" y1="0" x2="${qx}" y2="${H}" class="quorum"/>`;
  svg += `<text x="${qx + 4}" y="10" class="chart-value">quorum ` +
    `n=${QUORUM_RUNS}</text></svg>`;
  return `<div class="card chart"><h3>Evidence coverage by cohort</h3>` +
    `${svg}<p class="caption">Runs (n) and installations (i) per ` +
    `exact cohort. The line marks the ${QUORUM_RUNS}-run quorum ` +
    `(plus ${QUORUM_INSTALLS} installations, checked per cohort).</p></div>`;
}

export function filterHtml(models, facets, selected) {
  const modelOpts = models.map((m) =>
    `<option value="${escapeHtml(m.model_sha)}"` +
    `${selected.model_sha === m.model_sha ? " selected" : ""}>` +
    `${shortHash(m.model_sha)} (${m.published_runs} runs)</option>`
  ).join("");
  const facetSelect = (key, label) => {
    const opts = (facets[key] || []).map((v) =>
      `<option${selected[key] === v ? " selected" : ""}>` +
      `${escapeHtml(v)}</option>`).join("");
    return `<label>${escapeHtml(label)}<select data-facet="${key}">` +
      `<option value="">All</option>${opts}</select></label>`;
  };
  return `<form class="card filters" id="dashfilters">` +
    `<label>Model<select data-facet="model_sha">${modelOpts}</select>` +
    `</label>` +
    facetSelect("device_class", "Device class") +
    facetSelect("workload_id", "Workload") +
    facetSelect("quantization_label", "Quantization") +
    `<p class="caption">Filters subset exact cohorts; they never ` +
    `merge statistics across cohorts.</p></form>`;
}

// Browser wiring. Ships against EmptyProvider: renders structure +
// NO_SOURCE states, offers manual refresh (re-checks + timestamps).
export function initDashboard(root, provider) {
  const prov = provider || new EmptyProvider();
  const $ = (id) => root.querySelector(`#${id}`);
  const state = { facets: {}, checkedAt: null };
  async function load() {
    const kpi = $("dash-kpis"), charts = $("dash-charts"),
      meta = $("dash-meta");
    try {
      const [models, candidates] = await Promise.all([
        prov.listModels(), prov.getCandidates(),
      ]);
      const summary = summarizeModels(models.artifacts);
      const qual = candidates.candidates
        .reduce((s, c) => s + (c.qualifying_cohorts || 0), 0);
      kpi.innerHTML = kpiHtml(summary, qual);
      const first = models.artifacts[0];
      if (!first) {
        charts.innerHTML = stateHtml(STATE.BELOW_QUORUM);
      } else {
        const agg = await prov.getAggregates(
          state.facets.model_sha || first.model_sha, {});
        const groups = applyFacets(agg.groups, state.facets);
        const shown = groups.length === 0 && agg.groups.length > 0
          ? stateHtml(STATE.FILTERED_EMPTY)
          : throughputChart(groups) + coverageChart(groups);
        charts.innerHTML = shown;
      }
      state.checkedAt = new Date().toISOString();
      meta.textContent = `Source: ${prov.kind}; checked ${state.checkedAt}.`;
    } catch (e) {
      const st = (e && e.state) || STATE.SOURCE_ERROR;
      kpi.innerHTML = stateHtml(st);
      charts.innerHTML = stateHtml(st);
      state.checkedAt = new Date().toISOString();
      meta.textContent = `Source: ${prov.kind}; checked ${state.checkedAt}; ` +
        `${e && e.message ? e.message : "unavailable"}.`;
    }
  }
  const btn = $("dash-refresh");
  if (btn) btn.addEventListener("click", load);
  return { load, state };
}

if (typeof document !== "undefined" && document.getElementById) {
  document.addEventListener("DOMContentLoaded", () => {
    const root = document.getElementById("dashboard");
    if (root) initDashboard(root).load();
  });
}
