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

package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R

/**
 * A [PreferenceCategory] whose header row toggles the visibility of its children.
 *
 * Collapsing is implemented through [isOnSameScreenAsChildren]: when collapsed the
 * adapter excludes the whole subtree from the flat preference list, so children
 * hidden by the fragment (isVisible gates) cannot leak through a collapsed group.
 *
 * The expanded/collapsed state is deliberately transient (instance field): every
 * entry into the settings screen starts with all groups collapsed, as fragments
 * are re-created per screen entry. Nothing is persisted — there is no saved state
 * to go stale, and no extra file to manage.
 */
class CollapsiblePreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : PreferenceCategory(context, attrs) {

    var expanded: Boolean = false
        private set

    init {
        layoutResource = R.layout.preference_collapsible_category
        // Category defaults to non-clickable; make the header row clickable.
        isSelectable = true
        // Categories never persist anything into the preference data store.
        isPersistent = false
    }

    // PreferenceCategory.isEnabled() is hard-coded to false which would block
    // performClick(); keep the header enabled.
    override fun isEnabled(): Boolean = true

    // Collapsed groups are excluded from the visible list entirely.
    override fun isOnSameScreenAsChildren(): Boolean = expanded

    override fun onClick() {
        setExpanded(!expanded)
    }

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        // notifyChanged() only rebinds this header row (chevron rotation);
        // the flat visible list is rebuilt exclusively via notifyHierarchyChanged().
        notifyHierarchyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val arrow = holder.findViewById(R.id.expand_arrow) as? ImageView ?: return
        arrow.rotation = if (expanded) EXPANDED_ROTATION else COLLAPSED_ROTATION
    }

    companion object {
        private const val EXPANDED_ROTATION = 180f
        private const val COLLAPSED_ROTATION = 0f
    }
}
