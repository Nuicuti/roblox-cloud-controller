"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  GoogleAuthProvider,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut,
  type User,
} from "firebase/auth";
import {
  Activity,
  AlertTriangle,
  ArrowRight,
  Check,
  ChevronDown,
  ChevronRight,
  CircleHelp,
  Cpu,
  Download,
  Grid2X2,
  Layers3,
  LayoutDashboard,
  Link2,
  LoaderCircle,
  LogOut,
  Maximize2,
  Minimize2,
  Monitor,
  Pencil,
  Plus,
  RefreshCw,
  RotateCcw,
  ScanLine,
  Settings2,
  ShieldCheck,
  SlidersHorizontal,
  Smartphone,
  Square,
  Trash2,
  X,
} from "lucide-react";
import { clientAuth } from "@/lib/firebase-client";
import {
  DEFAULT_REJOIN_CONFIG,
  type Command,
  type Device,
  type HealthState,
  type Profile,
  type RejoinConfig,
  type Result,
  isOnline,
  suggestGrid,
} from "@/lib/contracts";
import { sealAccounts } from "@/lib/seal";

const auth = typeof window !== "undefined" ? clientAuth() : null;

const actionNames: Record<string, string> = {
  scan: "Quét bản clone",
  open_all: "Mở các bản đang bật",
  rejoin_all: "Rejoin tất cả",
  close_all: "Đóng tất cả tab Roblox",
  set_watch: "Auto-rejoin loop (toàn máy)",
  set_rejoin_config: "Lưu cấu hình Auto-rejoin",
  profile: "Lưu cấu hình bản clone",
  open: "Mở ứng dụng",
  close: "Đóng ứng dụng",
  rejoin: "Rejoin vào game",
  remove_profile: "Xóa tab khỏi danh sách",
  restore_profiles: "Khôi phục tab đã xóa",
  fullscreen: "Phóng to toàn màn hình",
  minimize_all: "Tab ra màn hình chính",
  retile: "Về lưới (giữ game)",
  trim_ram: "Dọn RAM & cache ngay",
  optimize_ram: "Áp dụng FPS & Low GFX",
  grid: "Xếp lưới",
  enable_freeform: "Bật cửa sổ tự do",
  restore_windowing: "Khôi phục cửa sổ",
  status: "Cập nhật trạng thái",
  set_accounts: "Gửi tài khoản E2EE",
  login: "Nạp cookie & mở lại tab",
  login_all: "Nạp cookie và mở",
  fill_login: "Điền TK/MK đăng nhập",
  fill_login_all: "Điền TK/MK đăng nhập tất cả",
  self_update: "Tự cập nhật APK",
};

const healthLabels: Record<HealthState, { text: string; tone: "green" | "blue" | "yellow" | "red" | "gray" }> = {
  running: { text: "Đang hoạt động", tone: "green" },
  booting: { text: "Đang khởi động", tone: "blue" },
  teleporting: { text: "Đang chuyển server", tone: "blue" },
  disconnected: { text: "Mất kết nối", tone: "red" },
  window_closed: { text: "Đóng cửa sổ", tone: "red" },
  crashed: { text: "Crash tiến trình", tone: "red" },
  auth_required: { text: "Cần đăng nhập / CAPTCHA thủ công", tone: "yellow" },
  paused_retries: { text: "Tạm nghỉ Retry", tone: "red" },
  stopped: { text: "Đã tắt", tone: "gray" },
};

function sampleDevice(): Device {
  const profiles: Record<string, Profile> = {};
  [1, 2, 3, 4].forEach(n => {
    profiles[String(n)] = {
      package: `free.noka${String.fromCharCode(64 + n)}`,
      label: `Noka ${n}`,
      username: `tai_khoan_${n}`,
      enabled: true,
      autoReopen: true,
      running: n !== 4,
      slot: [3, 4, 1, 2][n - 1],
      hasAccount: true,
      cookieStatus: n === 4 ? "invalid" : "valid",
      sqliteStatus: n === 4 ? "failed" : "written",
      appStatus: n === 4 ? "stopped" : "running",
      loginStatus: n === 4 ? "manual_verification_required" : "unverified_ingame",
      healthState: n === 4 ? "auth_required" : "running",
      healthMessage:
        n === 4
          ? "Phát hiện màn hình đăng nhập hoặc CAPTCHA — tạm dừng tự rejoin để bạn xác minh thủ công."
          : "Đang hoạt động bình thường.",
    };
  });
  return {
    id: "preview-device-only",
    meta: { name: "Redfinger 01 (Mẫu)", ownerUid: "preview" },
    telemetry: {
      lastSeen: Date.now(),
      model: "Redfinger Cloud",
      android: "12",
      watch: true,
      rejoinConfig: { ...DEFAULT_REJOIN_CONFIG, placeId: "1730877806" },
      profiles,
      grid: { rows: 2, cols: 2 },
      freeform: true,
    },
  };
}

