package sk.cartracker.ui.screens

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebPairTest {
    private val code = "a1".repeat(32)
    private val server = "https://example.sk/cartracking/api/"

    @Test fun okLink() {
        val r = WebPair.parse("trasnik://pair?c=$code&api=" + java.net.URLEncoder.encode("https://example.sk/cartracking/api", "UTF-8"), server)
        assertEquals(PairLink.Ok(code), r)
    }

    @Test fun linkWithoutApiIsAccepted() = assertEquals(PairLink.Ok(code), WebPair.parse("trasnik://pair?c=$code", server))

    @Test fun otherServerRejected() {
        val r = WebPair.parse("trasnik://pair?c=$code&api=https%3A%2F%2Finy.sk%2Fapi%2F", server)
        assertTrue(r is PairLink.Error && r.message.contains("iný server"))
    }

    @Test fun foreignQrRejected() {
        assertTrue(WebPair.parse("https://google.com", server) is PairLink.Error)
        assertTrue(WebPair.parse("trasnik://pair?c=xyz", server) is PairLink.Error)
    }
}
