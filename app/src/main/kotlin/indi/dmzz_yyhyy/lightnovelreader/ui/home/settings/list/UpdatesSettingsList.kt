package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import indi.dmzz_yyhyy.lightnovelreader.BuildConfig
import indi.dmzz_yyhyy.lightnovelreader.R
import io.nightfish.lightnovelreader.api.ui.components.SettingsBasicEntry
import io.nightfish.lightnovelreader.api.ui.components.SettingsClickableEntry
import io.nightfish.lightnovelreader.api.ui.components.SettingsMenuEntry
import io.nightfish.lightnovelreader.api.ui.components.SettingsSwitchEntry
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.SettingState
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.data.MenuOptions

@Composable
fun UpdatesSettingsList(
    updatePhase: String,
    settingState: SettingState,
    checkUpdate: () -> Unit,
    isCheckingUpdate: Boolean = false,
) {
    if (BuildConfig.INDEPENDENT_BUILD) {
        SettingsBasicEntry(
            modifier = Modifier.background(colorScheme.surfaceContainer),
            painter = painterResource(R.drawable.info_24px),
            title = stringResource(R.string.settings_independent_current_version),
            description = "${BuildConfig.VERSION_NAME} [${BuildConfig.VERSION_CODE}]"
        )
        SettingsBasicEntry(
            modifier = Modifier.background(colorScheme.surfaceContainer),
            painter = painterResource(R.drawable.deployed_code_update_24px),
            title = stringResource(R.string.settings_get_updates),
            description = stringResource(R.string.settings_independent_check_updates_desc),
            belowContent = { Text(text = updatePhase, color = colorScheme.primary) },
            trailingContent = {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            },
            onClick = checkUpdate,
            enabled = !isCheckingUpdate
        )
        SettingsClickableEntry(
            modifier = Modifier.background(colorScheme.surfaceContainer),
            painter = painterResource(R.drawable.archive_24px),
            title = stringResource(R.string.settings_independent_release_page),
            description = stringResource(R.string.settings_independent_release_page_desc),
            openUrl = "https://github.com/Maker-Wen/LightNovelReader/releases"
        )
        return
    }

    SettingsSwitchEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.cloud_download_24px),
        title = stringResource(R.string.settings_auto_check_updates),
        description = stringResource(R.string.settings_auto_check_updates_desc),
        checked = settingState.checkUpdate,
        booleanUserData = settingState.checkUpdateUserData
    )
    SettingsMenuEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.alt_route_24px),
        title = stringResource(R.string.settings_update_channel),
        description = stringResource(R.string.settings_update_channel_desc),
        options = MenuOptions.UpdatePlatformOptions
            .getOptionWithValueOrDefault(settingState.distributionPlatformKey)
            .value,
        selectedOptionKey = settingState.updateChannelKey,
        onOptionChange = settingState.updateChannelKeyUserData::asynchronousSet
    )
    SettingsMenuEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.outline_explore_24px),
        title = stringResource(R.string.settings_distribution_platform),
        options = MenuOptions.UpdatePlatformOptions,
        selectedOptionKey = settingState.distributionPlatformKey,
        onOptionChange = settingState.distributionPlatformKeyUserData::asynchronousSet
    )
    SettingsClickableEntry(
        modifier = Modifier.background(colorScheme.surfaceContainer),
        painter = painterResource(R.drawable.deployed_code_update_24px),
        title = stringResource(R.string.settings_get_updates),
        description = stringResource(R.string.settings_get_updates_desc),
        option = updatePhase,
        onClick = checkUpdate
    )
}
