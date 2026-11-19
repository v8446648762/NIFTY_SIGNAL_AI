const UPSTREAM = "https://mgrow-upstox-readonly.vikaskalburgi130.workers.dev/v1/nifty/candles";

export async function onRequest(context) {
  if (context.request.method !== "GET") {
    return new Response("Method Not Allowed", { status: 405, headers: { Allow: "GET" } });
  }
  try {
    const response = await fetch(UPSTREAM, {
      method: "GET",
      headers: { Accept: "application/json", "Cache-Control": "no-cache" },
      cf: { cacheTtl: 0, cacheEverything: false },
    });
    const body = await response.arrayBuffer();
    return new Response(body, {
      status: response.status,
      headers: {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store, max-age=0",
        "X-Content-Type-Options": "nosniff",
        "Referrer-Policy": "no-referrer",
      },
    });
  } catch {
    return Response.json({ error: "market_data_proxy_unavailable" }, { status: 502 });
  }
}