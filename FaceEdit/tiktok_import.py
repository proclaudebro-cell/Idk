"""Turn a TikTok data export (Favorite Videos) into local template videos."""
import json
import os
import re
import zipfile

LINK_RE = re.compile(r"https?://(?:www\.|vm\.|vt\.|m\.)?tiktok\.com/[^\s\"'<>\\]+", re.I)


def _walk(node, under_fav, out):
    if isinstance(node, dict):
        for k, v in node.items():
            _walk(v, under_fav or "favorite" in str(k).lower() and "video" in str(k).lower(), out)
    elif isinstance(node, list):
        for v in node:
            _walk(v, under_fav, out)
    elif isinstance(node, str) and under_fav:
        out.extend(LINK_RE.findall(node))


def _from_text(text):
    out, on = [], False
    for line in text.splitlines():
        low = line.strip().lower()
        if low.endswith(":") and "favorite" in low:
            on = "video" in low
            continue
        if low and low.endswith(":") and not low.startswith(("date", "link")):
            on = False if "favorite" not in low else on
        if on:
            out.extend(LINK_RE.findall(line))
    return out


def parse_favorites(path):
    """Return de-duplicated favorite-video links from a TikTok export (.json/.txt/.zip)."""
    if path.lower().endswith(".zip"):
        links = []
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.lower().endswith((".json", ".txt")):
                    links += _parse_text(z.read(n).decode("utf-8", "ignore"), n)
        return list(dict.fromkeys(links))
    with open(path, encoding="utf-8", errors="ignore") as f:
        return list(dict.fromkeys(_parse_text(f.read(), path)))


def _parse_text(text, name):
    out = []
    if name.lower().endswith(".json"):
        try:
            _walk(json.loads(text), False, out)
        except ValueError:
            pass
    else:
        out = _from_text(text)
    return out


def download(links, dest, progress=None, stop=lambda: False):
    """Download each link with yt-dlp. Returns (ok_count, failed_links)."""
    import yt_dlp  # imported lazily so the rest of the app works without it

    os.makedirs(dest, exist_ok=True)
    opts = {
        "outtmpl": os.path.join(dest, "tt_%(id)s.%(ext)s"),
        "format": "mp4/best",
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "retries": 2,
        "download_archive": os.path.join(dest, ".downloaded.txt"),
    }
    ok, failed = 0, []
    with yt_dlp.YoutubeDL(opts) as ydl:
        for i, url in enumerate(links):
            if stop():
                break
            try:
                ydl.download([url])
                ok += 1
            except Exception:
                failed.append(url)
            if progress:
                progress(i + 1, len(links))
    return ok, failed
