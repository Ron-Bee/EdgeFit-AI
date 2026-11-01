// EdgeFit evidence dashboard renderer. Pure builders return HTML
// strings (unit-tested in node); initDashboard wires the live DOM.
// Cohorts are NEVER merged: every statistic is labeled with its
// exact cohort identity, sample size, and qualification state.
// No measurement literals, no endpoint URLs, no credentials here.
"use strict";
import { STATE, QUORUM_RUNS, QUORUM_INSTALLS, summarizeModels,
  countPublishedGroups, facetValues, applyFacets,
  EmptyProvider, HttpProvider } from "./provider.js";
import { EVIDENCE_BASE } from "./config.js";

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
    body: "The source reports cohorts below the publication quorum. " +
      "Counts may be shown; statistics are withheld by the source, " +
      "never zeros.",
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
// inputs cannot honestly support them. opts.modelsTruncated warns
// that the server capped the listing, so totals are lower bounds.
export function kpiHtml(summary, qualifyingCohorts, opts) {
  if (summary === null) return stateHtml(STATE.NO_SOURCE);
  const truncWarn = opts && opts.modelsTruncated
    ? `<p class="caption warn">The model listing was truncated by ` +
      `the source: totals below are lower bounds, not exact counts.</p>`
    : "";
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
  return truncWarn + `<div class="grid-2">` + cards.map(([k, v, note]) =>
    `<div class="card kpi"><div class="k">${escapeHtml(k)}</div>` +
    `<div class="v">${v}</div><div class="note">${note}</div></div>`
  ).join("") + `</div>` +
    `<p class="caption">Installation counts are per model only: the ` +
    `public API cannot honestly deduplicate contributors across models.</p>`;
}

// Short on-chart label (layout-bounded, truncated with ellipsis).
// Full exact identity lives in cohortTitle tooltips and the data
// table's cohort-hash column: cohorts that differ only in hidden
// facets must still be distinguishable.
function cohortLabel(g) {
  const c = g.cohort;
  const full = `${shortHash(c.model_sha)} · ${c.device_class || "?"} · ` +
    `${c.workload_id || "?"} · ${c.quantization || "?"}`;
  return full.length > 34 ? `${full.slice(0, 33)}…` : full;
}

// Compact config identity for tables: ctx/threads/predict/sampler.
// Missing pieces render as "?", never as zeros or guesses.
export function formatConfig(cfg) {
  if (cfg === null || cfg === undefined) return "n/a";
  const part = (v) => Number.isInteger(v) ? String(v) : "?";
  const samp = typeof cfg.sampler === "string" ? cfg.sampler : "?";
  return `${part(cfg.n_ctx)}/${part(cfg.n_threads)}/` +
    `${part(cfg.n_predict)}/${samp}`;
}

export function cohortTitle(g) {
  const c = g.cohort;
  const cfg = c.config;
  const config = cfg === null || cfg === undefined
    ? "?"
    : `ctx=${Number.isInteger(cfg.n_ctx) ? cfg.n_ctx : "?"} ` +
      `threads=${Number.isInteger(cfg.n_threads) ? cfg.n_threads : "?"} ` +
      `pred=${Number.isInteger(cfg.n_predict) ? cfg.n_predict : "?"} ` +
      `sampler=${typeof cfg.sampler === "string" ? cfg.sampler : "?"}`;
  return ["model", "device", "workload", "quant", "prompt", "runtime",
    "backend", "build", "config"]
    .map((k, i) => `${k}=${[
      c.model_sha, c.device_class, c.workload_id,
      c.quantization, c.prompt_sha, c.runtime_impl,
      c.runtime_backend, c.build, config][i] || "?"}`)
    .join(" ");
}

