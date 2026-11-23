"""Makes one voice-over file per scene (out/voice/<id>.wav) with Piper, a free offline text-to-speech engine."""

import json
import os
import subprocess
import sys

here = os.path.dirname(os.path.abspath(__file__))
voice = sys.argv[1]  # path to the .onnx voice model
out = os.environ.get("OUT", "out") + "/voice"
os.makedirs(out, exist_ok=True)
for s in json.load(open(f"{here}/script.json")):
    dest = f"{out}/{s['id']}.wav"
    subprocess.run(
        [sys.executable, "-m", "piper", "-m", voice, "-f", dest, "--length-scale", "1.05", "--sentence-silence", "0.25"],
        input=s["say"], text=True, check=True,
    )
    print("voice", dest)
