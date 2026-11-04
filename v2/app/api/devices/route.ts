import { NextResponse } from "next/server";
import { operator, failure } from "@/lib/server";
import { ROOT, ownedBy } from "@/lib/contracts";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  try {
    const { user, db } = await operator(request);
    const index = await db.request(`${ROOT}/owners/${user.uid}`) ?? {};
    const devices = await Promise.all(Object.keys(index).slice(0, 100).map(async id => {
      const base = `${ROOT}/devices/${id}`;
      const [meta, telemetry, results, inbox] = await Promise.all([
        db.request(`${base}/meta`), db.request(`${base}/telemetry`),
        db.request(`${base}/results`, "GET", undefined, { orderBy: JSON.stringify("createdAt"), limitToLast: "20" }),
        db.request(`${base}/inbox`),
      ]);
      const queued = Object.fromEntries(Object.entries(inbox ?? {}).map(([commandId, command]) => {
        const value = command as { action: string; createdAt: number };
        return [commandId, { id: commandId, action: value.action, createdAt: value.createdAt, status: "queued" }];
      }));
      const item = { id, meta, telemetry, results: { ...queued, ...(results ?? {}) } };
      return ownedBy(item, user.uid) ? item : null;
    }));
    return NextResponse.json({ devices: devices.filter(Boolean) }, { headers: { "Cache-Control": "private, no-store" } });
  } catch (error) { return failure(error); }
}
