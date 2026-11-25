// Windows-only test data generator. Requires a locally installed Mandarin SAPI voice.
using System;
using System.IO;
using System.Speech.AudioFormat;
using System.Speech.Synthesis;

class MandarinSamples {
    static void Main(string[] args) {
        if (args.Length != 1) throw new ArgumentException("Pass the output directory for synthetic test recordings.");
        Directory.CreateDirectory(args[0]);
        string[,] lines = {
            {"standalone","一百二十"}, {"calc","打开计算器然后计算九乘十九"}, {"short","九乘十九"},
            {"mult","请帮我算三十七乘以十九"}, {"signed","计算负十二点五除以二点五"},
            {"phone","电话号码是一三八零零一三八零零零"}, {"code","编号是零零七二九"},
            {"date","二零二六年十一月二十五日"}, {"time","明天下午三点十五分提醒我"},
            {"qty","买十二个苹果和二十三个橙子"}, {"unit","这根木头长三点五米"},
            {"percent","百分之二十五"}, {"plain","他们相识已久，一起去五一广场"},
            {"ratio","我把这九成的项目完成了"}, {"other","明天下午三点我们一起去公园散步"}
        };
        using (var voice = new SpeechSynthesizer()) {
            voice.SelectVoice("Microsoft Huihui Desktop");
            var format = new SpeechAudioFormatInfo(16000, AudioBitsPerSample.Sixteen, AudioChannel.Mono);
            for (int i=0; i<lines.GetLength(0); i++) {
                voice.SetOutputToWaveFile(Path.Combine(args[0], lines[i,0]+".wav"),format);
                voice.Speak(lines[i,1]); voice.SetOutputToNull();
            }
        }
    }
}
