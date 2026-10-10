package com.musa.opencodeharness.data.runtime.launch

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Thrown when no free loopback port could be obtained after every attempt. */
class PortSelectionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The port-acquisition seam: bind an ephemeral port on 127.0.0.1, report it, and release it
 * before returning. Production is [LoopbackEphemeralBinder]; tests inject fakes to simulate
 * contention.
 */
fun interface LoopbackPortBinder {
    /** @return a loopback port that was free at bind time and is released by the time this returns. */
    fun bindEphemeralLoopback(): Int
}

/** Real implementation: let the kernel pick port 0's next free ephemeral port on loopback. */
object LoopbackEphemeralBinder : LoopbackPortBinder {
    override fun bindEphemeralLoopback(): Int =
        ServerSocket().use { socket ->
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            socket.localPort
        }
}

/**
 * Chooses a free loopback port for `opencode serve`.
 *
 * ## The trade-off we chose, and why
 *
 * The strategy is **bind-and-release**: bind `127.0.0.1:0`, read the kernel-chosen port, close
 * the socket, and hand the number to the server. The server then binds it for real a moment
 * later. That window — between our release and the server's bind — is inherently racy: any
 * process on the device could take the port in between.
 *
 * Why this over the alternatives:
 *
 *  - **Holding the socket until the server binds is impossible.** The server needs the socket
 *    itself; proot/opencode offers no fd handoff, and `SO_REUSEPORT` would split traffic.
 *  - **Scanning a fixed or random port list is strictly racy AND worse.** It needs N probes
 *    with the same race per probe, tends to herd on low or well-known numbers, and two app
 *    installs scanning the same list will contend with each other's probes rather than the
 *    kernel's allocator. Bind-on-port-0 delegates de-duplication to the kernel: while any
 *    probe or server socket is open, the kernel will not hand the same port to another
 *    binder, so "two installs must not collide" holds for everything except the tiny
 *    release-then-rebind window.
 *  - The race is **detectable and recoverable**: if we lose it, `opencode serve` exits with
 *    "address already in use", [ProotLauncher] surfaces that as `ServerState.Dead` with the
 *    exit code instead of a hung UI, and the caller may call `start()` again — a fresh
 *    `bind(0)` then picks a different port. The launcher deliberately does NOT auto-retry:
 *    keeping the failure visible at this layer makes the race observable rather than hidden
 *    behind a loop, and one retry loop per launch would not make the window smaller.
 *
 * A transient bind failure (e.g. `EADDRINUSE` while probing, which port-0 makes rare but not
 * impossible under a stale TIME_WAIT entry) is retried up to [attempts] times before
 * [PortSelectionException].
 */
class PortPicker(
    private val binder: LoopbackPortBinder = LoopbackEphemeralBinder,
    private val attempts: Int = DEFAULT_ATTEMPTS,
) {
    init {
        require(attempts >= 1) { "attempts must be at least 1" }
    }

    fun choose(): Int {
        var lastFailure: Exception? = null
        repeat(attempts) {
            try {
                val port = binder.bindEphemeralLoopback()
                if (port !in 1..65535) {
                    throw PortSelectionException("binder returned an out-of-range port: $port")
                }
                return port
            } catch (e: PortSelectionException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw PortSelectionException(
            "no free loopback port after $attempts attempts",
            lastFailure,
        )
    }

    companion object {
        const val DEFAULT_ATTEMPTS: Int = 3
    }
}
