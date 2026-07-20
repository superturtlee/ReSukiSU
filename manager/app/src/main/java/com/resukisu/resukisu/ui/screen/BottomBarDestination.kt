package com.resukisu.resukisu.ui.screen

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.twotone.AdminPanelSettings
import androidx.compose.material.icons.twotone.Archive
import androidx.compose.material.icons.twotone.Extension
import androidx.compose.material.icons.twotone.Home
import androidx.compose.material.icons.twotone.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.resukisu.resukisu.R
import com.resukisu.resukisu.data.appPreferences
import com.resukisu.resukisu.ksuApp
import com.resukisu.resukisu.ui.component.ksuIsValid
import com.resukisu.resukisu.ui.screen.main.HomePage
import com.resukisu.resukisu.ui.screen.main.KpmPage
import com.resukisu.resukisu.ui.screen.main.ModulePage
import com.resukisu.resukisu.ui.screen.main.SettingsPage
import com.resukisu.resukisu.ui.screen.main.SuperUserPage
import com.resukisu.resukisu.ui.util.getKpmVersion

enum class BottomBarDestination(
    val direction: @Composable (bottomPadding: Dp) -> Unit,
    @param:StringRes val label: Int,
    val iconSelected: ImageVector,
    val iconNotSelected: ImageVector,
    val rootRequired: Boolean,
) {
    Home(
        { bottomPadding -> HomePage(bottomPadding) },
        R.string.home,
        Icons.TwoTone.Home,
        Icons.TwoTone.Home,
        false
    ),
    Kpm(
        { bottomPadding -> KpmPage(bottomPadding) },
        R.string.kpm_title,
        Icons.TwoTone.Archive,
        Icons.TwoTone.Archive,
        true
    ),
    SuperUser(
        { bottomPadding -> SuperUserPage(bottomPadding) },
        R.string.superuser,
        Icons.TwoTone.AdminPanelSettings,
        Icons.TwoTone.AdminPanelSettings,
        true
    ),
    Module(
        { bottomPadding -> ModulePage(bottomPadding) },
        R.string.module,
        Icons.TwoTone.Extension,
        Icons.TwoTone.Extension,
        true
    ),
    Settings(
        { bottomPadding -> SettingsPage(bottomPadding) },
        R.string.settings,
        Icons.TwoTone.Settings,
        Icons.TwoTone.Settings,
        false
    );

    companion object {
        fun getPages(): List<BottomBarDestination> {
            return if (ksuIsValid()) {
                // 全功能管理器
                val kpmVersion = runCatching {
                    getKpmVersion()
                }.getOrNull()

                // 隐藏 KPM 功能开关（show_kpm_info 实为“隐藏 KPM 功能”）
                val hideKpm = ksuApp.appPreferences.getBoolean("show_kpm_info", false)

                BottomBarDestination.entries.filter {
                    when (it) {
                        Kpm -> !hideKpm && (kpmVersion?.isNotEmpty() ?: false)
                        else -> true
                    }
                }
            } else {
                BottomBarDestination.entries.filter {
                    !it.rootRequired
                }
            }
        }
    }
}
