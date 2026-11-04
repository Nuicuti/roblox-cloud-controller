import { z } from "zod";
import { NextResponse } from "next/server";
import { body, operator, failure, HttpError } from "@/lib/server";
import { ROOT, deviceIdSchema } from "@/lib/contracts";
export const runtime = "nodejs";
const input = z.object({ id: deviceIdSchema, code: z.string().regex(/^[a-f0-9]{32}$/), name: z.string().trim().min(1).max(60) }).strict();
export async function POST(request: Request) {
  try {
    const { user, db } = await operator(request);
    const data = input.parse(await body(request));
    const ticket = await db.request(`${ROOT}/pairingCodes/${data.code}`);
    if (!ticket || ticket.deviceUid !== data.id || ticket.expiresAt < Date.now())
      throw new HttpError(400, "Mã ghép máy không đúng hoặc đã hết hạn.");
    // Database rules atomically enforce immutable ownership and verify the pairing ticket.
    await db.request(`${ROOT}/devices/${data.id}/meta`, "PUT", {
      ownerUid: user.uid, name: data.name, pairedAt: Date.now(), pairingCode: data.code,
    });
    // Separate retryable index write: a partial failure never changes machine ownership.
    await db.request(`${ROOT}/owners/${user.uid}/${data.id}`, "PUT", true);
    return NextResponse.json({ ok: true });
  } catch (error) { return failure(error); }
}
