"""Invoked as root with the operator's cookie on stdin or payload file. Never print credentials."""
import json
from contextlib import closing
import os
from pathlib import Path
import re
import shutil
import sqlite3
import sys
import time

def write_cookie(package, cookie, data_root=Path('/data/data')):
    if not re.fullmatch(r'free\.noka[A-Za-z0-9_.]*', package) or not isinstance(cookie, str):
        raise ValueError('Package hoặc cookie không hợp lệ.')
    app = data_root/package
    choices = (app/'app_webview/Default/Cookies', app/'app_webview/Cookies')
    path = next((item for item in choices if item.is_file()), None)
    if path is None:
        return {'ok': False, 'message': f'Noka ({package}) chưa tạo kho cookie WebView. Cần mở Noka trước một lần.'}
    owner = path.stat()
    now = int(time.time()*1_000_000)+11644473600000000
    fields = {'creation_utc': now, 'host_key': '.roblox.com', 'top_frame_site_key': '', 'name': '.ROBLOSECURITY',
        'value': cookie, 'encrypted_value': b'', 'path': '/', 'expires_utc': now+31536000000000,
        'is_secure': 1, 'is_httponly': 1, 'last_access_utc': now, 'has_expires': 1, 'is_persistent': 1,
        'priority': 1, 'samesite': -1, 'source_scheme': 2, 'source_port': 443,
        'is_same_party': 0, 'last_update_utc': now, 'has_cross_site_ancestor': 0}
    backup = path.with_name('Cookies.noka-backup')
    with closing(sqlite3.connect(path, timeout=10)) as connection:
        with closing(sqlite3.connect(backup)) as destination:
            connection.backup(destination)
        os.chmod(backup, 0o600)
        columns = connection.execute('PRAGMA table_info(cookies)').fetchall()
        if not columns:
            return {'ok': False, 'message': 'Định dạng kho cookie WebView chưa được hỗ trợ.'}
        for _number, name, _kind, required, default, primary in columns:
            if required and default is None and not primary and name not in fields:
                return {'ok': False, 'message': 'Kho cookie có trường bắt buộc mới; cần cập nhật bộ ghi phù hợp.'}
        use = [row[1] for row in columns if row[1] in fields]
        connection.execute('BEGIN IMMEDIATE')
        connection.execute("DELETE FROM cookies WHERE host_key LIKE '%.roblox.com%'")
        connection.execute('INSERT INTO cookies ('+','.join('"'+name+'"' for name in use)+') VALUES ('+','.join('?' for _ in use)+')', [fields[name] for name in use])
        connection.commit()
        try:
            connection.execute('PRAGMA wal_checkpoint(FULL)').fetchall()
            connection.execute('PRAGMA journal_mode=DELETE').fetchall()
        except Exception:
            pass
        saved = connection.execute("SELECT value FROM cookies WHERE host_key = '.roblox.com' AND name = '.ROBLOSECURITY'").fetchone()
        if not saved or saved[0] != cookie:
            return {'ok': False, 'message': 'Đọc lại cookie trong SQLite không khớp.'}

    # Dọn dẹp tệp đệm để Noka đọc dữ liệu SQLite chính
    for ext in ('-wal', '-shm', '-journal'):
        f = path.with_name(path.name + ext)
        if f.is_file():
            try:
                f.unlink()
            except Exception:
                pass

    # Xóa cache login cũ của Roblox để app làm mới session
    shared_prefs = app/'shared_prefs'
    if shared_prefs.is_dir():
        for p in shared_prefs.glob('RBX_LOGIN*'):
            try:
                p.unlink()
            except Exception:
                pass

    os.chown(path, owner.st_uid, owner.st_gid)
    os.chmod(path, 0o660)
    return {'ok': True}

if __name__ == '__main__':
    try:
        if len(sys.argv) > 1 and os.path.exists(sys.argv[1]):
            with open(sys.argv[1], 'r', encoding='utf-8') as f:
                value = json.load(f)
        else:
            raw = sys.stdin.read(100000)
            value = json.loads(raw)
        result = write_cookie(value['package'], value['cookie'])
    except Exception as e:
        result = {'ok': False, 'message': f'Lỗi cookie_helper: {e}'}
    print(json.dumps(result, ensure_ascii=False))
