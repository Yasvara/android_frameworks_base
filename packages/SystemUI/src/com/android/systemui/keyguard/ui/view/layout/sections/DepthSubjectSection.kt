/*
 * SPDX-FileCopyrightText: The Yasvara Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardSection
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.yasvara.DepthRepository
import com.android.systemui.yasvara.DepthSubjectView
import com.android.systemui.yasvara.WallpaperParallax
import com.android.systemui.yasvara.WallpaperPlacement
import javax.inject.Inject
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Yasvara: the depth wallpaper's subject, layered over the lock screen (docs/specs/0001, 0013).
 *
 * The lock screen fades out when the bouncer comes in, so a second subject, in a layer of the
 * shade window under the bouncer, takes over until the device unlocks: the character stays
 * sharp in front of the blurred wallpaper while the PIN is entered.
 */
class DepthSubjectSection
@Inject
constructor(
    private val depth: DepthRepository,
    private val parallax: WallpaperParallax,
    private val placement: WallpaperPlacement,
    private val transitions: KeyguardTransitionInteractor,
    private val shade: ShadeInteractor,
) : KeyguardSection() {
    private var view: DepthSubjectView? = null
    private var bouncerView: DepthSubjectView? = null
    private var binding: DisposableHandle? = null

    override fun addViews(constraintLayout: ConstraintLayout) {
        if (constraintLayout.findViewById<DepthSubjectView>(DepthSubjectView.VIEW_ID) != null) return
        view = DepthSubjectView(constraintLayout.context, parallax, placement).apply { id = DepthSubjectView.VIEW_ID }
        constraintLayout.addView(view)
    }

    override fun bindData(constraintLayout: ConstraintLayout) {
        val view = view ?: return
        binding?.dispose()
        binding = view.repeatWhenAttached {
            val bouncer = bouncerView(view.rootView)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    depth.subject.collect {
                        view.subject = it
                        bouncer?.subject = it
                    }
                }
                launch {
                    depth.config.collect {
                        view.config = it
                        bouncer?.config = it
                    }
                }
                launch {
                    placement.state.collect {
                        view.wallpaper = it
                        bouncer?.wallpaper = it
                    }
                }
                launch {
                    // The alternate (fingerprint) bouncer keeps the lock screen up, so the
                    // subject stays with it.
                    combine(
                        transitions.transitionValue(KeyguardState.LOCKSCREEN),
                        transitions.transitionValue(KeyguardState.ALTERNATE_BOUNCER),
                        shade.anyExpansion,
                    ) { lockscreen, alternate, expansion ->
                        (lockscreen + alternate).coerceAtMost(1f) * (1f - expansion)
                    }
                        .collect { view.shownFraction = it }
                }
                if (bouncer != null) {
                    // Fades in as the lock screen fades out, and out as the device unlocks.
                    launch {
                        transitions.transitionValue(KeyguardState.PRIMARY_BOUNCER)
                            .collect { bouncer.shownFraction = it }
                    }
                }
            }
        }
    }

    /** The bouncer's copy of the subject, in the shade window's layer for it. */
    private fun bouncerView(root: android.view.View): DepthSubjectView? {
        bouncerView?.let { return it }
        val layer = root.findViewById<ViewGroup>(R.id.depth_bouncer_layer) ?: return null
        val subject = DepthSubjectView(layer.context, parallax, placement).apply { shownFraction = 0f }
        layer.addView(
            subject,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        bouncerView = subject
        return subject
    }

    override fun applyConstraints(constraintSet: ConstraintSet) {
        val id = DepthSubjectView.VIEW_ID
        constraintSet.apply {
            constrainWidth(id, ConstraintSet.MATCH_CONSTRAINT)
            constrainHeight(id, ConstraintSet.MATCH_CONSTRAINT)
            connect(id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            connect(id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
        }
    }

    override fun removeViews(constraintLayout: ConstraintLayout) {
        binding?.dispose()
        binding = null
        view?.let { constraintLayout.removeView(it) }
        view = null
        bouncerView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        bouncerView = null
    }
}
