# -*- coding: utf-8 -*-
"""
轮椅 3D 模拟器 · 启动器

双击后：用本机 Edge/Chrome 的「应用窗口模式」打开界面——
没有地址栏、没有标签页，是一个独立的桌面窗口。

自包含设计
----------
界面文件（index.html / css / js / lib）会被打包进 exe 本体。
启动时先在 exe 旁边找，找到就用（方便改代码热更新）；
找不到就把内置副本解到 %LOCALAPPDATA%\\Wheelchair3D\\web\\<版本>\\ 再打开。
所以 exe 单独拷到桌面、U 盘、任何地方都能直接双击运行。
"""

import ctypes
import os
import shutil
import subprocess
import sys
import tempfile
import time
import traceback

MB_OK = 0x0
MB_ICONERROR = 0x10

# 内置副本的版本号，改了界面文件就 +1，保证会重新解包
BUNDLE_VERSION = "2"

ASSETS = ("index.html", "css", "js", "lib")


def app_root():
    """exe（或脚本）所在目录。"""
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def bundle_root():
    """PyInstaller onefile 解包出来的临时目录（未打包时为 None）。"""
    return getattr(sys, "_MEIPASS", None)


def msg(text, title="轮椅 3D 模拟器"):
    ctypes.windll.user32.MessageBoxW(0, text, title, MB_OK | MB_ICONERROR)


def app_data():
    """应用数据根目录：%LOCALAPPDATA%\\Wheelchair3D（缓存、浏览器配置、日志都放这里）。"""
    return os.path.join(_cache_base(), "Wheelchair3D")


def log(text):
    # 优先写到 AppData：exe 放在桌面/U 盘时，不要在用户可见的目录里留 logs 文件夹
    for base in (app_data(), app_root()):
        try:
            d = os.path.join(base, "logs")
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, "launcher.log"), "a", encoding="utf-8") as f:
                f.write("%s %s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), text))
            return
        except Exception:
            continue


def _cache_base():
    return os.environ.get("LOCALAPPDATA") or tempfile.gettempdir()


def extract_bundle():
    """把打包在 exe 里的界面文件解到本地缓存，返回目录。"""
    src_root = bundle_root()
    if not src_root or not os.path.isfile(os.path.join(src_root, "index.html")):
        return None

    dest = os.path.join(app_data(), "web", BUNDLE_VERSION)
    if os.path.isfile(os.path.join(dest, "index.html")):
        return dest

    tmp = dest + ".tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    try:
        os.makedirs(tmp, exist_ok=True)
        for name in ASSETS:
            src = os.path.join(src_root, name)
            if os.path.isdir(src):
                shutil.copytree(src, os.path.join(tmp, name))
            elif os.path.isfile(src):
                shutil.copy2(src, os.path.join(tmp, name))
        shutil.rmtree(dest, ignore_errors=True)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        os.rename(tmp, dest)
        return dest
    except Exception:
        shutil.rmtree(tmp, ignore_errors=True)
        # 缓存写不进去就直接用临时解包目录，反正它是只读的
        return src_root if os.path.isfile(os.path.join(src_root, "index.html")) else None


def resolve_web():
    """返回 (含 index.html 的目录, 来源说明)。"""
    root = app_root()
    if os.path.isfile(os.path.join(root, "index.html")):
        return root, "同目录源码"
    got = extract_bundle()
    if got:
        return got, "内置副本"
    return None, "缺失"


def find_browser():
    cands = [
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        os.path.expandvars(r"%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe"),
        os.path.expandvars(r"%LOCALAPPDATA%\Microsoft\Edge\Application\msedge.exe"),
    ]
    for p in cands:
        if os.path.isfile(p):
            return p
    return None


def screen_size():
    u = ctypes.windll.user32
    try:
        u.SetProcessDPIAware()
    except Exception:
        pass
    return u.GetSystemMetrics(0), u.GetSystemMetrics(1)


def main():
    web, source = resolve_web()

    if not web:
        msg("找不到界面文件，也没能从程序内部释放出来。\n\n"
            "请把 Wheelchair3D.exe 放到一个你有写入权限的目录"
            "（例如 D:\\Wheelchair3D\\）再双击。")
        log("启动失败：界面文件缺失 root=%s" % app_root())
        return 1

    page = os.path.join(web, "index.html")
    url = "file:///" + page.replace("\\", "/")
    browser = find_browser()
    log("启动 page=%s 来源=%s browser=%s" % (page, source, browser))

    sw, sh = screen_size()
    w = int(min(1680, sw - 120))
    h = int(min(960, sh - 160))
    x = max(0, (sw - w) // 2)
    y = max(0, (sh - h) // 2 - 20)

    if browser:
        prof = os.path.join(app_data(), "browser")
        try:
            os.makedirs(prof, exist_ok=True)
        except Exception:
            prof = web
        args = [
            browser,
            "--app=" + url,
            "--user-data-dir=" + prof,
            "--window-size=%d,%d" % (w, h),
            "--window-position=%d,%d" % (x, y),
            "--no-first-run",
            "--no-default-browser-check",
            "--allow-file-access-from-files",
            # 只关翻译气泡；不要关 SmartScreen，否则顶部会挂一条安全警告条
            "--disable-features=Translate",
        ]
        # 自动化测试用：设置 WC_DEBUG_PORT 才开远程调试
        dbg = os.environ.get("WC_DEBUG_PORT")
        if dbg:
            args.append("--remote-debugging-port=" + dbg)
        try:
            subprocess.Popen(args, cwd=web,
                             creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
            return 0
        except Exception:
            log("启动浏览器失败\n" + traceback.format_exc())

    # 退化：交给系统默认浏览器
    try:
        os.startfile(page)
        return 0
    except Exception as e:
        msg("启动失败：%s" % e)
        return 1


if __name__ == "__main__":
    sys.exit(main())
