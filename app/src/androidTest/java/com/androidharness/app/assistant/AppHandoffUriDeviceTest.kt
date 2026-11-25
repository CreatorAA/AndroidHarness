package com.androidharness.app.assistant

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidharness.app.androiduse.MapHandoffRoute
import com.androidharness.app.androiduse.mapIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppHandoffUriDeviceTest {
    @Test fun vendorSearchPayloadsKeepOneExactKeywordAndNeverStartNavigation() {
        val query = "纸月测试公园 & A/B? #7"
        val baidu = mapIntent(MapHandoffRoute.BAIDU, query)
        assertEquals(Intent.ACTION_VIEW, baidu.action)
        assertEquals("baidumap", baidu.data!!.scheme)
        assertEquals("map", baidu.data!!.authority)
        assertEquals("/place/search", baidu.data!!.path)
        assertEquals(query, baidu.data!!.getQueryParameter("query"))
        assertEquals(setOf("query", "src"), baidu.data!!.queryParameterNames)
        val amap = mapIntent(MapHandoffRoute.AMAP, query).data!!
        assertEquals("androidamap", amap.scheme)
        assertEquals("poi", amap.authority)
        assertEquals(query, amap.getQueryParameter("keywords"))
        assertEquals(setOf("keywords", "sourceApplication"), amap.queryParameterNames)
        assertEquals("geo:0,0?q=%E7%BA%B8%E6%9C%88%E6%B5%8B%E8%AF%95%E5%85%AC%E5%9B%AD%20%26%20A%2FB%3F%20%237",
            mapIntent(MapHandoffRoute.GEO, query).dataString)
    }
}
