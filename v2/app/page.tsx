"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { GoogleAuthProvider, onAuthStateChanged, signInWithEmailAndPassword, signInWithPopup, signOut, type User } from "firebase/auth";
import { Activity, ArrowRight, Check, ChevronRight, CircleHelp, Cpu, Grid2X2, Layers3, LayoutDashboard,
  Link2, LoaderCircle, LogOut, Monitor, Plus, RefreshCw, ScanLine, Settings2, ShieldCheck, Smartphone, Square, X } from "lucide-react";
import { clientAuth } from "@/lib/firebase-client";
import { type Command, type Device, type Profile, type Result, isOnline } from "@/lib/contracts";
import { sealAccounts } from "@/lib/seal";

const auth = typeof window !== "undefined" ? clientAuth() : null;
const actionNames: Record<string, string> = { scan: "Quét bản Noka", open_all: "Mở các bản Noka", set_watch: "Tự mở lại",
  profile: "Lưu tài khoản", open: "Mở Noka", close: "Đóng Noka", grid: "Xếp lưới", enable_freeform: "Bật cửa sổ tự do",
  restore_windowing: "Khôi phục cửa sổ", status: "Cập nhật trạng thái", set_accounts: "Gửi tài khoản", login_all: "Nạp cookie và mở Noka" };

