#!/usr/bin/env python3
"""OwnPlay QA v37: restricted direct Drive file ingress/egress, NONSECRET code.

This is deliberately NOT an Actions workflow, an OAuth provisioning mechanism, a
key generator, or a public artifact uploader. The caller must first provide a
separately verified restricted qa-signing runner and a valid protected Drive
access token with read permission for the one input and create/read permission
in the canonical output folder. The token is never accepted as a CLI argument.
No external hosts other than Google Drive API; no redirects or proxy traversal.
"""
import hashlib
import json
import os
import stat
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HOST = "https://www.googleapis.com"
ROOT = "1K4laogfioYHgnwXRzIEFzlUiweZ7-3_F"
BUILD_PARENT = "10PNx--XlYzrpuUKZnRWFqw5K823UIoTR"  # 07_BUILD_ARTIFACTS
OUTPUT_PARENT = "18LOFaWlM0TCSYvctOpLGIDMWwGCDbiez"  # QA Builds
INPUT_PARENT = "1fzepCqj_Pkkm2vm5DF5i20FEJk27ezYY"
INPUT_ID = "1dizPKUUXYr5_GC2qrI2e6IlvRvPv77DD"
INPUT_NAME = "OwnPlay-QA-v37-presign-aligned.apk"
INPUT_MIME = "application/vnd.android.package-archive"
INPUT_SIZE = 18912498
INPUT_SHA = "4ea97b39997b97427d829df0db79019c05acaf92438006bd85626c2d496991cf"
OUTPUT_NAME = "OwnPlay-QA-v37-clean-install-signed.apk"
EVIDENCE_NAME = "OwnPlay-QA-v37-clean-install-signature-report.txt"
TOKEN = os.environ.get("OWNPLAY_DRIVE_ACCESS_TOKEN", "")
if not TOKEN or any(c.isspace() for c in TOKEN):
    raise SystemExit("BLOCKED: protected Drive authentication is not configured")
if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("OWNPLAY_QA_ENVIRONMENT") != "qa-signing":
    raise SystemExit("BLOCKED: restricted qa-signing runner is required")

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise ValueError("HTTP redirect refused; protected token cannot change host")

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

def call(method, suffix, body=None, content_type=None):
    if not suffix.startswith("/") or "//" in suffix:
        raise ValueError("invalid fixed Drive API path")
    headers = {"Authorization": "Bearer " + TOKEN, "Accept": "application/json"}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(HOST + suffix, data=body, headers=headers, method=method)
    try:
        return OPENER.open(req, timeout=120)
    except urllib.error.HTTPError as err:
        # Never print response bodies or token-bearing request headers.
        raise ValueError("Drive API refused request, HTTP status " + str(err.code)) from None

def metadata(file_id, fields="id,name,mimeType,size,parents,trashed"):
    with call("GET", "/drive/v3/files/" + file_id + "?supportsAllDrives=true&fields=" +
              urllib.parse.quote(fields, safe=",")) as response:
        return json.load(response)

def require_folder(folder_id, parent):
    m = metadata(folder_id)
    if m.get("id") != folder_id or m.get("trashed") or m.get("parents") != [parent] or m.get("mimeType") != "application/vnd.google-apps.folder":
        raise ValueError("canonical Drive folder ancestry check failed")

def require_canonical():
    require_folder(BUILD_PARENT, ROOT)
    require_folder(OUTPUT_PARENT, BUILD_PARENT)
    require_folder("1LeSanmjFHeTE6cx8BNMMLtOyk0RLr8uI", ROOT)  # QA audit root
    require_folder(INPUT_PARENT, "1LeSanmjFHeTE6cx8BNMMLtOyk0RLr8uI")
    m = metadata(INPUT_ID)
    if (m.get("id") != INPUT_ID or m.get("name") != INPUT_NAME or
        m.get("mimeType") != INPUT_MIME or m.get("parents") != [INPUT_PARENT] or
        m.get("trashed") or int(m.get("size", -1)) != INPUT_SIZE):
        raise ValueError("canonical APK input metadata mismatch")

def secure_dir(path):
    path = Path(path).resolve(strict=True)
    st = path.stat()
    if not stat.S_ISDIR(st.st_mode) or (st.st_mode & 0o077) or st.st_uid != os.getuid():
        raise ValueError("private working directory must be owned by runner and mode 0700")
    return path

