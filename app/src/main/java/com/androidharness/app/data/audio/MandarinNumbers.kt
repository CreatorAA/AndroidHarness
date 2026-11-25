package com.androidharness.app.data.audio

import java.math.BigInteger

/** Written forms for numbers that the recognizer actually heard. It never reconstructs missing digits. */
internal object MandarinNumbers {
    private const val HAN = "零〇○一二两幺三四五六七八九十百千万亿"
    private val protected = Regex("一点(?:一点|点|儿|也|都|不)|一会儿?|一起|一块儿|(?<![$HAN\\d])万一|五一(?:广场|劳动节|假期|期间)|(?:一|二|三|四|五|六|七|八|九|十)中(?:学)?|十分(?:满意|高兴|重要|必要|感谢|抱歉|清楚|明确|期待|好|困难|开心|喜爱|熟悉)|九成已久|一旦|一般|一直|一定|一致|一共|一下(?:子)?|零(?:下|件|部件|食|售|碎|星|散|工)")
    private val codeContext = Regex("(?:电话号码|电话号码是|手机号(?:码)?|电话|号码|编号|尾号|验证码|门牌号?|房间号|学号|工号|邮编|序列号|逐位|报号|账号|单号|密码|车牌)(?:是|为|为您|是我的|的)?[：:，,\\s]*$")
    private val codeGroup = Regex("(?:手机号(?:码)?|电话(?:号码)?|号码|编号|验证码|序列号|逐位|报号|账号|单号|邮编)(?:是|为)?[：:，,、 -]*[0-9０-９零〇○一幺二三四五六七八九，,、 -]+$")
    private val countPrefix = Regex("(?:共有|一共|总共|数量|等于|结果是|答案是|数值|数字|数到|写下|输入|填写|增加|减少|约为|大约|大概|大于|小于|至少|最多|不足|剩下|需要|买了|买|减去|加上|乘以|除以|加|减|乘|除|是|为|有|第|值为|到|至|比)[：:，,\\s]*$")
    private val counter = Regex("^(?:个|位|名|人|元|块|角钱|毛钱|分钱|万像素|万公里|公里|千米|毫米|厘米|米|公斤|千克|克|斤|吨|毫升|升|摄氏度|度|岁|本|件|笔|台|部|张|页|层|楼|盒|箱|辆|条|套|只|把|枚|颗|组|节|次|步|倍|份|包|瓶|天|年|月|日|号|小时|分钟|秒钟|秒|点|时|分(?:钟|钱|半|整|之|$)|刻钟|周|星期|折|档|级|章|项|种|题|门|场|家|杯|公里|以内|以上|以下|左右|加|减|乘|除|等于|到|至|比|[+＋=＝×÷*/%])")
    private val timeOfDay = Regex("(?:凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|今晚|明早|今晚的|明天早上|北京时间)\\s*$")
    private val numberOnly = Regex("[\\s，,。.!！?？]*")
    private val operators = "+＋-−*/×÷=＝"
    private val arithmetic = Regex("""(?<=[0-9])\s*(加上|减去|乘以|除以|加|减|乘)\s*(?=-?[0-9])""")
    private fun writeOperators(text: String) = arithmetic.replace(text) { m ->
        when (m.groups[1]!!.value.first()) { '加' -> " + "; '减' -> " − "; '乘' -> " × "; else -> " ÷ " }
    }

