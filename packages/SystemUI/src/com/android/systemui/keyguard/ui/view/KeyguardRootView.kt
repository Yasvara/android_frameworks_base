/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.android.systemui.yasvara.DepthSubjectView

/** Provides a container for all keyguard ui content. */
class KeyguardRootView(
    context: Context,
    attrs: AttributeSet?,
) :
    ConstraintLayout(
        context,
        attrs,
    ) {

    /**
     * Yasvara: with a depth wallpaper, the subject is drawn over the clock and the other
     * glanceable views but under what must stay readable and reachable, which the subject
     * names ([DepthSubjectView.drawsAbove]). Clock views are added at runtime, after the
     * subject, so child order alone cannot express this. Null until needed again.
     */
    private var drawingOrder: IntArray? = null

    /** Rebuilds the drawing order on the next frame; the subject calls this when it changes. */
    fun invalidateDrawingOrder() {
        drawingOrder = null
        isChildrenDrawingOrderEnabled = (0 until childCount).any { getChildAt(it) is DepthSubjectView }
        invalidate()
    }

    override fun onViewAdded(view: View?) {
        super.onViewAdded(view)
        invalidateDrawingOrder()
    }

    override fun onViewRemoved(view: View?) {
        super.onViewRemoved(view)
        invalidateDrawingOrder()
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        val order = drawingOrder?.takeIf { it.size == childCount }
            ?: buildDrawingOrder(childCount).also { drawingOrder = it }
        return order[drawingPosition]
    }

    /** Everything else in child order, then the subject, then what it puts above itself. */
    private fun buildDrawingOrder(count: Int): IntArray {
        val subject = (0 until count).firstOrNull { getChildAt(it) is DepthSubjectView }
            ?: return IntArray(count) { it }
        val view = getChildAt(subject) as DepthSubjectView
        val above = (0 until count).filter { it != subject && view.drawsAbove(getChildAt(it)) }
        val below = (0 until count).filter { it != subject && it !in above }
        return (below + subject + above).toIntArray()
    }
}
