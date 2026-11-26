const UPSTOX_OPTION_CHAIN_URL = "https://api.upstox.com/v2/option/chain";
const NIFTY_50_KEY = "NSE_INDEX|Nifty 50";
const UPSTREAM_TIMEOUT_MS = 5_000;
const MAX_STRIKES = 200;

const EXPIRY_KEYWORDS = new Set([
  "current_week", "next_week", "far_week",
  "current_month", "next_month", "far_month",
]);

function record(value: unknown): Record<string, unknown> | null {
  return value && typeof value === "object" && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function finiteNonNegative(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : null;
}

function optionalGreek(options: Record<string, unknown> | null, key: string): number | null {
  return finiteNonNegative(options?.[key]);
}

export function validateOptionExpiry(value: string | null): string | null {
  if (!value) return "current_week";
  if (EXPIRY_KEYWORDS.has(value)) return value;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const parsed = new Date(`${value}T00:00:00.000Z`);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value ? value : null;
}

/** Strictly normalizes the fixed NIFTY 50 option-chain response. */
export function extractValidOptionChain(payload: unknown): {
  expiry: string;
  spotPrice: number;
  strikes: Array<Record<string, unknown>>;
} | null {
  const root = record(payload);
  if (root?.status !== "success" || !Array.isArray(root.data) ||
      root.data.length === 0 || root.data.length > MAX_STRIKES) return null;

  const strikes: Array<Record<string, unknown>> = [];
  let expiry: string | null = null;
  let spotPrice: number | null = null;

  for (const raw of root.data) {
    const row = record(raw);
    if (!row) return null;
    const strike = finiteNonNegative(row.strike_price);
    const rowSpot = typeof row.underlying_spot_price === "number" &&
      Number.isFinite(row.underlying_spot_price) && row.underlying_spot_price > 0
      ? row.underlying_spot_price : null;
    if (strike === null || rowSpot === null || typeof row.expiry !== "string") return null;
    if (expiry !== null && expiry !== row.expiry) return null;
    if (spotPrice !== null && Math.abs(spotPrice - rowSpot) > 0.01) return null;
    expiry = row.expiry;
    spotPrice = rowSpot;

    const callOptions = record(row.call_options);
    const putOptions = record(row.put_options);
    const callMarket = record(callOptions?.market_data);
    const putMarket = record(putOptions?.market_data);
    const callGreeks = record(callOptions?.option_greeks);
    const putGreeks = record(putOptions?.option_greeks);

    const callOi = finiteNonNegative(callMarket?.oi);
    const putOi = finiteNonNegative(putMarket?.oi);
    const callPrevOi = finiteNonNegative(callMarket?.prev_oi);
    const putPrevOi = finiteNonNegative(putMarket?.prev_oi);
    const callLtp = finiteNonNegative(callMarket?.ltp);
    const putLtp = finiteNonNegative(putMarket?.ltp);
    const callVolume = finiteNonNegative(callMarket?.volume);
    const putVolume = finiteNonNegative(putMarket?.volume);

    strikes.push({
      strike,
      call: {
        oi: callOi,
        previousOi: callPrevOi,
        oiChange: callOi !== null && callPrevOi !== null ? callOi - callPrevOi : null,
        ltp: callLtp,
        volume: callVolume,
        delta: optionalGreek(callGreeks, "delta"),
        iv: optionalGreek(callGreeks, "iv"),
      },
      put: {
        oi: putOi,
        previousOi: putPrevOi,
        oiChange: putOi !== null && putPrevOi !== null ? putOi - putPrevOi : null,
        ltp: putLtp,
        volume: putVolume,
        delta: optionalGreek(putGreeks, "delta"),
        iv: optionalGreek(putGreeks, "iv"),
      },
    });
  }

  strikes.sort((a, b) => Number(a.strike) - Number(b.strike));
  if (!expiry || spotPrice === null || strikes.length === 0) return null;
  return { expiry, spotPrice, strikes };
}

export async function fetchNiftyOptionChain(expiry: string, token: string): Promise<unknown> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
  try {
    const query = new URLSearchParams({
      instrument_key: NIFTY_50_KEY,
      expiry_date: expiry,
    });
    const response = await fetch(`${UPSTOX_OPTION_CHAIN_URL}?${query.toString()}`, {
      method: "GET",
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${token}`,
      },
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new Error(response.status === 429 ? "upstream_rate_limited" : "upstream_option_chain_error");
    }
    return await response.json();
  } finally {
    clearTimeout(timeout);
  }
}
