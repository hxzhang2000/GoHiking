package com.gohiking.core.common.result

/**
 * 统一错误与结果类型（DEV §1.6）。
 * Repository 只返回 GhResult；面向用户的文案一律经 ErrorTextMapper 走 strings_error.xml。
 */
sealed interface GhResult<out T> {
    data class Ok<T>(val value: T) : GhResult<T>
    data class Err(val error: GhError) : GhResult<Nothing>
}

sealed class GhError(val cause: Throwable? = null) {
    data class Network(val reason: String) : GhError()
    data class Permission(val permission: String) : GhError()
    data class SensorUnavailable(val sensor: String) : GhError()
    data class ElevationUnavailable(val reason: String) : GhError()
    data class ImportError(val file: String, val entry: String?, val reason: String) : GhError()
    data class StorageFull(val requiredBytes: Long) : GhError()
    data class AmapNotInitialized(val reason: String) : GhError()
    data class Unknown(val t: Throwable) : GhError(t)
}

inline fun <T, R> GhResult<T>.map(transform: (T) -> R): GhResult<R> = when (this) {
    is GhResult.Ok -> GhResult.Ok(transform(value))
    is GhResult.Err -> this
}

inline fun <T> GhResult<T>.onOk(block: (T) -> Unit): GhResult<T> {
    if (this is GhResult.Ok) block(value)
    return this
}

inline fun <T> GhResult<T>.onErr(block: (GhError) -> Unit): GhResult<T> {
    if (this is GhResult.Err) block(error)
    return this
}
