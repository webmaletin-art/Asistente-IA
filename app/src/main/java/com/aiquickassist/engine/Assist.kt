package com.aiquickassist.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiquickassist.data.AnalysisException
import com.aiquickassist.data.AnalysisResult
import com.aiquickassist.data.HistoryStore
import kotlinx.coroutines.CancellationException

sealed interface PanelState {
    data object None : PanelState
    data object Loading : PanelState
    data class Done(val result: AnalysisResult) : PanelState
    data class Failed(val message: String, val needsGemini: Boolean, val url: String? = null, val urlLabel: String? = null) : PanelState
}

/** Estado de UI compartido por el panel flotante y el navegador. */
class AssistState {
    var panel by mutableStateOf<PanelState>(PanelState.None)
    var searchOpen by mutableStateOf(false)
    var searchText by mutableStateOf("")

    /** Ejecuta un análisis, actualiza el panel y guarda en historial. Devuelve true si tuvo éxito. */
    suspend fun run(block: suspend () -> AnalysisResult): Boolean {
        panel = PanelState.Loading
        return try {
            val r = block()
            HistoryStore.add(r)
            panel = PanelState.Done(r)
            true
        } catch (e: CancellationException) {
            panel = PanelState.None; throw e
        } catch (e: AnalysisException) {
            panel = PanelState.Failed(e.message ?: "Error", e.needsGemini, e.googleUrl, e.openLabel); false
        } catch (e: Exception) {
            panel = PanelState.Failed("No se pudo completar la consulta. Revisa tu conexión.", false); false
        }
    }
}
