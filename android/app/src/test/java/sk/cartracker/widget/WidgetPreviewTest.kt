package sk.cartracker.widget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sk.cartracker.data.SpeedSample
import sk.cartracker.service.DriveState
import java.io.File
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin

/**
 * Vykreslí náhľady všetkých widgetov do build/widget-previews ako PNG (CI ich zverejní na kontrolu dizajnu).
 * Zároveň overí, že sa RemoteViews dajú nafúknuť (nepovolená trieda v layoute by tu spadla).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPreviewTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val out = File("build/widget-previews").apply { mkdirs() }
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 20, 17, 42, 0) }.timeInMillis

    private fun render(rv: RemoteViews, wDp: Int, hDp: Int, name: String) {
        val v: View = rv.apply(ctx, FrameLayout(ctx))
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt(); val pad = (20 * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // „tapeta“ plochy
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), Color.rgb(0x5B, 0x6E, 0x8C), Color.rgb(0xC9, 0xA9, 0x86), Shader.TileMode.CLAMP) })
        c.translate(pad.toFloat(), pad.toFloat())
        v.draw(c)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun route(): List<RoutePt> = (0..160).map { i ->
        val f = i / 160.0
        RoutePt(47.98 - 0.09 * f + 0.012 * sin(f * 3 * PI), 18.16 - 0.11 * f + 0.02 * sin(f * 5 * PI) * f,
            when { f < 0.08 || f > 0.92 -> 30f + 20 * (f % 0.08).toFloat(); f in 0.4..0.6 -> 92f; else -> 55f + 20 * sin(f * 9).toFloat() })
    }
    private val trip = TripSummary(1, "u1", "Domov", "Práca · Hurbanovo", now - 3 * 3_600_000L, now - 3 * 3_600_000L + 18 * 60_000L,
        13.2, 44.0, 96.0, 86, false, 1.67, "Škoda Fabia · KN-052-FD", route())
    private val week = listOf("Ut" to 26.4, "St" to 13.1, "Št" to 41.0, "Pi" to 27.9, "So" to 0.0, "Ne" to 8.7, "Po" to 24.5)
    private val weekSteps = listOf("Ut" to 6200.0, "St" to 8400.0, "Št" to 4100.0, "Pi" to 9900.0, "So" to 12100.0, "Ne" to 3000.0, "Po" to 6245.0)
    private val speed = (0..120).map { i -> SpeedSample(now - (120 - i) * 9_000L, i * 110.0, (40 + 45 * sin(i / 18.0)).coerceAtLeast(0.0).toFloat()) }
    private fun main(state: DriveState) = MainModel(
        true, state, liveStartedAt = now - 14 * 60_000L, liveKmh = 78, liveDistanceM = 9_400.0, liveSteps = 1_284, liveLimit = 70, liveCost = 1.19,
        vehicle = "Škoda Fabia · KN-052-FD", todayKm = 24.5, todayTrips = 3, todaySteps = 6_245, todayCost = 3.11, todayMs = 41 * 60_000L,
        week = week, weekSteps = weekSteps, speed = speed, speedTitle = "Rýchlosť · 14:19 · 13,2 km", speedMax = 96f, speedAvg = 44.0,
        stepGoal = 8000, reminder = "STK: o 9 dní", parkingAddr = "Hlavná 12, Imeľ", parkingSince = now - 95 * 60_000L,
        parkingLat = 47.9, parkingLng = 18.05, lastTrip = trip,
    )

    @Test fun mainWidget() {
        render(WidgetRender.main(ctx, main(DriveState.IDLE), 360, 470, 1, WidgetRender.CHART_KM, now), 360, 470, "01-hlavny-4x5")
        render(WidgetRender.main(ctx, main(DriveState.IDLE), 360, 430, 1, WidgetRender.CHART_STEPS, now), 360, 430, "02-hlavny-4x4")
        render(WidgetRender.main(ctx, main(DriveState.IDLE), 360, 300, 1, WidgetRender.CHART_KM, now), 360, 300, "03-hlavny-4x3")
        render(WidgetRender.main(ctx, main(DriveState.IDLE), 360, 190, 1, WidgetRender.CHART_KM, now), 360, 190, "04-hlavny-4x2")
        render(WidgetRender.main(ctx, main(DriveState.DRIVING), 360, 360, 1, WidgetRender.CHART_SPEED, now), 360, 360, "05-jazda-4x4")
        render(WidgetRender.main(ctx, main(DriveState.DRIVING), 360, 190, 1, WidgetRender.CHART_KM, now), 360, 190, "06-jazda-4x2")
        render(WidgetRender.main(ctx, main(DriveState.WALKING), 360, 300, 1, WidgetRender.CHART_KM, now), 360, 300, "07-chodza-4x3")
        render(WidgetRender.main(ctx, main(DriveState.CANDIDATE), 360, 190, 1, WidgetRender.CHART_KM, now), 360, 190, "08-overujem-4x2")
    }

    @Test fun smallWidgets() {
        render(WidgetRender.quick(ctx, true, DriveState.IDLE, 0, 0.0, 0, 24_500.0), 300, 84, "09-tlacidla")
        render(WidgetRender.quick(ctx, true, DriveState.DRIVING, 78, 9_400.0, 0), 300, 84, "10-tlacidla-jazda")
        render(WidgetRender.steps(ctx, StepsModel(6_245, 8_000, 4.6, 5, 12, weekSteps), 300, 200), 300, 200, "11-kroky")
        render(WidgetRender.parking(ctx, ParkingModel(false, "Hlavná 12, Imeľ", now - 95 * 60_000L, 47.9, 18.05, now + 38 * 60_000L), now), 360, 190, "12-parkovanie")
        val days = (1..20).map { (if (it == 1 || it % 5 == 0) "$it" else "") to listOf(13.0, 26.4, 0.0, 41.0, 8.7)[it % 5] }
        render(WidgetRender.month(ctx, MonthModel("Október", 642.0, 38, 92.1, 214.0, 148_300, 96.4, 12, days, "september"), 360, 240), 360, 240, "13-mesiac")
        render(WidgetRender.trip(ctx, trip, 360, 210, now), 360, 210, "14-posledna-jazda")
    }
}
