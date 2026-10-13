package com.termux.app.settings.ui.categories

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.termux.R
import com.termux.app.models.UserAction
import com.termux.app.settings.ui.ActionRow
import com.termux.app.settings.ui.SettingRowContent
import com.termux.app.settings.ui.SettingsGroup
import com.termux.app.settings.ui.SettingsState
import com.termux.shared.activities.ReportActivity
import com.termux.shared.android.AndroidUtils
import com.termux.shared.file.FileUtils
import com.termux.shared.interact.ShareUtils
import com.termux.shared.models.ReportInfo
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.termux.TermuxUtils

fun LazyListScope.aboutSettings(state: SettingsState) {
    val donateRow: SettingRowContent? = if (state.availability?.donate == true) {
        { shapes ->
            ActionRow(
                title = stringResource(R.string.donate_preference_title),
                shapes = shapes,
                onClick = { ShareUtils.openUrl(state.context, TermuxConstants.TERMUX_DONATE_URL) },
            )
        }
    } else {
        null
    }
    item {
        SettingsGroup(
            rows = listOfNotNull(
                { shapes ->
                    ActionRow(
                        title = stringResource(R.string.about_preference_title),
                        shapes = shapes,
                        onClick = { showAbout(state.context) },
                    )
                },
                donateRow,
            ),
        )
    }
}

private fun showAbout(context: Context) {
    Thread {
        val title = "About"

        val aboutString = StringBuilder()
        aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES))
        aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true))
        aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context))

        val userActionName = UserAction.ABOUT.getName()

        val reportInfo = ReportInfo(userActionName, TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title)
        reportInfo.setReportString(aboutString.toString())
        reportInfo.setReportSaveFileLabelAndPath(userActionName,
            "${Environment.getExternalStorageDirectory()}/" +
                FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true))

        ReportActivity.startReportActivity(context, reportInfo)
    }.start()
}
