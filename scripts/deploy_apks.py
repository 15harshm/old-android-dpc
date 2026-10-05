#!/usr/bin/env python3
"""
Deploy built flavor APKs to each flavor's backend server in one command.

Reads a JSON config (default: scripts/deploy_targets.json, gitignored) that maps
each flavor to a server + remote path, and uploads
  <apk_dir>/<flavor>.apk  ->  <server>:<remote_path>
over FTP/FTPS (cPanel / aaPanel shared hosting) or SFTP (aaPanel/VPS over SSH).

Usage:
  python scripts/deploy_apks.py                     # deploy all flavors in config
  python scripts/deploy_apks.py --only paykist,rakshak
  python scripts/deploy_apks.py --config scripts/deploy_targets.json
  python scripts/deploy_apks.py --dry-run           # show what would upload, no network

Config shape — see scripts/deploy_targets.example.json.
  FTP/FTPS: no extra install needed (uses Python's built-in ftplib).
  SFTP:     pip install paramiko
"""
import argparse
import ftplib
import json
import os
import posixpath
import socket
import sys


def log(msg):
    print(msg, flush=True)


def install_dns_overrides(overrides):
    """Force specific hostnames to resolve to a given IPv4 address.

    Use when the machine's own DNS is broken/VPN'd and returns a wrong address
    for a target domain (symptom: FTPS to that domain hangs or resets while
    other servers — configured by raw IP — work fine). We still connect using
    the domain name, so TLS keeps verifying the real domain certificate; only
    the A-record lookup is substituted. Config key: "dns_overrides".
    """
    if not overrides:
        return
    real_getaddrinfo = socket.getaddrinfo

    def patched(host, port, *args, **kwargs):
        ip = overrides.get(host)
        if ip:
            return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port))]
        return real_getaddrinfo(host, port, *args, **kwargs)

    socket.getaddrinfo = patched
    log(f"(dns override active for: {', '.join(overrides)})")


def load_config(path):
    if not os.path.isfile(path):
        sys.exit(f"Config not found: {path}\nCopy scripts/deploy_targets.example.json to it and fill in your servers.")
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def upload_ftp(server, local_file, remote_path, secure):
    host = server["host"]
    port = int(server.get("port", 21))
    user = server["user"]
    password = server.get("password", "")
    remote_dir = posixpath.dirname(remote_path)
    remote_name = posixpath.basename(remote_path)

    ftp = ftplib.FTP_TLS() if secure else ftplib.FTP()
    ftp.connect(host, port, timeout=60)
    ftp.login(user, password)
    if secure:
        ftp.prot_p()  # secure the data channel
    # Make sure the remote directory exists, then cd into it.
    _ftp_make_dirs(ftp, remote_dir)
    if remote_dir:
        ftp.cwd(remote_dir)
    with open(local_file, "rb") as fh:
        ftp.storbinary(f"STOR {remote_name}", fh)
    ftp.quit()


def _ftp_make_dirs(ftp, remote_dir):
    if not remote_dir:
        return
    parts = remote_dir.strip("/").split("/")
    # Absolute vs relative: aaPanel often absolute (/www/wwwroot/..), cPanel relative (public_html/..)
    path = "/" if remote_dir.startswith("/") else ""
    for p in parts:
        path = posixpath.join(path, p) if path not in ("", "/") else (("/" + p) if remote_dir.startswith("/") else p)
        try:
            ftp.mkd(path)
        except ftplib.error_perm:
            pass  # already exists


def upload_sftp(server, local_file, remote_path):
    try:
        import paramiko  # noqa
    except ImportError:
        sys.exit("SFTP requires paramiko. Install it with:  pip install paramiko")
    host = server["host"]
    port = int(server.get("port", 22))
    user = server["user"]
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    if server.get("key"):
        client.connect(host, port=port, username=user, key_filename=server["key"], timeout=60)
    else:
        client.connect(host, port=port, username=user, password=server.get("password", ""), timeout=60)
    sftp = client.open_sftp()
    # Ensure remote dirs exist.
    remote_dir = posixpath.dirname(remote_path)
    _sftp_make_dirs(sftp, remote_dir)
    sftp.put(local_file, remote_path)
    sftp.close()
    client.close()


def _sftp_make_dirs(sftp, remote_dir):
    if not remote_dir:
        return
    parts = remote_dir.strip("/").split("/")
    path = "/" if remote_dir.startswith("/") else ""
    for p in parts:
        path = (path + "/" + p) if path else p
        norm = path if path.startswith("/") else path
        try:
            sftp.stat(norm)
        except IOError:
            try:
                sftp.mkdir(norm)
            except IOError:
                pass


def main():
    ap = argparse.ArgumentParser(description="Deploy flavor APKs to their servers.")
    ap.add_argument("--config", default=os.path.join(os.path.dirname(__file__), "deploy_targets.json"))
    ap.add_argument("--only", default="", help="comma-separated flavors to deploy (default: all)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    cfg = load_config(args.config)
    install_dns_overrides(cfg.get("dns_overrides"))
    apk_dir = cfg["apk_dir"]
    servers = cfg["servers"]
    flavors = cfg["flavors"]

    only = {s.strip() for s in args.only.split(",") if s.strip()}
    targets = {f: v for f, v in flavors.items() if not only or f in only}

    ok, fail = [], []
    for flavor, t in targets.items():
        # Local APK filename defaults to "<flavor>.apk" but can be overridden per
        # flavor via "local" (some flavors are saved under a branded name).
        local_name = t.get("local", f"{flavor}.apk")
        local = os.path.join(apk_dir, local_name)
        server_name = t["server"]
        server = servers.get(server_name)
        # Preferred: remote_dir + the same filename as local (so the server file is
        # named after the flavor, e.g. paykist.apk). Falls back to an explicit remote_path.
        if t.get("remote_dir"):
            remote = posixpath.join(t["remote_dir"], local_name)
        else:
            remote = t["remote_path"]
        if server is None:
            log(f"!! {flavor}: unknown server '{server_name}'"); fail.append(flavor); continue
        if not os.path.isfile(local):
            log(f"!! {flavor}: APK not found at {local} (build it first)"); fail.append(flavor); continue
        size = os.path.getsize(local) / 1e6
        method = server.get("method", "ftps").lower()
        log(f">> {flavor}: {local} ({size:.1f} MB) -> {server_name} [{method}] {remote}")
        if args.dry_run:
            ok.append(flavor); continue
        try:
            if method in ("ftp", "ftps"):
                upload_ftp(server, local, remote, secure=(method == "ftps"))
            elif method == "sftp":
                upload_sftp(server, local, remote)
            else:
                raise ValueError(f"unknown method '{method}'")
            log(f"   OK {flavor}")
            ok.append(flavor)
        except Exception as e:
            log(f"   FAIL {flavor}: {e}")
            fail.append(flavor)

    log("\n================ SUMMARY ================")
    log(f"OK  ({len(ok)}): {' '.join(ok)}")
    log(f"FAIL({len(fail)}): {' '.join(fail)}")
    sys.exit(1 if fail else 0)


if __name__ == "__main__":
    main()
