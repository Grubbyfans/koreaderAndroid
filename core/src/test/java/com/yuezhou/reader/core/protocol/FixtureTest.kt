package com.yuezhou.reader.core.protocol

/** 从 classpath 测试资源读取脱敏协议夹具的基类。 */
abstract class FixtureTest {
    protected fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "缺少夹具: $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}
