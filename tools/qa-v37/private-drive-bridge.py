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
import zipfile
from pathlib import Path

HOST = "https://www.googleapis.com"
ROOT = "1K4laogfioYHgnwXRzIEFzlUiweZ7-3_F"
BUILD_PARENT = "1TYXJbt70YO3Y60Tlcfh_MeIJs7rZWCft"  # 07_BUILD_ARTIFACTS
QA_PARENT = "11c69tA6CROAk9m3MiIDjGOTbv7yiWbdX"  # QA Builds
OUTPUT_PARENT = "1YShs0N0XR0U7jxQ-PmSCRfypqRGmY5Yj"  # dedicated signed-output child
INPUT_ID = "1J_Uzr3yV77R2AvP_5mhSqtZMFJLOuYHK"
INPUT_NAME = "OwnPlay-QA-v37-presign-unsigned-run-37101235385.zip"
INPUT_MIME = "application/zip"
APK_MIME = "application/vnd.android.package-archive"
INPUT_SIZE = 15135574
INPUT_SHA = "79a82dfc0eb3d3f0ae9a5f7f964db650e385089d4e42e84b3108b51a925a26ec"
APK_MEMBER = "OwnPlay-QA-v37-presign-aligned.apk"
APK_SIZE = 18961902
APK_SHA = "7faf4f50d189e55cc6591b7ef95f94bdb9f0ad5fb79d194e5438e152fcdc69cb"
PRESIGN_SHA_MEMBER = "PRESIGN_SHA256.txt"
PRESIGN_METADATA_MEMBER = "PRESIGN_METADATA.txt"
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
    require_folder(QA_PARENT, BUILD_PARENT)
    require_folder(OUTPUT_PARENT, QA_PARENT)
    m = metadata(INPUT_ID)
    if (m.get("id") != INPUT_ID or m.get("name") != INPUT_NAME or
        m.get("mimeType") != INPUT_MIME or m.get("parents") != [QA_PARENT] or
        m.get("trashed") or int(m.get("size", -1)) != INPUT_SIZE):
        raise ValueError("canonical unsigned v37 ZIP metadata mismatch")

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
    zip_fd, zip_name = tempfile.mkstemp(prefix=".ownplay-v37-source-", dir=str(directory))
    apk_name = None
    try:
        with os.fdopen(zip_fd, "wb") as out:
            os.fchmod(out.fileno(), 0o600)
            zip_size, zip_digest = read_content(INPUT_ID, out, INPUT_SIZE)
            out.flush()
            os.fsync(out.fileno())
        if zip_size != INPUT_SIZE or zip_digest != INPUT_SHA:
            raise ValueError("canonical unsigned v37 ZIP SHA-256 or size check failed")
        with zipfile.ZipFile(zip_name, "r") as archive:
            infos = archive.infolist()
            expected_names = {APK_MEMBER, PRESIGN_SHA_MEMBER, PRESIGN_METADATA_MEMBER}
            if len(infos) != 3 or {item.filename for item in infos} != expected_names:
                raise ValueError("canonical v37 ZIP member list mismatch")
            members = {item.filename: item for item in infos}
            for item in infos:
                mode = (item.external_attr >> 16) & 0o170000
                if item.is_dir() or mode not in (0, stat.S_IFREG) or item.flag_bits & 0x1:
                    raise ValueError("unsafe or encrypted ZIP member refused")
            checksum_text = archive.read(PRESIGN_SHA_MEMBER).decode("ascii")
            expected_checksum = APK_SHA + "  qa-output/" + APK_MEMBER + "\n"
            if checksum_text != expected_checksum:
                raise ValueError("embedded canonical APK SHA-256 mismatch")
            metadata_text = archive.read(PRESIGN_METADATA_MEMBER).decode("utf-8")
            metadata_fields = {}
            for line in metadata_text.splitlines():
                key, separator, value = line.partition(": ")
                if separator:
                    metadata_fields[key] = value
            required = {
                "SIGNED": "NO (presign only)",
                "PACKAGE": "app.ownplay.mobile",
                "VERSION_CODE": "37",
                "VERSION_NAME": "0.1.0-dev",
                "SOURCE_COMMIT": "e52aa593f3ac18ae96373c8b139e277d13c4a1f0",
                "SOURCE_TREE": "57c3e6fb137b5e650b55148a8c4ed2092e166caa",
            }
            if any(metadata_fields.get(key) != value for key, value in required.items()):
                raise ValueError("unsigned v37 metadata identity mismatch")
            apk_info = members[APK_MEMBER]
            if apk_info.file_size != APK_SIZE:
                raise ValueError("canonical unsigned v37 APK size mismatch")
            apk_fd, apk_name = tempfile.mkstemp(prefix=".ownplay-v37-apk-", dir=str(directory))
            digest = hashlib.sha256()
            size = 0
            with os.fdopen(apk_fd, "wb") as out, archive.open(apk_info, "r") as source:
                os.fchmod(out.fileno(), 0o600)
                while True:
                    part = source.read(1024 * 1024)
                    if not part:
                        break
                    size += len(part)
                    if size > APK_SIZE:
                        raise ValueError("unsigned v37 APK exceeds expected size")
                    digest.update(part)
                    out.write(part)
                out.flush()
                os.fsync(out.fileno())
            if size != APK_SIZE or digest.hexdigest() != APK_SHA:
                raise ValueError("canonical unsigned v37 APK SHA-256 or size check failed")
            os.replace(apk_name, target)
            apk_name = None
    finally:
        if os.path.exists(zip_name):
            os.unlink(zip_name)
        if apk_name and os.path.exists(apk_name):
            os.unlink(apk_name)
    secure_file(target)
    print("OWNPLAY_PRIVATE_INGRESS_PASS")
    print("INPUT_ZIP_SHA256=" + INPUT_SHA)
    print("INPUT_APK_SHA256=" + APK_SHA)

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

