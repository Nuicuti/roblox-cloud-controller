package com.rejoin.controller

import android.os.Process
import java.util.concurrent.TimeUnit

data class ShellResult(val exitCode: Int, val output: String, val timedOut: Boolean = false)

object RootShell {
    fun quote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    fun joinArgs(args: List<String>): String = args.joinToString(" ") { quote(it) }

    fun run(args: List<String>, check: Boolean = true, timeoutSec: Long = 15): ShellResult {
        return runRaw(joinArgs(args), check = check, timeoutSec = timeoutSec)
    }

    fun runRaw(shellCommand: String, check: Boolean = true, timeoutSec: Long = 15): ShellResult {
        val proc = ProcessBuilder("su", "-c", shellCommand)
            .redirectErrorStream(true)
            .start()
        val buffer = StringBuilder()
        val reader = Thread {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (buffer.length < 262_144) {
                            buffer.append(line).append('\n')
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        reader.isDaemon = true
        reader.start()

        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            reader.join(300)
            if (check) throw AgentException("Lệnh root quá thời gian chờ (${timeoutSec}s).")
            return ShellResult(-1, buffer.toString().trim(), timedOut = true)
        }
        reader.join(500)
        val out = buffer.toString().trim()
        val code = proc.exitValue()
        val failed = code != 0 || Regex("""(?m)^(Error:|Exception |java\.|SecurityException)""").containsMatchIn(out)
        if (failed && check) {
            throw AgentException(out.take(500).ifEmpty { "Lệnh Android không thành công." })
        }
        return ShellResult(code, out, timedOut = false)
    }

    fun hasRoot(): Boolean {
        return try {
            val res = run(listOf("/system/bin/id", "-u"), check = false, timeoutSec = 5)
            res.exitCode == 0 && res.output.trim() == "0"
        } catch (_: Exception) {
            false
        }
    }

    fun hardenRuntime(packageName: String) {
        val pid = Process.myPid()
        val qPkg = quote(packageName)
        val cmds = listOf(
            "echo -1000 > /proc/$pid/oom_score_adj",
            "/system/bin/dumpsys deviceidle whitelist +$qPkg",
            "/system/bin/cmd appops set $qPkg RUN_IN_BACKGROUND allow",
            "/system/bin/cmd appops set $qPkg RUN_ANY_IN_BACKGROUND allow",
            "/system/bin/cmd appops set $qPkg START_FOREGROUND allow",
            "/system/bin/am set-standby-bucket $qPkg active",
            "/system/bin/settings put global settings_enable_monitor_phantom_procs false",
        )
        for (c in cmds) {
            try {
                runRaw(c, check = false, timeoutSec = 5)
            } catch (_: Exception) {
            }
        }
    }
}
