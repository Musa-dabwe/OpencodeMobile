package com.musa.opencodeharness.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the transport-failure mapping that keeps Ktor out of the UI layer.
 *
 * These cases are unreachable through the ViewModel tests, which run against a fake and so never
 * exercise the real client's mapping — see the report's mutation note.
 *
 * Note there is no test that builds a `ClientRequestException`: Ktor 3.2.4's constructor reads
 * `response.call`, so a fake response would have to return a real `HttpClientCall`. That is why
 * the status-to-type decision lives in [apiExceptionForStatus], which is directly testable.
 */
class ApiExceptionMappingTest {

    /**
     * The one that matters most: a cancelled coroutine is being torn down, not failing. Wrapping
     * it would render "could not reach the server" for a ViewModel that is simply going away.
     */
    @Test
    fun cancellation_isReturnedUnchangedSoCallCanRethrowIt() {
        val cancellation = CancellationException("cancelled")

        assertSame("must not be wrapped", cancellation, asApiException(cancellation))
    }

    @Test
    fun connectFailure_becomesUnreachableAndKeepsItsCause() {
        val failure = java.net.ConnectException("connection refused")

        val mapped = asApiException(failure)

        assertTrue(mapped is ApiException.Unreachable)
        assertSame("cause is preserved, it carries the useful part", failure, mapped.cause)
    }

    @Test
    fun status401_becomesUnauthorized() {
        val mapped = apiExceptionForStatus(401, RuntimeException("boom"))

        assertTrue("401 is the one status the user can fix, so it gets its own type",
            mapped is ApiException.Unauthorized)
    }

    @Test
    fun otherStatuses_becomeServerErrorCarryingTheStatus() {
        // 401 must not leak into this branch; the boundary is the behaviour under test.
        listOf(400, 403, 404, 500, 503).forEach { status ->
            val mapped = apiExceptionForStatus(status, RuntimeException("boom"))

            assertTrue("$status should be a ServerError", mapped is ApiException.ServerError)
            assertEquals(status, (mapped as ApiException.ServerError).status)
        }
    }

    @Test
    fun anAlreadyMappedApiException_isNotRemapped() {
        // Guards against a future refactor wrapping ApiException in ApiException.Unreachable,
        // which would erase the 401-vs-500 distinction the UI branches on.
        val already = ApiException.Unauthorized(RuntimeException("boom"))

        val mapped = asApiException(already)

        assertTrue("must stay Unauthorized", mapped is ApiException.Unauthorized)
        assertSame(already, mapped)
    }
}