package com.yuezhou.reader.process

import org.junit.Assert.assertEquals
import org.junit.Test

class AppProcessTest {

    @Test
    fun `主进程识别为 MAIN`() {
        assertEquals(
            AppProcess.Role.MAIN,
            AppProcess.roleOf("com.yuezhou.reader", "com.yuezhou.reader"),
        )
    }

    @Test
    fun `网页进程识别为 WEB`() {
        assertEquals(
            AppProcess.Role.WEB,
            AppProcess.roleOf("com.yuezhou.reader", "com.yuezhou.reader:zlibraryweb"),
        )
    }

    @Test
    fun `空进程名回退为主进程`() {
        assertEquals(AppProcess.Role.MAIN, AppProcess.roleOf("com.yuezhou.reader", null))
        assertEquals(AppProcess.Role.MAIN, AppProcess.roleOf("com.yuezhou.reader", "  "))
    }

    @Test
    fun `其它进程不视为主进程`() {
        assertEquals(
            AppProcess.Role.OTHER,
            AppProcess.roleOf("com.yuezhou.reader", "com.yuezhou.reader:webprobe"),
        )
        assertEquals(
            AppProcess.Role.OTHER,
            AppProcess.roleOf("com.yuezhou.reader", "com.yuezhou.reader:zlibrarywebx"),
        )
    }
}
