package com.wkisai.tokenarb

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ultimo risultato della scansione tenuto in memoria. L'avanzamento passa da qui e non dal disco:
 * la schermata lo legge senza decodificare JSON e senza scrivere nelle preferenze di Android.
 */
object LiveState {
    @Volatile
    var outcome: ScanOutcome? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Nuovo risultato (parziale o finale): avvisa la schermata. Può essere chiamato da qualsiasi thread. */
    fun publish(o: ScanOutcome) {
        outcome = o
        for (l in listeners) l()
    }

    /** Risultato letto dal disco all'avvio: non sostituisce uno più recente già in memoria. */
    fun restore(o: ScanOutcome) {
        if (outcome == null) outcome = o
    }

    fun addListener(l: () -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}
