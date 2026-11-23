"""Drives the real Sawaari app on an Android emulator and records one clip per scene of the how-to video.

Runs in .github/workflows/howto-video.yml, after the emulator has booted and the APK is installed.
Riders other than the leader join the demo group from here through the Firebase REST API, so the
video shows a real group on one phone.

Output (in out/): clips/NN-scene.mp4, timeline.json, and debug/ (screenshots + UI dumps of failed steps).
"""

import json
import os
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import uiautomator2 as u2

sys.path.insert(0, os.path.dirname(__file__))
import itinerary as it  # noqa: E402

PKG = "app.sawaari"
OUT = os.environ.get("OUT", "out")
IST = ZoneInfo("Asia/Kolkata")
os.makedirs(f"{OUT}/clips", exist_ok=True)
os.makedirs(f"{OUT}/debug", exist_ok=True)


def adb(*args, check=False, timeout=60):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr or r.stdout}")
    return r.stdout.strip()


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ---------------------------------------------------------------- GPS

def gps(lat, lon):
    adb("emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}")


class Drive:
    """Moves the emulator's GPS along a list of points in the background (a rider on the road)."""

    def __init__(self):
        self.stop = threading.Event()
        self.thread = None

    def run(self, path, seconds_per_step=1.0):
        self.halt()
        self.stop.clear()

        def go():
            for lat, lon in path:
                if self.stop.is_set():
                    return
                gps(lat, lon)
                time.sleep(seconds_per_step)

        self.thread = threading.Thread(target=go, daemon=True)
        self.thread.start()

    def halt(self):
        self.stop.set()
        if self.thread:
            self.thread.join(timeout=5)


def line(a, b, n):
    return [(a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n) for i in range(n + 1)]


# NH48 (Tumkur Road) heading north-west out of the toll gate.
ROAD = line((13.0880, 77.4300), (13.1400, 77.3550), 60)

# ---------------------------------------------------------------- Firebase riders (REST)


class Firebase:
    def __init__(self):
        gs = json.load(open("app/google-services.json"))
        pi = gs["project_info"]
        # Same default as GroupRepository.sawaariDatabase().
        self.db = pi.get("firebase_url") or f"https://{pi['project_id']}-default-rtdb.asia-southeast1.firebasedatabase.app"
        self.key = gs["client"][0]["api_key"][0]["current_key"]
        self.cert = os.environ.get("APP_SHA1", "")

    def _req(self, url, method="GET", body=None, headers=None):
        h = {"Content-Type": "application/json", "X-Android-Package": PKG}
        if self.cert:
            h["X-Android-Cert"] = self.cert
        h.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(url, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                txt = r.read().decode()
                return json.loads(txt) if txt else None
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"{method} {url.split('?')[0]} -> {e.code} {e.read().decode()[:300]}")

    def sign_in(self):
        r = self._req(f"https://identitytoolkit.googleapis.com/v1/accounts:signUp?key={self.key}", "POST", {"returnSecureToken": True})
        return r["localId"], r["idToken"]

    def call(self, token, path, method="GET", body=None):
        return self._req(f"{self.db}/{path}.json?auth={token}", method, body)


TS = {".sv": "timestamp"}


class Rider:
    def __init__(self, fb, spec, slot):
        self.fb, self.spec, self.slot = fb, spec, slot
        self.name = spec["name"]
        self.uid, self.token = fb.sign_in()
        self.code = None

    def join(self, code):
        self.code = code
        self.fb.call(self.token, f"groups/{code}", "PATCH", {
            f"slots/{self.slot}": self.uid,
            f"members/{self.uid}": {"name": self.name, "role": "rider", "joinedAt": TS, "slot": str(self.slot)},
        })

    def at(self, lat, lon, speed=0.0):
        dlat, dlon = self.spec["offset"]
        self.fb.call(self.token, f"groups/{self.code}/locations/{self.uid}", "PUT",
                     {"lat": lat + dlat, "lon": lon + dlon, "speed": speed, "bearing": 300.0, "ts": TS})

    def share_gear(self):
        self.fb.call(self.token, f"groups/{self.code}/gear/{self.uid}", "PUT", self.spec["gear"])

    def say(self, text):
        self.fb.call(self.token, f"chats/{self.code}", "POST", {"uid": self.uid, "name": self.name, "ts": TS, "text": text})

    def take_break(self, on=True):
        self.fb.call(self.token, f"groups/{self.code}/members/{self.uid}/breakAt", "PUT" if on else "DELETE", TS if on else None)

    def ride(self, state):
        self.fb.call(self.token, f"groups/{self.code}/ride", "PUT", {"state": state, "at": TS, "by": self.name, "uid": self.uid})

    def leave(self):
        for p in (f"locations/{self.uid}", f"gear/{self.uid}", f"members/{self.uid}", f"slots/{self.slot}"):
            try:
                self.fb.call(self.token, f"groups/{self.code}/{p}", "DELETE")
            except Exception as e:  # noqa: BLE001
                log("cleanup:", e)


