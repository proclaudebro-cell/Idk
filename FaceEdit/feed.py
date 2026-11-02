"""Floating always-on-top feed that auto-scrolls through your saved edits."""
import os
import random
import tkinter as tk
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageTk

LIBRARY = Path.home() / "Videos" / "FaceEdit"
EXTS = {".mp4", ".mov", ".mkv", ".webm"}
FEED_W, FEED_H = 360, 640


def library_clips():
    LIBRARY.mkdir(parents=True, exist_ok=True)
    return sorted((p for p in LIBRARY.iterdir() if p.suffix.lower() in EXTS),
                  key=lambda p: p.stat().st_mtime, reverse=True)


def slide_frame(prev, nxt, t):
    """Vertical scroll transition: t=0 shows prev, t=1 shows nxt (swipe up)."""
    h = prev.shape[0]
    off = int(round(min(max(t, 0.0), 1.0) * h))
    return np.vstack([prev, nxt])[off:off + h]


def ease(t):
    return t * t * (3 - 2 * t)


class Feed(tk.Toplevel):
    TRANSITION_FRAMES = 14

    def __init__(self, master, on_close=None):
        super().__init__(master)
        self.on_close_cb = on_close
        self.overrideredirect(True)
        self.attributes("-topmost", True)
        try:
            self.attributes("-alpha", 0.97)
        except tk.TclError:
            pass
        sw, sh = self.winfo_screenwidth(), self.winfo_screenheight()
        self.geometry(f"{FEED_W}x{FEED_H}+{sw - FEED_W - 30}+{max(sh - FEED_H - 80, 0)}")
        self.label = tk.Label(self, bg="black", bd=0)
        self.label.pack(fill="both", expand=True)
        self.hint = tk.Label(self, text="drag to move · click/↓ next · right-click/Esc close",
                             bg="#111", fg="#aaa", font=("Segoe UI", 8))
        self.hint.place(relx=0.5, rely=0.0, anchor="n")
        self.after(3500, self.hint.place_forget)

        self.clips = []
        self.order = []
        self.cap = None
        self.last = np.zeros((FEED_H, FEED_W, 3), np.uint8)
        self.trans = None          # (next_first_frame, step)
        self.pending = None        # opened capture waiting for transition end
        self._drag = None
        self._moved = False
        self.running = True

        for w in (self, self.label):
            w.bind("<ButtonPress-1>", self._press)
            w.bind("<B1-Motion>", self._motion)
            w.bind("<ButtonRelease-1>", self._release)
            w.bind("<Button-3>", lambda e: self.close())
            w.bind("<MouseWheel>", lambda e: self.next_clip())
        self.bind("<Escape>", lambda e: self.close())
        self.bind("<Down>", lambda e: self.next_clip())
        self.bind("<space>", lambda e: self.next_clip())
        self.focus_force()

        self.reload()
        self.next_clip(first=True)
        self.tick()

    # ----- clip management -----
    def reload(self):
        self.clips = library_clips()
        self.order = list(self.clips)
        random.shuffle(self.order)

    def _open_next(self):
        for _ in range(len(self.order) + 1):
            if not self.order:
                self.reload()
                if not self.order:
                    return None
            p = self.order.pop()
            cap = cv2.VideoCapture(str(p))
            if cap.isOpened():
                return cap
        return None

    def next_clip(self, first=False):
        if self.trans is not None:
            return
        cap = self._open_next()
        if cap is None:
            self._show_message()
            return
        ok, frame = cap.read()
        if not ok:
            cap.release()
            return
        nxt = self._fit(frame)
        if first:
            self.cap = cap
            self.last = nxt
            self._show(nxt)
        else:
            self.pending = cap
            self.trans = (nxt, 0)

    def _show_message(self):
        img = np.zeros((FEED_H, FEED_W, 3), np.uint8)
        for i, line in enumerate(["No edits yet.", "Record or auto-edit,", "then open the feed."]):
            cv2.putText(img, line, (30, 280 + i * 40), cv2.FONT_HERSHEY_SIMPLEX, 0.9,
                        (255, 255, 255), 2, cv2.LINE_AA)
        self.last = img
        self._show(img)

    # ----- rendering -----
    @staticmethod
    def _fit(frame):
        h, w = frame.shape[:2]
        s = max(FEED_W / w, FEED_H / h)
        r = cv2.resize(frame, (int(w * s) + 1, int(h * s) + 1), interpolation=cv2.INTER_AREA)
        y, x = (r.shape[0] - FEED_H) // 2, (r.shape[1] - FEED_W) // 2
        return np.ascontiguousarray(r[y:y + FEED_H, x:x + FEED_W])

    def _show(self, bgr):
        img = ImageTk.PhotoImage(Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)))
        self.label.configure(image=img)
        self.label.image = img

    def tick(self):
        if not self.running:
            return
        delay = 33
        if self.trans is not None:
            nxt, step = self.trans
            step += 1
            t = ease(step / self.TRANSITION_FRAMES)
            self._show(slide_frame(self.last, nxt, t))
            if step >= self.TRANSITION_FRAMES:
                if self.cap is not None:
                    self.cap.release()
                self.cap, self.pending = self.pending, None
                self.last = nxt
                self.trans = None
            else:
                self.trans = (nxt, step)
        elif self.cap is not None:
            ok, frame = self.cap.read()
            if ok:
                self.last = self._fit(frame)
                self._show(self.last)
            else:
                self.next_clip()  # clip ended: auto-scroll to the next edit
        else:
            delay = 500
            self.reload()
            if self.order:
                self.next_clip(first=True)
        self.after(delay, self.tick)

    # ----- window behaviour -----
    def _press(self, e):
        self._drag = (e.x_root - self.winfo_x(), e.y_root - self.winfo_y())
        self._moved = False

    def _motion(self, e):
        if self._drag:
            self._moved = True
            self.geometry(f"+{e.x_root - self._drag[0]}+{e.y_root - self._drag[1]}")

    def _release(self, e):
        if not self._moved:
            self.next_clip()
        self._drag = None

    def close(self):
        self.running = False
        if self.cap is not None:
            self.cap.release()
        if self.on_close_cb:
            self.on_close_cb()
        self.destroy()