function sampleDevice(): Device {
  const profiles: Record<string, Profile> = {};
  [1, 2, 3, 4].forEach(n => { profiles[String(n)] = { package: `free.noka${String.fromCharCode(64+n)}`,
    label: `Noka ${n}`, username: `tai_khoan_${n}`, enabled: true, running: n !== 4, slot: [3, 4, 1, 2][n-1], hasAccount: true }; });
  return { id: "preview-device-only", meta: { name: "Redfinger 01", ownerUid: "preview" }, telemetry: {
    lastSeen: Date.now(), model: "Redfinger Cloud", android: "12", watch: true, profiles, grid: { rows: 2, cols: 2 }, freeform: true } };
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
    const response = await fetch(path, { method: payload === undefined ? "GET" : "POST",
      cache: "no-store", headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: payload === undefined ? undefined : JSON.stringify(payload) });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error ?? "Không thực hiện được yêu cầu.");
    return data;
  }, []);

  const refresh = useCallback(async () => {
    if (demo || !user) return;
    try { const data = await api("/api/devices"); setDevices(data.devices); setError(""); }
    catch (problem) { setError(problem instanceof Error ? problem.message : "Không đọc được dữ liệu máy."); }
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
      setNotice(`Đã gửi “${actionNames[command.action]}”. Chờ máy phản hồi trong nhật ký.`);
      void refresh();
    } catch (problem) { setError(problem instanceof Error ? problem.message : "Không gửi được lệnh."); throw problem; }
    finally { setPending(false); }
  }
  const issue = (command: Command) => { void send(command).catch(() => {}); };
  const blocked = pending || demo || !current || !online;

  if (initializing) return <main className="loading"><LoaderCircle className="spin" /><p>Đang mở bảng điều khiển…</p></main>;
  if (!user && !demo) return <main className="login-shell">
    <div className="login-art"><Brand /><div><span className="eyebrow">YOUR CLOUD, IN CONTROL</span><h1>Một nơi.<br />Mọi máy Noka.</h1><p>Quản lý tài khoản, mở lại ứng dụng và sắp xếp cửa sổ trên các máy Redfinger của bạn.</p>
      <div className="art-grid">{[3, 4, 1, 2].map(n => <div key={n}><Smartphone size={24} /><span>Noka {n}</span><i /></div>)}</div></div>
      <small><ShieldCheck size={15} /> Tài khoản được mã hóa trước khi gửi đến máy.</small></div>
    <div className="login-form"><div className="login-card"><span className="eyebrow">NOKA CONTROL</span><h2>Chào mừng trở lại</h2><p>Đăng nhập để kết nối với dàn máy của bạn.</p>
      {!auth ? <div className="setup-note"><Settings2 /><b>Cần hoàn tất kết nối Firebase</b><p>Mã dashboard đã sẵn sàng. Hãy thêm cấu hình Firebase và email quản trị trong phần cài đặt project Vercel.</p></div> : <>
      <button className="secondary full" disabled={pending} onClick={async () => { setPending(true); setError(""); try { const provider = new GoogleAuthProvider(); provider.setCustomParameters({prompt:"select_account"}); await signInWithPopup(auth, provider); } catch { setError("Chưa đăng nhập được Google. Kiểm tra cửa sổ đăng nhập và miền được phép trong Firebase."); } finally { setPending(false); } }}><b>G</b> Tiếp tục với Google</button>
      <div className="login-separator">hoặc dùng tài khoản Email/Password đã được cấp quyền</div>
      <form onSubmit={async event => { event.preventDefault(); setPending(true); setError("");
        const fields = new FormData(event.currentTarget);
        try { await signInWithEmailAndPassword(auth, String(fields.get("email")), String(fields.get("password"))); }
        catch { setError("Không đăng nhập được. Kiểm tra tài khoản và cấu hình Firebase Authentication."); }
        finally { setPending(false); }
      }}><label>Email quản trị<input name="email" type="email" autoComplete="username" required placeholder="Email của bạn" /></label>
      <label>Mật khẩu<input name="password" type="password" autoComplete="current-password" required placeholder="Nhập mật khẩu" /></label>
      <button className="primary full" disabled={pending}>{pending ? <LoaderCircle className="spin" size={17} /> : <>Đăng nhập <ArrowRight size={17} /></>}</button></form></>}
      {error && <p role="alert" className="error-text">{error}</p>}
      <button className="text-button demo-link" onClick={() => { setDemo(true); setDevices([sampleDevice()]); }}>Xem giao diện mẫu <ChevronRight size={14} /></button>
      <small className="login-foot">ROBLOX CLOUD CONTROLLER · REDFINGER</small></div></div>
  </main>;

  return <div className="app-shell">
    <aside className="sidebar"><Brand /><span className="nav-caption">KHÔNG GIAN LÀM VIỆC</span>
      <button className="nav-item active"><LayoutDashboard size={18} /> Điều khiển máy <span>{devices.length}</span></button>
      <div className="side-section"><div className="side-heading"><span>MÁY CỦA BẠN</span><button title="Ghép máy" onClick={() => setPair(true)} disabled={demo}><Plus size={16} /></button></div>
        {devices.map(device => <button key={device.id} className={`device-nav ${current?.id === device.id ? "selected" : ""}`} onClick={() => { setSelected(device.id); setTab("accounts"); }}>
          <span className={`dot ${isOnline(device.telemetry?.lastSeen) ? "green" : "gray"}`} /><span>{device.meta.name}<small>{Object.keys(device.telemetry?.profiles ?? {}).length} bản Noka</small></span><ChevronRight size={14} /></button>)}
        {!devices.length && <p className="side-empty">Máy đã ghép sẽ xuất hiện tại đây.</p>}
      </div>
      <div className="sidebar-bottom"><div className="connection-card"><ShieldCheck size={18} /><div>Kết nối riêng<small>Firebase · Mã hóa tài khoản</small></div></div>
        <button className="user-row" onClick={() => { if (demo) { setDemo(false); setDevices([]); } else if (auth) void signOut(auth); }}><span className="avatar">{(user?.email ?? "M")[0].toUpperCase()}</span><span>{demo ? "Chế độ xem mẫu" : user?.email}<small>Đăng xuất</small></span><LogOut size={16} /></button></div>
    </aside>
    <div className="workspace"><header className="topbar"><div className="breadcrumb">Không gian làm việc <ChevronRight size={14} /><b>Điều khiển máy</b></div><span className="live-label"><span className="dot green" /> Đồng bộ mỗi 10 giây</span></header>
      <main className="content">
        {demo && <div className="demo-banner"><CircleHelp size={17} /><span>Đây là dữ liệu mẫu. Các nút điều khiển máy đang tắt.</span><button onClick={() => { setDemo(false); setDevices([]); }}>Về đăng nhập</button></div>}
        <div className="page-heading"><div><span className="eyebrow">FLEET OVERVIEW</span><h1>Điều khiển máy</h1><p>Mọi tài khoản và cửa sổ Noka, ngay trong tầm tay.</p></div><button className="primary" onClick={() => setPair(true)} disabled={demo}><Plus size={17} /> Ghép máy mới</button></div>
        <div className="stats"><Stat label="Tổng số máy" value={devices.length} icon={<Monitor size={20} />} /><Stat label="Đang kết nối" value={devices.filter(d => isOnline(d.telemetry?.lastSeen)).length} icon={<Activity size={20} />} accent />
          <Stat label="Tài khoản đã gán" value={devices.reduce((total, d) => total+Object.values(d.telemetry?.profiles ?? {}).filter(p => p.hasAccount).length, 0)} icon={<Layers3 size={20} />} />
          <Stat label="Noka đang mở" value={devices.reduce((total, d) => total+(isOnline(d.telemetry?.lastSeen) ? Object.values(d.telemetry?.profiles ?? {}).filter(p => p.running).length : 0), 0)} icon={<Cpu size={20} />} /></div>
        {error && <div className="message error" role="alert">{error}<button onClick={() => setError("")} aria-label="Đóng lỗi"><X size={15} /></button></div>}
        {notice && <div className="message success" role="status"><Check size={16} />{notice}<button onClick={() => setNotice("")} aria-label="Đóng thông báo"><X size={15} /></button></div>}
        {!current ? <div className="empty-state"><div className="empty-icon"><Link2 size={32} /></div><h2>Kết nối máy Redfinger đầu tiên</h2><p>Chạy agent một lần trong Termux, sau đó dùng UID và mã ghép để thêm máy vào đây.</p><button className="primary" onClick={() => setPair(true)}><Plus size={17} /> Ghép máy mới</button></div> : <>
          <section className="device-header"><div className="device-symbol"><Smartphone size={24} /></div><div className="device-title"><h2>{current.meta.name}<span className={`badge ${online ? "online" : "offline"}`}>{online ? "Đang kết nối" : "Mất kết nối"}</span></h2><p>{current.telemetry?.model ?? "Đang chờ agent"} <span>·</span> Android {current.telemetry?.android ?? "—"} <span>·</span> {profiles.length} bản Noka</p></div>
            <button className="secondary" disabled={blocked} onClick={() => issue({ action: "scan" })}><ScanLine size={16} /> Quét Noka</button><button className="icon-button" disabled={pending || demo} onClick={() => { void refresh(); }} title="Làm mới dữ liệu"><RefreshCw size={17} /></button></section>
          {!online && <div className="offline-note">Chưa nhận được trạng thái mới từ máy. Agent cần đang chạy và có mạng để nhận lệnh.</div>}
          <div className="main-columns"><div className="main-panel"><div className="panel-tabs"><button className={tab === "accounts" ? "chosen" : ""} onClick={() => setTab("accounts")}>Tài khoản <span>{profiles.length}</span></button><button className={tab === "activity" ? "chosen" : ""} onClick={() => setTab("activity")}>Nhật ký lệnh</button></div>
            {tab === "accounts" ? <><div className="panel-toolbar"><div><h3>Các bản Noka</h3><p>Mỗi bản clone giữ một phiên tài khoản riêng.</p></div><button className="secondary compact" disabled={blocked || !profiles.length || !current.telemetry?.publicKey} onClick={() => setCredentials(true)}><Plus size={15} /> Nhập tài khoản</button></div>
            <div className="account-list">{profiles.map(profile => <div key={profile.package} className="account-row"><div className="noka-icon">{profile.label.match(/\d+/)?.[0] ?? "N"}</div><div className="account-info"><b>{profile.label}</b><span>{profile.username || "Chưa gán tài khoản"}</span><small>{profile.error || profile.loginMessage || `Vị trí ô ${profile.slot} · ${profile.package}`}</small></div>
              <span className={`account-status ${profile.running && online ? "running" : "stopped"}`}><span className={`dot ${profile.running && online ? "green" : "gray"}`} />{!online ? "Chưa rõ" : profile.running ? "Đang mở" : "Đã đóng"}</span>
              <button className="text-button" disabled={blocked} onClick={() => setEditing(profile)}>Sửa</button><button className="row-action" disabled={blocked} onClick={() => issue({ action: profile.running ? "close" : "open", package: profile.package })}>{profile.running ? <Square size={13} /> : <ArrowRight size={15} />}{profile.running ? "Đóng" : "Mở"}</button></div>)}</div>
            {!profiles.length && <div className="inline-empty">Chọn “Quét Noka” để tìm các bản đã cài trên máy.</div>}
            <div className="panel-footer"><ShieldCheck size={15} /><span>Mật khẩu và cookie chỉ được giải mã trên máy Redfinger.</span></div></> : <div className="command-list">{results.length ? results.map(result => <CommandRow key={result.id} result={result} />) : <div className="inline-empty">Lệnh gửi đến máy sẽ được hiển thị tại đây.</div>}</div>}
          </div>
          <aside className="controls-column"><section className="panel"><div className="control-title"><Grid2X2 size={18} /><h3>Sắp xếp cửa sổ</h3></div><p className="muted">Xếp Noka theo vị trí đã gán trên màn hình máy.</p><div className="grid-preview" style={{ gridTemplateColumns: `repeat(${cols}, 1fr)` }}>{Array.from({ length: rows*cols }, (_, i) => <div key={i}>{profiles.find(p => p.slot === i+1)?.label ?? <span>Ô {i+1}</span>}</div>)}</div>
            <div className="grid-fields"><label>Số hàng<select value={rows} onChange={e => setRows(Number(e.target.value))}>{[1, 2, 3, 4].map(n => <option key={n}>{n}</option>)}</select></label><label>Số cột<select value={cols} onChange={e => setCols(Number(e.target.value))}>{[1, 2, 3, 4].map(n => <option key={n}>{n}</option>)}</select></label></div>
            <button className="primary full" disabled={blocked || !profiles.length} onClick={() => issue({ action: "grid", rows, cols, gap: 6, top: 32, bottom: 36 })}><Grid2X2 size={16} /> Áp dụng lưới</button>
            <button className="text-button full freeform-button" disabled={blocked} onClick={() => issue({ action: "enable_freeform" })}>Bật cửa sổ tự do</button><button className="text-button full" disabled={blocked} onClick={() => issue({ action: "restore_windowing" })}>Khôi phục cấu hình cửa sổ</button>
          </section><section className="panel"><div className="watch-heading"><span className="watch-icon"><RefreshCw size={18} /></span><div><h3>Tự mở lại</h3><p>{current.telemetry?.watch ? "Đang theo dõi ứng dụng" : "Đang tắt"}</p></div><button role="switch" aria-checked={Boolean(current.telemetry?.watch)} aria-label="Tự mở lại Noka" className={`switch ${current.telemetry?.watch ? "on" : ""}`} disabled={blocked} onClick={() => issue({ action: "set_watch", enabled: !current.telemetry?.watch })}><span /></button></div><p className="muted">Tự mở Noka khi tiến trình đóng. Đóng từ dashboard sẽ tạm dừng theo dõi bản đó.</p></section>
          <button className="secondary full" disabled={blocked} onClick={() => issue({ action: "open_all" })}><Layers3 size={16} /> Mở các Noka đang quản lý</button><button className="secondary full" disabled={blocked || !profiles.some(p => p.hasAccount)} onClick={() => issue({ action: "login_all" })}><ShieldCheck size={16} /> Nạp cookie và mở Noka</button></aside></div>
          {results[0] && tab !== "activity" && <section className="latest-command"><div><Activity size={16} /><b>Lệnh gần nhất</b></div><CommandRow result={results[0]} /></section>}
        </>}
        <footer className="page-footer"><span>NOKA CONTROL <i /> roblox-cloud-farm</span><span>Redfinger · Termux · Vercel</span></footer>
      </main>
    </div>
    {pair && <Modal title="Ghép máy Redfinger" onClose={() => setPair(false)}><p className="muted">Lấy UID và mã ghép từ agent trong Termux. Mỗi mã có hiệu lực 10 phút.</p><form onSubmit={async event => {
      event.preventDefault(); setPending(true); setError(""); const fields = new FormData(event.currentTarget);
      try { await api("/api/pair", Object.fromEntries(fields)); setPair(false); setNotice("Đã ghép máy. Chờ agent gửi trạng thái."); void refresh(); }
      catch (problem) { setError(problem instanceof Error ? problem.message : "Chưa ghép được máy."); }
      finally { setPending(false); }
    }}><label>Tên máy<input name="name" required maxLength={60} placeholder="Ví dụ: Redfinger 01" /></label><label>UID máy<input name="id" required autoComplete="off" /></label><label>Mã ghép<input name="code" required autoComplete="off" pattern="[a-f0-9]{32}" /></label><button className="primary full" disabled={pending}>Ghép máy <Link2 size={16} /></button>{error && <p role="alert" className="error-text">{error}</p>}</form></Modal>}
    {editing && <Modal title={`Quản lý ${editing.label}`} onClose={() => setEditing(null)}><form onSubmit={async event => {
      event.preventDefault(); const fields = new FormData(event.currentTarget);
      const profile = { package: editing.package, label: String(fields.get("label")), username: String(fields.get("username")), slot: Number(fields.get("slot")), enabled: fields.get("enabled") === "on" };
      try { await send({ action: "profile", profile }); setEditing(null); } catch {}
    }}><label>Tên Noka<input name="label" defaultValue={editing.label} required maxLength={60} /></label><label>Tên tài khoản<input name="username" defaultValue={editing.username} maxLength={80} /></label><label>Vị trí trong lưới<input name="slot" type="number" min="1" max="36" defaultValue={editing.slot} required /></label><label className="checkbox-label"><input type="checkbox" name="enabled" defaultChecked={editing.enabled} /> Bật quản lý bản này</label><button className="primary full" disabled={pending}>Lưu cấu hình <Check size={16} /></button>{error && <p className="error-text">{error}</p>}</form></Modal>}
    {credentials && <Modal title="Nhập tài khoản Noka" onClose={() => setCredentials(false)}><p className="muted">Mỗi dòng: <code>tên:mật khẩu:cookie</code>. Các dòng được gán theo thứ tự: {profiles.map(p => p.label).join(", ")}. Có thể để trống mật khẩu khi dùng cookie.</p><form onSubmit={async event => {
      event.preventDefault(); const form = event.currentTarget; const text = String(new FormData(form).get("accounts"));
      try {
        const lines = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
        if (!lines.length || lines.length > profiles.length) throw new Error("Số dòng phải từ 1 đến số bản Noka đang quản lý.");
        const records = lines.map((line, index) => { const parts = line.split(":");
          if (parts.length < 3 || !parts[0] || !parts.slice(2).join(":")) throw new Error(`Dòng ${index+1} chưa đủ tên và cookie.`);
          return { package: profiles[index].package, username: parts[0].trim(), password: parts[1], cookie: parts.slice(2).join(":").trim() };
        });
        if (!current?.telemetry?.publicKey) throw new Error("Máy chưa gửi khóa mã hóa. Hãy chờ cập nhật trạng thái.");
        const sealed = await sealAccounts(current.telemetry.publicKey, records);
        await send({ action: "set_accounts", sealed }); form.reset(); setCredentials(false);
      } catch (problem) { setError(problem instanceof Error ? problem.message : "Chưa gửi được tài khoản."); }
    }}><label>Danh sách tài khoản<textarea name="accounts" rows={7} required autoComplete="off" spellCheck={false} placeholder="tai_khoan_1::cookie_cua_ban" /></label><div className="privacy-note"><ShieldCheck size={16} /> Nội dung được mã hóa trong trình duyệt. Firebase và Vercel không nhận bản rõ.</div><button className="primary full" disabled={pending}>Mã hóa và gửi đến máy <ArrowRight size={16} /></button>{error && <p className="error-text">{error}</p>}</form></Modal>}
  </div>;
}

