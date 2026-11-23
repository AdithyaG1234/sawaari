"""Builds the how-to video from the recorded clips and the voice-over.

    python compose.py RAW_DIR VOICE_DIR OUT.mp4

RAW_DIR has clips/*.mp4 and timeline.json from drive.py; VOICE_DIR has <scene id>.wav from narrate.py.
Each scene is the phone screen on the left and the step, caption and subtitles on the right (1920x1080).
The phone clip is sped up or held on its last frame so it lasts as long as the voice-over.
"""

import json
import os
import re
import subprocess
import sys
import tempfile
import wave

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import itinerary as it  # noqa: E402

W, H = 1920, 1080
FPS = 30
BG = (15, 23, 42)
PANEL = (30, 41, 59)
ACCENT = (249, 115, 22)
TEXT = (241, 245, 249)
MUTED = (148, 163, 184)
PHONE_H = 1000
PHONE_W = PHONE_H * 720 // 1600
PHONE_X, PHONE_Y = 150, (H - PHONE_H) // 2
RIGHT_X = PHONE_X + PHONE_W + 120
RIGHT_W = W - RIGHT_X - 110

FONT_DIRS = ["/usr/share/fonts/opentype/inter", "/usr/share/fonts/truetype/inter", "/usr/share/fonts/truetype/dejavu"]


def font(size, weight="Regular"):
    names = [f"Inter-{weight}.otf", f"Inter-{weight}.ttf", "DejaVuSans-Bold.ttf" if weight in ("Bold", "SemiBold") else "DejaVuSans.ttf"]
    for d in FONT_DIRS:
        for n in names:
            p = os.path.join(d, n)
            if os.path.exists(p):
                return ImageFont.truetype(p, size)
    return ImageFont.load_default(size)


def wrap(draw, text, f, width):
    lines, cur = [], ""
    for word in text.split():
        t = (cur + " " + word).strip()
        if draw.textlength(t, font=f) <= width:
            cur = t
        else:
            if cur:
                lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


def draw_lines(draw, xy, lines, f, fill, gap=1.3):
    x, y = xy
    for ln in lines:
        draw.text((x, y), ln, font=f, fill=fill)
        y += int(f.size * gap)
    return y


def footer(draw):
    f = font(26, "Medium")
    draw.text((RIGHT_X, H - 90), "Sawaari  ·  Goa ride, Option 1  ·  26 to 30 Nov", font=f, fill=MUTED)


def scene_background(title, caption, step_progress):
    """Background with the phone frame cut-out area, step title and caption."""
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    # Phone frame.
    pad = 14
    d.rounded_rectangle((PHONE_X - pad, PHONE_Y - pad, PHONE_X + PHONE_W + pad, PHONE_Y + PHONE_H + pad), radius=48, fill=(2, 6, 23), outline=(71, 85, 105), width=3)
    # Progress dots.
    total, cur = step_progress
    x = RIGHT_X
    for i in range(total):
        d.rounded_rectangle((x, 120, x + 34, 128), radius=4, fill=ACCENT if i <= cur else (51, 65, 85))
        x += 44
    y = 175
    y = draw_lines(d, (RIGHT_X, y), wrap(d, title, font(64, "Bold"), RIGHT_W), font(64, "Bold"), TEXT, 1.15) + 18
    y = draw_lines(d, (RIGHT_X, y), wrap(d, caption, font(36, "Medium"), RIGHT_W), font(36, "Medium"), ACCENT, 1.3)
    footer(d)
    return img, y + 40


def subtitle_png(text, top, path):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    f = font(38)
    lines = wrap(d, text, f, RIGHT_W - 60)
    h = int(len(lines) * f.size * 1.35) + 50
    top = max(top, 520)
    d.rounded_rectangle((RIGHT_X - 10, top, RIGHT_X + RIGHT_W, top + h), radius=22, fill=PANEL + (235,))
    draw_lines(d, (RIGHT_X + 26, top + 24), lines, f, TEXT, 1.35)
    img.save(path)


def card(kind, s, path):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    if kind == "intro":
        d.text((140, 110), "Sawaari", font=font(44, "Bold"), fill=ACCENT)
        d.text((140, 165), s["title"], font=font(84, "Bold"), fill=TEXT)
        d.text((140, 275), s["caption"], font=font(40, "Medium"), fill=MUTED)
        # Itinerary table (Option 1).
        cols = [("Date", 140), ("Time", 300), ("Stop", 450), ("Plan", 1120)]
        y = 380
        for name, x in cols:
            d.text((x, y), name, font=font(30, "SemiBold"), fill=ACCENT)
        y += 52
        last_day = None
        for i, (name, _, _, day, hhmm, remark) in enumerate(it.POINTS):
            if i % 2 == 0:
                d.rounded_rectangle((120, y - 8, W - 140, y + 50), radius=10, fill=PANEL)
            day_txt = f"{day} Nov" if day != last_day else ""
            last_day = day
            shown = it.RENAME.get(i, name) if i == 1 else name
            label = "Start" if i == 0 else ("Destination" if i == len(it.POINTS) - 1 else f"Pit stop {i}")
            for txt, x, f, c in ((day_txt, 140, font(30, "Medium"), TEXT), (hhmm, 300, font(30, "Medium"), TEXT),
                                 (f"{label}: {shown}", 450, font(30), TEXT), (remark, 1120, font(30), MUTED)):
                d.text((x, y), txt, font=f, fill=c)
            y += 62
    else:
        d.text((140, 330), s["title"], font=font(110, "Bold"), fill=TEXT)
        d.text((140, 480), "Create the group  ·  paste the route  ·  set the times  ·  ride together", font=font(40, "Medium"), fill=MUTED)
        d.text((140, 600), "Download Sawaari", font=font(36, "SemiBold"), fill=ACCENT)
        d.text((140, 650), "github.com/AdithyaG1234/sawaari/releases/latest", font=font(40, "Medium"), fill=TEXT)
        d.text((140, 760), "Free for the Goa trial: groups of any size, every feature.", font=font(34), fill=MUTED)
    img.save(path)


