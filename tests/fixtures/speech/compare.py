"""Compare stock recognizers on the same synthetic corpus; no network, model, or audio downloads."""
import argparse
import json
from pathlib import Path
import re
import unicodedata
import wave

parser = argparse.ArgumentParser()
parser.add_argument("audio", type=Path)
parser.add_argument("--vosk", type=Path, required=True, help="Extracted vosk-model-small-cn-0.22 directory")
parser.add_argument("--paraformer", type=Path, required=True, help="Directory containing model.int8.onnx and tokens.txt")
args = parser.parse_args()

import numpy as np
import sherpa_onnx
import vosk

vosk.SetLogLevel(-1)
vm = vosk.Model(str(args.vosk))
pm = sherpa_onnx.OfflineRecognizer.from_paraformer(
    paraformer=str(args.paraformer / "model.int8.onnx"), tokens=str(args.paraformer / "tokens.txt"),
    num_threads=2, sample_rate=16000, feature_dim=80)
def norm(text):
    return "".join(c for c in text if not c.isspace() and not unicodedata.category(c).startswith("P"))

source = Path(__file__).with_name("MandarinSamples.cs").read_text(encoding="utf-8")
scores = [0, 0]
examples = re.findall(r'\{"(\w+)","([^"]+)"\}', source)
for name, truth in examples:
    with wave.open(str(args.audio / (name + ".wav"))) as wav:
        assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (16000, 1, 2)
        data = wav.readframes(wav.getnframes())
    vr = vosk.KaldiRecognizer(vm, 16000)
    vr.AcceptWaveform(data)
    v = json.loads(vr.FinalResult())["text"]
    stream = pm.create_stream()
    stream.accept_waveform(16000, np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768)
    pm.decode_stream(stream)
    p = stream.result.text
    scores[0] += norm(v) == norm(truth)
    scores[1] += norm(p) == norm(truth)
    print(name, truth, v, p, sep="\t")
print(f"Exact utterances (ignore punctuation/space): Vosk {scores[0]}/{len(examples)}, Paraformer {scores[1]}/{len(examples)}")
