import { z } from "zod";

export const ROOT = "nokaController/v1";
export const deviceIdSchema = z.string().regex(/^[A-Za-z0-9_-]{10,128}$/);
export const packageSchema = z.string().regex(/^free\.noka[A-Za-z0-9_.]*$/).max(160);
export const profileSchema = z.object({
  package: packageSchema,
  label: z.string().trim().min(1).max(60),
  username: z.string().trim().max(80),
  slot: z.number().int().min(1).max(36),
  enabled: z.boolean(),
  autoReopen: z.boolean().optional(),
}).strict();
export const commandSchema = z.discriminatedUnion("action", [
  z.object({ action: z.literal("scan") }).strict(),
  z.object({ action: z.literal("open_all") }).strict(),
  z.object({ action: z.literal("status") }).strict(),
  z.object({ action: z.literal("set_watch"), enabled: z.boolean() }).strict(),
  z.object({ action: z.literal("profile"), profile: profileSchema }).strict(),
  z.object({ action: z.literal("open"), package: packageSchema }).strict(),
  z.object({ action: z.literal("close"), package: packageSchema }).strict(),
  z.object({ action: z.literal("grid"), rows: z.number().int().min(1).max(6),
    cols: z.number().int().min(1).max(6), gap: z.number().int().min(0).max(40),
    top: z.number().int().min(0).max(250), bottom: z.number().int().min(0).max(250) }).strict(),
  z.object({ action: z.literal("enable_freeform") }).strict(),
  z.object({ action: z.literal("restore_windowing") }).strict(),
  z.object({ action: z.literal("set_accounts"), sealed: z.object({
    key: z.string().regex(/^[A-Za-z0-9+/=]+$/).max(1024),
    iv: z.string().regex(/^[A-Za-z0-9+/=]+$/).max(32),
    data: z.string().regex(/^[A-Za-z0-9+/=]+$/).max(180000),
  }).strict() }).strict(),
  z.object({ action: z.literal("login_all") }).strict(),
]);
export type Command = z.infer<typeof commandSchema>;
export type Profile = z.infer<typeof profileSchema> & {
  running?: boolean;
  error?: string;
  taskId?: number;
  hasAccount?: boolean;
  loginMessage?: string;
  cookieStatus?: "valid" | "invalid" | "unverified";
  sqliteStatus?: "written" | "failed" | "none";
  appStatus?: "running" | "stopped";
  loginStatus?: "unverified_ingame" | "logged_in";
};
export type Result = { id: string; action: string; status: "queued" | "running" | "succeeded" | "failed";
  createdAt: number; finishedAt?: number; message?: string };
export type Device = { id: string; meta: { name: string; ownerUid: string }; telemetry?: {
  lastSeen: number; model: string; android: string; watch: boolean; profiles?: Record<string, Profile>;
  grid?: { rows: number; cols: number }; freeform?: boolean; error?: string;
  publicKey?: JsonWebKey;
}; results?: Record<string, Result> };

export function isOnline(lastSeen: number | undefined, now = Date.now()) {
  return typeof lastSeen === "number" && now - lastSeen < 60_000 && now - lastSeen > -60_000;
}
export function ownedBy(device: { meta?: { ownerUid?: string } } | null, uid: string) {
  return Boolean(device?.meta?.ownerUid && device.meta.ownerUid === uid);
}
