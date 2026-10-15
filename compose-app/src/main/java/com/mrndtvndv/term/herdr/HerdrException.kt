package com.mrndtvndv.term.herdr

import com.mrndtvndv.term.domain.SshStreamChannelException

/**
 * Failure from the Herdr control client. [error] is typed and safe to show in the UI. [cause] keeps the
 * underlying exception for logs when one exists.
 */
class HerdrException(val error: HerdrError, cause: Throwable? = null) : Exception(error.message, cause)

internal fun closedError() = HerdrException(HerdrError(HerdrErrorKind.Transport, "Herdr client is closed"))

internal fun disconnected(message: String) = HerdrException(HerdrError(HerdrErrorKind.Disconnected, message))

/** Maps a transport failure to a typed error, keeping the cause. Socket-level disconnects stay distinct. */
internal fun transportFailure(cause: Throwable): HerdrException {
    val lost = cause is SshStreamChannelException && cause.reason == SshStreamChannelException.Reason.DISCONNECTED
    val kind = if (lost) HerdrErrorKind.Disconnected else HerdrErrorKind.Transport
    return HerdrException(HerdrError(kind, cause.message ?: "Herdr transport failed"), cause)
}

/** The name of an optional event kind the server rejected, or null when the error is not that. */
internal fun rejectedEventName(error: HerdrError): String? =
    if (error.kind == HerdrErrorKind.Unsupported) unknownVariantName(error.message) else null
