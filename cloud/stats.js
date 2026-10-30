// EF-CLOUD-01A descriptive statistics. Transparent methods,
// documented here: mean (arithmetic), median and quartiles by
// linear interpolation on sorted values (type-7 quantiles),
// IQR = q3 - q1. Callers enforce the n>=8 IQR gate.

export function mean(values) {
  if (values.length === 0) return null;
  let sum = 0;
  for (const v of values) sum += v;
  return sum / values.length;
}

/** Linear-interpolation quantile; values must be finite. */
export function quantile(sorted, q) {
  if (sorted.length === 0) return null;
  if (sorted.length === 1) return sorted[0];
  const pos = (sorted.length - 1) * q;
  const lo = Math.floor(pos);
  const hi = Math.ceil(pos);
  if (lo === hi) return sorted[lo];
  return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
}

export function median(values) {
  if (values.length === 0) return null;
  return quantile([...values].sort((a, b) => a - b), 0.5);
}

export function iqr(values) {
  if (values.length < 8) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return quantile(sorted, 0.75) - quantile(sorted, 0.25);
}
