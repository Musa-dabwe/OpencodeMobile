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
/**
 * The health probe's two-request shape, pinned against the REAL responses captured from the
 * v2.0.22 binary on the Reno 3 (see docs/samples/health.json and docs/samples/info.json).
 *
 * These exist because the route moved between major versions and the old advice was exactly
 * backwards for v2: on v1.18.34 `/api/health` was real and `/api/info` was the SPA's HTML,
 * while on v2.0.22 `/api/health` is a 404 and `/api/info` is the live JSON endpoint.
 */
class HealthProbeRouteTest {

    /** Same classpath convention as [RecordedResponseShapeTest]: docs/samples is a test resource dir. */
    private fun sample(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "Missing recorded sample $name on the test classpath"
        }.bufferedReader().use { it.readText() }

    private val json = openCodeJson

    /** The v1 shape still decodes, so an older server is not broken by the fallback. */
    @Test
    fun v1HealthDocumentStillDecodes() {
        val health = json.decodeFromString<com.musa.opencodeharness.data.model.Health>(
            sample("health.json")
        )
        assertTrue(health.healthy)
    }

    /** The real v2 capture decodes, and carries a non-blank version. */
    @Test
    fun v2InfoDocumentDecodes() {
        val info = json.decodeFromString<com.musa.opencodeharness.data.model.ServerInfo>(
            sample("info.json")
        )
        assertEquals("2.0.22", info.version)
        assertTrue(info.version.isNotBlank())
    }

    /**
     * THE property that matters: the SPA's HTML can never be read as healthy.
     *
     * This is the whole reason the v1 notes warned against probing `/api/info`. The fix is
     * structural -- the body is decoded into a type with a non-null `version`, so HTML throws.
     *
     * Mutation: making `version` nullable, or checking the status code alone, fails this.
     */
    @Test
    fun spaHtmlBodyCannotDecodeAsHealthy() {
        val html = "<!doctype html><html><body><div id=root></div></body></html>"
        val thrown = runCatching {
            json.decodeFromString<com.musa.opencodeharness.data.model.ServerInfo>(html)
        }.exceptionOrNull()
        assertTrue("HTML must not decode as ServerInfo", thrown is kotlinx.serialization.SerializationException)
    }

    /** An empty 404 body (what v2 actually returns) must not decode either. */
    @Test
    fun emptyNotFoundBodyCannotDecodeAsHealthy() {
        val thrown = runCatching {
            json.decodeFromString<com.musa.opencodeharness.data.model.ServerInfo>("")
        }.exceptionOrNull()
        assertTrue(thrown != null)
    }
}
