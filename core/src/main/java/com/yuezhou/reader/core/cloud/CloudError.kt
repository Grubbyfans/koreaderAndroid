package com.yuezhou.reader.core.cloud

/**
 * 云端错误码，对齐 BaiduPanSync `modules/errors.lua` 的错误分类。
 *
 * 语义关键点：写入类操作失败时要区分 [Outcome.Failed] 与 [Outcome.Uncertain]。
 * - Failed：请求未发出或明确失败，可安全重试。
 * - Uncertain：写入可能已在远端生效但结果未确认，禁止直接重试写入，须先重新查询。
 */
enum class ErrorCode {
    INVALID_INPUT,
    AUTH,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    UNSUPPORTED,
    IO,
    NETWORK,
    TLS,
    CANCELLED,
    UNCERTAIN,
    UNKNOWN,
}

/** 写入失败的结果确定性。 */
enum class Outcome { Failed, Uncertain }

/**
 * 云端错误。
 *
 * @param code 错误分类。
 * @param message 面向用户的中文描述。
 * @param outcome 写入类操作的结果确定性，读类操作默认 [Outcome.Failed]。
 */
class CloudException(
    val code: ErrorCode,
    override val message: String,
    val outcome: Outcome = Outcome.Failed,
) : Exception(message)