function Brand() { return <div className="brand"><div className="brand-mark"><Grid2X2 size={22} /></div><span>Noka<span className="brand-light">Control</span><small>CLOUD WORKSPACE</small></span></div>; }
function Stat({ label, value, icon, accent = false }: { label: string; value: number; icon: React.ReactNode; accent?: boolean }) {
  return <div className={`stat ${accent ? "accent" : ""}`}><div><span>{label}</span><b>{String(value).padStart(2, "0")}</b></div><div className="stat-icon">{icon}</div></div>;
}
function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { const element = dialog.current; element?.showModal(); return () => element?.close(); }, []);
  return <dialog ref={dialog} className="modal" aria-label={title} onCancel={event => { event.preventDefault(); onClose(); }} onClick={event => { if (event.target === event.currentTarget) onClose(); }}><div className="modal-heading"><h2>{title}</h2><button onClick={onClose} aria-label="Đóng"><X size={20} /></button></div>{children}</dialog>;
}
function CommandRow({ result }: { result: Result }) {
  const names = { queued: "Chờ nhận", running: "Đang chạy", succeeded: "Hoàn tất", failed: "Có lỗi" };
  return <div className="command-row"><div><b>{actionNames[result.action] ?? result.action}</b><p>{result.message ?? "Chờ agent cập nhật kết quả."}</p><small>{new Date(result.createdAt).toLocaleString("vi-VN")}</small></div><span className={`result-status ${result.status}`}>{names[result.status]}</span></div>;
}
