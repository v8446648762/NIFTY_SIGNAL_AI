export interface GoogleAuthEnv { GOOGLE_CLIENT_ID: string; MGROW_ALLOWED_GOOGLE_EMAIL: string; }
type Claims={iss?:string;aud?:string|string[];exp?:number;iat?:number;email?:string;email_verified?:boolean};
type Jwk=JsonWebKey&{kid?:string};
let keys:Jwk[]|null=null, expires=0;
const decode=(s:string)=>Uint8Array.from(atob(s.replace(/-/g,"+").replace(/_/g,"/").padEnd(Math.ceil(s.length/4)*4,"=")),c=>c.charCodeAt(0));
export async function verifyGoogleIdToken(token:string,env:GoogleAuthEnv):Promise<string|null>{
 try{
  if(!env.GOOGLE_CLIENT_ID||!env.MGROW_ALLOWED_GOOGLE_EMAIL)return null;
  const p=token.split("."); if(p.length!==3)return null;
  const h=JSON.parse(new TextDecoder().decode(decode(p[0]))) as {alg?:string;kid?:string};
  const c=JSON.parse(new TextDecoder().decode(decode(p[1]))) as Claims;
  const now=Math.floor(Date.now()/1000);
  if(h.alg!=="RS256"||!h.kid||!(c.iss==="https://accounts.google.com"||c.iss==="accounts.google.com")||
   !(Array.isArray(c.aud)?c.aud.includes(env.GOOGLE_CLIENT_ID):c.aud===env.GOOGLE_CLIENT_ID)||
   typeof c.exp!=="number"||c.exp<=now||typeof c.iat!=="number"||c.iat>now+60||
   c.email_verified!==true||typeof c.email!=="string"||c.email.toLowerCase()!==env.MGROW_ALLOWED_GOOGLE_EMAIL.toLowerCase())return null;
  if(!keys||Date.now()>expires){
   const r=await fetch("https://www.googleapis.com/oauth2/v3/certs");
   if(!r.ok)return null; const j=await r.json() as {keys?:Jwk[]}; if(!j.keys?.length)return null;
   keys=j.keys; const m=r.headers.get("cache-control")?.match(/max-age=(\d+)/i); expires=Date.now()+Math.min(Math.max(Number(m?.[1]||300),60),3600)*1000;
  }
  const jwk=keys.find(k=>k.kid===h.kid); if(!jwk||jwk.kty!=="RSA")return null;
  const key=await crypto.subtle.importKey("jwk",jwk,{name:"RSASSA-PKCS1-v1_5",hash:"SHA-256"},false,["verify"]);
  const data=new TextEncoder().encode(p[0]+"."+p[1]), sig=decode(p[2]);
  const ok=await crypto.subtle.verify("RSASSA-PKCS1-v1_5",key,sig,data);
  return ok?c.email:null;
 }catch{return null;}
}