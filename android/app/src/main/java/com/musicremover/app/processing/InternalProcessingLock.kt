package com.musicremover.app.processing

import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide lock so that at most one on-device separation (about 1.2 GB of native memory) runs at a
 * time. A replaced or cancelled worker still inside an ORT run holds it until its session is closed.
 */
object InternalProcessingLock {
    val mutex = Mutex()
}
