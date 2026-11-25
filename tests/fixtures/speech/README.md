# Mandarin numbers: compare the recognizer before formatting

The fixtures cover two different problems in Mandarin dictation: formatting numbers that were recognized correctly and recognizing arithmetic expressions in the audio itself.

On Windows, compile `MandarinSamples.cs` against System.Speech and run it with an output directory. It uses Microsoft Huihui Desktop to write **15 synthetic, 16 kHz mono PCM** utterances. No microphone or user recording is needed.

### Same-audio comparison on Windows CPU

With punctuation and whitespace ignored, Vosk `vosk-model-small-cn-0.22` reproduced **8/15** complete utterances and [Paraformer Chinese small INT8](https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09) **15/15**. This compares stock recognition before AndroidHarness number formatting; a single synthetic voice is not an accuracy estimate for human speakers.

| Spoken | Vosk | Paraformer |
|---|---|---|
| 一百二十 | 一百二十 | 一百二十 |
| 九乘十九 | 教程十九 | 九乘十九 |
| 打开计算器然后计算九乘十九 | 打开计算器然后计算九成十九 | 打开计算器然后计算九乘十九 |
| 编号是零零七二九 | 编号是零零七二脚 | 编号是零零七二九 |
| 计算负十二点五除以二点五 | 计算副食二点五五二点二点五 | 计算负十二点五除以二点五 |

`SpokenNumbersDeviceTest` feeds seven of these WAVs through the Android decoder and checks the written draft before a chat request. In a disconnected emulator, 4.0.7 produced `120`, `9 × 19`, `打开计算器然后计算9 × 19`, `编号是00729`, `电话号码是13800138000`, `计算-12.5 ÷ 2.5` and `他们相识已久一起去五一广场`.

The duration test uses a separate 63.62-second recording with seven repeated phrase pairs and 2.5-second pauses. With the emulator offline, text from the beginning, middle and end was still present at finish. The cloud recovery test transcribed the original AAC after a simulated provider timeout rather than asking for a second recording.