    fun format(text: String): String {
        if (text.none { it in HAN || it == '负' || it in '０'..'９' }) return writeOperators(text)
        val keep = protected.findAll(text).associate { it.range.first to (it.range.last + 1) }
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val protectedEnd = keep[i]
            if (protectedEnd != null) { out.append(text, i, protectedEnd); i = protectedEnd; continue }
            val start = i
            val leadingMinus = text.startsWith("负", i)
            var p = i + if (leadingMinus) 1 else 0
            val percent = text.startsWith("百分之", p)
            if (percent) p += 3
            val innerMinus = percent && text.startsWith("负", p)
            if (innerMinus) p++
            val a = p
            while (p < text.length && isNumeral(text[p]) && p !in keep) p++
            if (p == a) { out.append(text[i++]); continue }
            val integerToken = text.substring(a,p)
            val whole = integer(integerToken)
            if (whole == null) { out.append(text, start, p); i = p; continue }
            val prefix = text.substring((start-36).coerceAtLeast(0),start).trimEnd()
            val afterInteger = p
            var written = whole
            var withFraction = false
            if (!percent && text.startsWith("分之",p)) {
                var q = p+2
                while (q < text.length && q !in keep && isNumeral(text[q])) q++
                val numerator = integer(text.substring(p+2,q))
                if (numerator != null && whole.toBigIntegerOrNull() != BigInteger.ZERO) {
                    if (leadingMinus) out.append('-')
                    out.append(numerator).append('/').append(whole); i = q; continue
                }
            }
            if (p < text.length && text[p] in "点.．" && p+1 < text.length && isNumeral(text[p+1])) {
                val dot = text[p]
                var q = p+1
                while (q < text.length && q !in keep && isNumeral(text[q])) q++
                val part = text.substring(p+1,q)
                val suffix = text.substring(q)
                val clock = dot == '点' && !percent && !leadingMinus &&
                    (suffix.startsWith("分") && !suffix.startsWith("分钟") && !suffix.startsWith("分之") ||
                        timeOfDay.containsMatchIn(prefix))
                val fraction = part.map(::digit)
                if (clock && integer(part) != null) {
                    written += "点" + integer(part); p = q; withFraction = true
                } else if (fraction.isNotEmpty() && fraction.none { it == null }) {
                    written += "." + fraction.joinToString(""); p = q; withFraction = true
                }
            }
            val next = text.substring(p)
            val digitsOnly = integerToken.all { digit(it) != null }
            val code = codeContext.containsMatchIn(prefix) || codeGroup.containsMatchIn(prefix)
            val magnitude = integerToken.length > 1 && integerToken.any { it in "十百千万亿" }
            val stronglyNumeric = percent || leadingMinus || withFraction || magnitude || code ||
                countPrefix.containsMatchIn(prefix) || prefix.lastOrNull()?.let { it in operators } == true ||
                (prefix.isEmpty() && numberOnly.matches(next)) || next.firstOrNull()?.let { it in operators } == true
            // “三四天、两三个人” are estimates, not 34 days or 23 people. A year/number/ID
            // or individually dictated zeroes are unambiguous exceptions.
            val approximate = digitsOnly && integerToken.length == 2 && integerToken.all { it in "一二两三四五六七八九" } &&
                next.isNotEmpty() && counter.containsMatchIn(next) && !next.startsWith("年") && !code &&
                !withFraction && !percent && !leadingMinus
            if (!approximate && (stronglyNumeric || counter.containsMatchIn(next))) {
                if (leadingMinus || innerMinus) out.append('-')
                out.append(written)
                if (percent) out.append('%')
                i = p
            } else {
                out.append(text, start, afterInteger); i = afterInteger
            }
        }
        return writeOperators(out.toString())
    }

    private fun isNumeral(c: Char) = c in HAN || c in '0'..'9' || c in '０'..'９'
    private fun digit(c: Char): Int? = when(c) {
        '零','〇','○' -> 0; '一','幺' -> 1; '二','两' -> 2
        '三' -> 3; '四' -> 4; '五' -> 5; '六' -> 6; '七' -> 7; '八' -> 8; '九' -> 9
        else -> c.digitToIntOrNull()
    }
    private fun integer(s: String): String? {
        if (s.isEmpty() || s.length > 64) return null
        if (s.all { digit(it) != null }) return s.map { digit(it)!! }.joinToString("")
        return scaled(s)?.toString()
    }
    private fun scaled(s: String): BigInteger? {
        for ((unit, factor) in listOf('亿' to 100000000L, '万' to 10000L)) {
            val i = s.indexOf(unit)
            if (i < 0) continue
            if (i == 0 || s.indexOf(unit,i+1) >= 0) return null
            val head = scaled(s.substring(0,i)) ?: return null
            val tail = s.substring(i+1)
            if (tail.length == 1 && digit(tail[0]) != null && digit(tail[0]) != 0) return null // 一万二: ambiguous omitted place
            val rest = if (tail.isEmpty()) BigInteger.ZERO else scaled(tail) ?: return null
            return head * BigInteger.valueOf(factor) + rest
        }
        if (s.all { digit(it) != null }) return s.map { digit(it)!! }.joinToString("").toBigIntegerOrNull()
        var sum = 0; var previous = 10000; var next: Int? = null; var zero = false
        for ((i,c) in s.withIndex()) {
            val d = digit(c)
            if (d != null) {
                if (next != null) return null
                if (d == 0) { zero = true; continue }
                next = d
            } else {
                val unit = when(c) { '千' -> 1000; '百' -> 100; '十' -> 10; else -> return null }
                if (unit >= previous) return null
                val coef = next ?: if (unit == 10 && i == 0) 1 else return null
                sum += coef * unit; previous = unit; next = null; zero = false
            }
        }
        if (next != null && previous > 10 && !zero) return null // 二百五 may mean 205 or 250.
        return BigInteger.valueOf((sum + (next ?: 0)).toLong())
    }
}
