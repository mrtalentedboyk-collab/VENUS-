package com.altrexis.venus.terminal

import android.content.Context
import android.widget.Toast

/** Executes only fixed, validated operations; arbitrary shell text is never passed to Termux. */
class CommandRunner(private val context: Context) {
    private val termux = TermuxRuntime(context)

    fun status(): String = termux.status()

    fun run(path: String, args: Array<String>, workdir: String): Boolean {
        val accepted = termux.dispatch(path, args, workdir)
        if (!accepted) {
            Toast.makeText(context, termux.status(), Toast.LENGTH_LONG).show()
        }
        return accepted
    }
}
