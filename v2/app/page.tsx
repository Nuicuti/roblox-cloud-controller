"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { GoogleAuthProvider, onAuthStateChanged, signInWithEmailAndPassword, signInWithPopup, signOut, type User } from "firebase/auth";
import { Activity, AlertTriangle, ArrowRight, Check, ChevronRight, CircleHelp, Cpu, Grid2X2, Layers3, LayoutDashboard,
  Link2, LoaderCircle, LogOut, Monitor, Plus, RefreshCw, ScanLine, Settings2, ShieldCheck, Smartphone, Square, X } from "lucide-react";
import { clientAuth } from "@/lib/firebase-client";
import { type Command, type Device, type Profile, type Result, isOnline } from "@/lib/contracts";
import { sealAccounts } from "@/lib/seal";

const auth = typeof window !== "undefined" ? clientAuth() : null;
const actionNames: Record<string, string> = {
  scan: "Quét bản Noka",
  open_all: "Mở các bản Noka",
  set_watch: "Tự mở lại (toàn máy)",
  profile: "Lưu cấu hình Noka",
  open: "Mở Noka",
  close: "Đóng Noka",
  grid: "Xếp lưới",
  enable_freeform: "Bật cửa sổ tự do",
  restore_windowing: "Khôi phục cửa sổ",
  status: "Cập nhật trạng thái",
  set_accounts: "Gửi tài khoản",
  login_all: "Nạp cookie và mở Noka"
};

