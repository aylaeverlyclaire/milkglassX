package com.milkglass.app.core.app

import com.milkglass.app.core.remote.SkipTimes

enum class SkipKind { Opening, Ending }

const val SKIP_PROMPT_SECONDS = 8.0

class SkipPrompter(private val showSeconds: Double = SKIP_PROMPT_SECONDS) {
    private var kind: SkipKind? = null
    private var left = 0.0
    private var wasOp = false
    private var wasEd = false

    fun update(t: SkipTimes, position: Double, dt: Double): SkipKind? {
        val inOp = t.opening?.contains(position) == true
        val inEd = t.ending?.contains(position) == true
        if (inOp && !wasOp) { kind = SkipKind.Opening; left = showSeconds }
        else if (inEd && !wasEd) { kind = SkipKind.Ending; left = showSeconds }
        else if (kind != null) {
            left -= dt
            val active = (kind == SkipKind.Opening && inOp) || (kind == SkipKind.Ending && inEd)
            if (!active || left <= 0.0) dismiss()
        }
        wasOp = inOp
        wasEd = inEd
        return kind
    }

    fun dismiss() { kind = null; left = 0.0 }
}
