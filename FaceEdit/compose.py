"""Compositing engine: combines a recorded face video with a template video."""
import os
import subprocess
import cv2
import numpy as np

OUT_W, OUT_H = 720, 1280  # vertical 9:16, TikTok style
LAYOUTS = [
    "Circle overlay (face on template)",
    "Split screen (template top, you bottom)",
    "Face full screen + template corner",
    "Blend overlay (ghost face over template)",
]

_cascade = None


def _face_cascade():
    global _cascade
    if _cascade is None:
        try:
            _cascade = cv2.CascadeClassifier(
                os.path.join(cv2.data.haarcascades, "haarcascade_frontalface_default.xml"))
            if _cascade.empty():
                _cascade = False
        except Exception:
            _cascade = False  # face framing disabled; center crop is used
    return _cascade


def fill(frame, w, h, center=None):
    """Resize+crop frame to exactly w x h (cover). center = (cx, cy) in 0..1."""
    fh, fw = frame.shape[:2]
    scale = max(w / fw, h / fh)
    nw, nh = int(fw * scale) + 1, int(fh * scale) + 1
    r = cv2.resize(frame, (nw, nh), interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR)
    cx, cy = center or (0.5, 0.5)
    x = int(min(max(cx * nw - w / 2, 0), nw - w))
    y = int(min(max(cy * nh - h / 2, 0), nh - h))
    return r[y:y + h, x:x + w]


class FaceTracker:
    """Smoothed face-center estimate so crops keep your face framed."""

    def __init__(self):
        self.c = (0.5, 0.45)
        self.n = 0

    def update(self, frame):
        self.n += 1
        if self.n % 5 == 1:  # detect every 5th frame
            g = cv2.cvtColor(cv2.resize(frame, None, fx=0.5, fy=0.5), cv2.COLOR_BGR2GRAY)
            cas = _face_cascade()
            faces = cas.detectMultiScale(g, 1.2, 5, minSize=(40, 40)) if cas else []
            if len(faces):
                x, y, w, h = max(faces, key=lambda f: f[2] * f[3])
                H, W = g.shape
                t = ((x + w / 2) / W, (y + h / 2) / H)
                self.c = (self.c[0] * 0.7 + t[0] * 0.3, self.c[1] * 0.7 + t[1] * 0.3)
        return self.c


def circle_paste(base, face, d, cx, cy, border=6):
    mask = np.zeros((d, d), np.uint8)
    cv2.circle(mask, (d // 2, d // 2), d // 2, 255, -1)
    x0, y0 = cx - d // 2, cy - d // 2
    x0 = max(0, min(base.shape[1] - d, x0))
    y0 = max(0, min(base.shape[0] - d, y0))
    roi = base[y0:y0 + d, x0:x0 + d]
    m = (mask > 0)[..., None]
    roi[:] = np.where(m, face, roi)
    cv2.circle(base, (x0 + d // 2, y0 + d // 2), d // 2, (255, 255, 255), border, cv2.LINE_AA)


def draw_caption(img, text):
    if not text:
        return
    font = cv2.FONT_HERSHEY_DUPLEX
    scale = 1.3
    (tw, th), _ = cv2.getTextSize(text, font, scale, 3)
    while tw > img.shape[1] - 40 and scale > 0.4:
        scale -= 0.1
        (tw, th), _ = cv2.getTextSize(text, font, scale, 3)
    x = (img.shape[1] - tw) // 2
    y = int(img.shape[0] * 0.82)
    cv2.putText(img, text, (x, y), font, scale, (0, 0, 0), 8, cv2.LINE_AA)
    cv2.putText(img, text, (x, y), font, scale, (255, 255, 255), 3, cv2.LINE_AA)


def compose_frame(layout, tpl, face_frame, tracker, caption="", mirror=True):
    if mirror:
        face_frame = cv2.flip(face_frame, 1)
    c = tracker.update(face_frame)
    if layout == 0:
        out = fill(tpl, OUT_W, OUT_H)
        d = 330
        circle_paste(out, fill(face_frame, d, d, c), d, OUT_W - d // 2 - 30, OUT_H - d // 2 - 220)
    elif layout == 1:
        h = OUT_H // 2
        out = np.vstack([fill(tpl, OUT_W, h), fill(face_frame, OUT_W, OUT_H - h, c)])
    elif layout == 2:
        out = fill(face_frame, OUT_W, OUT_H, c)
        w, h = 250, 444
        small = fill(tpl, w, h)
        out[40:40 + h, OUT_W - w - 30:OUT_W - 30] = small
        cv2.rectangle(out, (OUT_W - w - 30, 40), (OUT_W - 30, 40 + h), (255, 255, 255), 4)
    else:
        a = fill(tpl, OUT_W, OUT_H)
        b = fill(face_frame, OUT_W, OUT_H, c)
        out = cv2.addWeighted(a, 0.55, b, 0.55, 0)
    out = np.ascontiguousarray(out)
    draw_caption(out, caption)
    return out


def _ffmpeg():
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return "ffmpeg"


def render(template_path, face_path, out_path, layout=0, caption="", mirror=True,
           progress=None, use_template_audio=True):
    """Render the edit. Length = shorter of the two videos. Returns out_path."""
    tcap = cv2.VideoCapture(template_path)
    fcap = cv2.VideoCapture(face_path)
    if not tcap.isOpened() or not fcap.isOpened():
        raise RuntimeError("Could not open template or recording.")
    fps = tcap.get(cv2.CAP_PROP_FPS) or 30
    if not (5 <= fps <= 120):
        fps = 30
    t_frames = int(tcap.get(cv2.CAP_PROP_FRAME_COUNT)) or 10 ** 9
    f_frames = int(fcap.get(cv2.CAP_PROP_FRAME_COUNT)) or 10 ** 9
    f_fps = fcap.get(cv2.CAP_PROP_FPS) or 30
    total = min(t_frames, int(f_frames * fps / f_fps))
    tmp = out_path + ".silent.mp4"
    vw = cv2.VideoWriter(tmp, cv2.VideoWriter_fourcc(*"mp4v"), fps, (OUT_W, OUT_H))
    tracker = FaceTracker()
    i = 0
    face_pos = 0.0
    last_face = None
    while i < total:
        ok, tf = tcap.read()
        if not ok:
            break
        # sample the face video by time so differing fps stay in sync
        target = int(i * f_fps / fps)
        while face_pos <= target:
            ok2, ff = fcap.read()
            if not ok2:
                break
            last_face = ff
            face_pos += 1
        if last_face is None:
            break
        vw.write(compose_frame(layout, tf, last_face, tracker, caption, mirror))
        i += 1
        if progress and i % 5 == 0:
            progress(i / max(total, 1))
    vw.release()
    tcap.release()
    fcap.release()
    if i == 0:
        raise RuntimeError("No frames were rendered.")

    cmd = [_ffmpeg(), "-y", "-loglevel", "error", "-i", tmp]
    if use_template_audio:
        cmd += ["-i", template_path, "-map", "0:v", "-map", "1:a?", "-shortest"]
    cmd += ["-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-c:a", "aac", out_path]
    r = subprocess.run(cmd, capture_output=True, text=True,
                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if r.returncode != 0:
        raise RuntimeError("ffmpeg failed: " + r.stderr[-400:])
    os.remove(tmp)
    if progress:
        progress(1.0)
    return out_path
