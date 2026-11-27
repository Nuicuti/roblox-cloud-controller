import { z } from "zod";
import { NextResponse } from "next/server";
import { body, failure, HttpError, operator } from "@/lib/server";
import { deviceIdSchema, ownedBy, ROOT } from "@/lib/contracts";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const renameInput = z.object({
  id: deviceIdSchema,
  name: z.string().trim().min(1).max(60),
}).strict();

const deleteInput = z.object({
  id: deviceIdSchema,
}).strict();

export async function GET(request: Request) {
  try {
    const { user, db } = await operator(request);
    const index = (await db.request(`${ROOT}/owners/${user.uid}`)) ?? {};
    const devices = await Promise.all(
      Object.keys(index).slice(0, 100).map(async id => {
        const base = `${ROOT}/devices/${id}`;
        const [meta, telemetry, results, inbox] = await Promise.all([
          db.request(`${base}/meta`),
          db.request(`${base}/telemetry`),
          db.request(`${base}/results`, "GET", undefined, { orderBy: JSON.stringify("createdAt"), limitToLast: "20" }),
          db.request(`${base}/inbox`),
        ]);
        const queued = Object.fromEntries(
          Object.entries(inbox ?? {}).map(([commandId, command]) => {
            const value = command as { action: string; createdAt: number };
            return [commandId, { id: commandId, action: value.action, createdAt: value.createdAt, status: "queued" }];
          }),
        );
        const item = { id, meta, telemetry, results: { ...queued, ...(results ?? {}) } };
        return ownedBy(item, user.uid) ? item : null;
      }),
    );
    const sorted = devices
      .filter((item): item is NonNullable<typeof item> => Boolean(item))
      .sort((a, b) => (a.meta?.pairedAt ?? 0) - (b.meta?.pairedAt ?? 0));
    return NextResponse.json({ devices: sorted }, { headers: { "Cache-Control": "private, no-store" } });
  } catch (error) {
    return failure(error);
  }
}

export async function PATCH(request: Request) {
  try {
    const { user, db } = await operator(request);
    const data = renameInput.parse(await body(request));
    const metaPath = `${ROOT}/devices/${data.id}/meta`;
    const meta = await db.request(metaPath);
    if (!meta) {
      throw new HttpError(404, "Không tìm thấy thiết bị này.");
    }
    if (meta.ownerUid !== user.uid) {
      throw new HttpError(403, "Bạn không có quyền đổi tên máy này.");
    }
    await db.request(metaPath, "PATCH", { name: data.name });
    return NextResponse.json({ ok: true, id: data.id, name: data.name });
  } catch (error) {
    return failure(error);
  }
}

export async function DELETE(request: Request) {
  try {
    const { user, db } = await operator(request);
    const queryId = new URL(request.url).searchParams.get("id");
    const raw = queryId ? { id: queryId } : await body(request);
    const { id } = deleteInput.parse(raw);

    const devicePath = `${ROOT}/devices/${id}`;
    const ownerIndexPath = `${ROOT}/owners/${user.uid}/${id}`;
    const [meta, indexed] = await Promise.all([
      db.request(`${devicePath}/meta`),
      db.request(ownerIndexPath),
    ]);

    if (!meta && !indexed) {
      throw new HttpError(404, "Thiết bị không tồn tại hoặc đã bị xóa.");
    }
    if (meta && meta.ownerUid !== user.uid) {
      throw new HttpError(403, "Bạn không có quyền xóa máy này.");
    }

    if (meta) {
      await db.request(devicePath, "DELETE");
    }
    await db.request(ownerIndexPath, "DELETE");
    return NextResponse.json({ ok: true, id });
  } catch (error) {
    return failure(error);
  }
}