function sampleDevice(): Device {
  const profiles: Record<string, Profile> = {};
  [1, 2, 3, 4].forEach(n => {
    profiles[String(n)] = {
      package: `free.noka${String.fromCharCode(64+n)}`,
      label: `Noka ${n}`,
      username: `tai_khoan_${n}`,
      enabled: true,
      autoReopen: true,
      running: n !== 4,
      slot: [3, 4, 1, 2][n-1],
      hasAccount: true,
      cookieStatus: n === 4 ? "invalid" : "valid",
      sqliteStatus: n === 4 ? "failed" : "written",
      appStatus: n === 4 ? "stopped" : "running",
      loginStatus: "unverified_ingame",
      loginMessage: n === 4 ? "Cookie không hợp lệ" : "Cookie hợp lệ, đã ghi và mở Noka."
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
      profiles,
      grid: { rows: 2, cols: 2 },
      freeform: true
    }
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
  const [batchInput, setBatchInput] = useState("");
  const [rows, setRows] = useState(2);
  const [cols, setCols] = useState(2);
  const [tab, setTab] = useState<"accounts" | "activity">("accounts");

  const current = devices.find(d => d.id === selected) ?? devices[0];
  const profiles = Object.values(current?.telemetry?.profiles ?? {}).sort((a, b) => a.label.localeCompare(b.label, "vi", { numeric: true }));
  const online = isOnline(current?.telemetry?.lastSeen);
  const results = Object.values(current?.results ?? {}).sort((a, b) => b.createdAt-a.createdAt);

  useEffect(() => {
    if (!auth) { setInitializing(false); return; }
    return onAuthStateChanged(auth, next => { setUser(next); setInitializing(false); if (!next) setDevices([]); });
  }, []);

  const api = useCallback(async (path: string, payload?: unknown) => {
    if (!auth?.currentUser) throw new Error("Bạn cần đăng nhập trước.");
    const token = await auth.currentUser.getIdToken();
    const response = await fetch(path, {
      method: payload === undefined ? "GET" : "POST",
      cache: "no-store",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: payload === undefined ? undefined : JSON.stringify(payload)
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error ?? "Không thực hiện được yêu cầu.");
    return data;
  }, []);

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
    const timer = setInterval(() => { void refresh(); }, 10_000);
    return () => clearInterval(timer);
  }, [refresh]);

  async function send(command: Command) {
    if (demo || !current) return;
    setPending(true); setError("");
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

  const issue = (command: Command) => { void send(command).catch(() => {}); };
  const blocked = pending || demo || !current || !online;

  function parseBatchLines(text: string) {
    const lines = text.split(/\r?\n/).map(l => l.trim()).filter(Boolean);
    return lines.map((line, idx) => {
      const parts = line.split(":");
      const username = parts[0]?.trim() || "";
      const password = parts.length >= 3 ? parts[1] : "";
      let cookie = parts.length >= 3 ? parts.slice(2).join(":").trim() : parts.slice(1).join(":").trim();
      if (cookie.startsWith(".ROBLOSECURITY=")) {
        cookie = cookie.slice(".ROBLOSECURITY=".length).trim();
      }
      cookie = cookie.replace(/^["']|["']$/g, "").trim();
      const targetProfile = profiles[idx];
      return { username, password, cookie, line, target: targetProfile };
    });
  }

  if (initializing) return <main className="loading"><LoaderCircle className="spin" /><p>Đang mở bảng điều khiển…</p></main>;

  if (!user && !demo) return <main className="login-shell">
    <div className="login-art">
      <Brand />
      <div>
        <span className="eyebrow">YOUR CLOUD, IN CONTROL</span>
        <h1>Một nơi.<br />Mọi máy Noka.</h1>
        <p>Quản lý tài khoản, tự mở lại ứng dụng và chia lưới cửa sổ trên các máy Redfinger của bạn.</p>
        <div className="art-grid">{[3, 4, 1, 2].map(n => <div key={n}><Smartphone size={24} /><span>Noka {n}</span><i /></div>)}</div>
      </div>
      <small><ShieldCheck size={15} /> Tài khoản và Cookie được mã hóa trong trình duyệt trước khi gửi tới máy đích.</small>
    </div>
    <div className="login-form">
      <div className="login-card">
        <span className="eyebrow">NOKA CONTROL</span>
        <h2>Chào mừng trở lại</h2>
        <p>Đăng nhập để điều khiển hệ thống máy Redfinger.</p>
        {!auth ? <div className="setup-note"><Settings2 /><b>Cần cấu hình biến môi trường Firebase</b><p>Vui lòng cấu hình các biến NEXT_PUBLIC_FIREBASE_* trong cài đặt Vercel.</p></div> : <>
          <button className="secondary full" disabled={pending} onClick={async () => {
            setPending(true); setError("");
            try {
              const provider = new GoogleAuthProvider();
              provider.setCustomParameters({ prompt: "select_account" });
              await signInWithPopup(auth, provider);
            } catch {
              setError("Chưa đăng nhập được Google. Kiểm tra cửa sổ pop-up và miền được cấp phép trong Firebase.");
            } finally { setPending(false); }
          }}><b>G</b> Tiếp tục với Google</button>
          <div className="login-separator">hoặc đăng nhập bằng Email / Mật khẩu</div>
          <form onSubmit={async event => {
            event.preventDefault(); setPending(true); setError("");
            const fields = new FormData(event.currentTarget);
            try {
              await signInWithEmailAndPassword(auth, String(fields.get("email")), String(fields.get("password")));
            } catch {
              setError("Không đăng nhập được. Kiểm tra tài khoản và mật khẩu.");
            } finally { setPending(false); }
          }}>
            <label>Email quản trị<input name="email" type="email" autoComplete="username" required placeholder="admin@example.com" /></label>
            <label>Mật khẩu<input name="password" type="password" autoComplete="current-password" required placeholder="••••••••" /></label>
            <button className="primary full" disabled={pending}>{pending ? <LoaderCircle className="spin" size={17} /> : <>Đăng nhập <ArrowRight size={17} /></>}</button>
          </form>
        </>}
        {error && <p role="alert" className="error-text">{error}</p>}
        <button className="text-button demo-link" onClick={() => { setDemo(true); setDevices([sampleDevice()]); }}>Xem giao diện mẫu <ChevronRight size={14} /></button>
        <small className="login-foot">ROBLOX CLOUD CONTROLLER · REDFINGER</small>
      </div>
    </div>
  </main>;

  return <div className="app-shell">
    <aside className="sidebar">
      <Brand />
      <span className="nav-caption">KHÔNG GIAN LÀM VIỆC</span>
      <button className="nav-item active"><LayoutDashboard size={18} /> Điều khiển máy <span>{devices.length}</span></button>
      <div className="side-section">
        <div className="side-heading">
          <span>DANH SÁCH MÁY</span>
          <button title="Ghép máy" onClick={() => setPair(true)} disabled={demo}><Plus size={16} /></button>
        </div>
        {devices.map(device => <button key={device.id} className={`device-nav ${current?.id === device.id ? "selected" : ""}`} onClick={() => { setSelected(device.id); setTab("accounts"); }}>
          <span className={`dot ${isOnline(device.telemetry?.lastSeen) ? "green" : "gray"}`} />
          <span>{device.meta.name}<small>{Object.keys(device.telemetry?.profiles ?? {}).length} bản Noka</small></span>
          <ChevronRight size={14} />
        </button>)}
        {!devices.length && <p className="side-empty">Chưa có máy nào. Bấm (+) để ghép máy.</p>}
      </div>
      <div className="sidebar-bottom">
        <div className="connection-card"><ShieldCheck size={18} /><div>Kết nối an toàn<small>Firebase RTDB · Mã hóa E2EE</small></div></div>
        <button className="user-row" onClick={() => { if (demo) { setDemo(false); setDevices([]); } else if (auth) void signOut(auth); }}>
          <span className="avatar">{(user?.email ?? "M")[0].toUpperCase()}</span>
          <span>{demo ? "Chế độ xem mẫu" : user?.email}<small>Đăng xuất</small></span>
          <LogOut size={16} />
        </button>
      </div>
    </aside>

    <div className="workspace">
      <header className="topbar">
        <div className="breadcrumb">Không gian làm việc <ChevronRight size={14} /><b>Điều khiển máy</b></div>
        <span className="live-label"><span className="dot green" /> Tự động cập nhật mỗi 10 giây</span>
      </header>

      <main className="content">
        {demo && <div className="demo-banner">
          <CircleHelp size={17} />
          <span>Đây là chế độ xem mẫu với dữ liệu giả lập. Các nút điều khiển máy thật bị vô hiệu hóa.</span>
          <button onClick={() => { setDemo(false); setDevices([]); }}>Thoát chế độ mẫu</button>
        </div>}

        <div className="page-heading">
          <div>
            <span className="eyebrow">FLEET OVERVIEW</span>
            <h1>Điều khiển Redfinger</h1>
            <p>Quản lý tài khoản, watchdog tự mở lại và sắp xếp cửa sổ Roblox Noka.</p>
          </div>
          <button className="primary" onClick={() => setPair(true)} disabled={demo}><Plus size={17} /> Ghép máy mới</button>
        </div>

        <div className="stats">
          <Stat label="Tổng số máy" value={devices.length} icon={<Monitor size={20} />} />
          <Stat label="Máy Online" value={devices.filter(d => isOnline(d.telemetry?.lastSeen)).length} icon={<Activity size={20} />} accent />
          <Stat label="Tài khoản đã gán" value={devices.reduce((total, d) => total+Object.values(d.telemetry?.profiles ?? {}).filter(p => p.hasAccount).length, 0)} icon={<Layers3 size={20} />} />
          <Stat label="Noka đang mở" value={devices.reduce((total, d) => total+(isOnline(d.telemetry?.lastSeen) ? Object.values(d.telemetry?.profiles ?? {}).filter(p => p.running).length : 0), 0)} icon={<Cpu size={20} />} />
        </div>

        {error && <div className="message error" role="alert">{error}<button onClick={() => setError("")} aria-label="Đóng lỗi"><X size={15} /></button></div>}
        {notice && <div className="message success" role="status"><Check size={16} />{notice}<button onClick={() => setNotice("")} aria-label="Đóng thông báo"><X size={15} /></button></div>}

        {!current ? <div className="empty-state">
          <div className="empty-icon"><Link2 size={32} /></div>
          <h2>Chưa có máy Redfinger nào được ghép</h2>
          <p>Cài đặt agent trên Termux máy Redfinger, chạy lệnh setup để lấy UID và mã ghép 10 phút, sau đó nhập vào đây.</p>
          <button className="primary" onClick={() => setPair(true)}><Plus size={17} /> Ghép máy mới</button>
        </div> : <>
          <section className="device-header">
            <div className="device-symbol"><Smartphone size={24} /></div>
            <div className="device-title">
              <h2>{current.meta.name}<span className={`badge ${online ? "online" : "offline"}`}>{online ? "Đang kết nối" : "Mất kết nối"}</span></h2>
              <p>{current.telemetry?.model ?? "Đang chờ agent"} <span>·</span> Android {current.telemetry?.android ?? "—"} <span>·</span> {profiles.length} bản Noka <span>·</span> Cập nhật: {new Date(current.telemetry?.lastSeen ?? 0).toLocaleTimeString("vi-VN")}</p>
            </div>
            <button className="secondary" disabled={blocked} onClick={() => issue({ action: "scan" })}><ScanLine size={16} /> Quét Noka</button>
            <button className="icon-button" disabled={pending || demo} onClick={() => { void refresh(); }} title="Làm mới dữ liệu"><RefreshCw size={17} /></button>
          </section>

          {!online && <div className="offline-note">Máy hiện không trực tuyến hoặc agent chưa chạy. Không thể gửi lệnh mới.</div>}

          <div className="watchdog-banner">
            <AlertTriangle size={18} />
            <div>
              <b>Quy tắc tự mở lại (Watchdog) & Giới hạn kỹ thuật:</b>
              <span> Watchdog phát hiện và mở lại ứng dụng khi tiến trình Android bị kết thúc (crash/kill). Giới hạn: Chưa phát hiện được trường hợp game bị treo màn hình (freeze/ANR) hoặc mất kết nối trong game khi tiến trình Roblox vẫn còn chạy.</span>
            </div>
          </div>

          <div className="main-columns">
            <div className="main-panel">
              <div className="panel-tabs">
                <button className={tab === "accounts" ? "chosen" : ""} onClick={() => setTab("accounts")}>Tài khoản & Trạng thái <span>{profiles.length}</span></button>
                <button className={tab === "activity" ? "chosen" : ""} onClick={() => setTab("activity")}>Nhật ký lệnh</button>
              </div>

              {tab === "accounts" ? <>
                <div className="panel-toolbar">
                  <div>
                    <h3>Danh sách bản Noka clone</h3>
                    <p>Mỗi bản clone có cấu hình tài khoản, vị trí ô và trạng thái watchdog riêng biệt.</p>
                  </div>
                  <button className="secondary compact" disabled={blocked || !profiles.length || !current.telemetry?.publicKey} onClick={() => { setBatchInput(""); setCredentials(true); }}>
                    <Plus size={15} /> Nhập hàng loạt tài khoản
                  </button>
                </div>

                <div className="account-list">
                  {profiles.map(profile => (
                    <div key={profile.package} className="account-row">
                      <div className="noka-icon">{profile.label.match(/\d+/)?.[0] ?? "N"}</div>
                      <div className="account-info">
                        <b>{profile.label} <small style={{ display: "inline", color: "#8a96a8" }}>({profile.package})</small></b>
                        <span>Tài khoản: <strong>{profile.username || "Chưa gán tài khoản"}</strong> · Vị trí ô: {profile.slot}</span>
                        
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

                          <span className={`status-pill ${profile.autoReopen !== false ? "blue" : "gray"}`}>
                            Tự mở lại: {profile.autoReopen !== false ? "Bật" : "Tắt"}
                          </span>

                          <span className="status-pill yellow" title="Không kết luận đăng nhập thành công khi chưa kiểm tra màn hình trong game">
                            Phiên trong game: Chưa xác minh
                          </span>
                        </div>

                        {profile.error && <small style={{ color: "#f29494" }}>Lỗi: {profile.error}</small>}
                        {profile.loginMessage && !profile.error && <small>{profile.loginMessage}</small>}
                      </div>

                      <button className="text-button" disabled={blocked} onClick={() => setEditing(profile)}>Sửa</button>
                      <button className="row-action" disabled={blocked} onClick={() => issue({ action: profile.running ? "close" : "open", package: profile.package })}>
                        {profile.running ? <Square size={13} /> : <ArrowRight size={15} />}
                        {profile.running ? "Đóng" : "Mở"}
                      </button>
                    </div>
                  ))}
                </div>

                {!profiles.length && <div className="inline-empty">Chọn “Quét Noka” để nhận diện các bản Roblox clone đã cài trên máy Redfinger.</div>}
                
                <div className="panel-footer">
                  <ShieldCheck size={15} />
                  <span>Bảo mật: Mật khẩu và Cookie được mã hóa bằng cặp khóa RSA-2048 / AES-GCM của thiết bị. Chỉ máy đích giải mã được.</span>
                </div>
              </> : <div className="command-list">
                {results.length ? results.map(result => <CommandRow key={result.id} result={result} />) : <div className="inline-empty">Chưa có lệnh nào được gửi đến máy.</div>}
              </div>}
            </div>

            <aside className="controls-column">
              <section className="panel">
                <div className="control-title"><Grid2X2 size={18} /><h3>Sắp xếp cửa sổ (Sort tab)</h3></div>
                <p className="muted">Chia màn hình Android thành lưới cửa sổ freeform theo vị trí từng Noka.</p>
                <div className="grid-preview" style={{ gridTemplateColumns: `repeat(${cols}, 1fr)` }}>
                  {Array.from({ length: rows*cols }, (_, i) => (
                    <div key={i}>{profiles.find(p => p.slot === i+1)?.label ?? <span>Ô {i+1}</span>}</div>
                  ))}
                </div>
                <div className="grid-fields">
                  <label>Số hàng<select value={rows} onChange={e => setRows(Number(e.target.value))}>{[1, 2, 3, 4].map(n => <option key={n}>{n}</option>)}</select></label>
                  <label>Số cột<select value={cols} onChange={e => setCols(Number(e.target.value))}>{[1, 2, 3, 4].map(n => <option key={n}>{n}</option>)}</select></label>
                </div>
                <button className="primary full" disabled={blocked || !profiles.length} onClick={() => issue({ action: "grid", rows, cols, gap: 6, top: 32, bottom: 36 })}>
                  <Grid2X2 size={16} /> Áp dụng lưới
                </button>
                <button className="text-button full freeform-button" disabled={blocked} onClick={() => issue({ action: "enable_freeform" })}>Bật cửa sổ tự do</button>
                <button className="text-button full" disabled={blocked} onClick={() => issue({ action: "restore_windowing" })}>Khôi phục cấu hình cửa sổ</button>
              </section>

              <section className="panel">
                <div className="watch-heading">
                  <span className="watch-icon"><RefreshCw size={18} /></span>
                  <div>
                    <h3>Tự mở lại toàn máy</h3>
                    <p>{current.telemetry?.watch ? "Watchdog đang theo dõi" : "Watchdog đang tắt"}</p>
                  </div>
                  <button role="switch" aria-checked={Boolean(current.telemetry?.watch)} aria-label="Tự mở lại Noka" className={`switch ${current.telemetry?.watch ? "on" : ""}`} disabled={blocked} onClick={() => issue({ action: "set_watch", enabled: !current.telemetry?.watch })}><span /></button>
                </div>
                <p className="muted">Khi bật: Tự mở lại các Noka có cờ “Tự mở lại”. Khi chủ động bấm Đóng một Noka từ dashboard, Noka đó sẽ tạm dừng tự mở lại.</p>
              </section>

              <button className="secondary full" disabled={blocked} onClick={() => issue({ action: "open_all" })}>
                <Layers3 size={16} /> Mở tất cả Noka đang bật
              </button>
              <button className="secondary full" disabled={blocked || !profiles.some(p => p.hasAccount)} onClick={() => issue({ action: "login_all" })}>
                <ShieldCheck size={16} /> Nạp cookie và mở Noka
              </button>
            </aside>
          </div>

          {results[0] && tab !== "activity" && <section className="latest-command">
            <div><Activity size={16} /><b>Lệnh gần nhất</b></div>
            <CommandRow result={results[0]} />
          </section>}
        </>}

        <footer className="page-footer">
          <span>NOKA CONTROL <i /> roblox-cloud-farm</span>
          <span>Redfinger · Termux · Vercel · Firebase RTDB</span>
        </footer>
      </main>
    </div>

    {/* Modal Ghép máy */}
    {pair && <Modal title="Ghép máy Redfinger mới" onClose={() => setPair(false)}>
      <p className="muted">Lấy UID máy và Mã ghép từ agent chạy trong Termux (lệnh <code>python ~/noka-controller/noka_agent.py setup</code>). Mã ghép có hiệu lực trong 10 phút.</p>
      <form onSubmit={async event => {
        event.preventDefault(); setPending(true); setError("");
        const fields = new FormData(event.currentTarget);
        try {
          await api("/api/pair", Object.fromEntries(fields));
          setPair(false);
          setNotice("Đã ghép máy thành công. Đang đồng bộ trạng thái...");
          void refresh();
        } catch (problem) {
          setError(problem instanceof Error ? problem.message : "Chưa ghép được máy.");
        } finally { setPending(false); }
      }}>
        <label>Tên máy gợi nhớ<input name="name" required maxLength={60} placeholder="Ví dụ: Redfinger 01" /></label>
        <label>UID máy (từ terminal Termux)<input name="id" required autoComplete="off" placeholder="UID ngẫu nhiên do Firebase cấp" /></label>
        <label>Mã ghép (16 byte hex)<input name="code" required autoComplete="off" pattern="[a-f0-9]{32}" placeholder="32 ký tự hex" /></label>
        <button className="primary full" disabled={pending}>Xác nhận ghép máy <Link2 size={16} /></button>
        {error && <p role="alert" className="error-text">{error}</p>}
      </form>
    </Modal>}

    {/* Modal Sửa cấu hình & Tài khoản riêng cho từng Noka */}
    {editing && <Modal title={`Cấu hình ${editing.label}`} onClose={() => setEditing(null)}>
      <form onSubmit={async event => {
        event.preventDefault(); setPending(true); setError("");
        const fields = new FormData(event.currentTarget);
        const username = String(fields.get("username")).trim();
        const password = String(fields.get("password") ?? "");
        let cookie = String(fields.get("cookie") ?? "").trim();
        if (cookie.startsWith(".ROBLOSECURITY=")) {
          cookie = cookie.slice(".ROBLOSECURITY=".length).trim();
        }
        cookie = cookie.replace(/^["']|["']$/g, "").trim();

        const profileUpdate: Profile = {
          package: editing.package,
          label: String(fields.get("label")).trim(),
          username: username,
          slot: Number(fields.get("slot")),
          enabled: fields.get("enabled") === "on",
          autoReopen: fields.get("autoReopen") === "on"
        };

        try {
          // 1. Cập nhật profile metadata
          await send({ action: "profile", profile: profileUpdate });

          // 2. Nếu người dùng nhập cookie mới, mã hóa và gửi set_accounts cho riêng Noka này
          if (cookie) {
            if (!username) throw new Error("Cần nhập tên tài khoản Roblox khi thay đổi cookie.");
            if (!current?.telemetry?.publicKey) throw new Error("Máy chưa gửi khóa mã hóa public key.");
            const record = { package: editing.package, username, password, cookie };
            const sealed = await sealAccounts(current.telemetry.publicKey, [record]);
            await send({ action: "set_accounts", sealed });
          }

          setEditing(null);
          setNotice(`Đã lưu cấu hình cho ${editing.label}.`);
          void refresh();
        } catch (problem) {
          setError(problem instanceof Error ? problem.message : "Chưa lưu được cấu hình.");
        } finally { setPending(false); }
      }}>
        <label>Tên hiển thị Noka<input name="label" defaultValue={editing.label} required maxLength={60} /></label>
        <label>Vị trí trong lưới ô (Slot 1–36)<input name="slot" type="number" min="1" max="36" defaultValue={editing.slot} required /></label>
        
        <label className="checkbox-label" style={{ marginTop: 12 }}>
          <input type="checkbox" name="enabled" defaultChecked={editing.enabled} /> Bật quản lý bản Noka này
        </label>
        <label className="checkbox-label" style={{ marginTop: 8 }}>
          <input type="checkbox" name="autoReopen" defaultChecked={editing.autoReopen !== false} /> Cho phép Watchdog tự mở lại khi ứng dụng bị tắt
        </label>

        <div className="section-subhead">Gán / Thay đổi tài khoản cho bản Noka này</div>
        <label>Tên tài khoản Roblox (Username)<input name="username" defaultValue={editing.username} maxLength={80} placeholder="Ví dụ: RobloxPlayer_01" /></label>
        <label>Mật khẩu (Tùy chọn)<input name="password" type="password" placeholder="Để trống nếu chỉ dùng cookie" /></label>
        <label>Cookie (.ROBLOSECURITY - Điền để cập nhật mới)<textarea name="cookie" rows={3} placeholder="_|WARNING:-DO-NOT-SHARE-THIS..." /></label>

        <button className="primary full" disabled={pending} style={{ marginTop: 16 }}>Lưu cấu hình & Cập nhật <Check size={16} /></button>
        {error && <p className="error-text">{error}</p>}
      </form>
    </Modal>}

    {/* Modal Nhập hàng loạt tài khoản */}
    {credentials && <Modal title="Nhập hàng loạt tài khoản Noka" onClose={() => setCredentials(false)}>
      <p className="muted">
        Định dạng mỗi dòng: <code>username:password:cookie</code> (Có thể để trống mật khẩu: <code>username::cookie</code>).
      </p>

      {profiles.length > 0 && <div style={{ marginBottom: 12, fontSize: 11, color: "#8a97ad" }}>
        Thứ tự gán các dòng theo danh sách Noka hiện có:
        <table className="account-preview-table">
          <thead><tr><th>Dòng</th><th>Bản Noka</th><th>Package</th><th>Vị trí ô</th></tr></thead>
          <tbody>
            {profiles.map((p, i) => (
              <tr key={p.package}>
                <td>Dòng {i+1}</td>
                <td><b>{p.label}</b></td>
                <td>{p.package}</td>
                <td>Ô {p.slot}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>}

      <form onSubmit={async event => {
        event.preventDefault(); setPending(true); setError("");
        try {
          const parsed = parseBatchLines(batchInput);
          if (!parsed.length) throw new Error("Chưa nhập dòng tài khoản nào.");
          if (parsed.length > profiles.length) {
            throw new Error(`Đã nhập ${parsed.length} dòng nhưng máy chỉ có ${profiles.length} bản Noka.`);
          }

          const records = parsed.map((item, index) => {
            if (!item.username || !item.cookie) {
              throw new Error(`Dòng ${index+1} thiếu tên tài khoản hoặc cookie.`);
            }
            return {
              package: profiles[index].package,
              username: item.username,
              password: item.password,
              cookie: item.cookie
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
        } finally { setPending(false); }
      }}>
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
          Tất cả dữ liệu cookie/mật khẩu được mã hóa trực tiếp trong trình duyệt bằng WebCrypto (AES-256-GCM + RSA-OAEP). Vercel và Firebase không thể đọc bản rõ.
        </div>

        <button className="primary full" disabled={pending}>Mã hóa và gửi đến máy <ArrowRight size={16} /></button>
        {error && <p className="error-text">{error}</p>}
      </form>
    </Modal>}
  </div>;
}

function Brand() {
  return <div className="brand">
    <div className="brand-mark"><Grid2X2 size={22} /></div>
    <span>Noka<span className="brand-light">Control</span><small>CLOUD WORKSPACE</small></span>
  </div>;
}

function Stat({ label, value, icon, accent = false }: { label: string; value: number; icon: React.ReactNode; accent?: boolean }) {
  return <div className={`stat ${accent ? "accent" : ""}`}>
    <div><span>{label}</span><b>{String(value).padStart(2, "0")}</b></div>
    <div className="stat-icon">{icon}</div>
  </div>;
}

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { const element = dialog.current; element?.showModal(); return () => element?.close(); }, []);
  return <dialog ref={dialog} className="modal" aria-label={title} onCancel={event => { event.preventDefault(); onClose(); }} onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
    <div className="modal-heading"><h2>{title}</h2><button onClick={onClose} aria-label="Đóng"><X size={20} /></button></div>
    {children}
  </dialog>;
}

function CommandRow({ result }: { result: Result }) {
  const names = { queued: "Chờ nhận", running: "Đang chạy", succeeded: "Hoàn tất", failed: "Có lỗi" };
  return <div className="command-row">
    <div>
      <b>{actionNames[result.action] ?? result.action}</b>
      <p>{result.message ?? "Chờ agent cập nhật kết quả."}</p>
      <small>{new Date(result.createdAt).toLocaleString("vi-VN")}</small>
    </div>
    <span className={`result-status ${result.status}`}>{names[result.status]}</span>
  </div>;
}