// Median-throughput bars (published cohorts only) with min/max
// whiskers. Below-quorum groups are counted in prose, never plotted.
// opts.truncated warns the server capped the listing.
export function throughputChart(groups, opts) {
  const pub = groups.filter((g) => g.status === "published" &&
    g.aggregates !== null &&
    typeof g.aggregates.median_gen_tps === "number");
  const thin = groups.length - pub.length;
  if (pub.length === 0) return stateHtml(STATE.BELOW_QUORUM);
  // Degenerate all-zero medians must not produce NaN widths: the
  // printed values stay exact while every bar collapses to zero.
  const maxV = Math.max(...pub.map((g) => g.aggregates.max_gen_tps ??
    g.aggregates.median_gen_tps)) || 1;
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
    svg += `<g><title>${escapeHtml(cohortTitle(g))}</title>` +
      `<text x="0" y="${y + 22}" class="chart-label">` +
      `${escapeHtml(cohortLabel(g))}</text>` +
      `<line x1="${x0}" y1="${y + 18}" x2="${x1}" y2="${y + 18}" ` +
      `class="whisker"/>` +
      `<rect x="200" y="${y}" width="${scale(med)}" height="22" ` +
      `class="bar"/>` +
      `<text x="${204 + scale(med)}" y="${y + 17}" ` +
      `class="chart-value">${escapeHtml(formatTps(med))} ` +
      `(n=${g.n}, i=${g.installs})</text></g>`;
  });
  svg += "</svg>";
  const rows = pub.map((g) =>
    `<tr><td>${escapeHtml(cohortLabel(g))}</td>` +
    `<td>${g.cohort_hash === null ? "n/a"
      : escapeHtml(g.cohort_hash)}</td>` +
    `<td>${escapeHtml(formatConfig(g.cohort.config))}</td>` +
    `<td>${escapeHtml(formatTps(g.aggregates.median_gen_tps))}</td>` +
    `<td>${g.n}</td><td>${g.installs}</td></tr>`).join("");
  const truncNote = opts && opts.truncated
    ? ` Group listing truncated: figures are lower bounds.`
    : "";
  return `<div class="card chart"><h3>Median generation throughput ` +
    `<span class="demo-tag">tok/s</span></h3>${svg}` +
    `<details class="chart-data"><summary>Chart data table</summary>` +
    `<table><thead><tr><th>Cohort</th><th>Cohort hash</th>` +
    `<th>Config</th><th>Median</th><th>Runs</th>` +
    `<th>Installs</th></tr></thead><tbody>${rows}</tbody></table>` +
    `</details><p class="caption">One bar per published exact ` +
    `cohort; whiskers span min–max. ${thin} below-quorum ` +
    `cohort${thin === 1 ? " is" : "s are"} counted, not plotted.` +
    `${truncNote}</p></div>`;
}

// Sample-size bars with quorum thresholds. Counts are public even
// for thin cohorts, so every group appears here. opts.quorum carries
// the server-advertised {minRuns, minInstalls}; the v1 constants are
// only the fallback. opts.truncated warns of a capped listing.
export function coverageChart(groups, opts) {
  if (groups.length === 0) return stateHtml(STATE.BELOW_QUORUM);
  const q = (opts && opts.quorum) || {};
  const qRuns = Number.isInteger(q.minRuns) && q.minRuns > 0
    ? q.minRuns : QUORUM_RUNS;
  const qInstalls = Number.isInteger(q.minInstalls) && q.minInstalls > 0
    ? q.minInstalls : QUORUM_INSTALLS;
  const maxN = Math.max(...groups.map((g) => g.n), qRuns);
  const W = 620, H = 56 + groups.length * 52, top = 12;
  const scale = (v) => Math.round((v / maxN) * (W - 260));
  let svg = `<svg viewBox="0 0 ${W} ${H}" role="img" ` +
    `aria-label="Sample sizes for ${groups.length} cohorts with ` +
    `quorum thresholds">`;
  groups.forEach((g, i) => {
    const y = top + i * 52;
    const ok = g.status === "published";
    svg += `<g><title>${escapeHtml(cohortTitle(g))}</title>` +
      `<text x="0" y="${y + 20}" class="chart-label">` +
      `${escapeHtml(cohortLabel(g))}</text>` +
      `<rect x="200" y="${y}" width="${scale(g.n)}" height="18" ` +
      `class="${ok ? "bar" : "bar thin"}"/>` +
      `<text x="${204 + scale(g.n)}" y="${y + 15}" ` +
      `class="chart-value">n=${g.n} i=${g.installs}` +
      `${ok ? "" : " (thin)"}</text></g>`;
  });
  const qx = 200 + scale(qRuns);
  svg += `<line x1="${qx}" y1="0" x2="${qx}" y2="${H}" class="quorum"/>`;
  svg += `<text x="${qx + 4}" y="10" class="chart-value">quorum ` +
    `n=${qRuns}</text></svg>`;
  const truncNote = opts && opts.truncated
    ? ` Group listing truncated: figures are lower bounds.`
    : "";
  return `<div class="card chart"><h3>Evidence coverage by cohort</h3>` +
    `${svg}<p class="caption">Runs (n) and installations (i) per ` +
    `exact cohort. The line marks the ${qRuns}-run quorum ` +
    `(plus ${qInstalls} installations, checked per cohort).` +
    `${truncNote}</p></div>`;
}

export function filterHtml(models, facets, selected) {
  const sel = selected || {};
  const modelOpts = models.map((m) =>
    `<option value="${escapeHtml(m.model_sha)}"` +
    `${sel.model_sha === m.model_sha ? " selected" : ""}>` +
    `${shortHash(m.model_sha)} (${m.published_runs} runs)</option>`
  ).join("");
  const facetSelect = (key, label) => {
    const opts = (facets[key] || []).map((v) =>
      `<option value="${escapeHtml(v)}"` +
      `${sel[key] === v ? " selected" : ""}>` +
      `${escapeHtml(v)}</option>`).join("");
    return `<label>${escapeHtml(label)}<select data-facet="${key}">` +
      `<option value="">All</option>${opts}</select></label>`;
  };
  return `<form class="card filters" id="dashfilters">` +
    `<label>Model<select data-facet="model_sha">${modelOpts}</select>` +
    `</label>` +
    facetSelect("device_class", "Device class") +
    facetSelect("workload_id", "Workload") +
    facetSelect("quantization", "Quantization") +
    facetSelect("n_ctx", "Context size") +
    `<p><button type="button" class="btn btn-ghost" ` +
    `data-action="reset-filters">Reset filters</button></p>` +
    `<p class="caption">Filters subset exact cohorts; they never ` +
    `merge statistics across cohorts.</p></form>`;
}

