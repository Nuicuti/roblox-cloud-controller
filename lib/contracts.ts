import { z } from "zod";

export const ROOT = "nokaController/v1";
export const deviceIdSchema = z.string().trim().regex(/^[A-Za-z0-9_-]{10,128}$/);
export const packageSchema = z
  .string()
  .regex(/^([A-Za-z][A-Za-z0-9_]*\.)*[A-Za-z0-9_]*(?:noka|roblox|arceus)[A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*$/i)
  .max(160);
export const placeIdSchema = z.string().trim().regex(/^\d{0,20}$/);
export const jobIdSchema = z.string().trim().regex(/^[A-Za-z0-9-]{0,64}$/);

export const rejoinConfigSchema = z.object({
  enabled: z.boolean(),
  placeId: placeIdSchema,
  jobId: jobIdSchema,
  checkIntervalSec: z.number().int().min(3).max(60),
  delayAfterLaunchSec: z.number().int().min(0).max(120),
  delayBetweenClonesSec: z.number().int().min(0).max(60),
  bootGraceSec: z.number().int().min(10).max(300),
  windowGraceSec: z.number().int().min(10).max(300),
  disconnectConfirmSec: z.number().int().min(2).max(60),
  networkTimeoutSec: z.number().int().min(0).max(300),
  maxRejoinIntervalSec: z.number().int().min(30).max(900),
  retryAttempts: z.number().int().min(0).max(50),
  backoffSec: z.number().int().min(5).max(120),
  teleportDetection: z.boolean(),
  teleportGraceSec: z.number().int().min(10).max(300),
  rejoinOnCrash: z.boolean(),
  detectDisconnectUi: z.boolean(),
}).strict();

export const profileSchema = z.object({
  package: packageSchema,
  label: z.string().trim().min(1).max(60),
  username: z.string().trim().max(80),
  slot: z.number().int().min(1).max(36),
  enabled: z.boolean(),
  autoReopen: z.boolean().optional(),
  placeId: placeIdSchema.optional(),
  jobId: jobIdSchema.optional(),
}).strict();

export const commandSchema = z.discriminatedUnion("action", [
  z.object({ action: z.literal("scan") }).strict(),
  z.object({ action: z.literal("open_all") }).strict(),
  z.object({ action: z.literal("rejoin_all") }).strict(),
  z.object({ action: z.literal("status") }).strict(),
  z.object({ action: z.literal("set_watch"), enabled: z.boolean() }).strict(),
  z.object({ action: z.literal("set_rejoin_config"), config: rejoinConfigSchema }).strict(),
  z.object({ action: z.literal("profile"), profile: profileSchema }).strict(),
  z.object({ action: z.literal("open"), package: packageSchema }).strict(),
  z.object({ action: z.literal("close"), package: packageSchema }).strict(),
  z.object({ action: z.literal("rejoin"), package: packageSchema }).strict(),
  z.object({
    action: z.literal("grid"),
    rows: z.number().int().min(1).max(6),
    cols: z.number().int().min(1).max(6),
    gap: z.number().int().min(0).max(40),
    top: z.number().int().min(0).max(250),
    bottom: z.number().int().min(0).max(250),
    smart: z.boolean().optional(),
  }).strict(),
  z.object({ action: z.literal("enable_freeform") }).strict(),
  z.object({ action: z.literal("restore_windowing") }).strict(),
  z.object({
    action: z.literal("set_accounts"),
    sealed: z.object({
      key: z.string().regex(/^[A-Za-z0-9+/=]+$/).max(1024),
      iv: z.string().regex(/^[A-Za-z0-9+/=]+$/).max(32),
      data: z.string().regex(/^[A-Za-z0-9+/=]+$/).max(180000),
    }).strict(),
  }).strict(),
  z.object({ action: z.literal("login_all") }).strict(),
]);

export type RejoinConfig = z.infer<typeof rejoinConfigSchema>;
export type Command = z.infer<typeof commandSchema>;
export type HealthState =
  | "running"
  | "booting"
  | "crashed"
  | "window_closed"
  | "disconnected"
  | "teleporting"
  | "auth_required"
  | "paused_retries"
  | "stopped";

export type Profile = z.infer<typeof profileSchema> & {
  running?: boolean;
  error?: string;
  taskId?: number;
  hasAccount?: boolean;
  loginMessage?: string;
  cookieStatus?: "valid" | "invalid" | "unverified";
  sqliteStatus?: "written" | "failed" | "none";
  appStatus?: "running" | "stopped";
  loginStatus?: "unverified_ingame" | "logged_in" | "manual_verification_required";
  healthState?: HealthState;
  healthMessage?: string;
  retryCount?: number;
  lastRejoinAt?: number;
};

export type Result = {
  id: string;
  action: string;
  status: "queued" | "running" | "succeeded" | "failed";
  createdAt: number;
  finishedAt?: number;
  message?: string;
};

export type Device = {
  id: string;
  meta: { name: string; ownerUid: string; pairedAt?: number };
  telemetry?: {
    lastSeen: number;
    model: string;
    android: string;
    watch: boolean;
    rejoinConfig?: RejoinConfig;
    profiles?: Record<string, Profile>;
    grid?: { rows: number; cols: number };
    freeform?: boolean;
    error?: string;
    publicKey?: JsonWebKey;
  };
  results?: Record<string, Result>;
};

export const DEFAULT_REJOIN_CONFIG: RejoinConfig = {
  enabled: true,
  placeId: "",
  jobId: "",
  checkIntervalSec: 15,
  delayAfterLaunchSec: 10,
  delayBetweenClonesSec: 15,
  bootGraceSec: 150,
  windowGraceSec: 90,
  disconnectConfirmSec: 10,
  networkTimeoutSec: 0,
  maxRejoinIntervalSec: 300,
  retryAttempts: 0,
  backoffSec: 10,
  teleportDetection: true,
  teleportGraceSec: 120,
  rejoinOnCrash: true,
  detectDisconnectUi: true,
};

export function isOnline(lastSeen: number | undefined, now = Date.now()) {
  return typeof lastSeen === "number" && now - lastSeen < 60_000 && now - lastSeen > -60_000;
}

export function ownedBy(device: { meta?: { ownerUid?: string } } | null, uid: string) {
  return Boolean(device?.meta?.ownerUid && device.meta.ownerUid === uid);
}

export function suggestGrid(count: number, width = 1280, height = 720) {
  const n = Math.max(1, count);
  const landscape = width >= height;
  let rows = 2;
  let cols = 2;
  if (n <= 1) {
    rows = 1;
    cols = 1;
  } else if (n === 2) {
    rows = landscape ? 1 : 2;
    cols = landscape ? 2 : 1;
  } else if (n === 3) {
    if (landscape) {
      rows = width >= 960 ? 1 : 2;
      cols = width >= 960 ? 3 : 2;
    } else {
      rows = 3;
      cols = 1;
    }
  } else if (n === 4) {
    rows = 2;
    cols = 2;
  } else if (n <= 6) {
    rows = landscape ? 2 : 3;
    cols = landscape ? 3 : 2;
  } else if (n <= 9) {
    rows = 3;
    cols = 3;
  } else {
    const big = Math.min(6, Math.ceil(Math.sqrt(n)));
    const small = Math.min(6, Math.ceil(n / big));
    rows = landscape ? small : big;
    cols = landscape ? big : small;
  }
  return { rows, cols, gap: 4, top: 24, bottom: 44 };
}
