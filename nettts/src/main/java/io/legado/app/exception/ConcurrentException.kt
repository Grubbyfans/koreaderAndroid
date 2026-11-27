package io.legado.app.exception

/**
 * 并发率限制异常。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/exception/ConcurrentException.kt`（GPLv3）。
 */
class ConcurrentException(msg: String, val waitTime: Long = 0) : Exception(msg)
