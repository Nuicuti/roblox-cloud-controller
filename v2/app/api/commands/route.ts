import { randomUUID } from "node:crypto";
import { z } from "zod";
import { NextResponse } from "next/server";
import { body, operator, failure, HttpError } from "@/lib/server";
import { commandSchema, deviceIdSchema, ROOT } from "@/lib/contracts";
export const runtime = "nodejs";
export async function POST(request: Request) {
  try {
    const { user, db } = await operator(request);
    const input = z.object({ deviceId: deviceIdSchema, command: commandSchema }).strict().parse(await body(request));
    const base = `${ROOT}/devices/${input.deviceId}`;
    const meta = await db.request(`${base}/meta`);
    if (!meta || meta.ownerUid !== user.uid) throw new HttpError(403, "Bạn không có quyền điều khiển máy này.");
    const now = Date.now(), id = randomUUID();
    const command = { id, ...input.command, createdAt: now, expiresAt: now + 120_000 };
    const inbox = await db.request(`${base}/inbox`, "GET", undefined, { shallow: "true" });
    if (Object.keys(inbox ?? {}).length >= 20) throw new HttpError(429, "Máy đang có nhiều lệnh chờ. Vui lòng thử lại sau.");
    await db.request(base, "PATCH", {
      [`inbox/${id}`]: command,
      ...(input.command.action === "set_accounts" ? { vault: { sealed: input.command.sealed, updatedAt: now } } : {}),
    });
    return NextResponse.json({ id, status: "queued" });
  } catch (error) { return failure(error); }
}
