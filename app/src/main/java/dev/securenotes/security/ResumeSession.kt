package dev.securenotes.security

/** Only ciphertext and a monotonic deadline survive device lock, never process death. */
class ResumeSession(
    private val now: () -> Long,
    private val seal: (ByteArray) -> ByteArray,
    private val unseal: (ByteArray) -> ByteArray,
) {
    companion object { const val WINDOW_MS = 5 * 60 * 1000L }
    private var token: ByteArray? = null
    private var authenticatedAt = 0L
    @Synchronized fun remember(root: ByteArray, at: Long = now()) {
        clear()
        val encrypted = seal(root)
        authenticatedAt = at
        token = encrypted
        clearIfExpired()
    }
    @Synchronized fun remainingMillis(): Long {
        if (token == null) return 0
        val elapsed = now() - authenticatedAt
        return if (elapsed < 0) 0 else (WINDOW_MS - elapsed).coerceAtLeast(0)
    }
    @Synchronized fun clearIfExpired() { if (remainingMillis() == 0L) clear() }
    @Synchronized fun resume(deviceUnlocked: Boolean): ByteArray? {
        clearIfExpired()
        if (!deviceUnlocked) return null
        val encrypted = token ?: return null
        return try {
            unseal(encrypted).also { root ->
                if (remainingMillis() == 0L) { root.fill(0); clear(); return null }
            }
        } catch (_: Exception) { clear(); null }
    }
    @Synchronized fun clear() { token?.fill(0); token = null; authenticatedAt = 0 }
}
