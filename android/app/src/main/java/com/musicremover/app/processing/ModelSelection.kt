package com.musicremover.app.processing

import com.musicremover.app.processing.internal.ModelCatalog

/** Which model is selected when the processing mode changes. Pure. */
object ModelSelection {
    /**
     * Returns (newSelected, newRemembered). Entering internal mode with a model it does not support selects
     * HQ3 and remembers the previous model; an earlier remembered model wins, so a UI reset in internal
     * mode does not overwrite it. Leaving internal mode restores the remembered model. Otherwise the inputs
     * are returned unchanged.
     */
    fun forMode(internal: Boolean, selected: String, supported: List<String>, remembered: String?): Pair<String, String?> =
        when {
            internal && selected !in supported -> ModelCatalog.HQ3 to (remembered ?: selected)
            !internal && remembered != null -> remembered to null
            else -> selected to remembered
        }
}