def wav_seconds(path):
    with wave.open(path) as w:
        return w.getnframes() / w.getframerate()


def clip_seconds(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", path],
                       capture_output=True, text=True)
    return float(r.stdout.strip() or 0)


def sentences(text):
    parts = re.split(r"(?<=[.!?])\s+", text.strip())
    out = []
    for p in parts:  # keep subtitles short: merge tiny sentences with the next
        if out and len(out[-1]) < 40:
            out[-1] += " " + p
        else:
            out.append(p)
    return out


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(" ".join(cmd))
        print(r.stderr[-3000:])
        raise SystemExit(1)


def main(raw, voices, dest):
    here = os.path.dirname(os.path.abspath(__file__))
    script = json.load(open(f"{here}/script.json"))
    timeline = {t["scene"]: t for t in json.load(open(f"{raw}/timeline.json"))} if os.path.exists(f"{raw}/timeline.json") else {}
    tmp = tempfile.mkdtemp(prefix="howto-")
    steps = [s for s in script if not s.get("card")]
    step_ids = list(dict.fromkeys(s["title"] for s in steps))
    segments = []
    for n, s in enumerate(script):
        wav = f"{voices}/{s['id']}.wav"
        if not os.path.exists(wav):
            print("no voice for", s["id"], "- skipped")
            continue
        voice = wav_seconds(wav)
        target = voice + 1.2
        seg = f"{tmp}/{n:02d}.mp4"
        if s.get("card"):
            png = f"{tmp}/{n:02d}-card.png"
            card(s["card"], s, png)
            run(["ffmpeg", "-y", "-loop", "1", "-framerate", str(FPS), "-i", png, "-i", wav,
                 "-filter_complex", f"[1:a]adelay=500|500,apad,aformat=sample_rates=44100:channel_layouts=stereo[a]",
                 "-map", "0:v", "-map", "[a]", "-t", f"{target + 0.5:.2f}",
                 "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "20", "-r", str(FPS),
                 "-c:a", "aac", "-b:a", "160k", seg])
            segments.append(seg)
            continue
        t = timeline.get(s["id"])
        clip = f"{raw}/clips/{t['clip']}" if t else None
        if not clip or not os.path.exists(clip) or not t.get("ok", True):
            print("no good clip for", s["id"], "- skipped")
            continue
        length = clip_seconds(clip) - 1.0  # drop the first second (recording warm-up)
        speed = max(1.0, length / target)
        if speed > 4.0:
            speed = 4.0
            target = length / speed
        bg, sub_top = scene_background(s["title"], s["caption"], (len(step_ids), step_ids.index(s["title"])))
        bgp = f"{tmp}/{n:02d}-bg.png"
        bg.save(bgp)
        # Subtitles: one sentence at a time, timed by length over the voice-over.
        subs = sentences(s["say"])
        total_chars = sum(len(x) for x in subs)
        inputs = ["-loop", "1", "-framerate", str(FPS), "-i", bgp, "-ss", "1.0", "-i", clip, "-i", wav]
        filt = [f"[1:v]setpts=(PTS-STARTPTS)/{speed:.4f},scale={PHONE_W}:{PHONE_H},fps={FPS},"
                f"tpad=stop_mode=clone:stop_duration=30[ph]",
                f"[0:v][ph]overlay={PHONE_X}:{PHONE_Y}:shortest=0[v0]"]
        t0 = 0.5
        last = "v0"
        for k, line in enumerate(subs):
            dur = voice * len(line) / total_chars
            p = f"{tmp}/{n:02d}-sub{k}.png"
            subtitle_png(line, sub_top, p)
            inputs += ["-loop", "1", "-framerate", str(FPS), "-i", p]
            idx = 3 + k
            end = t0 + dur if k < len(subs) - 1 else target + 1
            filt.append(f"[{last}][{idx}:v]overlay=0:0:enable='between(t,{t0:.2f},{end:.2f})'[v{k + 1}]")
            last = f"v{k + 1}"
            t0 += dur
        filt.append(f"[2:a]adelay=500|500,apad,aformat=sample_rates=44100:channel_layouts=stereo[a]")
        run(["ffmpeg", "-y", *inputs, "-filter_complex", ";".join(filt), "-map", f"[{last}]", "-map", "[a]",
             "-t", f"{target + 0.5:.2f}", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "medium", "-crf", "20",
             "-r", str(FPS), "-c:a", "aac", "-b:a", "160k", seg])
        print(f"scene {s['id']}: clip {length:.1f}s x{speed:.2f}, voice {voice:.1f}s")
        segments.append(seg)
    lst = f"{tmp}/list.txt"
    open(lst, "w").write("".join(f"file '{x}'\n" for x in segments))
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", dest])
    # Subtitles file too, for players that show captions.
    print("video", dest, f"{clip_seconds(dest):.1f}s")


if __name__ == "__main__":
    main(*sys.argv[1:4])
