import { z } from "zod";
import { NextResponse } from "next/server";
import { body, operator, failure, HttpError } from "@/lib/server";
import { ROOT, deviceIdSchema } from "@/lib/contracts";
export const runtime = "nodejs";

const input = z.object({
  id: deviceIdSchema,
  code: z.string().trim().regex(/^[a-f0-9]{32}$/),
  name: z.string().trim().min(1).max(60)
}).strict();

export async function POST(request: Request) {
  try {
    const { user, db } = await operator(request);
    const data = input.parse(await body(request));
    const ticket = await db.request(`${ROOT}/pairingCodes/${data.code}`);
    if (!ticket || ticket.expiresAt < Date.now())
      throw new HttpError(400, "Mã ghép máy không đúng hoặc đã hết hạn.");
    if (ticket.deviceUid !== data.id && ticket.deviceUid.toLowerCase() !== data.id.toLowerCase())
      throw new HttpError(400, "UID máy không khớp với mã ghép.");
    
    // Luôn dùng UID chuẩn từ ticket của thiết bị để khớp chính xác database rules
    const deviceId = ticket.deviceUid;
    await db.request(`${ROOT}/devices/${deviceId}/meta`, "PUT", {
      ownerUid: user.uid, name: data.name, pairedAt: Date.now(), pairingCode: data.code,
    });
    await db.request(`${ROOT}/owners/${user.uid}/${deviceId}`, "PUT", true);
    return NextResponse.json({ ok: true });
  } catch (error) { return failure(error); }
}