// Pure filter-state step: empty values clear the key. Nothing here
// touches the DOM, so session behavior is unit-testable.
export function nextFacets(current, key, value) {
  const next = { ...(current || {}) };
  if (value === "" || value === undefined || value === null) {
    delete next[key];
  } else {
    next[key] = value;
  }
  return next;
}

// Render the filter form into box and wire changes. Defensive about
// partial DOMs (unit-test stubs): renders HTML first, then attaches
// only the listeners the host supports.
export function mountFilters(box, models, groups, selected, hooks) {
  if (!box) return;
  const sel = selected || {};
  box.innerHTML = filterHtml(models, {
    device_class: facetValues(groups, "device_class"),
    workload_id: facetValues(groups, "workload_id"),
    quantization: facetValues(groups, "quantization"),
    n_ctx: facetValues(groups, "n_ctx"),
  }, sel);
  if (typeof box.querySelector !== "function") return;
  const form = box.querySelector("form");
  if (!form || typeof form.addEventListener !== "function") return;
  form.addEventListener("submit", (e) => {
    if (e && typeof e.preventDefault === "function") e.preventDefault();
  });
  const onChange = hooks && hooks.onChange;
  const onReset = hooks && hooks.onReset;
  if (typeof form.querySelectorAll === "function" && onChange) {
    const selects = form.querySelectorAll("select[data-facet]");
    (selects.forEach ? [...selects] : Array.from(selects || []))
      .forEach((s) => {
        if (s && typeof s.addEventListener === "function") {
          s.addEventListener("change", () => {
            const k = typeof s.getAttribute === "function"
              ? s.getAttribute("data-facet") : s.dataset?.facet;
            onChange(k, s.value);
          });
        }
      });
  }
  if (typeof form.querySelector === "function" && onReset) {
    const reset = form.querySelector('[data-action="reset-filters"]');
    if (reset && typeof reset.addEventListener === "function") {
      reset.addEventListener("click", onReset);
    }
  }
}

// Browser wiring. Provider selection is the one-line config
// boundary: EVIDENCE_BASE null (today) means EmptyProvider and
// truthful NO_SOURCE states; a future authorized URL selects
// HttpProvider. Manual refresh re-checks + timestamps.
export function initDashboard(root, provider) {
  const prov = provider ||
    (EVIDENCE_BASE ? new HttpProvider(EVIDENCE_BASE) : new EmptyProvider());
  const $ = (id) => root.querySelector(`#${id}`);
  const state = { facets: {}, checkedAt: null };
  async function load() {
    const kpi = $("dash-kpis"), charts = $("dash-charts"),
      meta = $("dash-meta"), filtersBox = $("dash-filters");
    try {
      const [models, candidates] = await Promise.all([
        prov.listModels(), prov.getCandidates(),
      ]);
      const summary = summarizeModels(models.artifacts);
      const qual = candidates.candidates
        .reduce((s, c) => s + (c.qualifying_cohorts || 0), 0);
      kpi.innerHTML = kpiHtml(summary, qual,
        { modelsTruncated: models.truncated === true });
      const first = models.artifacts[0];
      if (!first) {
        charts.innerHTML = stateHtml(STATE.BELOW_QUORUM);
        if (filtersBox) filtersBox.innerHTML = "";
      } else {
        const modelSha = state.facets.model_sha || first.model_sha;
        const agg = await prov.getAggregates(modelSha, {});
        const groups = applyFacets(agg.groups, state.facets);
        mountFilters(filtersBox, models.artifacts, agg.groups,
          { ...state.facets, model_sha: modelSha },
          { onChange: (k, v) => {
              state.facets = nextFacets(state.facets, k, v);
              load();
            },
            onReset: () => {
              state.facets = {};
              load();
            } });
        const shown = groups.length === 0 && agg.groups.length > 0
          ? stateHtml(STATE.FILTERED_EMPTY)
          : throughputChart(groups, { truncated: agg.truncated }) +
            coverageChart(groups,
              { truncated: agg.truncated, quorum: agg.quorum || null });
        charts.innerHTML = shown;
      }
      state.checkedAt = new Date().toISOString();
      meta.textContent = `Source: ${prov.kind}; checked ${state.checkedAt}.`;
    } catch (e) {
      const st = (e && e.state) || STATE.SOURCE_ERROR;
      kpi.innerHTML = stateHtml(st);
      charts.innerHTML = stateHtml(st);
      if (filtersBox) filtersBox.innerHTML = "";
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
