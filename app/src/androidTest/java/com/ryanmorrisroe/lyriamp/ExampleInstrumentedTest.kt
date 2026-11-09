package com.ryanmorrisroe.lyriamp

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ryanmorrisroe.lyriamp.data.persistence.AndroidKeystoreCredentialCipher
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.ryanmorrisroe.lyriamp", appContext.packageName)
    }

    @Test
    fun keystoreCipherRoundTripsCredentialsAndRejectsTampering() {
        val cipher = AndroidKeystoreCredentialCipher()
        val credentials = "listener\u0000private-password".toByteArray(StandardCharsets.UTF_8)
        val encrypted = cipher.encrypt(credentials)

        assertFalse(credentials.contentEquals(encrypted))
        assertArrayEquals(credentials, cipher.decrypt(encrypted))

        val tampered = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { cipher.decrypt(tampered) }
    }
}