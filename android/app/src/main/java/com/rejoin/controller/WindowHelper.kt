package com.rejoin.controller

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.IBinder
import android.os.Looper
import java.lang.reflect.Method

/**
 * Executed via `su` + `app_process /system/bin com.rejoin.controller.WindowHelper`
 * so the binder calls into ActivityTaskManagerService run with uid=0 (root) and bypass
 * normal app permission limits on MANAGE_ACTIVITY_TASKS.
 */
object WindowHelper {
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val RESIZE_MODE_PRESERVE_WINDOW = 1
    private const val RESIZE_MODE_FORCED = 2

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("ERR: missing command")
            kotlin.system.exitProcess(2)
        }
        try {
            @Suppress("DEPRECATION")
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper()
            }
        } catch (_: Throwable) {
        }
        exemptHiddenApi()

        val log = StringBuilder()
        try {
            val (svcName, svc) = obtainService(log)
            if (svc == null) {
                println("ERR: no activity service ($log)")
                kotlin.system.exitProcess(1)
            }
            when (args[0]) {
                "mode" -> {
                    val taskId = args.getOrNull(1)?.toIntOrNull() ?: error("invalid taskId")
                    val mode = args.getOrNull(2)?.toIntOrNull() ?: WINDOWING_MODE_FREEFORM
                    val ok = setWindowingMode(svc, taskId, mode, log)
                    println("${if (ok) "OK" else "FAIL"}: svc=$svcName $log")
                    if (!ok) kotlin.system.exitProcess(3)
                }
                "resize" -> {
                    if (args.size < 6) error("usage: resize <taskId> <l> <t> <r> <b> [stackId]")
                    val taskId = args[1].toInt()
                    val rect = Rect(args[2].toInt(), args[3].toInt(), args[4].toInt(), args[5].toInt())
                    val stackId = args.getOrNull(6)?.toIntOrNull() ?: 0
                    val modeOk = setWindowingMode(svc, taskId, WINDOWING_MODE_FREEFORM, log)
                    val taskOk = resizeTask(svc, taskId, rect, log)
                    val stackOk = if (stackId > 0) resizeStack(svc, stackId, rect, log) else false
                    val ok = taskOk || stackOk
                    println("${if (ok) "OK" else "FAIL"}: svc=$svcName mode=$modeOk $log")
                    if (!ok) kotlin.system.exitProcess(3)
                }
                "stack-resize" -> {
                    if (args.size < 6) error("usage: stack-resize <stackId> <l> <t> <r> <b>")
                    val stackId = args[1].toInt()
                    val rect = Rect(args[2].toInt(), args[3].toInt(), args[4].toInt(), args[5].toInt())
                    val ok = resizeStack(svc, stackId, rect, log)
                    println("${if (ok) "OK" else "FAIL"}: svc=$svcName $log")
                    if (!ok) kotlin.system.exitProcess(3)
                }
                else -> error("unknown command: ${args[0]}")
            }
        } catch (t: Throwable) {
            val root = t.cause ?: t
            println("ERR: ${root.javaClass.simpleName}: ${root.message} | $log")
            kotlin.system.exitProcess(1)
        }
    }

    @SuppressLint("PrivateApi")
    private fun exemptHiddenApi() {
        try {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntime.getDeclaredMethod("getRuntime")
            val runtime = getRuntime.invoke(null)
            val setExemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            setExemptions.invoke(runtime, arrayOf("L"))
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("PrivateApi")
    private fun obtainService(log: StringBuilder): Pair<String, Any?> {
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        val candidates = listOf(
            "activity_task" to "android.app.IActivityTaskManager\$Stub",
            "activity" to "android.app.IActivityManager\$Stub",
        )
        for ((name, stubName) in candidates) {
            try {
                val binder = getService.invoke(null, name) as? IBinder
                if (binder == null) {
                    log.append("$name=null; ")
                    continue
                }
                val stub = Class.forName(stubName)
                val asInterface = stub.getMethod("asInterface", IBinder::class.java)
                val proxy = asInterface.invoke(null, binder)
                if (proxy != null) return name to proxy
            } catch (t: Throwable) {
                val root = t.cause ?: t
                log.append("$name:${root.javaClass.simpleName}; ")
            }
        }
        return "none" to null
    }

    private fun setWindowingMode(svc: Any, taskId: Int, mode: Int, log: StringBuilder): Boolean {
        val methods = svc.javaClass.methods.filter { it.name == "setTaskWindowingMode" }
        if (methods.isEmpty()) {
            log.append("no-setTaskWindowingMode; ")
            return false
        }
        for (m in methods.sortedByDescending { it.parameterCount }) {
            val args = buildArgsForWindowingMode(m, taskId, mode) ?: continue
            try {
                m.invoke(svc, *args)
                log.append("setTaskWindowingMode(${m.parameterCount})=ok; ")
                return true
            } catch (t: Throwable) {
                val root = t.cause ?: t
                log.append("setTaskWindowingMode=${root.javaClass.simpleName}:${root.message}; ")
            }
        }
        return false
    }

    private fun buildArgsForWindowingMode(m: Method, taskId: Int, mode: Int): Array<Any>? {
        val p = m.parameterTypes
        return when {
            p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType && p[2] == Boolean::class.javaPrimitiveType ->
                arrayOf(taskId, mode, true)
            p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Int::class.javaPrimitiveType ->
                arrayOf(taskId, mode)
            else -> null
        }
    }

    private fun resizeTask(svc: Any, taskId: Int, rect: Rect, log: StringBuilder): Boolean {
        val methods = svc.javaClass.methods.filter { it.name == "resizeTask" }
        if (methods.isEmpty()) {
            log.append("no-resizeTask; ")
            return false
        }
        for (m in methods.sortedByDescending { it.parameterCount }) {
            val p = m.parameterTypes
            when {
                p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == Rect::class.java && p[2] == Int::class.javaPrimitiveType -> {
                    for (mode in listOf(RESIZE_MODE_FORCED or RESIZE_MODE_PRESERVE_WINDOW, RESIZE_MODE_FORCED, RESIZE_MODE_PRESERVE_WINDOW, 0)) {
                        try {
                            m.invoke(svc, taskId, rect, mode)
                            log.append("resizeTask(mode=$mode)=ok; ")
                            return true
                        } catch (t: Throwable) {
                            val root = t.cause ?: t
                            log.append("resizeTask($mode)=${root.javaClass.simpleName}:${root.message}; ")
                        }
                    }
                }
                p.size == 2 && p[0] == Int::class.javaPrimitiveType && p[1] == Rect::class.java -> {
                    try {
                        m.invoke(svc, taskId, rect)
                        log.append("resizeTask(2)=ok; ")
                        return true
                    } catch (t: Throwable) {
                        val root = t.cause ?: t
                        log.append("resizeTask(2)=${root.javaClass.simpleName}:${root.message}; ")
                    }
                }
            }
        }
        return false
    }

    private fun resizeStack(svc: Any, stackId: Int, rect: Rect, log: StringBuilder): Boolean {
        val methods = svc.javaClass.methods.filter { it.name == "resizeStack" }
        if (methods.isEmpty()) {
            log.append("no-resizeStack; ")
            return false
        }
        for (m in methods.sortedBy { it.parameterCount }) {
            val p = m.parameterTypes
            if (p.size < 2 || p[0] != Int::class.javaPrimitiveType || p[1] != Rect::class.java) continue
            val args = Array<Any?>(p.size) { null }
            args[0] = stackId
            args[1] = rect
            var supported = true
            for (i in 2 until p.size) {
                args[i] = when (p[i]) {
                    Boolean::class.javaPrimitiveType -> i == 3 // preserveWindows = true when 4th param
                    Int::class.javaPrimitiveType -> -1
                    else -> {
                        supported = false
                        null
                    }
                }
            }
            if (!supported) continue
            try {
                m.invoke(svc, *args)
                log.append("resizeStack(${p.size})=ok; ")
                return true
            } catch (t: Throwable) {
                val root = t.cause ?: t
                log.append("resizeStack(${p.size})=${root.javaClass.simpleName}:${root.message}; ")
            }
        }
        return false
    }
}
