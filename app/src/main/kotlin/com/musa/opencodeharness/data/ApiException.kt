package com.musa.opencodeharness.data

/**
 * Transport failures expressed in the app's own vocabulary.
 *
 * Why these exist: without them the UI layer has to catch Ktor's `ClientRequestException` and
 * read `response.status` off it. That couples the ViewModel to the HTTP library, and it makes the
 * failure paths nearly untestable — `ClientRequestException`'s constructor reaches into an
 * `HttpClientCall`, so a test cannot build one without fabricating a whole response object whose
 * `call` accessor throws.
 *
 * Mapping at the client boundary instead keeps Ktor out of the UI and lets a fake throw these
 * types directly. See [OpenCodeClient] for the translation.
 */
sealed class ApiException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** No usable answer: refused, timed out, DNS failed, or the host is simply not there. */
    class Unreachable(cause: Throwable?) : ApiException("unreachable", cause)

    /**
     * The server answered, and rejected our credentials.
     *
     * Distinct from [ServerError] because it is the one failure the user can actually fix, and
     * usually the first thing they will hit while the password wiring is still a placeholder.
     */
    class Unauthorized(cause: Throwable?) : ApiException("unauthorized", cause)

    /** The server answered with a non-2xx status that is not 401. [status] is the code. */
    class ServerError(val status: Int, cause: Throwable?) :
        ApiException("server error $status", cause)
}