# ---------------------------------------------------------------- recording

class Recorder:
    """One screenrecord per scene (Android caps a recording at 3 minutes)."""

    def __init__(self):
        self.timeline = []
        self.proc = None
        self.n = 0

    def start(self, scene):
        self.n += 1
        self.name = f"{self.n:02d}-{scene}"
        adb("shell", "rm", "-f", "/sdcard/clip.mp4")
        self.proc = subprocess.Popen(["adb", "shell", "screenrecord", "--size", "720x1600", "--bit-rate", "8000000",
                                      "--time-limit", "180", "/sdcard/clip.mp4"])
        self.t0 = time.time()
        self.marks = []
        time.sleep(1.5)

    def mark(self, label):
        self.marks.append({"t": round(time.time() - self.t0, 2), "label": label})

    def stop(self, ok=True):
        time.sleep(1.0)
        adb("shell", "pkill", "-INT", "screenrecord")
        try:
            self.proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        time.sleep(2)
        dest = f"{OUT}/clips/{self.name}.mp4"
        adb("pull", "/sdcard/clip.mp4", dest, timeout=120)
        self.timeline.append({"clip": os.path.basename(dest), "scene": self.name[3:], "ok": ok,
                              "seconds": round(time.time() - self.t0, 2), "marks": self.marks})
        json.dump(self.timeline, open(f"{OUT}/timeline.json", "w"), indent=1)


# ---------------------------------------------------------------- UI helpers

d = None


def debug(tag):
    try:
        d.screenshot(f"{OUT}/debug/{tag}.png")
        open(f"{OUT}/debug/{tag}.xml", "w").write(d.dump_hierarchy())
    except Exception as e:  # noqa: BLE001
        log("debug dump failed:", e)


def tap(timeout=15, pause=1.2, **sel):
    el = d(**sel)
    if not el.wait(timeout=timeout):
        raise RuntimeError(f"not found: {sel}")
    el.click()
    time.sleep(pause)


def has(timeout=3, **sel):
    return d(**sel).wait(timeout=timeout)


def scroll_to(**sel):
    if d(**sel).exists:
        return
    s = d(scrollable=True)
    if s.exists:
        s.scroll.to(**sel)
    if not d(**sel).exists:
        raise RuntimeError(f"can't scroll to {sel}")


def field(label):
    """The text field labelled [label] (Compose puts the label inside the field's node, or just above it)."""
    for sel in ({"className": "android.widget.EditText", "text": label},
                {"className": "android.widget.EditText", "textContains": label},
                {"className": "android.widget.EditText", "descriptionContains": label}):
        if d(**sel).exists:
            return d(**sel)
    lab = d(textContains=label)
    if lab.exists:
        b = lab.info["bounds"]
        cy = (b["top"] + b["bottom"]) / 2
        for e in d(className="android.widget.EditText"):
            eb = e.info["bounds"]
            if eb["top"] - 10 <= cy <= eb["bottom"] + 10:
                return e
    raise RuntimeError(f"no field {label}")


def keyboard_shown():
    return "mInputShown=true" in adb("shell", "dumpsys", "input_method")


def hide_keyboard():
    if keyboard_shown():
        d.press("back")
        time.sleep(0.6)


def type_into(label, text):
    f = field(label)
    f.click()
    time.sleep(0.5)
    f.set_text(text)
    time.sleep(0.8)


def center_y(el):
    b = el.info["bounds"]
    return (b["top"] + b["bottom"]) / 2


