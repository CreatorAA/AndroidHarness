package com.androidharness.app.data.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class MandarinNumbersTest {
    private fun cases(vararg data: Pair<String,String>) = data.forEach { (spoken,written) ->
        assertEquals(spoken, written, MandarinNumbers.format(spoken))
        assertEquals("already formatted: $spoken", written, MandarinNumbers.format(written))
    }
    @Test fun standaloneAndQuantities() = cases(
        "一百二十" to "120", "十五。" to "15。", "零" to "0", "两百" to "200",
        "负三百零五" to "-305", "给我两百零三个零件" to "给我203个零件",
        "拿十六盒，另买两瓶水" to "拿16盒，另买2瓶水", "第十二页第七题" to "第12页第7题",
        "一万一千零九" to "11009", "一亿零三万零九" to "100030009",
        "我刚才说一百二十然后停住" to "我刚才说120然后停住",
    )
    @Test fun clockAndCalendar() = cases(
        "二零二六年十一月二十五日" to "2026年11月25日",
        "下午三点去，十点半回来" to "下午3点去，10点半回来",
        "明早六点零五分" to "明早6点05分", "下午三点五十九分" to "下午3点59分",
        "一小时十五分钟" to "1小时15分钟", "倒计时三十秒" to "倒计时30秒",
    )
    @Test fun measurementsAndMath() = cases(
        "三十七乘以十九" to "37 × 19", "九乘十九" to "9 × 19", "负十二点五除以二点五" to "-12.5 ÷ 2.5",
        "9乘以19" to "9 × 19", "负三点一四" to "-3.14",
        "温度是零下十二摄氏度" to "温度是零下12摄氏度",
        "一百二十点零五元" to "120.05元", "百分之三点五" to "3.5%",
        "负百分之十二" to "-12%", "百分之负零点二" to "-0.2%", "三分之一" to "1/3",
    )
    @Test fun identifiersKeepTheirOrderAndLeadingZeroes() = cases(
        "编号是零零一二三" to "编号是00123", "电话：幺三八零零幺三八零零零" to "电话：13800138000",
        "验证码是零五零六" to "验证码是0506", "零一二" to "012",
        "手机号一三八，零零一三，八零零零" to "手机号138，0013，8000",
    )
    @Test fun namesIdiomsAndGenuinelyAmbiguousSpeechStayAsSpoken() = cases(
        "一会儿我们一起去纸月城一中和五一剧场" to "一会儿我们一起去纸月城一中和五一剧场",
        "万一堵车，一点也不着急，十分满意" to "万一堵车，一点也不着急，十分满意",
        "九成已久" to "九成已久", "过两三天" to "过两三天", "二百五" to "二百五",
        "打开编号零零七，再去五一剧场" to "打开编号007，再去五一剧场",
    )
}
