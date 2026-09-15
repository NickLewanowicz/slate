package dev.slate.android.widget

import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import dev.slate.android.data.AnsweredChoice
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Persona-driven fixes: expired/preview options inert, answered marker rendering. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonaFixTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun collectTextsAndAlphas(root: android.view.View): Pair<List<String>, List<Float>> {
        val texts = mutableListOf<String>()
        val alphas = mutableListOf<Float>()
        fun walk(v: android.view.View) {
            alphas.add(v.alpha)
            (v as? android.widget.TextView)?.let { texts.add(it.text.toString()) }
            (v as? android.view.ViewGroup)?.let { g -> for (i in 0 until g.childCount) walk(g.getChildAt(i)) }
        }
        walk(root)
        return texts to alphas
    }

    private fun renderFor(
        json: String,
        answered: Map<String, AnsweredChoice> = emptyMap(),
        preview: Boolean = false,
    ): Pair<List<String>, List<Float>> {
        val spec = dev.slate.android.spec.SpecParser().parse(json)
        val cached = dev.slate.android.data.CachedSlate("home", json, "hash", "hash", System.currentTimeMillis())
        val rv = WidgetRenderer.render(
            context = context,
            bucket = SizeBucket.MEDIUM,
            spec = spec,
            cached = cached,
            answered = answered,
            preview = preview,
        )
        val frame = FrameLayout(context)
        frame.addView(rv.apply(context, frame))
        return collectTextsAndAlphas(frame)
    }

    @Test
    fun `normal unanswered question options stay fully opaque`() {
        val json = """{"id":"home","version":2,"title":"t","children":[
            {"type":"question","id":"q1","prompt":"?","options":[
                {"id":"a","label":"Alpha"},{"id":"b","label":"Beta"}]}]}"""
        val (texts, alphas) = renderFor(json)
        assertTrue("labels render", texts.containsAll(listOf("Alpha", "Beta")))
        assertTrue("tappable options are full alpha", alphas.all { it == 1f })
    }

    @Test
    fun `expired question options render dimmed (inert)`() {
        val json = """{"id":"home","version":2,"title":"t","expiresAt":"2020-01-01T00:00:00Z","children":[
            {"type":"question","id":"q1","prompt":"?","options":[
                {"id":"a","label":"Alpha"},{"id":"b","label":"Beta"}]}]}"""
        val (_, alphas) = renderFor(json)
        assertTrue("expired surface must dim option buttons", alphas.any { it < 1f })
    }

    @Test
    fun `preview question options render dimmed (in-app mockup)`() {
        val json = """{"id":"home","version":2,"title":"t","children":[
            {"type":"question","id":"q1","prompt":"?","options":[
                {"id":"a","label":"Alpha"},{"id":"b","label":"Beta"}]}]}"""
        val (_, alphas) = renderFor(json, preview = true)
        assertTrue("preview must dim option buttons", alphas.any { it < 1f })
    }

    @Test
    fun `answered question shows chosen marker and dims siblings`() {
        val json = """{"id":"home","version":2,"title":"t","children":[
            {"type":"question","id":"q1","prompt":"?","options":[
                {"id":"a","label":"Alpha"},{"id":"b","label":"Beta"}]}]}"""
        val (texts, alphas) = renderFor(
            json,
            answered = mapOf("q1" to AnsweredChoice(questionId = "q1", optionId = "a", optionLabel = "Alpha", at = 0L)),
        )
        assertTrue("chosen keeps label: $texts", texts.any { it.contains("Alpha") })
        assertTrue("dimmed siblings present: $alphas", alphas.any { it < 1f })
    }
}