def preflight(private_dir):
    """Real scoped write + raw readback + cleanup BEFORE creating a QA key.

    This creates only a small, nonsecret temporary object in the dedicated
    signed-output folder. Ambiguous upload/deletion failure is fatal: do not
    retry blindly or generate signing material while it remains unresolved.
    """
    probe()
    directory = secure_dir(private_dir)
    fd, name = tempfile.mkstemp(prefix=".ownplay-transfer-preflight-", dir=str(directory))
    try:
        with os.fdopen(fd, "wb") as out:
            os.fchmod(out.fileno(), 0o600)
            out.write(b"OwnPlay QA v37 private Drive transfer check - NONSECRET\\n")
            out.flush()
            os.fsync(out.fileno())
        remote_id = resumable("OwnPlay-QA-v37-private-transfer-preflight.txt",
                              "text/plain", Path(name))
        with call("DELETE", "/drive/v3/files/" + remote_id + "?supportsAllDrives=true") as response:
            if response.status != 204:
                raise ValueError("private preflight cleanup was not confirmed")
    finally:
        if os.path.exists(name):
            os.unlink(name)
    print("OWNPLAY_PRIVATE_DRIVE_UPLOAD_READBACK_CLEANUP_PREFLIGHT_PASS")

def upload(apk, verification_file):
    require_canonical()
    apk = secure_file(apk)
    evidence = secure_file(verification_file)
    report = evidence.read_bytes()
    # Evidence must be nonsecret and generated by the protected signing step.
    if len(report) > 8192 or not report.startswith(b"OWNPLAY_QA_V37_SIGNED_VERIFIED\n"):
        raise ValueError("invalid nonsecret signature report")
    signed_size = apk.stat().st_size
    if signed_size < APK_SIZE or signed_size > APK_SIZE + 131072:
        raise ValueError("unexpected signed APK size")
    apk_sha = sha_of_file(apk)
    expected_line = ("SIGNED_APK_SHA256=" + apk_sha).encode("ascii")
    if expected_line not in report.splitlines():
        raise ValueError("signature report SHA-256 does not match signed input")
    apk_id = resumable(OUTPUT_NAME, APK_MIME, apk)
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
    elif len(sys.argv) == 3 and sys.argv[1] == "preflight":
        preflight(sys.argv[2])
    elif len(sys.argv) == 4 and sys.argv[1] == "upload":
        upload(sys.argv[2], sys.argv[3])
    else:
        raise ValueError("usage: private-drive-bridge.py probe | preflight <private-dir> | download <private-apk-path> | upload <signed-apk-path> <nonsecret-verification-report-path>")

try:
    main()
except (ValueError, OSError, urllib.error.URLError) as exc:
    # Keep logs nonsecret and non-provider-descriptive beyond coarse failure class.
    print("OWNPLAY_PRIVATE_DRIVE_BRIDGE_BLOCKED: " + type(exc).__name__, file=sys.stderr)
    sys.exit(2)