def secure_file(path):
    path = Path(path)
    if path.is_symlink():
        raise ValueError("symlink input refused")
    st = path.stat()
    if not stat.S_ISREG(st.st_mode) or (st.st_mode & 0o077) or st.st_uid != os.getuid():
        raise ValueError("private file must be owned by runner and mode 0600")
    secure_dir(path.parent)
    return path

def sha_of_file(path):
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for piece in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(piece)
    return h.hexdigest()

def stream_and_hash(response, output=None, upper_bound=None):
    size = 0
    h = hashlib.sha256()
    while True:
        part = response.read(1024 * 1024)
        if not part:
            break
        size += len(part)
        if upper_bound is not None and size > upper_bound:
            raise ValueError("response exceeds expected private file size")
        h.update(part)
        if output is not None:
            output.write(part)
    return size, h.hexdigest()

def read_content(file_id, output=None, upper_bound=None):
    with call("GET", "/drive/v3/files/" + file_id + "?alt=media&supportsAllDrives=true") as response:
        return stream_and_hash(response, output, upper_bound)

def probe():
    require_canonical()
    m = metadata(OUTPUT_PARENT, "id,name,mimeType,parents,trashed,capabilities(canAddChildren)")
    if m.get("capabilities", {}).get("canAddChildren") is not True:
        raise ValueError("authenticated output folder cannot accept a new private QA file")
    print("OWNPLAY_PRIVATE_DRIVE_AUTH_AND_FOLDER_PREFLIGHT_PASS")

def download(target):
    target = Path(target)
    directory = secure_dir(target.parent)
    if target.exists() or target.is_symlink():
        raise ValueError("private APK destination already exists")
    require_canonical()
    fd, name = tempfile.mkstemp(prefix=".ownplay-v37-", dir=str(directory))
    try:
        with os.fdopen(fd, "wb") as out:
            os.fchmod(out.fileno(), 0o600)
            size, digest = read_content(INPUT_ID, out, INPUT_SIZE)
            out.flush()
            os.fsync(out.fileno())
        if size != INPUT_SIZE or digest != INPUT_SHA:
            raise ValueError("canonical APK SHA-256 or size check failed")
        os.replace(name, target)
    finally:
        if os.path.exists(name):
            os.unlink(name)
    print("OWNPLAY_PRIVATE_INGRESS_PASS")
    print("INPUT_SHA256=" + INPUT_SHA)

