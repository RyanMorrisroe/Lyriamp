package com.ryanmorrisroe.lyriamp.data.lms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LmsArtworkTest {
    @Test
    fun resolvesRelativeArtworkUrlsAndUsesSameOriginCredentials() {
        val source = LmsServerConnection(
            host = "lms.local",
            username = "user",
            password = "pass"
        ).artworkSource(null, "../imageproxy/cover.jpg")

        assertEquals("http://lms.local:9000/imageproxy/cover.jpg", source?.url)
        assertEquals("Basic dXNlcjpwYXNz", source?.authorizationHeader)
    }

    @Test
    fun buildsCoverEndpointFromArtworkId() {
        val source = LmsServerConnection("lms.local", username = "user", password = "pass")
            .artworkSource("5466de2c", null)

        assertEquals("http://lms.local:9000/music/5466de2c/cover.jpg", source?.url)
        assertEquals("Basic dXNlcjpwYXNz", source?.authorizationHeader)
    }

    @Test
    fun resolvesContributorPortraitEndpointWithSameOriginCredentials() {
        val source = LmsServerConnection("lms.local", username = "user", password = "pass")
            .artworkSource(null, "/contributor/portrait-42/image")

        assertEquals("http://lms.local:9000/contributor/portrait-42/image", source?.url)
        assertEquals("Basic dXNlcjpwYXNz", source?.authorizationHeader)
    }

    @Test
    fun rejectsArtworkIdsThatCouldEscapeTheCoverPath() {
        assertNull(LmsServerConnection("lms.local").artworkSource("../secret", null))
    }

    @Test
    fun doesNotSendCredentialsToAnotherOrigin() {
        val source = LmsServerConnection("lms.local", username = "user", password = "pass")
            .artworkSource(null, "https://art.example/cover.jpg")

        assertEquals("https://art.example/cover.jpg", source?.url)
        assertNull(source?.authorizationHeader)
    }

    @Test
    fun rejectsUnsafeAndCredentialBearingArtworkUrls() {
        val connection = LmsServerConnection("lms.local")

        assertNull(connection.artworkSource(null, "file:///secret.jpg"))
        assertNull(connection.artworkSource(null, "http://user:pass@lms.local/cover.jpg"))
    }

    @Test
    fun fallsBackToArtworkIdWhenArtworkUrlIsMalformed() {
        val source = LmsServerConnection("lms.local")
            .artworkSource("501", "http://[invalid")

        assertEquals("http://lms.local:9000/music/501/cover.jpg", source?.url)
    }
}
