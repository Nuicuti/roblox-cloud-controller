import "server-only";
import { NextResponse } from "next/server";
import { ROOT } from "./contracts";
import { verifyFirebaseIdToken } from "./verify-token";

export class HttpError extends Error {
  constructor(public status: number, message: string) { super(message); }
}
export class FirebaseREST {
  constructor(private databaseURL: string, private token: string) {}
  async request(path: string, method = "GET", value?: unknown, parameters: Record<string, string> = {}) {
    const url = new URL(path.split("/").map(encodeURIComponent).join("/")+".json", this.databaseURL.replace(/\/$/, "")+"/");
    url.search = new URLSearchParams({ auth: this.token, ...parameters }).toString();
    const response = await fetch(url, { method, cache: "no-store", headers: { "Content-Type": "application/json" },
      body: value === undefined ? undefined : JSON.stringify(value) });
    if (!response.ok) throw new HttpError(response.status === 401 || response.status === 403 ? 403 : 502,
      "Firebase chưa cho phép thao tác này. Kiểm tra quyền điều khiển và database rules.");
    return response.status === 204 ? null : response.json();
  }
}
export function services(token: string) {
  const projectId = process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID;
  const databaseURL = process.env.FIREBASE_DATABASE_URL;
  if (!projectId || !databaseURL) {
    throw new HttpError(503, "Dashboard chưa được cấu hình kết nối Firebase trên Vercel.");
  }
  return { projectId, db: new FirebaseREST(databaseURL, token) };
}
export async function operator(request: Request) {
  const bearer = request.headers.get("authorization") ?? "";
  if (!bearer.startsWith("Bearer ")) throw new HttpError(401, "Bạn cần đăng nhập.");
  const { projectId, db } = services(bearer.slice(7));
  let user;
  try { user = await verifyFirebaseIdToken(bearer.slice(7), projectId); }
  catch { throw new HttpError(401, "Phiên đăng nhập đã hết hạn."); }
  if (!user.email || user.firebase.sign_in_provider === "anonymous") {
    throw new HttpError(403, "Tài khoản này chưa được phép điều khiển.");
  }
  const delegated = await db.request(`${ROOT}/operators/${user.uid}`) === true;
  if (!delegated) {
    let ownerEmail: string | null = null;
    try { ownerEmail = await db.request(`${ROOT}/ownerEmail`); } catch {}
    if (!user.email_verified || ownerEmail?.toLowerCase() !== user.email.toLowerCase())
      throw new HttpError(403, "Tài khoản này chưa được phép điều khiển.");
  }
  return { user, db };
}
export async function body(request: Request) {
  const text = await request.text();
  if (text.length > 262_144) throw new HttpError(413, "Nội dung quá dài.");
  try { return JSON.parse(text); } catch { throw new HttpError(400, "Dữ liệu không hợp lệ."); }
}
export function failure(error: unknown) {
  if (error instanceof HttpError) return NextResponse.json({ error: error.message }, { status: error.status });
  if (error instanceof Error && error.name === "ZodError") return NextResponse.json({ error: "Thông tin lệnh không hợp lệ." }, { status: 400 });
  console.error("Controller operation failed", error instanceof Error ? error.name : "unknown");
  return NextResponse.json({ error: "Không thể hoàn tất thao tác. Kiểm tra kết nối Firebase." }, { status: 500 });
}