def resumable(name, mime, private_file):
    """Upload with Drive resumable protocol. No third-party hosts, no retries.

    Only a completed, raw-byte-readback-matched file is eligible as an output.
    A partial/ambiguous upload stops; do not blindly retry and create duplicates.
    """
    file_path = secure_file(private_file)
    size = file_path.stat().st_size
    digest = sha_of_file(file_path)
    meta = json.dumps({"name": name, "mimeType": mime, "parents": [OUTPUT_PARENT]},
                      separators=(",", ":")).encode("utf-8")
    headers = {"Authorization": "Bearer " + TOKEN,
               "Content-Type": "application/json; charset=UTF-8",
               "X-Upload-Content-Type": mime,
               "X-Upload-Content-Length": str(size)}
    init_url = HOST + "/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true&fields=id,name,mimeType,size,parents,trashed"
    req = urllib.request.Request(init_url, data=meta, headers=headers, method="POST")
    try:
        with OPENER.open(req, timeout=120) as response:
            location = response.headers.get("Location", "")
    except urllib.error.HTTPError as err:
        raise ValueError("Drive resumable initialization refused, HTTP " + str(err.code)) from None
    parsed = urllib.parse.urlsplit(location)
    if (parsed.scheme != "https" or parsed.netloc != "www.googleapis.com" or
        parsed.path != "/upload/drive/v3/files" or parsed.fragment or
        not any(k == "upload_id" and v for k, v in urllib.parse.parse_qsl(parsed.query, keep_blank_values=True))):
        raise ValueError("unsafe Drive resumable session location refused")
    final = None
    sent = 0
    block_size = 1024 * 1024  # 256 KiB-aligned chunks
    with file_path.open("rb") as source:
        while sent < size:
            chunk = source.read(block_size)
            end = sent + len(chunk) - 1
            if not chunk or end >= size:
                raise ValueError("local upload input changed")
            put = urllib.request.Request(location, data=chunk,
                 headers={"Authorization": "Bearer " + TOKEN,
                          "Content-Type": mime,
                          "Content-Length": str(len(chunk)),
                          "Content-Range": "bytes " + str(sent) + "-" + str(end) + "/" + str(size)},
                 method="PUT")
            try:
                with OPENER.open(put, timeout=120) as response:
                    if end != size - 1 or response.status not in (200, 201):
                        raise ValueError("unexpected early completion from Drive")
                    final = json.load(response)
            except urllib.error.HTTPError as err:
                if err.code != 308 or end == size - 1:
                    raise ValueError("Drive resumable chunk refused, HTTP " + str(err.code)) from None
                accepted = err.headers.get("Range", "")
                if accepted != "bytes=0-" + str(end):
                    raise ValueError("Drive upload acknowledgement offset mismatch")
            sent = end + 1
    if sent != size or not isinstance(final, dict):
        raise ValueError("Drive upload not completed")
    remote_id = final.get("id")
    if (not isinstance(remote_id, str) or not remote_id or final.get("trashed") or
        final.get("name") != name or final.get("mimeType") != mime or
        final.get("parents") != [OUTPUT_PARENT] or int(final.get("size", -1)) != size):
        raise ValueError("Drive upload metadata mismatch; do not retry blindly")
    remote_meta = metadata(remote_id)
    if (remote_meta.get("id") != remote_id or remote_meta.get("parents") != [OUTPUT_PARENT] or
        remote_meta.get("name") != name or remote_meta.get("mimeType") != mime or
        int(remote_meta.get("size", -1)) != size):
        raise ValueError("Drive raw-readback metadata mismatch")
    remote_size, remote_digest = read_content(remote_id, upper_bound=size)
    if remote_size != size or remote_digest != digest:
        raise ValueError("Drive raw-readback content mismatch; do not promote")
    return remote_id

def upload(apk, verification_file):
    require_canonical()
    apk = secure_file(apk)
    evidence = secure_file(verification_file)
    report = evidence.read_bytes()
    # Evidence must be nonsecret and generated by the protected signing step.
    if len(report) > 8192 or not report.startswith(b"OWNPLAY_QA_V37_SIGNED_VERIFIED\n"):
        raise ValueError("invalid nonsecret signature report")
    signed_size = apk.stat().st_size
    if signed_size < INPUT_SIZE or signed_size > INPUT_SIZE + 131072:
        raise ValueError("unexpected signed APK size")
    apk_sha = sha_of_file(apk)
    expected_line = ("SIGNED_APK_SHA256=" + apk_sha).encode("ascii")
    if expected_line not in report.splitlines():
        raise ValueError("signature report SHA-256 does not match signed input")
    apk_id = resumable(OUTPUT_NAME, INPUT_MIME, apk)
    print("OWNPLAY_PRIVATE_SIGNED_APK_READBACK_PASS")
    print("SIGNED_APK_SHA256=" + apk_sha)
    print("SIGNED_APK_DRIVE_ID=" + apk_id)
    evidence_id = resumable(EVIDENCE_NAME, "text/plain", evidence)
    print("OWNPLAY_PRIVATE_EVIDENCE_READBACK_PASS")
    print("EVIDENCE_DRIVE_ID=" + evidence_id)

def main():
    if len(sys.argv) == 2 and sys.argv[1] == "probe":
        probe()
    elif len(sys.argv) == 3 and sys.argv[1] == "download":
        download(sys.argv[2])
    elif len(sys.argv) == 4 and sys.argv[1] == "upload":
        upload(sys.argv[2], sys.argv[3])
    else:
        raise ValueError("usage: private-drive-bridge.py probe | download <private-apk-path> | upload <signed-apk-path> <nonsecret-verification-report-path>")

try:
    main()
except (ValueError, OSError, urllib.error.URLError) as exc:
    # Keep logs nonsecret and non-provider-descriptive beyond coarse failure class.
    print("OWNPLAY_PRIVATE_DRIVE_BRIDGE_BLOCKED: " + type(exc).__name__, file=sys.stderr)
    sys.exit(2)
