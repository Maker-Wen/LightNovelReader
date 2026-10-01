package indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.sourcechange

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import indi.dmzz_yyhyy.lightnovelreader.data.local.LocalDataManager
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceManager
import indi.dmzz_yyhyy.lightnovelreader.data.web.WebBookDataSourceProvider
import indi.dmzz_yyhyy.lightnovelreader.utils.restart
import io.nightfish.lightnovelreader.api.identifier.Identifier
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SourceChangeViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    webBookDataSourceProvider: WebBookDataSourceProvider,
    private val localDataManager: LocalDataManager,
    webBookDataSourceManager: WebBookDataSourceManager
) : ViewModel() {
    private val _uiState = MutableSourceChangeUiState().apply {
        currentSourceId = webBookDataSourceProvider.value.id
        webDataSourceItems = webBookDataSourceManager.webDataSourceItems
    }
    val uiState: SourceChangeUiState = _uiState

    fun changeWebSource(newWebDataSourceId: Identifier) {
        if (newWebDataSourceId == _uiState.currentSourceId || _uiState.isProcessing) return
        _uiState.isProcessing = true
        viewModelScope.launch {
            // Once source replacement starts, navigation away must not cancel the required
            // restart after commit and leave the provider pointing at the previous source.
            withContext(NonCancellable) {
                try {
                    localDataManager.switchSource(newWebDataSourceId)
                        .onErr {
                            Log.e("SourceChangeViewModel", "Failed to change data source", it)
                            Toast.makeText(
                                appContext,
                                "Failed to change data source. Please check the log for more information",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        .onOk {
                            _uiState.currentSourceId = newWebDataSourceId
                            restart(appContext)
                        }
                } finally {
                    _uiState.isProcessing = false
                }
            }
        }
    }
}
