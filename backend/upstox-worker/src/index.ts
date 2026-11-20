import { verifyGoogleIdToken } from "./google-auth.js";
interface Env { UPSTOX_ANALYTICS_TOKEN:string; GOOGLE_CLIENT_ID:string; MGROW_ALLOWED_GOOGLE_EMAIL:string; QUOTE_LIMITER:{limit(o:{key:string}):Promise<{success:boolean}>}; }
const BASE="https://api.upstox.com", NIFTY="NSE_INDEX|Nifty 50", TIMEOUT=6000;
const json=(v:unknown,s=200)=>new Response(JSON.stringify(v),{status:s,headers:{"content-type":"application/json","cache-control":"no-store","x-content-type-options":"nosniff","referrer-policy":"no-referrer"}});
async function upstream(path:string,token:string){
 const c=new AbortController(), t=setTimeout(()=>c.abort(),TIMEOUT);
 try{const r=await fetch(BASE+path,{headers:{Accept:"application/json",Authorization:"Bearer "+token},signal:c.signal}); if(!r.ok)throw new Error("upstream_"+r.status); return await r.json() as any;}finally{clearTimeout(t);}
}
function n(v:any):number|null{return typeof v==="number"&&Number.isFinite(v)?v:null;}
export default {async fetch(req:Request,env:Env):Promise<Response>{
 const u=new URL(req.url); if(u.pathname==="/health"&&req.method==="GET")return json({service:"mgrow-readonly",status:"ready"});
 if(req.method!=="GET")return json({error:"method_not_allowed"},405);
 if(!["/v1/nifty/option-chain","/v1/nifty/candles"].includes(u.pathname))return json({error:"not_found"},404);
 const h=req.headers.get("Authorization")||"", token=h.startsWith("Bearer ")?h.slice(7):"";
 const owner=token?await verifyGoogleIdToken(token,env):null; if(!owner)return json({error:"unauthorized"},401);
 if(!env.UPSTOX_ANALYTICS_TOKEN)return json({error:"upstox_token_not_configured"},503);
 if(!env.QUOTE_LIMITER)return json({error:"rate_limiter_not_configured"},503);
 try{const limit=await env.QUOTE_LIMITER.limit({key:"mgrow:"+owner.toLowerCase()});if(!limit.success)return json({error:"rate_limit_exceeded"},429);}catch{return json({error:"rate_limiter_unavailable"},503);}
 try{
  if(u.pathname==="/v1/nifty/option-chain"){
   const expiry=u.searchParams.get("expiry_date")||"";
   if(!/^\d{4}-\d{2}-\d{2}$/.test(expiry)||Number.isNaN(Date.parse(expiry))||new Date(expiry).toISOString().slice(0,10)!==expiry)return json({error:"invalid_expiry_date"},400);
   const p=await upstream("/v2/option/chain?instrument_key="+encodeURIComponent(NIFTY)+"&expiry_date="+encodeURIComponent(expiry),env.UPSTOX_ANALYTICS_TOKEN);
   if(p.status!=="success"||!Array.isArray(p.data))return json({error:"invalid_upstream_payload"},502);
   const rows=p.data.map((r:any)=>{const ce=r.call_options||{},pe=r.put_options||{},cm=ce.market_data||{},pm=pe.market_data||{},cg=ce.option_greeks||{},pg=pe.option_greeks||{};
    return {strike:n(r.strike_price),spot:n(r.underlying_spot_price),pcr:n(r.pcr),
     callOi:n(cm.oi),callOiChange:n(cm.oi)!=null&&n(cm.prev_oi)!=null?n(cm.oi)-n(cm.prev_oi):null,callVolume:n(cm.volume),callLtp:n(cm.ltp),callIv:n(cg.iv),callDelta:n(cg.delta),callBid:n(cm.bid_price),callAsk:n(cm.ask_price),
     putOi:n(pm.oi),putOiChange:n(pm.oi)!=null&&n(pm.prev_oi)!=null?n(pm.oi)-n(pm.prev_oi):null,putVolume:n(pm.volume),putLtp:n(pm.ltp),putIv:n(pg.iv),putDelta:n(pg.delta),putBid:n(pm.bid_price),putAsk:n(pm.ask_price)};
   }).filter((r:any)=>r.strike!==null);
   if(!rows.length)return json({error:"empty_option_chain"},502);
   return json({provider:"upstox",instrumentKey:NIFTY,expiryDate:expiry,receivedAt:new Date().toISOString(),data:rows});
  }
  const [five,one]=await Promise.all([
   upstream("/v3/historical-candle/intraday/"+encodeURIComponent(NIFTY)+"/minutes/5",env.UPSTOX_ANALYTICS_TOKEN),
   upstream("/v3/historical-candle/intraday/"+encodeURIComponent(NIFTY)+"/minutes/1",env.UPSTOX_ANALYTICS_TOKEN).catch(()=>null)
  ]);
  const parse=(p:any)=>{const a=p?.data?.candles;if(p?.status!=="success"||!Array.isArray(a))return null;return a.map((x:any)=>({timestamp:x[0],open:n(x[1]),high:n(x[2]),low:n(x[3]),close:n(x[4]),volume:n(x[5])??0})).filter((x:any)=>typeof x.timestamp==="string"&&[x.open,x.high,x.low,x.close].every((v:any)=>v!==null&&v>0)).sort((a:any,b:any)=>Date.parse(a.timestamp)-Date.parse(b.timestamp)).slice(-180);};
  const f=parse(five),o=one?parse(one):[];
  if(!f?.length)return json({error:"invalid_five_minute_candles"},502);
  return json({provider:"upstox",instrumentKey:NIFTY,receivedAt:new Date().toISOString(),data:{fiveMinuteCandles:f,oneMinuteCandles:o||[]},warnings:o?[]:["one_minute_temporarily_unavailable"]});
 }catch(e){return json({error:e instanceof Error&&e.name==="AbortError"?"upstream_timeout":"upstream_request_failed"},502);}
}};