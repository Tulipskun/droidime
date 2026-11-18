package com.tulipskun.droidime

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// Apps cannot exec() binaries from their own data directory: SELinux labels those files
// app_data_file and denies execute even with mode 755. The tunnel already requires Shizuku, so
// run its binary through Shizuku's shell from /data/local/tmp, where execution is permitted.
internal object ShizukuShell {
    data class Result(val exitCode: Int, val output: String)

    fun available(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun newProcess(command: String): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return try {
            method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw error.targetException ?: error
        }
    }

    fun execute(command: String, timeoutSeconds: Long = 30): Result {
        val process = newProcess(command)
        val output = ByteArrayOutputStream()
        val reader = Thread { process.inputStream.use { it.copyTo(output) } }
        reader.start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            throw IllegalStateException("Shizuku command timed out")
        }
        reader.join(2_000L)
        return Result(process.exitValue(), output.toString(Charsets.UTF_8.name()).trim())
    }
}
