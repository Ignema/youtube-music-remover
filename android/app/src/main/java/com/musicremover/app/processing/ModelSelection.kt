package com.musicremover.app.processing

import com.musicremover.app.processing.internal.ModelCatalog

/** Which model is selected when the processing mode changes. Pure. */
object ModelSelection {
    /**
     * Returns (newSelected, newRemembered). In internal mode the model always comes from the
     * `internal_model` pref ([internalDefault], HQ3 if it is not supported), on entry and on every later
     * apply (reset, refresh); the remote pick is remembered for leaving, and an earlier remembered model
     * wins. Leaving internal mode restores the remembered model. Otherwise the inputs are returned unchanged.
     */
    fun forMode(
        internal: Boolean,
        selected: String,
        supported: List<String>,
        remembered: String?,
        internalDefault: String,
    ): Pair<String, String?> =
        when {
            internal -> (internalDefault.takeIf { it in supported } ?: ModelCatalog.HQ3) to (remembered ?: selected)
            !internal && remembered != null -> remembered to null
            else -> selected to remembered
        }
}
