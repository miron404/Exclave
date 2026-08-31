/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 Matsuri authors                                         *
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

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.res.TypedArrayUtils
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import java.util.Locale
import kotlin.math.roundToInt

@SuppressLint("RestrictedApi")
class ColorPickerPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = TypedArrayUtils.getAttr(
        context,
        androidx.preference.R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    )
) : Preference(
    context, attrs, defStyle
) {

    var inited = false

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val widgetFrame = holder.findViewById(android.R.id.widget_frame) as LinearLayout

        if (!inited) {
            inited = true

            var color = context.getColorAttr(android.R.attr.colorPrimary)
            if (color == ContextCompat.getColor(context, R.color.white)) {
                color = ContextCompat.getColor(context, R.color.material_light_black)
            }

            widgetFrame.addView(
                getImageViewAtColor(
                    color,
                    36,
                    0
                )
            )
            widgetFrame.visibility = View.VISIBLE
        }
    }

    fun getImageViewAtColor(color: Int, sizeDp: Int, paddingDp: Int): ImageView {
        // dp to pixel
        val factor = context.resources.displayMetrics.density
        val size = (sizeDp * factor).roundToInt()
        val paddingSize = (paddingDp * factor).roundToInt()

        return ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            setPadding(paddingSize)
            setImageDrawable(getColor(resources, color))
        }
    }

    fun getColor(res: Resources, color: Int): Drawable =
        tinted(res, R.drawable.ic_baseline_fiber_manual_record_24, color)

    private fun tinted(res: Resources, @DrawableRes drawableId: Int, color: Int): Drawable {
        val drawable = ResourcesCompat.getDrawable(res, drawableId, null)!!
        DrawableCompat.setTint(drawable.mutate(), color)
        return drawable
    }

    override fun onClick() {
        super.onClick()

        lateinit var dialog: AlertDialog
        // An unset preference still themes the app with the default, so show
        // that as the selected one rather than showing nothing selected.
        val current = DataStore.appTheme.let { if (it == 0) Theme.PINK else it }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(16), dp2px(8), dp2px(16), 0)

            addView(sectionLabel(R.string.theme_palette_material))
            addView(swatches {
                val colors = context.resources.getIntArray(R.array.material_colors)
                for ((i, color) in colors.withIndex()) {
                    addView(swatch(color, current == i + 1) {
                        persistInt(i + 1)
                        dialog.dismiss()
                        callChangeListener(i + 1)
                    })
                }
            })

            addView(divider())
            addView(sectionLabel(R.string.theme_palette_muted))
            addView(swatches {
                for (i in 0 until Theme.hueCount) {
                    val selected = current == Theme.CUSTOM && DataStore.appThemeHue == i
                    addView(swatch(ContextCompat.getColor(context, Theme.hueColorRes(i)), selected) {
                        DataStore.appThemeHue = i
                        persistInt(Theme.CUSTOM)
                        dialog.dismiss()
                        callChangeListener(Theme.CUSTOM)
                    })
                }
            })
        }

        val scrollView = NestedScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(content)
        }

        dialog = MaterialAlertDialogBuilder(context).setTitle(title)
            .setView(scrollView)
            .setNeutralButton(R.string.theme_custom) { _, _ -> askForColor() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun swatches(build: FlexboxLayout.() -> Unit) = FlexboxLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        flexWrap = FlexWrap.WRAP
        justifyContent = JustifyContent.SPACE_BETWEEN
        build()
    }

    private fun sectionLabel(@StringRes text: Int) = TextView(context).apply {
        setText(text)
        textSize = 12f
        isAllCaps = true
        letterSpacing = 0.08f
        setTextColor(context.getColorAttr(android.R.attr.textColorSecondary))
        setPadding(dp2px(4), dp2px(12), 0, dp2px(8))
    }

    private fun divider() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp2px(1)
        ).apply { topMargin = dp2px(12) }
        setBackgroundColor(
            ColorUtils.setAlphaComponent(
                context.getColorAttr(android.R.attr.textColorSecondary), 40
            )
        )
    }

    /** One colour, with a tick on it when it is the one in use. */
    private fun swatch(color: Int, selected: Boolean, onSelected: () -> Unit): View {
        val size = dp2px(64)
        return FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(size, size)
            addView(getImageViewAtColor(color, 64, 0))
            if (selected) {
                addView(ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(size, size)
                    setPadding(dp2px(19))
                    // The wheel runs from tones that carry white to tones that
                    // carry black, so the tick has to be picked per swatch.
                    setImageDrawable(
                        tinted(
                            context.resources,
                            R.drawable.ic_action_done,
                            if (ColorUtils.calculateLuminance(color) > 0.4) {
                                ContextCompat.getColor(context, R.color.black)
                            } else {
                                ContextCompat.getColor(context, R.color.white)
                            }
                        )
                    )
                })
            }
            setOnClickListener { onSelected() }
        }
    }

    /**
     * Asks for a colour as hex and keeps the step of the hue wheel it falls on.
     *
     * Only the hue survives: how saturated or how light the colour was is not
     * carried over, since the wheel is there to keep every choice inside one
     * restrained range. A theme attribute also cannot hold a value computed at
     * runtime, so the wheel is a set of resources and this picks one of them.
     */
    private fun askForColor() {
        val field = EditText(context).apply {
            setSingleLine()
            hint = context.getString(R.string.theme_custom_hint)
            setText(currentHex())
            setSelection(text.length)
        }
        val frame = FrameLayout(context).apply {
            setPadding(dp2px(24), dp2px(8), dp2px(24), 0)
            addView(field)
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.theme_custom)
            .setMessage(R.string.theme_custom_message)
            .setView(frame)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        // Bound after the dialog is shown so that a value that does not parse
        // can be reported on the field instead of closing over it.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val color = parseColor(field.text.toString())
            if (color == null) {
                field.error = context.getString(R.string.theme_custom_invalid)
                return@setOnClickListener
            }
            DataStore.appThemeHue = Theme.hueOf(color)
            persistInt(Theme.CUSTOM)
            dialog.dismiss()
            callChangeListener(Theme.CUSTOM)
        }
    }

    private fun currentHex(): String = String.format(
        Locale.ROOT,
        "#%06X",
        0xFFFFFF and ContextCompat.getColor(context, Theme.hueColorRes(DataStore.appThemeHue))
    )

    private fun parseColor(input: String): Int? {
        val text = input.trim().let { if (it.startsWith("#")) it else "#$it" }
        if (text.length != 7 && text.length != 9) return null
        return runCatching { Color.parseColor(text) }.getOrNull()
    }
}
