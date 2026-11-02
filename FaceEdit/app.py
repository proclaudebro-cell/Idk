"""FaceEdit - record your face and drop it into a template video (Windows/Tk)."""
import os
import tempfile
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

import cv2
from PIL import Image, ImageTk

import compose


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("FaceEdit")
        self.geometry("980x720")
        self.template = None
        self.cam = None
        self.recording = False
        self.writer = None
        self.rec_path = None
        self.rec_start = 0
        self.last_frame = None

        left = ttk.Frame(self, padding=10)
        left.pack(side="left", fill="both", expand=True)
        right = ttk.Frame(self, padding=10)
        right.pack(side="right", fill="y")

        self.preview = tk.Label(left, bg="black")
        self.preview.pack(fill="both", expand=True)
        self.status = ttk.Label(left, text="Pick a template video, then Record.")
        self.status.pack(fill="x", pady=(8, 0))
        self.bar = ttk.Progressbar(left, maximum=1.0)
        self.bar.pack(fill="x", pady=4)

        ttk.Label(right, text="1. Template video").pack(anchor="w")
        ttk.Button(right, text="Choose template...", command=self.pick_template).pack(fill="x")
        self.tpl_label = ttk.Label(right, text="(none)", wraplength=220)
        self.tpl_label.pack(anchor="w", pady=(2, 10))

        ttk.Label(right, text="2. Layout").pack(anchor="w")
        self.layout = ttk.Combobox(right, values=compose.LAYOUTS, state="readonly", width=38)
        self.layout.current(0)
        self.layout.pack(fill="x", pady=(0, 10))

        ttk.Label(right, text="3. Caption (optional)").pack(anchor="w")
        self.caption = ttk.Entry(right)
        self.caption.pack(fill="x", pady=(0, 6))
        self.mirror = tk.BooleanVar(value=True)
        ttk.Checkbutton(right, text="Mirror camera", variable=self.mirror).pack(anchor="w")
        self.audio = tk.BooleanVar(value=True)
        ttk.Checkbutton(right, text="Keep template audio", variable=self.audio).pack(anchor="w", pady=(0, 10))

        ttk.Label(right, text="4. Record").pack(anchor="w")
        self.rec_btn = ttk.Button(right, text="● Start recording", command=self.toggle_rec)
        self.rec_btn.pack(fill="x", pady=2)
        self.export_btn = ttk.Button(right, text="Export edit...", command=self.export, state="disabled")
        self.export_btn.pack(fill="x", pady=2)
        ttk.Label(right, text="Recording is capped at the template's length.\n"
                              "Use only videos you have the rights to.",
                  wraplength=230, foreground="#666").pack(anchor="w", pady=14)

        self.open_cam()
        self.protocol("WM_DELETE_WINDOW", self.on_close)
        self.tick()

    def open_cam(self):
        backend = cv2.CAP_DSHOW if os.name == "nt" else cv2.CAP_ANY
        self.cam = cv2.VideoCapture(0, backend)
        if not self.cam.isOpened():
            self.status.config(text="No webcam found. Plug one in and restart.")
            self.cam = None
            return
        self.cam.set(cv2.CAP_PROP_FRAME_WIDTH, 1280)
        self.cam.set(cv2.CAP_PROP_FRAME_HEIGHT, 720)

    def pick_template(self):
        p = filedialog.askopenfilename(
            title="Template video",
            filetypes=[("Video", "*.mp4 *.mov *.mkv *.avi *.webm"), ("All files", "*.*")])
        if p:
            self.template = p
            self.tpl_label.config(text=os.path.basename(p))

    def tick(self):
        if self.cam:
            ok, frame = self.cam.read()
            if ok:
                self.last_frame = frame
                if self.recording:
                    self.writer.write(frame)
                    self.status.config(text=f"Recording... {time.time() - self.rec_start:.1f}s")
                    if self.max_secs and time.time() - self.rec_start >= self.max_secs:
                        self.toggle_rec()
                show = cv2.flip(frame, 1) if self.mirror.get() else frame
                w = max(self.preview.winfo_width(), 100)
                h = max(self.preview.winfo_height(), 100)
                show = compose.fill(show, w, h)
                img = ImageTk.PhotoImage(Image.fromarray(cv2.cvtColor(show, cv2.COLOR_BGR2RGB)))
                self.preview.configure(image=img)
                self.preview.image = img
        self.after(15, self.tick)

    def toggle_rec(self):
        if not self.recording:
            if not self.cam or self.last_frame is None:
                return messagebox.showerror("FaceEdit", "No webcam available.")
            if not self.template:
                return messagebox.showinfo("FaceEdit", "Choose a template video first.")
            t = cv2.VideoCapture(self.template)
            n, f = t.get(cv2.CAP_PROP_FRAME_COUNT), t.get(cv2.CAP_PROP_FPS) or 30
            t.release()
            self.max_secs = n / f if n > 0 else 60
            h, w = self.last_frame.shape[:2]
            self.rec_path = os.path.join(tempfile.gettempdir(), "faceedit_rec.mp4")
            self.writer = cv2.VideoWriter(self.rec_path, cv2.VideoWriter_fourcc(*"mp4v"), 30, (w, h))
            self.recording = True
            self.rec_start = time.time()
            self.rec_btn.config(text="■ Stop recording")
            self.export_btn.config(state="disabled")
        else:
            self.recording = False
            self.writer.release()
            self.rec_btn.config(text="● Start recording")
            self.export_btn.config(state="normal")
            self.status.config(text="Recorded. Click Export to build your edit.")

    def export(self):
        out = filedialog.asksaveasfilename(defaultextension=".mp4", initialfile="my_edit.mp4",
                                           filetypes=[("MP4", "*.mp4")])
        if not out:
            return
        self.export_btn.config(state="disabled")
        self.status.config(text="Rendering...")
        args = (self.template, self.rec_path, out, self.layout.current(),
                self.caption.get().strip(), self.mirror.get(), self.audio.get())

        def work():
            try:
                compose.render(*args[:3], layout=args[3], caption=args[4], mirror=args[5],
                               use_template_audio=args[6],
                               progress=lambda p: self.after(0, self.bar.configure, {"value": p}))
                self.after(0, lambda: (self.status.config(text=f"Saved: {out}"),
                                       messagebox.showinfo("FaceEdit", f"Saved to\n{out}")))
            except Exception as e:
                self.after(0, lambda: messagebox.showerror("FaceEdit", str(e)))
            finally:
                self.after(0, lambda: self.export_btn.config(state="normal"))

        threading.Thread(target=work, daemon=True).start()

    def on_close(self):
        if self.cam:
            self.cam.release()
        self.destroy()


if __name__ == "__main__":
    App().mainloop()
