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

package io.nekohasekai.sagernet.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app

object Theme {

    const val RED = 1
    const val PINK = 2
    const val PURPLE = 3
    const val DEEP_PURPLE = 4
    const val INDIGO = 5
    const val BLUE = 6
    const val LIGHT_BLUE = 7
    const val CYAN = 8
    const val TEAL = 9
    const val GREEN = 10
    const val LIGHT_GREEN = 11
    const val LIME = 12
    const val YELLOW = 13
    const val AMBER = 14
    const val ORANGE = 15
    const val DEEP_ORANGE = 16

    const val BROWN = 17
    const val GREY = 18
    const val BLUE_GREY = 19
    const val BLACK = 20

    const val DYNAMIC = 21
    const val CUSTOM = 22

    private fun defaultTheme() = PINK

    // The generated hue wheel behind CUSTOM. A colour typed in as hex is snapped
    // to the nearest of these, because a theme attribute cannot be given a value
    // computed at runtime: it has to name a resource, so the resources exist up
    // front. See values/themes_hue.xml.
    private val hueThemes = intArrayOf(R.style.Theme_SagerNet_Hue0, R.style.Theme_SagerNet_Hue1,
        R.style.Theme_SagerNet_Hue2, R.style.Theme_SagerNet_Hue3, R.style.Theme_SagerNet_Hue4,
        R.style.Theme_SagerNet_Hue5, R.style.Theme_SagerNet_Hue6, R.style.Theme_SagerNet_Hue7,
        R.style.Theme_SagerNet_Hue8, R.style.Theme_SagerNet_Hue9, R.style.Theme_SagerNet_Hue10,
        R.style.Theme_SagerNet_Hue11, R.style.Theme_SagerNet_Hue12, R.style.Theme_SagerNet_Hue13,
        R.style.Theme_SagerNet_Hue14, R.style.Theme_SagerNet_Hue15, R.style.Theme_SagerNet_Hue16,
        R.style.Theme_SagerNet_Hue17, R.style.Theme_SagerNet_Hue18, R.style.Theme_SagerNet_Hue19,
        R.style.Theme_SagerNet_Hue20, R.style.Theme_SagerNet_Hue21, R.style.Theme_SagerNet_Hue22,
        R.style.Theme_SagerNet_Hue23)
    private val hueDialogThemes = intArrayOf(R.style.Theme_SagerNet_Dialog_Hue0,
        R.style.Theme_SagerNet_Dialog_Hue1, R.style.Theme_SagerNet_Dialog_Hue2,
        R.style.Theme_SagerNet_Dialog_Hue3, R.style.Theme_SagerNet_Dialog_Hue4,
        R.style.Theme_SagerNet_Dialog_Hue5, R.style.Theme_SagerNet_Dialog_Hue6,
        R.style.Theme_SagerNet_Dialog_Hue7, R.style.Theme_SagerNet_Dialog_Hue8,
        R.style.Theme_SagerNet_Dialog_Hue9, R.style.Theme_SagerNet_Dialog_Hue10,
        R.style.Theme_SagerNet_Dialog_Hue11, R.style.Theme_SagerNet_Dialog_Hue12,
        R.style.Theme_SagerNet_Dialog_Hue13, R.style.Theme_SagerNet_Dialog_Hue14,
        R.style.Theme_SagerNet_Dialog_Hue15, R.style.Theme_SagerNet_Dialog_Hue16,
        R.style.Theme_SagerNet_Dialog_Hue17, R.style.Theme_SagerNet_Dialog_Hue18,
        R.style.Theme_SagerNet_Dialog_Hue19, R.style.Theme_SagerNet_Dialog_Hue20,
        R.style.Theme_SagerNet_Dialog_Hue21, R.style.Theme_SagerNet_Dialog_Hue22,
        R.style.Theme_SagerNet_Dialog_Hue23)
    private val hueColors = intArrayOf(R.color.hue_0_primary, R.color.hue_1_primary,
        R.color.hue_2_primary, R.color.hue_3_primary, R.color.hue_4_primary,
        R.color.hue_5_primary, R.color.hue_6_primary, R.color.hue_7_primary,
        R.color.hue_8_primary, R.color.hue_9_primary, R.color.hue_10_primary,
        R.color.hue_11_primary, R.color.hue_12_primary, R.color.hue_13_primary,
        R.color.hue_14_primary, R.color.hue_15_primary, R.color.hue_16_primary,
        R.color.hue_17_primary, R.color.hue_18_primary, R.color.hue_19_primary,
        R.color.hue_20_primary, R.color.hue_21_primary, R.color.hue_22_primary,
        R.color.hue_23_primary)