export default function Dashboard() {
  const [user, setUser] = useState<User | null>(null);
  const [initializing, setInitializing] = useState(true);
  const [demo, setDemo] = useState(false);
  const [devices, setDevices] = useState<Device[]>([]);
  const [selected, setSelected] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [pending, setPending] = useState(false);
  const [pair, setPair] = useState(false);
  const [credentials, setCredentials] = useState(false);
  const [editing, setEditing] = useState<Profile | null>(null);
  const [editQuickLine, setEditQuickLine] = useState("");
  const [editUsername, setEditUsername] = useState("");
  const [editPassword, setEditPassword] = useState("");
  const [editCookie, setEditCookie] = useState("");
  const [editAutoApplyRestart, setEditAutoApplyRestart] = useState(true);
  const [batchAutoLogin, setBatchAutoLogin] = useState(true);
  const [renamingDevice, setRenamingDevice] = useState<Device | null>(null);
  const [deletingDevice, setDeletingDevice] = useState<Device | null>(null);
  const [batchInput, setBatchInput] = useState("");
  const [rows, setRows] = useState(2);
  const [cols, setCols] = useState(2);
  const [tab, setTab] = useState<"accounts" | "rejoin" | "activity">("accounts");
  const [rejoinDraft, setRejoinDraft] = useState<RejoinConfig>(DEFAULT_REJOIN_CONFIG);
  const [rejoinDirty, setRejoinDirty] = useState(false);
  const [optFpsCap, setOptFpsCap] = useState(0);
  const [optLowGraphics, setOptLowGraphics] = useState(false);
  const [optDirty, setOptDirty] = useState(false);
  const [showRejoinAdvanced, setShowRejoinAdvanced] = useState(false);
  const [showExtraBatch, setShowExtraBatch] = useState(false);

  const current = devices.find(d => d.id === selected) ?? devices[0];
  const profiles = Object.values(current?.telemetry?.profiles ?? {}).sort((a, b) =>
    a.label.localeCompare(b.label, "vi", { numeric: true }),
  );
  const activeCount = profiles.filter(p => p.enabled).length || profiles.length;
  const smartGrid = suggestGrid(activeCount);
  const online = isOnline(current?.telemetry?.lastSeen);
  const results = Object.values(current?.results ?? {}).sort((a, b) => b.createdAt - a.createdAt);
  const hasActiveCommand = results.slice(0, 3).some(r => r.status === "queued" || r.status === "running");

  useEffect(() => {
    const saved = current?.telemetry?.grid;
    if (saved) {
      setRows(saved.rows);
      setCols(saved.cols);
    } else if (activeCount > 0) {
      const next = suggestGrid(activeCount);
      setRows(next.rows);
      setCols(next.cols);
    }
  }, [current?.id]);

  useEffect(() => {
    if (!rejoinDirty && current?.telemetry?.rejoinConfig) {
      setRejoinDraft(current.telemetry.rejoinConfig);
    } else if (!rejoinDirty && current?.telemetry) {
      setRejoinDraft({ ...DEFAULT_REJOIN_CONFIG, enabled: Boolean(current.telemetry.watch) });
    }
  }, [current?.id, current?.telemetry, rejoinDirty]);

  useEffect(() => {
    if (!optDirty && current?.telemetry?.optimization) {
      setOptFpsCap(current.telemetry.optimization.fpsCap);
      setOptLowGraphics(current.telemetry.optimization.lowGraphics);
    }
  }, [current?.id, current?.telemetry?.optimization, optDirty]);

  useEffect(() => {
    if (!auth) {
      setInitializing(false);
      return;
    }
    return onAuthStateChanged(auth, next => {
      setUser(next);
      setInitializing(false);
      if (!next) setDevices([]);
    });
  }, []);

  const api = useCallback(
    async (path: string, payload?: unknown, method?: "GET" | "POST" | "PATCH" | "DELETE") => {
      if (!auth?.currentUser) throw new Error("Bạn cần đăng nhập trước.");
      const token = await auth.currentUser.getIdToken();
      const response = await fetch(path, {
        method: method ?? (payload === undefined ? "GET" : "POST"),
        cache: "no-store",
        headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
        body: payload === undefined ? undefined : JSON.stringify(payload),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.error ?? "Không thực hiện được yêu cầu.");
      return data;
    },
    [],
  );

  const refresh = useCallback(async () => {
    if (demo || !user) return;
    try {
      const data = await api("/api/devices");
      setDevices(data.devices);
      setError("");
    } catch (problem) {
      setError(problem instanceof Error ? problem.message : "Không đọc được dữ liệu máy.");
    }
  }, [api, demo, user]);

  useEffect(() => {
    void refresh();
    const timer = setInterval(() => {
      void refresh();
    }, hasActiveCommand ? 450 : 8_000);
    return () => clearInterval(timer);
  }, [refresh, hasActiveCommand]);

  async function send(command: Command) {
    if (demo || !current) return;
    setPending(true);
    setError("");
    try {
      const queued = (await api("/api/commands", { deviceId: current.id, command })) as {
        id?: string;
        action?: string;
        createdAt?: number;
      };
      if (queued.id) {
        const queuedEntry: Result = {
          id: queued.id,
          action: queued.action ?? command.action,
          createdAt: queued.createdAt ?? Date.now(),
          status: "queued",
        };
        setDevices(prev =>
          prev.map(d =>
            d.id === current.id
              ? { ...d, results: { ...(d.results ?? {}), [queuedEntry.id]: queuedEntry } }
              : d,
          ),
        );
      }
      setNotice(`Đã gửi “${actionNames[command.action] ?? command.action}”. Chờ máy phản hồi trong nhật ký.`);
      setTimeout(() => void refresh(), 220);
      setTimeout(() => void refresh(), 650);
      setTimeout(() => void refresh(), 1200);
    } catch (problem) {
      setError(problem instanceof Error ? problem.message : "Không gửi được lệnh.");
      throw problem;
    } finally {
      setPending(false);
    }
  }

  const issue = (command: Command) => {
    void send(command).catch(() => {});
  };
  const blocked = pending || demo || !current || !online;
  const activePlaceId = rejoinDraft.placeId || current?.telemetry?.rejoinConfig?.placeId || "";

  function parsePlaceIdInput(raw: string): string {
    const trimmed = raw.trim();
    const urlMatch =
      trimmed.match(/\/games\/(\d{1,20})/i) ??
      trimmed.match(/[?&]placeId=(\d{1,20})/i) ??
      trimmed.match(/placeId[=:](\d{1,20})/i);
    if (urlMatch) return urlMatch[1];
    return trimmed.replace(/\D/g, "").slice(0, 20);
  }

  async function saveRejoinConfig(nextConfig: RejoinConfig = rejoinDraft) {
    setRejoinDraft(nextConfig);
    try {
      await send({ action: "set_rejoin_config", config: nextConfig });
      setRejoinDirty(false);
    } catch {}
  }

  async function runOneClickRejoin() {
    if (blocked || !profiles.length) return;
    const saved = current?.telemetry?.rejoinConfig;
    const nextConfig: RejoinConfig = { ...rejoinDraft, enabled: true };
    const needsSave =
      rejoinDirty ||
      !saved ||
      !saved.enabled ||
      !current?.telemetry?.watch ||
      saved.placeId !== nextConfig.placeId ||
      saved.jobId !== nextConfig.jobId;
    try {
      if (needsSave) {
        setRejoinDraft(nextConfig);
        await send({ action: "set_rejoin_config", config: nextConfig });
        setRejoinDirty(false);
      }
      await send({ action: "rejoin_all" });
    } catch {}
  }

  async function trimToFourTabs() {
    if (blocked || profiles.length <= 4) return;
    const extras = [...profiles].sort((a, b) => a.slot - b.slot).slice(4);
    try {
      for (const extra of extras) {
        await send({ action: "remove_profile", package: extra.package });
      }
      setRows(2);
      setCols(2);
    } catch {}
  }

  function cleanCookieValue(raw: string) {
    let cookie = raw.trim();
    if (cookie.startsWith(".ROBLOSECURITY=")) cookie = cookie.slice(".ROBLOSECURITY=".length).trim();
    return cookie.replace(/^["']|["']$/g, "").trim();
  }

  function parseCredentialLine(rawLine: string) {
    const line = rawLine.trim();
    if (line.startsWith("_|WARNING:") || line.startsWith(".ROBLOSECURITY=")) {
      return { username: "", password: "", cookie: cleanCookieValue(line) };
    }
    const parts = line.split(":");
    const username = parts[0]?.trim() || "";
    let password = "";
    let cookie = "";
    if (parts.length >= 3) {
      password = parts[1] ?? "";
      cookie = parts.slice(2).join(":").trim();
    } else if (parts.length === 2) {
      const second = parts[1] ?? "";
      if (second.includes("_|WARNING:") || second.trim().length > 80) {
        cookie = second.trim();
      } else {
        password = second;
      }
    }
    return { username, password, cookie: cleanCookieValue(cookie) };
  }

  function parseBatchLines(text: string) {
    return text
      .split(/\r?\n/)
      .map(l => l.trim())
      .filter(Boolean)
      .map((line, idx) => ({
        ...parseCredentialLine(line),
        line,
        target: profiles[idx],
      }));
  }

  function openEditModal(profile: Profile) {
    setError("");
    setEditQuickLine("");
    setEditUsername(profile.username ?? "");
    setEditPassword("");
    setEditCookie("");
    setEditAutoApplyRestart(true);
    setEditing(profile);
  }

  if (initializing) {
    return (
      <main className="loading">
        <LoaderCircle className="spin" />
        <p>Đang mở bảng điều khiển…</p>
      </main>
    );
  }

  if (!user && !demo) {
    return (
      <main className="login-shell">
        <div className="login-art">
          <Brand />
          <div>
            <span className="eyebrow">CLOUD REJOIN WORKSPACE</span>
            <h1>Một nơi.<br />Mọi máy Redfinger.</h1>
            <p>Quản lý 4–6 clone, chia lưới Freeform và tự động rejoin đa lớp không cần giải CAPTCHA.</p>
            <div className="art-grid">
              {[3, 4, 1, 2].map(n => (
                <div key={n}>
                  <Smartphone size={24} />
                  <span>Clone {n}</span>
                  <i />
                </div>
              ))}
            </div>
          </div>
          <small>
            <ShieldCheck size={15} /> Tài khoản và Cookie được mã hóa E2EE ngay trong trình duyệt trước khi gửi.
          </small>
        </div>
        <div className="login-form">
          <div className="login-card">
            <span className="eyebrow">REJOIN CONTROL</span>
            <h2>Chào mừng trở lại</h2>
            <p>Đăng nhập để điều khiển hệ thống máy Redfinger.</p>
            {!auth ? (
              <div className="setup-note">
                <Settings2 />
                <b>Cần cấu hình biến môi trường Firebase</b>
                <p>Vui lòng cấu hình các biến NEXT_PUBLIC_FIREBASE_* trong cài đặt Vercel.</p>
              </div>
            ) : (
              <>
                <button
                  className="secondary full"
                  disabled={pending}
                  onClick={async () => {
                    setPending(true);
                    setError("");
                    try {
                      const provider = new GoogleAuthProvider();
                      provider.setCustomParameters({ prompt: "select_account" });
                      await signInWithPopup(auth, provider);
                    } catch {
                      setError("Chưa đăng nhập được Google. Kiểm tra cửa sổ pop-up và miền được cấp phép trong Firebase.");
                    } finally {
                      setPending(false);
                    }
                  }}
                >
                  <b>G</b> Tiếp tục với Google
                </button>
                <div className="login-separator">hoặc đăng nhập bằng Email / Mật khẩu</div>
                <form
                  onSubmit={async event => {
                    event.preventDefault();
                    setPending(true);
                    setError("");
                    const fields = new FormData(event.currentTarget);
                    try {
                      await signInWithEmailAndPassword(auth, String(fields.get("email")), String(fields.get("password")));
                    } catch {
                      setError("Không đăng nhập được. Kiểm tra tài khoản và mật khẩu.");
                    } finally {
                      setPending(false);
                    }
                  }}
                >
                  <label>
                    Email quản trị
                    <input name="email" type="email" autoComplete="username" required placeholder="admin@example.com" />
                  </label>
                  <label>
                    Mật khẩu
                    <input name="password" type="password" autoComplete="current-password" required placeholder="••••••••" />
                  </label>
                  <button className="primary full" disabled={pending}>
                    {pending ? <LoaderCircle className="spin" size={17} /> : <>Đăng nhập <ArrowRight size={17} /></>}
                  </button>
                </form>
              </>
            )}
            {error && (
              <p role="alert" className="error-text">
                {error}
              </p>
            )}
            <button
              className="text-button demo-link"
              onClick={() => {
                setDemo(true);
                setDevices([sampleDevice()]);
              }}
            >
              Xem giao diện mẫu <ChevronRight size={14} />
            </button>
            <small className="login-foot">ROBLOX CLOUD CONTROLLER · REDFINGER</small>
          </div>
        </div>
      </main>
    );
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Brand />
        <span className="nav-caption">KHÔNG GIAN LÀM VIỆC</span>
        <button className="nav-item active">
          <LayoutDashboard size={18} /> Điều khiển máy <span>{devices.length}</span>
        </button>
        <div className="side-section">
          <div className="side-heading">
            <span>DANH SÁCH MÁY</span>
            <button title="Ghép máy" onClick={() => setPair(true)} disabled={demo}>
              <Plus size={16} />
            </button>
          </div>
          {devices.map(device => (
            <div key={device.id} className={`device-nav-row ${current?.id === device.id ? "selected" : ""}`}>
              <button
                className="device-nav"
                onClick={() => {
                  setSelected(device.id);
                  setTab("accounts");
                  setRejoinDirty(false);
                }}
              >
                <span className={`dot ${isOnline(device.telemetry?.lastSeen) ? "green" : "gray"}`} />
                <span>
                  {device.meta.name}
                  <small>{Object.keys(device.telemetry?.profiles ?? {}).length} clone</small>
                </span>
                <ChevronRight size={14} />
              </button>
              <button
                type="button"
                className="device-trash"
                title={`Xóa ${device.meta.name}`}
                aria-label={`Xóa ${device.meta.name}`}
                disabled={pending || demo}
                onClick={() => {
                  setError("");
                  setDeletingDevice(device);
                }}
              >
                <Trash2 size={13} />
              </button>
            </div>
          ))}
          {!devices.length && <p className="side-empty">Chưa có máy nào. Bấm (+) để ghép máy.</p>}
        </div>
        <div className="sidebar-bottom">
          <div className="connection-card">
            <ShieldCheck size={18} />
            <div>
              Kết nối an toàn<small>Firebase RTDB · Mã hóa E2EE</small>
            </div>
          </div>
          <button
            className="user-row"
            onClick={() => {
              if (demo) {
                setDemo(false);
                setDevices([]);
              } else if (auth) {
                void signOut(auth);
              }
            }}
          >
            <span className="avatar">{(user?.email ?? "M")[0].toUpperCase()}</span>
            <span>
              {demo ? "Chế độ xem mẫu" : user?.email}
              <small>Đăng xuất</small>
            </span>
            <LogOut size={16} />
          </button>
        </div>
      </aside>

      <div className="workspace">
        <header className="topbar">
          <div className="breadcrumb">
            Không gian làm việc <ChevronRight size={14} />
            <b>Điều khiển máy</b>
          </div>
          <span className="live-label">
            <span className="dot green" /> Tự động cập nhật mỗi 10 giây
          </span>
        </header>

        <main className="content">
          {demo && (
            <div className="demo-banner">
              <CircleHelp size={17} />
              <span>Đây là chế độ xem mẫu với dữ liệu giả lập. Các nút điều khiển máy thật bị vô hiệu hóa.</span>
              <button
                onClick={() => {
                  setDemo(false);
                  setDevices([]);
                }}
              >
                Thoát chế độ mẫu
              </button>
            </div>
          )}

          <div className="page-heading">
            <div>
              <span className="eyebrow">FLEET OVERVIEW</span>
              <h1>Điều khiển Redfinger</h1>
              <p>Quản lý 4–6 clone, chia lưới Freeform và Auto-rejoin đa lớp.</p>
            </div>
            <button className="primary" onClick={() => setPair(true)} disabled={demo}>
              <Plus size={17} /> Ghép máy mới
            </button>
          </div>

          <div className="stats">
            <Stat label="Tổng số máy" value={devices.length} icon={<Monitor size={20} />} />
            <Stat
              label="Máy Online"
              value={devices.filter(d => isOnline(d.telemetry?.lastSeen)).length}
              icon={<Activity size={20} />}
              accent
            />
            <Stat
              label="Tài khoản đã gán"
              value={devices.reduce(
                (total, d) => total + Object.values(d.telemetry?.profiles ?? {}).filter(p => p.hasAccount).length,
                0,
              )}
              icon={<Layers3 size={20} />}
            />
            <Stat
              label="Clone đang mở"
              value={devices.reduce(
                (total, d) =>
                  total +
                  (isOnline(d.telemetry?.lastSeen)
                    ? Object.values(d.telemetry?.profiles ?? {}).filter(p => p.running).length
                    : 0),
                0,
              )}
              icon={<Cpu size={20} />}
            />
          </div>

          {error && (
            <div className="message error" role="alert">
              {error}
              <button onClick={() => setError("")} aria-label="Đóng lỗi">
                <X size={15} />
              </button>
            </div>
          )}
          {notice && (
            <div className="message success" role="status">
              <Check size={16} />
              {notice}
              <button onClick={() => setNotice("")} aria-label="Đóng thông báo">
                <X size={15} />
              </button>
            </div>
          )}

          {!current ? (
            <div className="empty-state">
              <div className="empty-icon">
                <Link2 size={32} />
              </div>
              <h2>Chưa có máy Redfinger nào được ghép</h2>
              <p>
                Cài đặt <code>rejoin-control.apk</code> trên máy Redfinger, mở app và bấm <b>Tạo mã ghép máy</b> để lấy
                UID + mã ghép 10 phút, sau đó nhập vào đây.
              </p>
              <button className="primary" onClick={() => setPair(true)}>
                <Plus size={17} /> Ghép máy mới
              </button>
            </div>
          ) : (
            <>
              <section className="device-header">
                <div className="device-symbol">
                  <Smartphone size={24} />
                </div>
                <div className="device-title">
                  <h2>
                    {current.meta.name}
                    <span className={`badge ${online ? "online" : "offline"}`}>
                      {online ? "Đang kết nối" : "Mất kết nối"}
                    </span>
                  </h2>
                  <p>
                    {current.telemetry?.model ?? "Đang chờ agent"} <span>·</span> Android{" "}
                    {current.telemetry?.android ?? "—"}
                    {current.telemetry?.appVersion ? (
                      <>
                        {" "}
                        <span>·</span> v{current.telemetry.appVersion}
                        {current.telemetry.buildCode ? ` (#${current.telemetry.buildCode})` : ""}
                      </>
                    ) : null}{" "}
                    <span>·</span> {profiles.length} clone <span>·</span> Cập nhật:{" "}
                    {new Date(current.telemetry?.lastSeen ?? 0).toLocaleTimeString("vi-VN")}
                  </p>
                </div>
                <button className="secondary" disabled={blocked} onClick={() => issue({ action: "scan" })}>
                  <ScanLine size={16} /> Quét Clone
                </button>
                <button
                  className="secondary"
                  disabled={pending || demo || !current}
                  onClick={() => issue({ action: "self_update" })}
                  title="Tự tải và cài đè APK mới nhất bằng quyền Root (không mất ghép máy)"
                >
                  <Download size={15} /> Cập nhật APK
                </button>
                <button
                  className="icon-button"
                  disabled={pending || demo}
                  onClick={() => {
                    setError("");
                    setRenamingDevice(current);
                  }}
                  title="Đổi tên máy"
                  aria-label="Đổi tên máy"
                >
                  <Pencil size={15} />
                </button>
                <button
                  className="icon-button danger"
                  disabled={pending || demo}
                  onClick={() => {
                    setError("");
                    setDeletingDevice(current);
                  }}
                  title="Xóa máy khỏi bảng điều khiển"
                  aria-label="Xóa máy khỏi bảng điều khiển"
                >
                  <Trash2 size={15} />
                </button>
                <button
                  className="icon-button"
                  disabled={pending || demo}
                  onClick={() => void refresh()}
                  title="Làm mới dữ liệu"
                >
                  <RefreshCw size={17} />
                </button>
              </section>

              {!online && (
                <div className="offline-note">
                  Máy hiện không trực tuyến hoặc agent chưa chạy. Không thể gửi lệnh mới.
                </div>
              )}

              <div className="watchdog-banner">
                <AlertTriangle size={18} />
                <div>
                  <b>Auto-rejoin Loop đa lớp (Không dùng auto-solve CAPTCHA):</b>
                  <span>
                    {" "}
                    Giám sát Crash bằng 4 lớp độc lập (Task list, Window manager, Surfaces, Screen tree), phát hiện màn
                    hình Disconnect kèm Teleport grace và tự vào lại Place ID qua deep link. Nếu gặp màn hình đăng nhập
                    hoặc CAPTCHA, hệ thống sẽ tự tạm dừng tab đó để bạn xác minh thủ công.
                  </span>
                </div>
              </div>

              {current.telemetry?.memory && (() => {
                const mem = current.telemetry.memory;
                const pct = mem.totalMb > 0 ? Math.min(100, Math.round((mem.usedMb / mem.totalMb) * 100)) : 0;
                const swapUsedMb = Math.max(0, (mem.swapTotalMb ?? 0) - (mem.swapFreeMb ?? 0));
                const color = mem.lowMemory || pct >= 88 ? "#f29494" : pct >= 75 ? "#f2cb73" : "#b6f273";
                const fmtGb = (mb: number) => `${(mb / 1024).toFixed(2)} GB`;
                return (
                  <div
                    style={{
                      background: "#111620",
                      border: "1px solid #252d3a",
                      borderRadius: 10,
                      padding: "12px 16px",
                      marginBottom: 16,
                      display: "grid",
                      gap: 10,
                    }}
                  >
                    <div
                      style={{
                        display: "flex",
                        flexWrap: "wrap",
                        alignItems: "center",
                        justifyContent: "space-between",
                        gap: 8,
                        fontSize: 12,
                      }}
                    >
                      <span>
                        <b>RAM máy:</b>{" "}
                        <span style={{ color }}>
                          {fmtGb(mem.usedMb)} / {fmtGb(mem.totalMb)} ({pct}%)
                        </span>{" "}
                        · Còn trống <b>{fmtGb(mem.availMb)}</b>
                        {(mem.swapTotalMb ?? 0) > 0 ? (
                          <span style={{ color: "#8e96a8" }}>
                            {" "}
                            · ZRAM/Swap: {fmtGb(swapUsedMb)} / {fmtGb(mem.swapTotalMb ?? 0)}
                          </span>
                        ) : null}
                      </span>
                      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                        {current.telemetry.streamConnected !== undefined && (
                          <span className={`status-pill ${current.telemetry.streamConnected ? "green" : "gray"}`}>
                            {current.telemetry.streamConnected ? "SSE Tức thì" : "Poll"}
                          </span>
                        )}
                        <span style={{ color: "#8e96a8", fontSize: 11 }}>
                          CPU: {mem.cpuCores ?? "?"} nhân
                          {typeof mem.load1m === "number" ? ` · Tải: ${mem.load1m.toFixed(2)}` : ""}
                          {mem.lowMemory ? " · CẢNH BÁO THIẾU RAM" : ""}
                        </span>
                        <button
                          type="button"
                          className="secondary compact"
                          disabled={blocked || !profiles.length}
                          onClick={() => issue({ action: "trim_ram" })}
                          title="Gửi trim-memory cho các tab đang chạy và dọn cache rác (giữ nguyên game & cookie)"
                        >
                          Dọn RAM ngay
                        </button>
                      </div>
                    </div>
                    <div style={{ height: 6, borderRadius: 99, background: "#1d2432", overflow: "hidden" }}>
                      <div style={{ width: `${pct}%`, height: "100%", background: color, transition: "width 0.3s" }} />
                    </div>
                    <div
                      style={{
                        display: "flex",
                        flexWrap: "wrap",
                        alignItems: "center",
                        gap: 10,
                        fontSize: 11,
                        paddingTop: 2,
                      }}
                    >
                      <label style={{ display: "flex", alignItems: "center", gap: 6, margin: 0 }}>
                        <span>FPS (ClientAppSettings):</span>
                        <select
                          value={optFpsCap}
                          onChange={e => {
                            setOptDirty(true);
                            setOptFpsCap(Number(e.target.value));
                          }}
                          style={{ width: "auto", marginTop: 0, padding: "4px 8px", fontSize: 11 }}
                        >
                          <option value={0}>Mặc định Noka (20 FPS)</option>
                          <option value={15}>15 FPS (Nhẹ AFK)</option>
                          <option value={20}>20 FPS</option>
                          <option value={30}>30 FPS</option>
                          <option value={60}>60 FPS</option>
                        </select>
                      </label>
                      <label className="checkbox-label" style={{ margin: 0, fontSize: 11 }}>
                        <input
                          type="checkbox"
                          checked={optLowGraphics}
                          onChange={e => {
                            setOptDirty(true);
                            setOptLowGraphics(e.target.checked);
                          }}
                        />
                        <span>Tắt PostFX / Bóng đổ</span>
                      </label>
                      <button
                        type="button"
                        className="secondary compact"
                        disabled={blocked || !profiles.length}
                        onClick={() => {
                          setOptDirty(false);
                          issue({ action: "optimize_ram", fpsCap: optFpsCap, lowGraphics: optLowGraphics });
                        }}
                        title="Ghi ClientAppSettings.json tối ưu FPS & đồ họa (bấm Rejoin tất cả để game nhận)"
                      >
                        Áp dụng
                      </button>
                      <button
                        type="button"
                        className="text-button"
                        disabled={blocked || !profiles.length}
                        onClick={() => {
                          setOptDirty(false);
                          setOptFpsCap(0);
                          setOptLowGraphics(false);
                          issue({ action: "optimize_ram", fpsCap: 0, lowGraphics: false });
                        }}
                        title="Xóa file ClientAppSettings ghi đè để Delta/Noka chạy lại đúng như gốc"
                      >
                        Khôi phục gốc Noka
                      </button>
                      <label style={{ display: "flex", alignItems: "center", gap: 6, margin: 0, marginLeft: "auto" }}>
                        <span>Tự Rejoin tab khi RAM &gt;:</span>
                        <select
                          value={rejoinDraft.maxRamMb}
                          disabled={blocked}
                          onChange={e => {
                            const nextMax = Number(e.target.value);
                            setRejoinDirty(false);
                            void saveRejoinConfig({ ...rejoinDraft, maxRamMb: nextMax });
                          }}
                          style={{ width: "auto", marginTop: 0, padding: "4px 8px", fontSize: 11 }}
                        >
                          <option value={0}>Tắt</option>
                          <option value={900}>900 MB</option>
                          <option value={1100}>1100 MB</option>
                          <option value={1300}>1300 MB</option>
                          <option value={1500}>1500 MB</option>
                          <option value={1800}>1800 MB</option>
                        </select>
                      </label>
                    </div>
                    {current.telemetry.watch && current.telemetry.accessibilityEnabled === false && (
                      <div style={{ fontSize: 11, color: "#f2cb73" }}>
                        Gợi ý chống khựng: Hãy mở app <b>Rejoin Control</b> trên Redfinger → <b>Mở cài đặt Accessibility</b>{" "}
                        và bật quyền cho <i>Rejoin Control</i>.
                      </div>
                    )}
                  </div>
                );
              })()}

              <div className="main-columns">
                <div className="main-panel">
                  <div className="panel-tabs">
                    <button className={tab === "accounts" ? "chosen" : ""} onClick={() => setTab("accounts")}>
                      Clone & Trạng thái <span>{profiles.length}</span>
                    </button>
                    <button className={tab === "rejoin" ? "chosen" : ""} onClick={() => setTab("rejoin")}>
                      Auto-Rejoin Loop
                    </button>
                    <button className={tab === "activity" ? "chosen" : ""} onClick={() => setTab("activity")}>
                      Nhật ký lệnh
                    </button>
                  </div>

                  {tab === "accounts" ? (
                    <>
                      <div className="panel-toolbar" style={{ flexWrap: "wrap", gap: 10 }}>
                        <div>
                          <h3>Danh sách bản clone trên máy</h3>
                          <p>Mỗi bản clone có cấu hình tài khoản, vị trí ô và trạng thái watchdog riêng biệt.</p>
                        </div>
                        <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 8 }}>
                          {profiles.length > 4 && (
                            <button
                              className="secondary compact"
                              style={{ color: "#f2cb73", borderColor: "#f2cb7344" }}
                              disabled={blocked}
                              onClick={() => void trimToFourTabs()}
                              title="Tắt hẳn và xóa 2 tab cuối để chạy 4 tab lưới 2×2 nhẹ RAM hơn"
                            >
                              <Trash2 size={14} /> Bỏ bớt còn 4 tab (2×2)
                            </button>
                          )}
                          {(current.telemetry?.excludedCount ?? 0) > 0 && (
                            <button
                              className="text-button"
                              disabled={blocked}
                              onClick={() => issue({ action: "restore_profiles" })}
                              title="Quét và hiện lại các tab đã xóa trước đó"
                            >
                              Khôi phục {current.telemetry?.excludedCount} tab đã xóa
                            </button>
                          )}
                          <button
                            className="secondary compact"
                            disabled={blocked || !profiles.length || !current.telemetry?.publicKey}
                            onClick={() => {
                              setBatchInput("");
                              setCredentials(true);
                            }}
                          >
                            <Plus size={15} /> Nhập hàng loạt tài khoản
                          </button>
                        </div>
                      </div>

                      <div className="account-list">
                        {profiles.map(profile => {
                          const hs = profile.healthState ? healthLabels[profile.healthState] : null;
                          return (
                            <div key={profile.package} className="account-row">
                              <div className="noka-icon">{profile.label.match(/\d+/)?.[0] ?? "R"}</div>
                              <div className="account-info">
                                <b>
                                  {profile.label}{" "}
                                  <small style={{ display: "inline", color: "#8a96a8" }}>({profile.package})</small>
                                </b>
                                <span>
                                  Tài khoản: <strong>{profile.username || "Chưa gán tài khoản"}</strong> · Ô:{" "}
                                  {profile.slot}
                                  {profile.placeId ? ` · Place: ${profile.placeId}` : ""}
                                </span>

                                <div className="status-badges">
                                  {profile.cookieStatus === "valid" ? (
                                    <span className="status-pill green">Cookie: Hợp lệ</span>
                                  ) : profile.cookieStatus === "invalid" ? (
                                    <span className="status-pill red">Cookie: Lỗi/Hết hạn</span>
                                  ) : (profile.hasCookie ?? profile.hasAccount) ? (
                                    <span className="status-pill blue">Cookie: Đã lưu trên máy</span>
                                  ) : (
                                    <span className="status-pill gray">Cookie: Chưa có</span>
                                  )}

                                  {profile.hasPassword && (
                                    <span className="status-pill blue">Mật khẩu: Đã lưu</span>
                                  )}

                                  {profile.sqliteStatus === "written" ? (
                                    <span className="status-pill green">SQLite: Đã nạp</span>
                                  ) : profile.sqliteStatus === "failed" ? (
                                    <span className="status-pill red">SQLite: Lỗi ghi</span>
                                  ) : null}

                                  <span className={`status-pill ${profile.running && online ? "green" : "gray"}`}>
                                    Tiến trình: {profile.running && online ? "Đang chạy" : "Đã tắt"}
                                  </span>

                                  {profile.running && online && profile.inGameOnline === true && (
                                    <span className="status-pill green">Online trong game</span>
                                  )}

                                  {profile.running &&
                                    online &&
                                    profile.inGameOnline === false &&
                                    profile.healthState !== "booting" &&
                                    profile.healthState !== "disconnected" && (
                                      <span className="status-pill yellow">Ở sảnh / Chưa vào server</span>
                                    )}

                                  {profile.running && online && typeof profile.ramMb === "number" && (
                                    <span
                                      className={`status-pill ${
                                        profile.ramMb >= 1000 ? "red" : profile.ramMb >= 650 ? "yellow" : "green"
                                      }`}
                                    >
                                      RAM: {profile.ramMb} MB
                                    </span>
                                  )}

                                  {hs && <span className={`status-pill ${hs.tone}`}>{hs.text}</span>}

                                  <span className={`status-pill ${profile.autoReopen !== false ? "blue" : "gray"}`}>
                                    Tự mở lại: {profile.autoReopen !== false ? "Bật" : "Tắt"}
                                  </span>

                                  {typeof profile.retryCount === "number" && profile.retryCount > 0 && (
                                    <span className="status-pill gray">Thử lại: {profile.retryCount}</span>
                                  )}
                                </div>

                                {profile.error && <small style={{ color: "#f29494" }}>Lỗi: {profile.error}</small>}
                                {profile.healthMessage && !profile.error && <small>{profile.healthMessage}</small>}
                                {profile.loginMessage && !profile.error && !profile.healthMessage && (
                                  <small>{profile.loginMessage}</small>
                                )}
                              </div>

                              <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 6, justifyContent: "flex-end" }}>
                                <button className="text-button" disabled={blocked} onClick={() => openEditModal(profile)}>
                                  Import / Sửa
                                </button>
                                <button
                                  className="row-action"
                                  disabled={blocked || !(profile.hasCookie ?? profile.hasAccount)}
                                  onClick={() => issue({ action: "login", package: profile.package })}
                                  title={
                                    profile.hasCookie ?? profile.hasAccount
                                      ? "Nạp cookie vào SQLite rồi tắt mở lại riêng tab này"
                                      : "Chưa lưu cookie cho tab này"
                                  }
                                >
                                  <ShieldCheck size={13} /> Nạp CK & Mở
                                </button>
                                <button
                                  className="row-action"
                                  disabled={blocked || !profile.hasPassword}
                                  onClick={() => issue({ action: "fill_login", package: profile.package })}
                                  title={
                                    profile.hasPassword
                                      ? "Thử điền TK/MK khi đã mở sẵn ô nhập"
                                      : "Chưa lưu mật khẩu cho tab này"
                                  }
                                >
                                  Điền ĐN
                                </button>
                                <button
                                  className="row-action"
                                  disabled={blocked}
                                  onClick={() => issue({ action: "fullscreen", package: profile.package })}
                                  title="Phóng to riêng tab này toàn màn hình để kiểm tra (không tắt game đang chạy)"
                                >
                                  <Maximize2 size={13} /> Toàn MH
                                </button>
                                <button
                                  className="row-action"
                                  disabled={blocked}
                                  onClick={() => issue({ action: "rejoin", package: profile.package })}
                                  title="Giữ nguyên nếu đang online; tự bấm Leave & join lại nếu bị kick; hoặc join thẳng nếu đang ở sảnh"
                                >
                                  <RotateCcw size={13} /> Rejoin
                                </button>
                                <button
                                  className="row-action"
                                  disabled={blocked}
                                  onClick={() =>
                                    issue({ action: profile.running ? "close" : "open", package: profile.package })
                                  }
                                >
                                  {profile.running ? <Square size={13} /> : <ArrowRight size={15} />}
                                  {profile.running ? "Tắt" : "Mở"}
                                </button>
                                <button
                                  className="row-action"
                                  style={{ color: "#f29494", borderColor: "#f2949433" }}
                                  disabled={blocked}
                                  onClick={() => issue({ action: "remove_profile", package: profile.package })}
                                  title="Tắt hẳn và xóa tab này khỏi danh sách quản lý (giảm RAM/chống crash)"
                                >
                                  <Trash2 size={13} /> Xóa
                                </button>
                              </div>
                            </div>
                          );
                        })}
                      </div>

                      {!profiles.length && (
                        <div className="inline-empty">
                          Chọn “Quét Clone” để nhận diện các bản clone đã cài trên máy Redfinger.
                        </div>
                      )}

                      <div className="panel-footer">
                        <ShieldCheck size={15} />
                        <span>
                          Bảo mật: Mật khẩu và Cookie được mã hóa bằng cặp khóa RSA-2048 / AES-GCM của thiết bị. Chỉ
                          máy đích giải mã được.
                        </span>
                      </div>
                    </>
                  ) : tab === "rejoin" ? (
                    <form
                      className="rejoin-panel"
                      onSubmit={event => {
                        event.preventDefault();
                        void runOneClickRejoin();
                      }}
                    >
                      <div className="rejoin-header">
                        <div className="watch-heading">
                          <span className="watch-icon">
                            <SlidersHorizontal size={18} />
                          </span>
                          <div>
                            <h3>
                              Auto-rejoin loop <span className="status-pill blue">ALL CLONES</span>
                            </h3>
                            <p>
                              Nhập Place ID (hoặc dán link Roblox) rồi bấm 1 nút Rejoin tất cả bên dưới; không dùng
                              auto-solve CAPTCHA.
                            </p>
                          </div>
                        </div>
                        <button
                          type="button"
                          role="switch"
                          aria-checked={rejoinDraft.enabled}
                          className={`switch ${rejoinDraft.enabled ? "on" : ""}`}
                          onClick={() => {
                            const nextConfig = { ...rejoinDraft, enabled: !rejoinDraft.enabled };
                            setRejoinDirty(true);
                            setRejoinDraft(nextConfig);
                            if (!blocked) void saveRejoinConfig(nextConfig);
                          }}
                        >
                          <span />
                        </button>
                      </div>

                      <div className="rejoin-target-grid">
                        <label>
                          Place ID (hoặc dán link game Roblox)
                          <input
                            value={rejoinDraft.placeId}
                            pattern="\d{0,20}"
                            placeholder="Ví dụ: 1730877806 hoặc dán link Roblox"
                            onChange={e => {
                              setRejoinDirty(true);
                              setRejoinDraft(prev => ({
                                ...prev,
                                placeId: parsePlaceIdInput(e.target.value),
                              }));
                            }}
                          />
                        </label>
                        <label>
                          Job ID / Server ID (Tùy chọn)
                          <input
                            value={rejoinDraft.jobId}
                            maxLength={64}
                            placeholder="Để trống để vào server bất kỳ"
                            onChange={e => {
                              setRejoinDirty(true);
                              setRejoinDraft(prev => ({
                                ...prev,
                                jobId: e.target.value.replace(/[^A-Za-z0-9-]/g, "").slice(0, 64),
                              }));
                            }}
                          />
                        </label>
                      </div>

                      <button type="submit" className="primary full" disabled={blocked || !profiles.length}>
                        <RotateCcw size={16} /> Rejoin tất cả{rejoinDraft.placeId ? ` vào ${rejoinDraft.placeId}` : ""}
                      </button>
                      <p className="muted" style={{ fontSize: 11, marginTop: -8 }}>
                        1 nút làm trọn gói: lưu Place ID, bật Auto-rejoin watchdog và đóng/mở lại toàn bộ clone đang bật.
                      </p>

                      <button
                        type="button"
                        className="text-button"
                        style={{ justifyContent: "flex-start", paddingLeft: 0 }}
                        onClick={() => setShowRejoinAdvanced(prev => !prev)}
                      >
                        {showRejoinAdvanced ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                        Tùy chỉnh nâng cao (Thời gian chờ & cảm biến watchdog)
                      </button>

                      {showRejoinAdvanced && (
                        <>
                          <div className="rejoin-sliders">
                            <SliderField
                              label="Check interval"
                              unit="s"
                              min={3}
                              max={60}
                              value={rejoinDraft.checkIntervalSec}
                              hint="Chu kỳ đọc trạng thái app, cửa sổ và tiến trình"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, checkIntervalSec: v }));
                              }}
                            />
                            <SliderField
                              label="Delay after launch"
                              unit="s"
                              min={0}
                              max={120}
                              value={rejoinDraft.delayAfterLaunchSec}
                              hint="Thời gian chờ tối thiểu trước khi mở lại cùng 1 bản clone"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, delayAfterLaunchSec: v }));
                              }}
                            />
                            <SliderField
                              label="Delay between clones"
                              unit="s"
                              min={0}
                              max={60}
                              value={rejoinDraft.delayBetweenClonesSec}
                              hint="Giãn cách khi khởi chạy lần lượt giữa các bản clone"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, delayBetweenClonesSec: v }));
                              }}
                            />
                            <SliderField
                              label="Boot grace"
                              unit="s"
                              min={10}
                              max={300}
                              value={rejoinDraft.bootGraceSec}
                              hint="Thời gian chờ clone tải game và mở cửa sổ sau khi khởi chạy"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, bootGraceSec: v }));
                              }}
                            />
                            <SliderField
                              label="Window grace"
                              unit="s"
                              min={10}
                              max={300}
                              value={rejoinDraft.windowGraceSec}
                              hint="Mất cửa sổ quá lâu sẽ tính là crash; bỏ qua khi dump lỗi"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, windowGraceSec: v }));
                              }}
                            />
                            <SliderField
                              label="Disconnect confirm"
                              unit="s"
                              min={2}
                              max={60}
                              value={rejoinDraft.disconnectConfirmSec}
                              hint="Màn hình mất kết nối phải giữ đủ thời gian này mới rejoin"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, disconnectConfirmSec: v }));
                              }}
                            />
                            <SliderField
                              label="Network UDP timeout"
                              unit="s"
                              min={0}
                              max={300}
                              value={rejoinDraft.networkTimeoutSec}
                              zeroLabel="Tắt"
                              hint="Kiểm tra socket UDP theo UID (thay thế heartbeat; 0 = tắt)"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, networkTimeoutSec: v }));
                              }}
                            />
                            <SliderField
                              label="Max rejoin interval"
                              unit="s"
                              min={30}
                              max={900}
                              value={rejoinDraft.maxRejoinIntervalSec}
                              hint="Khoảng nghỉ tối đa giữa hai lần thử lại"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, maxRejoinIntervalSec: v }));
                              }}
                            />
                            <SliderField
                              label="Retry attempts"
                              unit="lần"
                              min={0}
                              max={50}
                              value={rejoinDraft.retryAttempts}
                              zeroLabel="Không giới hạn"
                              hint="0 = thử lại không giới hạn khi mất kết nối/crash"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, retryAttempts: v }));
                              }}
                            />
                            <SliderField
                              label="Backoff"
                              unit="s"
                              min={5}
                              max={120}
                              value={rejoinDraft.backoffSec}
                              hint="Thời gian nghỉ cơ bản, nhân đôi sau mỗi lần lỗi liên tiếp"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, backoffSec: v }));
                              }}
                            />
                            <SliderField
                              label="Auto-Rejoin tràn RAM"
                              unit=" MB"
                              min={0}
                              max={3000}
                              zeroLabel="Tắt"
                              value={rejoinDraft.maxRamMb}
                              hint="Tự đóng & mở lại riêng tab bị leak RAM quá ngưỡng (0 = tắt)"
                              onChange={v => {
                                setRejoinDirty(true);
                                setRejoinDraft(p => ({ ...p, maxRamMb: v }));
                              }}
                            />
                          </div>

                          <div className="rejoin-toggles">
                            <div className="rejoin-toggle-box">
                              <div className="watch-heading">
                                <div>
                                  <h4>Teleport detection</h4>
                                  <p>Bỏ qua ngắt kết nối ngắn ngay sau khi người chơi chuyển server.</p>
                                </div>
                                <button
                                  type="button"
                                  role="switch"
                                  aria-checked={rejoinDraft.teleportDetection}
                                  className={`switch ${rejoinDraft.teleportDetection ? "on" : ""}`}
                                  onClick={() => {
                                    setRejoinDirty(true);
                                    setRejoinDraft(p => ({ ...p, teleportDetection: !p.teleportDetection }));
                                  }}
                                >
                                  <span />
                                </button>
                              </div>
                              {rejoinDraft.teleportDetection && (
                                <SliderField
                                  label="Grace period"
                                  unit="s"
                                  min={10}
                                  max={300}
                                  value={rejoinDraft.teleportGraceSec}
                                  hint="Thời gian miễn kiểm tra disconnect sau khi chuyển server"
                                  onChange={v => {
                                    setRejoinDirty(true);
                                    setRejoinDraft(p => ({ ...p, teleportGraceSec: v }));
                                  }}
                                />
                              )}
                            </div>

                            <div className="rejoin-toggle-box">
                              <div className="watch-heading">
                                <div>
                                  <h4>Rejoin on crash (4 lớp kiểm tra)</h4>
                                  <p>
                                    Phát hiện tiến trình chết hoặc cửa sổ biến mất khi cả 4 lớp (task list, window
                                    manager, surfaces, screen tree) đồng thuận.
                                  </p>
                                </div>
                                <button
                                  type="button"
                                  role="switch"
                                  aria-checked={rejoinDraft.rejoinOnCrash}
                                  className={`switch ${rejoinDraft.rejoinOnCrash ? "on" : ""}`}
                                  onClick={() => {
                                    setRejoinDirty(true);
                                    setRejoinDraft(p => ({ ...p, rejoinOnCrash: !p.rejoinOnCrash }));
                                  }}
                                >
                                  <span />
                                </button>
                              </div>
                            </div>

                            <div className="rejoin-toggle-box">
                              <div className="watch-heading">
                                <div>
                                  <h4>Phát hiện màn hình Disconnect & CAPTCHA</h4>
                                  <p>
                                    Quét cây UI có giới hạn timeout để rejoin khi Disconnected; tự tạm dừng khi gặp màn
                                    hình đăng nhập hoặc CAPTCHA.
                                  </p>
                                </div>
                                <button
                                  type="button"
                                  role="switch"
                                  aria-checked={rejoinDraft.detectDisconnectUi}
                                  className={`switch ${rejoinDraft.detectDisconnectUi ? "on" : ""}`}
                                  onClick={() => {
                                    setRejoinDirty(true);
                                    setRejoinDraft(p => ({ ...p, detectDisconnectUi: !p.detectDisconnectUi }));
                                  }}
                                >
                                  <span />
                                </button>
                              </div>
                            </div>
                          </div>

                          <div className="rejoin-actions">
                            <button
                              type="button"
                              className="secondary"
                              onClick={() => {
                                setRejoinDraft(current.telemetry?.rejoinConfig ?? DEFAULT_REJOIN_CONFIG);
                                setRejoinDirty(false);
                              }}
                            >
                              Hoàn tác
                            </button>
                            <button
                              type="button"
                              className="secondary"
                              disabled={blocked}
                              onClick={() => void saveRejoinConfig()}
                            >
                              <Check size={16} /> Chỉ lưu cấu hình
                            </button>
                          </div>
                        </>
                      )}
                    </form>
                  ) : (
                    <div className="command-list">
                      {results.length ? (
                        results.map(result => <CommandRow key={result.id} result={result} />)
                      ) : (
                        <div className="inline-empty">Chưa có lệnh nào được gửi đến máy.</div>
                      )}
                    </div>
                  )}
                </div>

                <aside className="controls-column">
                  <section className="panel">
                    <div className="control-title">
                      <Grid2X2 size={18} />
                      <h3>Sắp xếp cửa sổ</h3>
                    </div>
                    <p className="muted">
                      Xếp các tab đang chạy về lưới khít màn hình mà <b>không tắt game</b>; chỉ mở thêm các tab chưa chạy.
                    </p>
                    <div className="grid-preview" style={{ gridTemplateColumns: `repeat(${cols}, 1fr)` }}>
                      {Array.from({ length: rows * cols }, (_, i) => (
                        <div key={i}>{profiles.find(p => p.slot === i + 1)?.label ?? <span>Ô {i + 1}</span>}</div>
                      ))}
                    </div>
                    <div className="grid-fields">
                      <label>
                        Số hàng
                        <select value={rows} onChange={e => setRows(Number(e.target.value))}>
                          {[1, 2, 3, 4].map(n => (
                            <option key={n}>{n}</option>
                          ))}
                        </select>
                      </label>
                      <label>
                        Số cột
                        <select value={cols} onChange={e => setCols(Number(e.target.value))}>
                          {[1, 2, 3, 4].map(n => (
                            <option key={n}>{n}</option>
                          ))}
                        </select>
                      </label>
                    </div>
                    <div className="grid-presets">
                      <button
                        type="button"
                        className="secondary compact"
                        onClick={() => {
                          setRows(2);
                          setCols(3);
                        }}
                      >
                        2×3 (Dễ nhìn)
                      </button>
                      <button
                        type="button"
                        className="secondary compact"
                        onClick={() => {
                          setRows(2);
                          setCols(2);
                        }}
                      >
                        2×2 (4 acc)
                      </button>
                      <button
                        type="button"
                        className="secondary compact"
                        onClick={() => {
                          setRows(3);
                          setCols(2);
                        }}
                      >
                        3×2 (6 acc)
                      </button>
                    </div>
                    {rows >= 3 && (
                      <p className="muted" style={{ fontSize: 10, marginTop: -4, marginBottom: 12 }}>
                        Màn hình ngang Redfinger nên ưu tiên <b>2×3 (Dễ nhìn)</b> để chiều cao mỗi ô lớn hơn, không bị
                        cắt nút Đăng nhập.
                      </p>
                    )}
                    <button
                      className="primary full"
                      disabled={blocked || !profiles.length}
                      onClick={() =>
                        issue({
                          action: "grid",
                          rows,
                          cols,
                          gap: 4,
                          top: 18,
                          bottom: 48,
                          smart: rows === smartGrid.rows && cols === smartGrid.cols,
                        })
                      }
                      title="Xếp tất cả tab vào lưới mà không tắt các tab đang chạy online"
                    >
                      <Grid2X2 size={16} /> Sắp xếp lưới {rows}×{cols} (Giữ game)
                    </button>
                    <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 6, marginTop: 8 }}>
                      <button
                        type="button"
                        className="secondary compact"
                        disabled={blocked || !profiles.length}
                        onClick={() => issue({ action: "retile" })}
                        title="Xếp lại các tab đang chạy về lưới hiện tại mà không tắt game"
                      >
                        <Grid2X2 size={13} /> Về lưới
                      </button>
                      <button
                        type="button"
                        className="secondary compact"
                        disabled={blocked || !profiles.length}
                        onClick={() => issue({ action: "minimize_all" })}
                        title="Ẩn toàn bộ tab Noka xuống màn hình chính (giữ nguyên game)"
                      >
                        <Minimize2 size={13} /> Tab ra ngoài
                      </button>
                    </div>
                    <button
                      type="button"
                      className="secondary compact full"
                      style={{ marginTop: 8, color: "#f29494", borderColor: "#f2949444" }}
                      disabled={blocked || !profiles.length}
                      onClick={() => issue({ action: "close_all" })}
                      title="Đóng toàn bộ tab Noka/Roblox đang chạy và tạm dừng tự mở lại"
                    >
                      <Square size={13} /> Đóng tất cả tab Roblox
                    </button>
                    <button
                      className="text-button full freeform-button"
                      disabled={blocked}
                      onClick={() => issue({ action: "enable_freeform" })}
                    >
                      Bật cửa sổ tự do
                    </button>
                    <button
                      className="text-button full"
                      disabled={blocked}
                      onClick={() => issue({ action: "restore_windowing" })}
                      title="Đóng các clone đang kẹt, xóa lưới và khôi phục mở cửa sổ bình thường"
                    >
                      Khôi phục cửa sổ (Sửa kẹt)
                    </button>
                  </section>

                  <section className="panel">
                    <div className="watch-heading">
                      <span className="watch-icon">
                        <RefreshCw size={18} />
                      </span>
                      <div>
                        <h3>Auto-rejoin loop</h3>
                        <p>{current.telemetry?.watch ? "Watchdog đa lớp đang bật" : "Watchdog đang tắt"}</p>
                      </div>
                      <button
                        role="switch"
                        aria-checked={Boolean(current.telemetry?.watch)}
                        aria-label="Auto-rejoin loop"
                        className={`switch ${current.telemetry?.watch ? "on" : ""}`}
                        disabled={blocked}
                        onClick={() => issue({ action: "set_watch", enabled: !current.telemetry?.watch })}
                      >
                        <span />
                      </button>
                    </div>
                    <p className="muted">
                      {activePlaceId ? (
                        <>
                          Place ID: <b style={{ color: "#dce4f2" }}>{activePlaceId}</b> · Giữ tab online; tự bấm{" "}
                          <b>Leave</b> khi bị kick để join lại.
                        </>
                      ) : (
                        <>
                          Chưa đặt Place ID · Giữ tab online; tự bấm <b>Leave</b> khi bị kick để join lại.
                        </>
                      )}
                    </p>
                    <button
                      className="primary full"
                      style={{ marginTop: 12 }}
                      disabled={blocked || !profiles.length}
                      onClick={() => void runOneClickRejoin()}
                      title="Giữ nguyên tab đang online; nếu hiện chữ Leave/Disconnected sẽ tự bấm Leave để join lại; tab ở sảnh/tắt sẽ vào game"
                    >
                      <RotateCcw size={16} /> Rejoin thông minh{activePlaceId ? ` (${activePlaceId})` : ""}
                    </button>
                    <button className="text-button full freeform-button" onClick={() => setTab("rejoin")}>
                      <SlidersHorizontal size={14} /> Đổi Place ID / Nâng cao
                    </button>
                  </section>

                  <button className="text-button full" onClick={() => setShowExtraBatch(prev => !prev)}>
                    {showExtraBatch ? <ChevronDown size={14} /> : <ChevronRight size={14} />} Thao tác phụ khác
                  </button>

                  {showExtraBatch && (
                    <>
                      <button
                        className="secondary full"
                        disabled={blocked || !profiles.length}
                        onClick={() => issue({ action: "rejoin_all", force: true })}
                        title="Buộc tắt hẳn (force-stop) tất cả tab đang bật rồi mở lại từ đầu"
                      >
                        <RotateCcw size={16} /> Tắt & Mở lại tất cả (Force)
                      </button>
                      <button className="secondary full" disabled={blocked} onClick={() => issue({ action: "open_all" })}>
                        <Layers3 size={16} /> Mở tất cả đang bật
                      </button>
                      <button
                        className="secondary full"
                        disabled={blocked || !profiles.some(p => p.hasCookie ?? p.hasAccount)}
                        onClick={() => issue({ action: "login_all" })}
                      >
                        <ShieldCheck size={16} /> Nạp cookie và mở
                      </button>
                      <button
                        className="secondary full"
                        disabled={blocked || !profiles.some(p => p.hasPassword)}
                        onClick={() => issue({ action: "fill_login_all" })}
                        title="Tự bấm Đăng nhập và điền tài khoản/mật khẩu cho các bản đang bật (tạm dừng nếu gặp CAPTCHA)"
                      >
                        <ShieldCheck size={16} /> Điền TK & MK đăng nhập
                      </button>
                    </>
                  )}
                </aside>
              </div>

              {results[0] && tab !== "activity" && (
                <section className="latest-command">
                  <div>
                    <Activity size={16} />
                    <b>Lệnh gần nhất</b>
                  </div>
                  <CommandRow result={results[0]} />
                </section>
              )}
            </>
          )}

          <footer className="page-footer">
            <span>CLOUD REJOIN CONTROL <i /> roblox-cloud-farm</span>
            <span>Redfinger · Android APK · Vercel · Firebase RTDB</span>
          </footer>
        </main>
      </div>

      {pair && (
        <Modal title="Ghép máy Redfinger mới" onClose={() => setPair(false)}>
          <p className="muted">
            Tải và cài{" "}
            <a
              href="https://github.com/Nuicuti/roblox-cloud-controller/releases/latest/download/rejoin-control.apk"
              target="_blank"
              rel="noreferrer"
              style={{ color: "#b6f273", textDecoration: "underline" }}
            >
              rejoin-control.apk
            </a>{" "}
            trên Redfinger, mở app và bấm <b>Tạo mã ghép máy</b> để lấy UID + Mã ghép 10 phút.
          </p>
          <form
            onSubmit={async event => {
              event.preventDefault();
              setPending(true);
              setError("");
              const fields = new FormData(event.currentTarget);
              try {
                await api("/api/pair", Object.fromEntries(fields));
                setPair(false);
                setNotice("Đã ghép máy thành công. Đang đồng bộ trạng thái...");
                void refresh();
              } catch (problem) {
                setError(problem instanceof Error ? problem.message : "Chưa ghép được máy.");
              } finally {
                setPending(false);
              }
            }}
          >
            <label>
              Tên máy gợi nhớ
              <input name="name" required maxLength={60} placeholder="Ví dụ: Redfinger 01" />
            </label>
            <label>
              UID máy (trong app Rejoin Control)
              <input name="id" required autoComplete="off" placeholder="UID ngẫu nhiên do Firebase cấp" />
            </label>
            <label>
              Mã ghép (16 byte hex)
              <input name="code" required autoComplete="off" pattern="[a-f0-9]{32}" placeholder="32 ký tự hex" />
            </label>
            <button className="primary full" disabled={pending}>
              Xác nhận ghép máy <Link2 size={16} />
            </button>
            {error && (
              <p role="alert" className="error-text">
                {error}
              </p>
            )}
          </form>
        </Modal>
      )}

      {editing && (
        <Modal title={`Import & Cấu hình ${editing.label}`} onClose={() => setEditing(null)}>
          <form
            onSubmit={async event => {
              event.preventDefault();
              setPending(true);
              setError("");
              const fields = new FormData(event.currentTarget);
              const username = editUsername.trim();
              const password = editPassword;
              const cookie = cleanCookieValue(editCookie);

              const placeId = String(fields.get("placeId") ?? "").replace(/\D/g, "").slice(0, 20);
              const jobId = String(fields.get("jobId") ?? "").replace(/[^A-Za-z0-9-]/g, "").slice(0, 64);

              const profileUpdate: Profile = {
                package: editing.package,
                label: String(fields.get("label")).trim(),
                username,
                slot: Number(fields.get("slot")),
                enabled: fields.get("enabled") === "on",
                autoReopen: fields.get("autoReopen") === "on",
                placeId,
                jobId,
              };

              try {
                await send({ action: "profile", profile: profileUpdate });
                if (cookie || password) {
                  if (!username) throw new Error("Cần nhập tên tài khoản Roblox khi lưu mật khẩu hoặc cookie.");
                  if (!current?.telemetry?.publicKey) throw new Error("Máy chưa gửi khóa mã hóa public key.");
                  const sealed = await sealAccounts(current.telemetry.publicKey, [
                    { package: editing.package, username, password, cookie },
                  ]);
                  await send({ action: "set_accounts", sealed });
                }
                let actionMsg = `Đã lưu cấu hình cho ${editing.label}.`;
                if (editAutoApplyRestart) {
                  const hasAnyCookie = Boolean(cookie || (!password && (editing.hasCookie ?? editing.hasAccount)));
                  if (hasAnyCookie) {
                    await send({ action: "login", package: editing.package });
                    actionMsg = `Đã lưu & đang nạp cookie + tắt mở lại riêng ${editing.label}.`;
                  } else {
                    await send({ action: "rejoin", package: editing.package });
                    actionMsg = `Đã lưu & đang tắt mở lại riêng ${editing.label}.`;
                  }
                }
                setEditing(null);
                setNotice(actionMsg);
                void refresh();
              } catch (problem) {
                setError(problem instanceof Error ? problem.message : "Chưa lưu được cấu hình.");
              } finally {
                setPending(false);
              }
            }}
          >
            <div className="section-subhead">Import tài khoản & cookie riêng cho tab này</div>
            <label>
              Dán nhanh 1 dòng (tự tách TK / MK / Cookie)
              <input
                value={editQuickLine}
                onChange={e => {
                  const raw = e.target.value;
                  setEditQuickLine(raw);
                  const parsed = parseCredentialLine(raw);
                  if (parsed.username) setEditUsername(parsed.username);
                  if (parsed.password) setEditPassword(parsed.password);
                  if (parsed.cookie) setEditCookie(parsed.cookie);
                }}
                autoComplete="off"
                spellCheck={false}
                placeholder="user:pass:_|WARNING:... hoặc user:pass hoặc _|WARNING:..."
              />
            </label>
            <label>
              Tên tài khoản Roblox (Username)
              <input
                name="username"
                value={editUsername}
                onChange={e => setEditUsername(e.target.value)}
                maxLength={80}
                placeholder="Ví dụ: RobloxPlayer_01"
              />
            </label>
            <label>
              Mật khẩu (tùy chọn — giữ mật khẩu cũ nếu để trống)
              <input
                name="password"
                type="password"
                value={editPassword}
                onChange={e => setEditPassword(e.target.value)}
                placeholder="Nhập mật khẩu Roblox"
              />
            </label>
            <label>
              Cookie (.ROBLOSECURITY — giữ cookie cũ nếu để trống)
              <textarea
                name="cookie"
                rows={3}
                value={editCookie}
                onChange={e => setEditCookie(e.target.value)}
                spellCheck={false}
                placeholder="_|WARNING:-DO-NOT-SHARE-THIS..."
              />
            </label>
            <label className="checkbox-label" style={{ marginTop: 10 }}>
              <input
                type="checkbox"
                checked={editAutoApplyRestart}
                onChange={e => setEditAutoApplyRestart(e.target.checked)}
              />{" "}
              Lưu xong tự động nạp cookie (nếu có) và tắt → mở lại riêng tab này
            </label>

            <div className="section-subhead">Hiển thị & vị trí ô lưới</div>
            <label>
              Tên hiển thị
              <input name="label" defaultValue={editing.label} required maxLength={60} />
            </label>
            <label>
              Vị trí trong lưới ô (Slot 1–36)
              <input name="slot" type="number" min="1" max="36" defaultValue={editing.slot} required />
            </label>

            <label className="checkbox-label" style={{ marginTop: 12 }}>
              <input type="checkbox" name="enabled" defaultChecked={editing.enabled} /> Bật quản lý bản này
            </label>
            <label className="checkbox-label" style={{ marginTop: 8 }}>
              <input type="checkbox" name="autoReopen" defaultChecked={editing.autoReopen !== false} /> Cho phép
              Auto-Rejoin tự mở lại bản này
            </label>

            <div className="section-subhead">Server riêng cho bản này (tùy chọn)</div>
            <label>
              Place ID riêng
              <input
                name="placeId"
                defaultValue={editing.placeId ?? ""}
                maxLength={20}
                placeholder="Để trống để dùng Place ID chung"
              />
            </label>
            <label>
              Job ID riêng
              <input
                name="jobId"
                defaultValue={editing.jobId ?? ""}
                maxLength={64}
                placeholder="Để trống để vào server bất kỳ"
              />
            </label>

            <button className="primary full" disabled={pending} style={{ marginTop: 16 }}>
              {editAutoApplyRestart ? `Lưu, Nạp & Mở lại ${editing.label}` : "Lưu cấu hình & Tài khoản"}{" "}
              <Check size={16} />
            </button>
            {error && <p className="error-text">{error}</p>}
          </form>
        </Modal>
      )}

      {credentials && (
        <Modal title="Nhập hàng loạt tài khoản" onClose={() => setCredentials(false)}>
          <p className="muted">
            Mỗi dòng một bản: <code>username:password</code> hoặc <code>username:password:cookie</code> hoặc{" "}
            <code>username::cookie</code>.
          </p>

          {profiles.length > 0 && (
            <div style={{ marginBottom: 12, fontSize: 11, color: "#8a97ad" }}>
              Thứ tự gán các dòng theo danh sách clone hiện có:
              <table className="account-preview-table">
                <thead>
                  <tr>
                    <th>Dòng</th>
                    <th>Clone</th>
                    <th>Package</th>
                    <th>Vị trí ô</th>
                  </tr>
                </thead>
                <tbody>
                  {profiles.map((p, i) => (
                    <tr key={p.package}>
                      <td>Dòng {i + 1}</td>
                      <td>
                        <b>{p.label}</b>
                      </td>
                      <td>{p.package}</td>
                      <td>Ô {p.slot}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <form
            onSubmit={async event => {
              event.preventDefault();
              setPending(true);
              setError("");
              try {
                const parsed = parseBatchLines(batchInput);
                if (!parsed.length) throw new Error("Chưa nhập dòng tài khoản nào.");
                if (parsed.length > profiles.length) {
                  throw new Error(`Đã nhập ${parsed.length} dòng nhưng máy chỉ có ${profiles.length} clone.`);
                }
                const records = parsed.map((item, index) => {
                  if (!item.username || (!item.cookie && !item.password)) {
                    throw new Error(`Dòng ${index + 1} cần có username kèm mật khẩu hoặc cookie.`);
                  }
                  return {
                    package: profiles[index].package,
                    username: item.username,
                    password: item.password,
                    cookie: item.cookie,
                  };
                });
                if (!current?.telemetry?.publicKey) throw new Error("Máy chưa gửi khóa public key.");
                const sealed = await sealAccounts(current.telemetry.publicKey, records);
                await send({ action: "set_accounts", sealed });
                const hasCookies = records.some(r => r.cookie.length > 0);
                if (batchAutoLogin && hasCookies) {
                  await send({ action: "login_all" });
                }
                setBatchInput("");
                setCredentials(false);
                setNotice(
                  batchAutoLogin && hasCookies
                    ? `Đã gửi ${records.length} tài khoản và tự động nạp cookie + mở lại các tab.`
                    : `Đã mã hóa và gửi ${records.length} tài khoản đến máy.`,
                );
                void refresh();
              } catch (problem) {
                setError(problem instanceof Error ? problem.message : "Chưa gửi được danh sách tài khoản.");
              } finally {
                setPending(false);
              }
            }}
          >
            <label>
              Danh sách tài khoản (mỗi dòng một tài khoản)
              <textarea
                name="accounts"
                rows={7}
                required
                autoComplete="off"
                spellCheck={false}
                value={batchInput}
                onChange={e => setBatchInput(e.target.value)}
                placeholder={"player1:matkhau123\nplayer2:matkhau:_|WARNING:-DO-NOT-SHARE-THIS...\nplayer3::_|WARNING:-DO-NOT-SHARE-THIS..."}
              />
            </label>

            <label className="checkbox-label" style={{ marginTop: 10 }}>
              <input
                type="checkbox"
                checked={batchAutoLogin}
                onChange={e => setBatchAutoLogin(e.target.checked)}
              />{" "}
              Gửi xong tự động nạp cookie và mở lại các tab có cookie
            </label>

            <div className="privacy-note">
              <ShieldCheck size={16} />
              Tất cả cookie/mật khẩu được mã hóa trực tiếp trong trình duyệt bằng WebCrypto (AES-256-GCM + RSA-OAEP).
              Vercel và Firebase không thể đọc bản rõ.
            </div>

            <button className="primary full" disabled={pending}>
              {batchAutoLogin ? "Mã hóa, Gửi & Nạp cookie mở lại" : "Mã hóa và gửi đến máy"} <ArrowRight size={16} />
            </button>
            {error && <p className="error-text">{error}</p>}
          </form>
        </Modal>
      )}

      {renamingDevice && (
        <Modal title="Đổi tên máy Redfinger" onClose={() => setRenamingDevice(null)}>
          <form
            onSubmit={async event => {
              event.preventDefault();
              setPending(true);
              setError("");
              const fields = new FormData(event.currentTarget);
              const name = String(fields.get("name") ?? "").trim();
              try {
                await api("/api/devices", { id: renamingDevice.id, name }, "PATCH");
                setRenamingDevice(null);
                setNotice(`Đã đổi tên máy thành “${name}”.`);
                void refresh();
              } catch (problem) {
                setError(problem instanceof Error ? problem.message : "Chưa đổi được tên máy.");
              } finally {
                setPending(false);
              }
            }}
          >
            <label>
              Tên hiển thị mới
              <input name="name" defaultValue={renamingDevice.meta.name} required maxLength={60} />
            </label>
            <button className="primary full" disabled={pending}>
              Lưu tên mới <Check size={16} />
            </button>
            {error && <p className="error-text">{error}</p>}
          </form>
        </Modal>
      )}

      {deletingDevice && (
        <Modal title="Xóa máy khỏi bảng điều khiển" onClose={() => setDeletingDevice(null)}>
          <p className="muted">
            Bạn sắp xóa máy <b>{deletingDevice.meta.name}</b> (<code>{deletingDevice.id}</code>,{" "}
            {Object.keys(deletingDevice.telemetry?.profiles ?? {}).length} clone) khỏi bảng điều khiển và Firebase RTDB.
          </p>
          <p className="muted" style={{ marginTop: 10 }}>
            Thao tác này chỉ xóa dữ liệu điều khiển của phiên ghép này trên Web & Firebase (không gỡ cài đặt app trên
            Redfinger). Nếu là máy cũ không dùng nữa, bạn có thể xóa an toàn.
          </p>
          {error && <p className="error-text">{error}</p>}
          <div className="rejoin-actions" style={{ marginTop: 18 }}>
            <button type="button" className="secondary" disabled={pending} onClick={() => setDeletingDevice(null)}>
              Hủy
            </button>
            <button
              type="button"
              className="danger-button"
              disabled={pending}
              onClick={async () => {
                setPending(true);
                setError("");
                const deleted = deletingDevice;
                try {
                  await api("/api/devices", { id: deleted.id }, "DELETE");
                  if (selected === deleted.id) setSelected("");
                  setDeletingDevice(null);
                  setNotice(`Đã xóa máy “${deleted.meta.name}”.`);
                  void refresh();
                } catch (problem) {
                  setError(problem instanceof Error ? problem.message : "Chưa xóa được máy.");
                } finally {
                  setPending(false);
                }
              }}
            >
              <Trash2 size={15} /> Xác nhận xóa máy
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}

function Brand() {
  return (
    <div className="brand">
      <div className="brand-mark">
        <Grid2X2 size={22} />
      </div>
      <span>
        Rejoin<span className="brand-light">Control</span>
        <small>CLOUD WORKSPACE</small>
      </span>
    </div>
  );
}

function Stat({ label, value, icon, accent = false }: { label: string; value: number; icon: React.ReactNode; accent?: boolean }) {
  return (
    <div className={`stat ${accent ? "accent" : ""}`}>
      <div>
        <span>{label}</span>
        <b>{String(value).padStart(2, "0")}</b>
      </div>
      <div className="stat-icon">{icon}</div>
    </div>
  );
}

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    element?.showModal();
    return () => element?.close();
  }, []);
  return (
    <dialog
      ref={dialog}
      className="modal"
      aria-label={title}
      onCancel={event => {
        event.preventDefault();
        onClose();
      }}
      onClick={event => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className="modal-heading">
        <h2>{title}</h2>
        <button onClick={onClose} aria-label="Đóng">
          <X size={20} />
        </button>
      </div>
      {children}
    </dialog>
  );
}

function CommandRow({ result }: { result: Result }) {
  const names = { queued: "Chờ nhận", running: "Đang chạy", succeeded: "Hoàn tất", failed: "Có lỗi" };
  return (
    <div className="command-row">
      <div>
        <b>{actionNames[result.action] ?? result.action}</b>
        <p>{result.message ?? "Chờ agent cập nhật kết quả."}</p>
        <small>{new Date(result.createdAt).toLocaleString("vi-VN")}</small>
      </div>
      <span className={`result-status ${result.status}`}>{names[result.status]}</span>
    </div>
  );
}

function SliderField({
  label,
  unit,
  min,
  max,
  value,
  hint,
  zeroLabel,
  onChange,
}: {
  label: string;
  unit: string;
  min: number;
  max: number;
  value: number;
  hint: string;
  zeroLabel?: string;
  onChange: (next: number) => void;
}) {
  const shown = value === 0 && zeroLabel ? `0 (${zeroLabel})` : `${value}${unit}`;
  return (
    <div className="slider-field">
      <div className="slider-top">
        <span>{label}</span>
        <b>{shown}</b>
      </div>
      <input type="range" min={min} max={max} value={value} onChange={e => onChange(Number(e.target.value))} />
      <small>{hint}</small>
    </div>
  );
}
