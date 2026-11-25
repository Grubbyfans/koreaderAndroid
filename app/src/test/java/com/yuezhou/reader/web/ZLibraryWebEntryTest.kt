package com.yuezhou.reader.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZLibraryWebEntryTest {

    @Test
    fun `无关键词打开站点首页并去掉末尾斜杠`() {
        assertEquals("https://zh.z-lib.gd", ZLibraryWebEntry.searchUrl("https://zh.z-lib.gd/", null))
        assertEquals("https://zh.z-lib.gd", ZLibraryWebEntry.searchUrl(" https://zh.z-lib.gd ", "  "))
    }

    @Test
    fun `关键词按已验证的搜索路径编码`() {
        assertEquals(
            "https://zh.z-lib.gd/s/%E4%B8%89%E4%BD%93%20%E5%85%A8%E9%9B%86",
            ZLibraryWebEntry.searchUrl("https://zh.z-lib.gd", "三体 全集"),
        )
    }

    @Test
    fun `非 HTTPS 或未配置服务器不打开网页`() {
        assertNull(ZLibraryWebEntry.searchUrl(null, "三体"))
        assertNull(ZLibraryWebEntry.searchUrl("http://zh.z-lib.gd", "三体"))
        assertNull(ZLibraryWebEntry.searchUrl("", "三体"))
    }
}