    val hueCount get() = hueThemes.size

    /** The step of the wheel a colour falls on. */
    fun hueOf(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return Math.round(hsv[0] / (360f / hueCount)) % hueCount
    }

    /** The primary colour of one step, for showing the wheel back to the user. */
    fun hueColorRes(index: Int): Int = hueColors[index.coerceIn(0, hueCount - 1)]


    fun apply(context: Context) {
        context.setTheme(getTheme())
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
    }

    fun getTheme(): Int {
        return getTheme(DataStore.appTheme)
    }

    fun getDialogTheme(): Int {
        return getDialogTheme(DataStore.appTheme)
    }

    fun getTheme(theme: Int): Int {
        return when (theme) {
            RED -> R.style.Theme_SagerNet_Red
            PINK -> R.style.Theme_SagerNet
            PURPLE -> R.style.Theme_SagerNet_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Indigo
            BLUE -> R.style.Theme_SagerNet_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_LightBlue
            CYAN -> R.style.Theme_SagerNet_Cyan
            TEAL -> R.style.Theme_SagerNet_Teal
            GREEN -> R.style.Theme_SagerNet_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_LightGreen
            LIME -> R.style.Theme_SagerNet_Lime
            YELLOW -> R.style.Theme_SagerNet_Yellow
            AMBER -> R.style.Theme_SagerNet_Amber
            ORANGE -> R.style.Theme_SagerNet_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Brown
            GREY -> R.style.Theme_SagerNet_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_BlueGrey
            BLACK -> if (usingNightMode()) R.style.Theme_SagerNet_Black else R.style.Theme_SagerNet_LightBlack
            DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.style.Theme_SagerNet_Dynamic else getTheme(defaultTheme())
            CUSTOM -> hueThemes[DataStore.appThemeHue.coerceIn(0, hueCount - 1)]
            else -> getTheme(defaultTheme())
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (theme) {
            RED -> R.style.Theme_SagerNet_Dialog_Red
            PINK -> R.style.Theme_SagerNet_Dialog
            PURPLE -> R.style.Theme_SagerNet_Dialog_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_Dialog_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Dialog_Indigo
            BLUE -> R.style.Theme_SagerNet_Dialog_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_Dialog_LightBlue
            CYAN -> R.style.Theme_SagerNet_Dialog_Cyan
            TEAL -> R.style.Theme_SagerNet_Dialog_Teal
            GREEN -> R.style.Theme_SagerNet_Dialog_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_Dialog_LightGreen
            LIME -> R.style.Theme_SagerNet_Dialog_Lime
            YELLOW -> R.style.Theme_SagerNet_Dialog_Yellow
            AMBER -> R.style.Theme_SagerNet_Dialog_Amber
            ORANGE -> R.style.Theme_SagerNet_Dialog_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_Dialog_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Dialog_Brown
            GREY -> R.style.Theme_SagerNet_Dialog_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_Dialog_BlueGrey
            BLACK -> if (usingNightMode()) R.style.Theme_SagerNet_Dialog_Black else R.style.Theme_SagerNet_Dialog_LightBlack
            DYNAMIC -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) R.style.Theme_SagerNet_Dialog_Dynamic else getDialogTheme(defaultTheme())
            CUSTOM -> hueDialogThemes[DataStore.appThemeHue.coerceIn(0, hueCount - 1)]
            else -> getDialogTheme(defaultTheme())
        }
    }

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(): Boolean {
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyNightTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

}