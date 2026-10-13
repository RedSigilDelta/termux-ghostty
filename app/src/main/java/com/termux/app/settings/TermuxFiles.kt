package com.termux.app.settings

import com.termux.shared.termux.TermuxConstants
import java.io.File

object TermuxFiles {
    val TERMUX_WALLPAPER_FILE = File(TermuxConstants.TERMUX_DATA_HOME_DIR_PATH, "wallpaper")
    val fontFile = TermuxConstants.TERMUX_FONT_FILE
}
