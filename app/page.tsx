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
  ChevronRight,
  CircleHelp,
  Cpu,
  Grid2X2,
  Layers3,
  LayoutDashboard,
  Link2,
  LoaderCircle,
  LogOut,
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
} from "@/lib/contracts";
import { sealAccounts } from "@/lib/seal";

const auth = typeof window !== "undefined" ? clientAuth() : null;

const actionNames: Record<string, string> = {
  scan: "Quét bản clone",
  open_all: "Mở các bản đang bật",
  rejoin_all: "Rejoin tất cả",
  set_watch: "Auto-rejoin loop (toàn máy)",
  set_rejoin_config: "Lưu cấu hình Auto-rejoin",
  profile: "Lưu cấu hình bản clone",
  open: "Mở ứng dụng",
  close: "Đóng ứng dụng",
  rejoin: "Rejoin vào game",
  grid: "Xếp lưới",
  enable_freeform: "Bật cửa sổ tự do",
  restore_windowing: "Khôi phục cửa sổ",
  status: "Cập nhật trạng thái",
  set_accounts: "Gửi tài khoản E2EE",
  login_all: "Nạp cookie và mở",
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
  const [renamingDevice, setRenamingDevice] = useState<Device | null>(null);
  const [deletingDevice, setDeletingDevice] = useState<Device | null>(null);
  const [batchInput, setBatchInput] = useState("");
  const [rows, setRows] = useState(2);
  const [cols, setCols] = useState(2);
  const [tab, setTab] = useState<"accounts" | "rejoin" | "activity">("accounts");
  const [rejoinDraft, setRejoinDraft] = useState<RejoinConfig>(DEFAULT_REJOIN_CONFIG);
  const [rejoinDirty, setRejoinDirty] = useState(false);

  const current = devices.find(d => d.id === selected) ?? devices[0];
  const profiles = Object.values(current?.telemetry?.profiles ?? {}).sort((a, b) =>
    a.label.localeCompare(b.label, "vi", { numeric: true }),
  );
  const online = isOnline(current?.telemetry?.lastSeen);
  const results = Object.values(current?.results ?? {}).sort((a, b) => b.createdAt - a.createdAt);

  useEffect(() => {
    if (!rejoinDirty && current?.telemetry?.rejoinConfig) {
      setRejoinDraft(current.telemetry.rejoinConfig);
    } else if (!rejoinDirty && current?.telemetry) {
      setRejoinDraft({ ...DEFAULT_REJOIN_CONFIG, enabled: Boolean(current.telemetry.watch) });
    }
  }, [current?.id, current?.telemetry, rejoinDirty]);

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
    }, 10_000);
    return () => clearInterval(timer);
  }, [refresh]);

  async function send(command: Command) {
    if (demo || !current) return;
    setPending(true);
    setError("");
    try {
      await api("/api/commands", { deviceId: current.id, command });
      setNotice(`Đã gửi “${actionNames[command.action] ?? command.action}”. Chờ máy phản hồi trong nhật ký.`);
      void refresh();
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

  function parseBatchLines(text: string) {
    return text
      .split(/\r?\n/)
      .map(l => l.trim())
      .filter(Boolean)
      .map((line, idx) => {
        const parts = line.split(":");
        const username = parts[0]?.trim() || "";
        const password = parts.length >= 3 ? parts[1] : "";
        let cookie = parts.length >= 3 ? parts.slice(2).join(":").trim() : parts.slice(1).join(":").trim();
        if (cookie.startsWith(".ROBLOSECURITY=")) cookie = cookie.slice(".ROBLOSECURITY=".length).trim();
        cookie = cookie.replace(/^["']|["']$/g, "").trim();
        return { username, password, cookie, line, target: profiles[idx] };
      });
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
                    {current.telemetry?.android ?? "—"} <span>·</span> {profiles.length} clone <span>·</span> Cập nhật:{" "}
                    {new Date(current.telemetry?.lastSeen ?? 0).toLocaleTimeString("vi-VN")}
                  </p>
                </div>
                <button className="secondary" disabled={blocked} onClick={() => issue({ action: "scan" })}>
                  <ScanLine size={16} /> Quét Clone
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
                      <div className="panel-toolbar">
                        <div>
                          <h3>Danh sách bản clone trên máy</h3>
                          <p>Mỗi bản clone có cấu hình tài khoản, vị trí ô và trạng thái watchdog riêng biệt.</p>
                        </div>
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
                                  ) : profile.hasAccount ? (
                                    <span className="status-pill blue">Cookie: Đã lưu trên máy</span>
                                  ) : (
                                    <span className="status-pill gray">Cookie: Chưa có</span>
                                  )}

                                  {profile.sqliteStatus === "written" ? (
                                    <span className="status-pill green">SQLite: Đã nạp</span>
                                  ) : profile.sqliteStatus === "failed" ? (
                                    <span className="status-pill red">SQLite: Lỗi ghi</span>
                                  ) : null}

                                  <span className={`status-pill ${profile.running && online ? "green" : "gray"}`}>
                                    Tiến trình: {profile.running && online ? "Đang chạy" : "Đã tắt"}
                                  </span>

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

                              <button className="text-button" disabled={blocked} onClick={() => setEditing(profile)}>
                                Sửa
                              </button>
                              <button
                                className="row-action"
                                disabled={blocked}
                                onClick={() => issue({ action: "rejoin", package: profile.package })}
                                title="Force-stop và vào lại Place ID"
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
                                {profile.running ? "Đóng" : "Mở"}
                              </button>
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
                      onSubmit={async event => {
                        event.preventDefault();
                        try {
                          await send({ action: "set_rejoin_config", config: rejoinDraft });
                          setRejoinDirty(false);
                        } catch {}
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
                            <p>Cấu hình mặc định cho mọi bản clone trên máy; không dùng auto-solve CAPTCHA.</p>
                          </div>
                        </div>
                        <button
                          type="button"
                          role="switch"
                          aria-checked={rejoinDraft.enabled}
                          className={`switch ${rejoinDraft.enabled ? "on" : ""}`}
                          onClick={() => {
                            setRejoinDirty(true);
                            setRejoinDraft(prev => ({ ...prev, enabled: !prev.enabled }));
                          }}
                        >
                          <span />
                        </button>
                      </div>

                      <div className="rejoin-target-grid">
                        <label>
                          Place ID (Deep link vào thẳng game)
                          <input
                            value={rejoinDraft.placeId}
                            pattern="\d{0,20}"
                            placeholder="Ví dụ: 1730877806 (để trống nếu chỉ mở app)"
                            onChange={e => {
                              setRejoinDirty(true);
                              setRejoinDraft(prev => ({
                                ...prev,
                                placeId: e.target.value.replace(/\D/g, "").slice(0, 20),
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
                                Phát hiện tiến trình chết hoặc cửa sổ biến mất khi cả 4 lớp (task list, window manager,
                                surfaces, screen tree) đồng thuận.
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
                                Quét cây UI có giới hạn timeout để rejoin khi Disconnected; tự tạm dừng khi gặp màn hình
                                đăng nhập hoặc CAPTCHA.
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
                        <button type="submit" className="primary" disabled={blocked}>
                          <Check size={16} /> Lưu cấu hình Auto-rejoin
                        </button>
                      </div>
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
                    <p className="muted">Chia màn hình Android thành lưới cửa sổ Freeform theo vị trí từng clone.</p>
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
                          setCols(2);
                        }}
                      >
                        2×2 (4 acc)
                      </button>
                      <button
                        type="button"
                        className="secondary compact"
                        onClick={() => {
                          setRows(2);
                          setCols(3);
                        }}
                      >
                        2×3 (6 acc)
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
                    <button
                      className="primary full"
                      disabled={blocked || !profiles.length}
                      onClick={() => issue({ action: "grid", rows, cols, gap: 6, top: 32, bottom: 36 })}
                    >
                      <Grid2X2 size={16} /> Áp dụng lưới
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
                    >
                      Khôi phục cấu hình cửa sổ
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
                      Tự kiểm tra crash 4 lớp & màn hình Disconnect, có hàng đợi giãn cách tuần tự giữa các clone. Tạm
                      dừng nếu gặp CAPTCHA.
                    </p>
                    <button className="text-button full freeform-button" onClick={() => setTab("rejoin")}>
                      <SlidersHorizontal size={14} /> Chỉnh tham số Auto-Rejoin
                    </button>
                  </section>

                  <button
                    className="secondary full"
                    disabled={blocked || !profiles.length}
                    onClick={() => issue({ action: "rejoin_all" })}
                  >
                    <RotateCcw size={16} /> Rejoin tất cả vào Place ID
                  </button>
                  <button className="secondary full" disabled={blocked} onClick={() => issue({ action: "open_all" })}>
                    <Layers3 size={16} /> Mở tất cả đang bật
                  </button>
                  <button
                    className="secondary full"
                    disabled={blocked || !profiles.some(p => p.hasAccount)}
                    onClick={() => issue({ action: "login_all" })}
                  >
                    <ShieldCheck size={16} /> Nạp cookie và mở
                  </button>
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
        <Modal title={`Cấu hình ${editing.label}`} onClose={() => setEditing(null)}>
          <form
            onSubmit={async event => {
              event.preventDefault();
              setPending(true);
              setError("");
              const fields = new FormData(event.currentTarget);
              const username = String(fields.get("username")).trim();
              const password = String(fields.get("password") ?? "");
              let cookie = String(fields.get("cookie") ?? "").trim();
              if (cookie.startsWith(".ROBLOSECURITY=")) cookie = cookie.slice(".ROBLOSECURITY=".length).trim();
              cookie = cookie.replace(/^["']|["']$/g, "").trim();

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
                if (cookie) {
                  if (!username) throw new Error("Cần nhập tên tài khoản Roblox khi thay đổi cookie.");
                  if (!current?.telemetry?.publicKey) throw new Error("Máy chưa gửi khóa mã hóa public key.");
                  const sealed = await sealAccounts(current.telemetry.publicKey, [
                    { package: editing.package, username, password, cookie },
                  ]);
                  await send({ action: "set_accounts", sealed });
                }
                setEditing(null);
                setNotice(`Đã lưu cấu hình cho ${editing.label}.`);
                void refresh();
              } catch (problem) {
                setError(problem instanceof Error ? problem.message : "Chưa lưu được cấu hình.");
              } finally {
                setPending(false);
              }
            }}
          >
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

            <div className="section-subhead">Gán / Thay đổi tài khoản (tùy chọn)</div>
            <label>
              Tên tài khoản Roblox (Username)
              <input name="username" defaultValue={editing.username} maxLength={80} placeholder="Ví dụ: RobloxPlayer_01" />
            </label>
            <label>
              Mật khẩu (Tùy chọn)
              <input name="password" type="password" placeholder="Để trống nếu chỉ dùng cookie" />
            </label>
            <label>
              Cookie (.ROBLOSECURITY - Điền để cập nhật mới)
              <textarea name="cookie" rows={3} placeholder="_|WARNING:-DO-NOT-SHARE-THIS..." />
            </label>

            <button className="primary full" disabled={pending} style={{ marginTop: 16 }}>
              Lưu cấu hình & Cập nhật <Check size={16} />
            </button>
            {error && <p className="error-text">{error}</p>}
          </form>
        </Modal>
      )}

      {credentials && (
        <Modal title="Nhập hàng loạt tài khoản" onClose={() => setCredentials(false)}>
          <p className="muted">
            Định dạng mỗi dòng: <code>username:password:cookie</code> (hoặc <code>username::cookie</code>).
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
                  if (!item.username || !item.cookie) {
                    throw new Error(`Dòng ${index + 1} thiếu tên tài khoản hoặc cookie.`);
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
                setBatchInput("");
                setCredentials(false);
                setNotice(`Đã mã hóa và gửi ${records.length} tài khoản đến máy.`);
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
                placeholder={"player1:matkhau:_|WARNING:-DO-NOT-SHARE-THIS...\nplayer2::_|WARNING:-DO-NOT-SHARE-THIS..."}
              />
            </label>

            <div className="privacy-note">
              <ShieldCheck size={16} />
              Tất cả cookie/mật khẩu được mã hóa trực tiếp trong trình duyệt bằng WebCrypto (AES-256-GCM + RSA-OAEP).
              Vercel và Firebase không thể đọc bản rõ.
            </div>

            <button className="primary full" disabled={pending}>
              Mã hóa và gửi đến máy <ArrowRight size={16} />
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
