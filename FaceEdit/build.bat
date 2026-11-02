@echo off
REM Builds dist\FaceEdit.exe (run on Windows with Python 3.10+)
python -m pip install -r requirements.txt
python -m PyInstaller --noconfirm --onefile --windowed --name FaceEdit ^
  --collect-submodules yt_dlp --collect-data cv2 --collect-binaries imageio_ffmpeg --collect-data imageio_ffmpeg app.py
echo Done. See dist\FaceEdit.exe
