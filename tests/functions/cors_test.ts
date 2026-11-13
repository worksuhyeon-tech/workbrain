// PC 웹 CORS(DEC-102) 시험. 실행: npx -y deno@2.1.4 test tests/functions/cors_test.ts
import { assertEquals } from "jsr:@std/assert@1";
import { withCors } from "../../supabase/functions/_shared/cors.ts";

const body = withCors(() => Promise.resolve(new Response("{}", { status: 401, headers: { "content-type": "application/json", "x-wb-draft": "true" } })));
const req = (method: string, origin?: string) =>
  new Request("https://example.test/functions/v1/x", { method, headers: origin ? { Origin: origin } : {} });

Deno.test("허용 주소의 사전 확인(OPTIONS)은 본체를 부르지 않고 204 + 허용 머리", async () => {
  let called = false;
  const h = withCors(() => { called = true; return Promise.resolve(new Response("x")); });
  const res = await h(req("OPTIONS", "https://workbrain-web.vercel.app"));
  assertEquals(res.status, 204);
  assertEquals(called, false);
  assertEquals(res.headers.get("access-control-allow-origin"), "https://workbrain-web.vercel.app");
  assertEquals(res.headers.get("access-control-allow-headers")?.includes("authorization"), true);
});

Deno.test("허용 주소의 본체 응답은 상태·머리를 그대로 두고 CORS 머리만 더한다", async () => {
  const res = await body(req("POST", "http://localhost:5173"));
  assertEquals(res.status, 401);
  assertEquals(res.headers.get("content-type"), "application/json");
  assertEquals(res.headers.get("x-wb-draft"), "true");
  assertEquals(res.headers.get("access-control-allow-origin"), "http://localhost:5173");
  assertEquals(res.headers.get("access-control-expose-headers")?.includes("x-wb-meeting-date"), true);
});

Deno.test("다른 주소·앱(Origin 없음)에는 아무것도 더하지 않는다", async () => {
  for (const origin of ["https://evil.example", undefined]) {
    const res = await body(req("POST", origin));
    assertEquals(res.status, 401);
    assertEquals(res.headers.get("access-control-allow-origin"), null);
  }
  const pre = await body(req("OPTIONS", "https://evil.example"));
  assertEquals(pre.headers.get("access-control-allow-origin"), null);
});
