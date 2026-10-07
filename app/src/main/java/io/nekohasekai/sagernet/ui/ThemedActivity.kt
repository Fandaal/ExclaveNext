/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getBooleanProperty
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    enum class Type {
        Default,
        Dialog
    }

    open val type = Type.Default

    var themeResId = 0
    var uiMode = 0

    private val setNavigationBarColor = DataStore.experimentalFlagsProperties.getBooleanProperty("setNavigationBarColor")

    override fun onCreate(savedInstanceState: Bundle?) {
        when (type) {
            Type.Default -> {
                Theme.apply(this)
            }
            Type.Dialog -> {
                Theme.applyDialog(this)
            }
        }
        Theme.applyNightTheme()

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // https://stackoverflow.com/questions/79319740/edge-to-edge-doesnt-work-when-activity-recreated-or-appcompatdelegate-setdefaul
            // BAKLAVA and later VANILLA_ICE_CREAM have fixed this
            // set this before super.onCreate(savedInstanceState)
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }

        super.onCreate(savedInstanceState)
        uiMode = resources.configuration.uiMode

        onBackPressedCallback?.let {
            onBackPressedDispatcher.addCallback(this, it)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT <= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val insetController = WindowCompat.getInsetsController(window, window.decorView)
            if (setNavigationBarColor) {
                insetController.isAppearanceLightNavigationBars =
                    if (DataStore.appTheme == Theme.BLACK) !Theme.usingNightMode() else false
                @Suppress("DEPRECATION")
                window.navigationBarColor = getColorAttr(androidx.appcompat.R.attr.colorPrimaryDark)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = true
                }
            } else {
                insetController.isAppearanceLightNavigationBars = !Theme.usingNightMode()
            }
            insetController.isAppearanceLightStatusBars =
                if (DataStore.appTheme == Theme.BLACK) !Theme.usingNightMode() else false
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            findViewById<AppBarLayout>(R.id.appbar)?.apply {
                updatePadding(
                    top = bars.top,
                    left = bars.left,
                    right = bars.right,
                )
            }
            insets
        }
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode

            if (DataStore.appTheme == Theme.BLACK) {
                Theme.apply(this)
            }

            ActivityCompat.recreate(this)
        }
    }

    /**
     * Exclave Next: snackbars in front of dialogs.
     *
     * A Snackbar is added to a ViewGroup of the ACTIVITY window, while a
     * dialog is a separate window that is always above it — so a snackbar
     * shown while any dialog is up renders behind that dialog and is never
     * seen. Material offers no way to raise a Snackbar above another window
     * (checked against material 1.14: Snackbar has no overlay/window entry
     * point), so the show() itself is deferred instead.
     *
     * Held back until the activity owns the window focus again, i.e. until the
     * LAST dialog of a stack is closed — not merely the top one, which is
     * what made a per-dialog dismissal callback show the snackbar behind the
     * dialog below it.
     */
    private val heldSnackbars = ArrayList<Snackbar>()
    private var ownsWindowFocus = true

    /** Shows the snackbar now when this activity is the front window, and
     *  holds it back until every open dialog has been closed otherwise. */
    internal fun showOnForeground(snackbar: Snackbar) {
        if (ownsWindowFocus) snackbar.show() else heldSnackbars.add(snackbar)
    }

    /** Forgets a held snackbar that must never be shown. Dismissing it instead
     *  would leave it queued, and it would appear later with stale content. */
    internal fun discardHeldSnackbar(snackbar: Snackbar) {
        if (heldSnackbars.remove(snackbar)) return
        // Already shown (or being shown): fall back to a real dismissal, which
        // is what the caller expects from this call.
        snackbar.dismiss()
    }

    private fun releaseHeldSnackbars() {
        if (heldSnackbars.isEmpty()) return
        // Material queues snackbars itself, so releasing several at once shows
        // them one after another instead of stacking them on top of each other.
        val pending = heldSnackbars.toList()
        heldSnackbars.clear()
        pending.forEach { it.show() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Losing the focus is how this activity learns that something else
        // (a dialog) is on top of it; regaining it means the last one is gone
        // and the surface below is visible again.
        if (hasFocus) {
            ownsWindowFocus = true
            releaseHeldSnackbars()
        } else {
            ownsWindowFocus = false
        }
    }

    override fun onResume() {
        super.onResume()
        // Safety net: a focus callback can be missed while the activity is
        // being recreated, and a snackbar held back since then would then
        // never be shown at all.
        ownsWindowFocus = true
        releaseHeldSnackbars()
    }

    /**
     * Exclave Next: a Snackbar that waits for the foreground before showing.
     *
     * Snackbar's constructor is private and its show() only attaches the view
     * to the parent captured at make() time, so interception cannot happen
     * inside Snackbar itself — and the parent is an ACTIVITY view, while a
     * dialog lives in its own, always-higher window. Material 1.14 has no
     * overlay/window API to change that, so the show() is deferred through
     * this handle instead.
     *
     * It forwards the Snackbar API this app uses, so every call site keeps
     * working unchanged — including chaining like
     * `snackbar(x).setAction(y) { … }.show()`.
     */
    class SnackbarHandle internal constructor(
        private val activity: ThemedActivity,
        /** The real snackbar, for callers that need the Material type. */
        val delegate: Snackbar,
    ) {
        fun setText(resId: Int) = apply { delegate.setText(resId) }
        fun setText(text: CharSequence) = apply { delegate.setText(text) }
        fun setAction(resId: Int, listener: View.OnClickListener?) =
            apply { delegate.setAction(resId, listener) }

        fun addCallback(callback: Snackbar.Callback?) = apply { delegate.addCallback(callback) }
        fun setDuration(duration: Int) = apply { delegate.setDuration(duration) }
        fun setTextColor(color: Int) = apply { delegate.setTextColor(color) }
        fun setAnchorView(anchorView: View?) = apply { delegate.anchorView = anchorView }
        fun dismiss() = delegate.dismiss()
        fun isShown(): Boolean = delegate.isShown

        /** Drops this snackbar without showing it and without the dismissal
         *  callback firing — for a message whose content is already obsolete,
         *  such as the "Undo" offer of a deletion that has been committed. */
        fun discard() = activity.discardHeldSnackbar(delegate)

        /** The one method that behaves differently: it defers while any
         *  dialog covers this activity, so the message is actually seen. */
        fun show() = activity.showOnForeground(delegate)
    }

    fun snackbar(@StringRes resId: Int): SnackbarHandle = snackbar("").setText(resId)

    fun snackbar(text: CharSequence): SnackbarHandle =
        SnackbarHandle(this, snackbarInternal(text).apply {
            view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
                maxLines = 10
            }
        })

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

    open val onBackPressedCallback: OnBackPressedCallback? get() = null

}