def in_row(anchor_text, **sel):
    """The element matching [sel] on the same row as the text [anchor_text]."""
    a = d(text=anchor_text)
    if not a.exists:
        return None
    y = center_y(a)
    best = None
    for e in d(**sel):
        if abs(center_y(e) - y) < 50:
            best = e
    return best


def to_top(text):
    """Scroll the open list so [text] sits near the top of the screen."""
    el = d(text=text)
    if not el.exists:
        return
    y = center_y(el)
    w, h = d.window_size()
    target = int(h * 0.30)
    if y > target + 50:
        d.swipe(w // 2, int(y), w // 2, target, duration=0.5)
        time.sleep(1.2)


def ride_button():
    el = d(textMatches=r"^(Ride|\d+ riding)$")
    if el.exists:
        return el
    # Collapsed (no group yet): the round Ride button on the right, below search.
    w, h = d.window_size()
    for e in d(clickable=True):
        b = e.info["bounds"]
        cx, cy = (b["left"] + b["right"]) / 2, (b["top"] + b["bottom"]) / 2
        size = b["right"] - b["left"]
        if cx > w * 0.75 and h * 0.2 < cy < h * 0.4 and 100 < size < 260:
            return e
    raise RuntimeError("no Ride button")


def open_ride_window():
    if d(description="Minimise").exists:
        return
    ride_button().click()
    time.sleep(1.8)


def close_window():
    if d(description="Minimise").exists:
        d(description="Minimise").click()
        time.sleep(1.2)


def set_clock(dt):
    adb("shell", "date", dt.strftime("%m%d%H%M%Y.%S"))
    log("clock", adb("shell", "date"))


def notified(text):
    return text in adb("shell", "dumpsys", "notification", "--noredact")


# ---------------------------------------------------------------- scenes

def pick_date_time(day, hhmm):
    """Android's date and time pickers: pick the day, then type the time."""
    h, m = map(int, hhmm.split(":"))
    tap(text="Pick date and time", timeout=5) if d(text="Pick date and time").exists else tap(textMatches=r"^\w{3} \d+ \w{3}, .*")
    target = datetime(it.YEAR, 11, day)
    for desc in (target.strftime("%d %B %Y"), target.strftime("%-d %B %Y"), target.strftime("%A, %B %-d, %Y")):
        if d(description=desc).exists:
            d(description=desc).click()
            break
    else:
        d(text=str(day), className="android.view.View").click()
    time.sleep(0.6)
    tap(resourceId="android:id/button1", pause=1.0)
    # Time picker: switch to typing, then hour, minute, AM/PM.
    if d(descriptionContains="text input mode").exists:
        d(descriptionContains="text input mode").click()
        time.sleep(0.6)
    h12 = h % 12 or 12
    d(resourceId="android:id/input_hour").set_text(str(h12))
    d(resourceId="android:id/input_minute").set_text(f"{m:02d}")
    ampm = "AM" if h < 12 else "PM"
    sp = d(resourceId="android:id/am_pm_spinner")
    if sp.exists and sp.get_text() != ampm:
        sp.click()
        time.sleep(0.5)
        tap(text=ampm, pause=0.5)
    time.sleep(0.6)
    tap(resourceId="android:id/button1", pause=1.0)


def scene_first_open(rec):
    rec.start("first-open")
    d.app_start(PKG, wait=True)
    time.sleep(6)
    rec.mark("tour")
    for _ in range(3):
        if has(timeout=4, text="Next"):
            time.sleep(1.8)
            tap(text="Next", pause=1.5)
    if has(timeout=2, text="Skip"):
        tap(text="Skip")
    rec.mark("sos prompt")
    if has(timeout=4, text="Later"):
        time.sleep(2)
        tap(text="Later")
    rec.mark("map")
    time.sleep(5)
    rec.stop()


def scene_profile_name(rec):
    rec.start("profile-name")
    tap(description="Profile and settings")
    rec.mark("profile")
    type_into("Your name", it.LEADER)
    if has(timeout=2, text="Save"):
        tap(text="Save")
    hide_keyboard()
    time.sleep(2)
    d.press("back")
    time.sleep(2)
    rec.stop()


def scene_create_group(rec):
    rec.start("create-group")
    open_ride_window()
    rec.mark("ride window")
    time.sleep(2)
    scroll_to(text="Start a ride group")
    type_into("Ride name", it.GROUP_NAME)
    hide_keyboard()
    tap(text="Create group", pause=4)
    rec.mark("group created")
    has(timeout=15, text="Invite code")
    time.sleep(3)
    code = None
    for e in d(className="android.widget.TextView"):
        t = e.get_text() or ""
        if len(t) == 6 and t.isalnum() and t.upper() == t and any(c.isalpha() for c in t):
            code = t
            break
    log("group code", code)
    time.sleep(2)
    rec.stop()
    return code


def scene_paste_route(rec):
    rec.start("paste-route")
    open_ride_window()
    scroll_to(text="Paste Google Maps link")
    time.sleep(1)
    tap(text="Paste Google Maps link")
    rec.mark("paste box")
    f = d(className="android.widget.EditText")
    f.wait(timeout=5)
    f.set_text(it.google_maps_link())
    time.sleep(2.5)
    tap(text="Load route", pause=3)
    rec.mark("route loaded")
    time.sleep(6)
    # The plan card: scroll it so the stops show, then share it with the group.
    rec.mark("plan card")
    if has(timeout=3, textContains="Share plan with group"):
        sc = d(scrollable=True)
        try:
            sc.scroll.toEnd(max_swipes=3) if sc.exists else None
        except Exception:  # noqa: BLE001
            pass
        time.sleep(2)
        tap(textContains="Share plan with group", pause=3)
        rec.mark("shared")
    time.sleep(3)
    rec.stop()


def scene_riders_join(rec, riders, code):
    rec.start("riders-join")
    lat, lon = it.POINTS[0][1], it.POINTS[0][2]
    for r in riders:
        r.join(code)
        r.at(lat, lon)
        r.share_gear()
    open_ride_window()
    time.sleep(2)
    try:
        scroll_to(textStartsWith="Riders (")
    except Exception:  # noqa: BLE001
        pass
    rec.mark("riders list")
    time.sleep(5)
    close_window()
    rec.mark("riders on map")
    time.sleep(5)
    rec.stop()


def schedule_entry(i):
    key = "Start" if i == 0 else ("Destination" if i == len(it.POINTS) - 1 else f"Pit stop {i}")
    _, _, _, day, hhmm, _ = it.POINTS[i]
    sel = {"textMatches": rf"^{key}(: .*)?$"}
    scroll_to(**sel)
    row = d(**sel)
    w, h = d.window_size()
    if center_y(row) > h * 0.85:
        d.swipe(w // 2, int(h * 0.8), w // 2, int(h * 0.5), duration=0.5)
        time.sleep(1)
    d(**sel).click()
    time.sleep(1.5)
    if i in it.RENAME:
        f = d(className="android.widget.EditText")
        f.click()
        time.sleep(0.5)
        f.set_text(it.RENAME[i])
        time.sleep(1.5)
        hide_keyboard()
    pick_date_time(day, hhmm)
    time.sleep(1.0)
    tap(text="Save", pause=1.8)


def scene_schedule(rec, first, last, tag):
    rec.start(tag)
    open_ride_window()
    scroll_to(text="Ride schedule")
    time.sleep(1)
    # Bring the card near the top.
    to_top("Ride schedule")
    time.sleep(1.5)
    for i in range(first, last + 1):
        rec.mark(f"entry {i}")
        try:
            schedule_entry(i)
        except Exception:  # noqa: BLE001
            log(traceback.format_exc())
            debug(f"schedule-{i}")
            for _ in range(3):
                if d(text="Cancel").exists:
                    d(text="Cancel").click()
                    time.sleep(0.5)
    time.sleep(3)
    rec.stop()


def scene_packing(rec):
    rec.start("packing")
    open_ride_window()
    scroll_to(text="What to carry")
    time.sleep(1)
    to_top("What to carry")
    time.sleep(2)
    rec.mark("shared by others")
    time.sleep(3)
    tap(text="Open my checklist")
    rec.mark("checklist")
    time.sleep(1.5)
    for item in ("Helmet", "Riding jacket", "Gloves", "Driving licence"):
        box = in_row(item, className="android.widget.CheckBox")
        if box:
            box.click()
            time.sleep(0.8)
    lists = d(scrollable=True)
    if lists.exists and not d(text="Tyre inflator").exists:
        lists[len(lists) - 1].scroll.to(text="Tyre inflator")
        time.sleep(1)
    for item in ("Puncture repair kit", "Tyre inflator"):
        chip = in_row(item, text="Share")
        if chip:
            rec.mark(f"share {item}")
            chip.click()
            time.sleep(1.2)
    time.sleep(2)
    tap(text="Done", pause=2)
    rec.mark("card")
    time.sleep(4)
    rec.stop()


def scene_reminder(rec):
    """Jump the phone's clock to just before the start, to show the schedule notification."""
    close_window()
    d.press("home")
    time.sleep(1.5)
    set_clock(datetime(it.YEAR, 11, 26, 3, 59, 40))
    rec.start("reminder")
    rec.mark("waiting")
    for _ in range(60):
        if notified("Time to start"):
            break
        time.sleep(1)
    time.sleep(1)
    d.open_notification()
    rec.mark("notification")
    time.sleep(7)
    d.press("back")
    time.sleep(1)
    rec.stop()
    set_clock(datetime.now(IST).replace(tzinfo=None))
    d.app_start(PKG)
    time.sleep(3)


def scene_start_ride(rec):
    rec.start("start-ride")
    open_ride_window()
    scroll_to(text="Ride schedule")
    to_top("Ride schedule")
    time.sleep(1.5)
    scroll_to(text="Start ride")
    tap(text="Start ride", pause=2)
    rec.mark("confirm")
    time.sleep(2)
    tap(text="Start ride", pause=3)  # the confirm dialog's button
    rec.mark("started")
    d(textStartsWith="In progress").wait(timeout=10)
    to_top("Ride schedule")
    time.sleep(5)
    close_window()
    time.sleep(2)
    rec.stop()


def scene_riding(rec, riders, drive):
    rec.start("riding")
    drive.run(ROAD, 1.0)
    rec.mark("riding")

    def follow():
        for k in range(0, len(ROAD), 4):
            lat, lon = ROAD[min(k, len(ROAD) - 1)]
            for r in riders:
                try:
                    r.at(lat, lon, 16.0)
                except Exception as e:  # noqa: BLE001
                    log(e)
            time.sleep(4)

    t = threading.Thread(target=follow, daemon=True)
    t.start()
    time.sleep(4)
    if d(description="My location").exists:
        d(description="My location").click()
    time.sleep(10)
    riders[1].say("Fuel stop at the next pump")
    rec.mark("chat")
    time.sleep(6)
    open_ride_window()
    try:
        scroll_to(textStartsWith="Group chat")
        tap(textStartsWith="Group chat", pause=2)
        rec.mark("chat open")
        time.sleep(2)
        tap(text="All good", pause=2)
        time.sleep(3)
        d.press("back")
        time.sleep(1)
    except Exception:  # noqa: BLE001
        log(traceback.format_exc())
        debug("chat")
    close_window()
    riders[0].take_break(True)
    rec.mark("break")
    time.sleep(8)
    riders[0].take_break(False)
    time.sleep(2)
    rec.stop()


def scene_sos(rec):
    rec.start("sos")
    tap(description="Profile and settings")
    scroll_to(text="Set up SOS contacts")
    tap(text="Set up SOS contacts")
    rec.mark("sos setup")
    try:
        type_into("Name", "Home")
        type_into("Phone", "+91 98450 12345")
        hide_keyboard()
        tap(text="Add", pause=2)
    except Exception:  # noqa: BLE001
        log(traceback.format_exc())
        debug("sos")
    time.sleep(3)
    d.press("back")
    time.sleep(1)
    d.press("back")
    time.sleep(1.5)
    tap(text="SOS", pause=1)
    rec.mark("countdown")
    time.sleep(3)
    tap(text="CANCEL", pause=2)
    rec.stop()


def scene_offline(rec):
    rec.start("offline-maps")
    tap(description="Profile and settings")
    tap(text="Offline maps and navigation", pause=2)
    rec.mark("offline")
    try:
        scroll_to(text="Goa & Konkan coast")
        row = d(text="Goa & Konkan coast")
        b = row.info["bounds"]
        for e in d(description="Download"):
            eb = e.info["bounds"]
            if abs((eb["top"] + eb["bottom"]) / 2 - (b["top"] + b["bottom"]) / 2) < 60:
                e.click()
                rec.mark("downloading")
                break
    except Exception:  # noqa: BLE001
        log(traceback.format_exc())
        debug("offline")
    time.sleep(10)
    d.press("back")
    time.sleep(1)
    d.press("back")
    time.sleep(1.5)
    rec.stop()


def scene_end_ride(rec, riders):
    rec.start("end-ride")
    riders[1].ride("ended")
    time.sleep(2)
    open_ride_window()
    scroll_to(text="Ride schedule")
    to_top("Ride schedule")
    rec.mark("ended")
    time.sleep(6)
    close_window()
    time.sleep(1)
    rec.stop()


def scene_history(rec):
    rec.start("history")
    tap(description="Profile and settings")
    scroll_to(text="Ride history and stats")
    tap(text="Ride history and stats", pause=3)
    rec.mark("history")
    time.sleep(5)
    d.press("back")
    time.sleep(1)
    d.press("back")
    rec.stop()


# ---------------------------------------------------------------- main

def main():
    global d
    for p in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS", "RECORD_AUDIO"):
        adb("shell", "pm", "grant", PKG, f"android.permission.{p}")
    adb("shell", "appops", "set", PKG, "SCHEDULE_EXACT_ALARM", "allow")
    adb("shell", "settings", "put", "global", "auto_time", "0")
    adb("shell", "settings", "put", "system", "screen_off_timeout", "1800000")
    adb("shell", "svc", "power", "stayon", "true")
    gps(it.POINTS[0][1], it.POINTS[0][2])

    d = u2.connect()
    d.implicitly_wait(10)
    log("device", d.info)
    fb = Firebase()
    riders = [Rider(fb, spec, slot=i + 2) for i, spec in enumerate(it.RIDERS)]
    rec = Recorder()
    drive = Drive()
    code = None

    scenes = [
        ("first-open", lambda: scene_first_open(rec)),
        ("profile-name", lambda: scene_profile_name(rec)),
        ("create-group", None),
        ("paste-route", lambda: scene_paste_route(rec)),
        ("riders-join", lambda: scene_riders_join(rec, riders, code)),
        ("schedule-a", lambda: scene_schedule(rec, 0, 1, "schedule-a")),
        ("schedule-b", lambda: scene_schedule(rec, 2, len(it.POINTS) - 1, "schedule-b")),
        ("packing", lambda: scene_packing(rec)),
        ("reminder", lambda: scene_reminder(rec)),
        ("start-ride", lambda: scene_start_ride(rec)),
        ("riding", lambda: scene_riding(rec, riders, drive)),
        ("sos", lambda: scene_sos(rec)),
        ("offline-maps", lambda: scene_offline(rec)),
        ("end-ride", lambda: scene_end_ride(rec, riders)),
        ("history", lambda: scene_history(rec)),
    ]
    only = os.environ.get("SCENES")
    for name, fn in scenes:
        if only and name not in only.split(","):
            continue
        log("scene", name)
        try:
            if name == "create-group":
                code = scene_create_group(rec)
                if not code:
                    raise RuntimeError("no group code")
            else:
                fn()
        except Exception:  # noqa: BLE001
            log(traceback.format_exc())
            debug(f"fail-{name}")
            try:
                rec.stop(ok=False)
            except Exception:  # noqa: BLE001
                pass
            for _ in range(3):
                d.press("back")
            d.app_start(PKG)
            time.sleep(3)

    drive.halt()
    set_clock(datetime.now(IST).replace(tzinfo=None))
    # Tidy the demo group out of the live database.
    for r in riders:
        if r.code:
            r.leave()
    try:
        open_ride_window()
        scroll_to(text="Leave group")
        tap(text="Leave group", pause=3)
        if has(timeout=3, text="Leave"):
            tap(text="Leave", pause=3)
    except Exception:  # noqa: BLE001
        log(traceback.format_exc())
    json.dump(rec.timeline, open(f"{OUT}/timeline.json", "w"), indent=1)
    log("done", [t["clip"] for t in rec.timeline])


if __name__ == "__main__":
    main()
