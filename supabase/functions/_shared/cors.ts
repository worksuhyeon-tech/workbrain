// PC 웹(DEC-102)이 브라우저에서 처리 함수·Word 함수를 부를 수 있게 하는 CORS. Android 앱은 브라우저가 아니라 상관없다.
// 허용 주소는 배포 주소와 이 PC의 개발 주소만 연다. 로그인 확인은 지금처럼 함수 안에서 한다(이 머리는 브라우저가 답을 읽게 해 줄 뿐).
export const ALLOWED_ORIGINS = new Set(["https://workbrain-web.vercel.app", "http://localhost:5173"]);

export function corsHeaders(req: Request): Record<string, string> {
  const origin = req.headers.get("Origin") ?? "";
  if (!ALLOWED_ORIGINS.has(origin)) return {};
  return {
    "Access-Control-Allow-Origin": origin,
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    // supabase-js가 함수를 부를 때 붙이는 머리
    "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-region",
    // Word 받기에서 회의 날짜·초안 여부를 읽는다(minutes-word)
    "Access-Control-Expose-Headers": "x-wb-meeting-date, x-wb-draft, x-wb-plan-kind",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
}

/** 함수 본체를 감싸 브라우저 사전 확인(OPTIONS)에 바로 답하고, 본체 응답에 CORS 머리를 붙인다. 허용 주소가 아니면 아무것도 더하지 않는다 */
export function withCors(handler: (req: Request) => Promise<Response>): (req: Request) => Promise<Response> {
  return async (req) => {
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: corsHeaders(req) });
    const res = await handler(req);
    const extra = corsHeaders(req);
    if (Object.keys(extra).length === 0) return res;
    const headers = new Headers(res.headers);
    for (const [k, v] of Object.entries(extra)) headers.set(k, v);
    return new Response(res.body, { status: res.status, statusText: res.statusText, headers });
  };
}
