package dev.securenotes

import dev.securenotes.security.ResumeSession
import org.junit.Assert.*
import org.junit.Test

class ResumeSessionTest {
    @Test fun `only authentication restarts window and exact deadline rejects resume`() {
        var now = 1_000L
        val session = ResumeSession({ now }, { it.copyOf() }, { it.copyOf() })
        val root = byteArrayOf(7, 8, 9)
        session.remember(root)
        now += 100_000
        assertArrayEquals(root, session.resume(true)); assertNull(session.resume(false))
        now = 300_999
        assertArrayEquals(root, session.resume(true))
        now++
        assertNull(session.resume(true))
        session.remember(root)
        now += 299_999
        assertArrayEquals(root, session.resume(true))
    }
    @Test fun `deadline passing during decryption zeroes returned root`() {
        var now = 10L
        val clear = byteArrayOf(1, 2, 3)
        val session = ResumeSession({ now }, { it.copyOf() }, { now += ResumeSession.WINDOW_MS; clear })
        session.remember(clear)
        assertNull(session.resume(true)); assertTrue(clear.all { it == 0.toByte() })
    }
    @Test fun `expiry and forgetting wipe retained ciphertext and fresh processes have no token`() {
        var now = 0L
        val encrypted = byteArrayOf(8, 9)
        val session = ResumeSession({ now }, { encrypted }, { it.copyOf() })
        session.remember(byteArrayOf(1))
        now = ResumeSession.WINDOW_MS; session.clearIfExpired()
        assertTrue(encrypted.all { it == 0.toByte() }); assertNull(session.resume(true))
        assertNull(ResumeSession({ now }, { it.copyOf() }, { it.copyOf() }).resume(true))
    }
    @Test fun `failed decryption discards token and cannot silently retry it`() {
        var attempts = 0
        val session = ResumeSession({ 0L }, { it.copyOf() }, { attempts++; error("Unavailable key") })
        session.remember(byteArrayOf(1))
        assertNull(session.resume(true)); assertNull(session.resume(true)); assertEquals(1, attempts)
    }
}
