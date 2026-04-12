package com.musicremover.app.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object TermuxHelper {
    private const val TERMUX_PKG = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PKG, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Run a command inside Termux via RUN_COMMAND intent.
     * Returns null on success, or an error message string on failure.
     */
    private fun runCommand(
        context: Context,
        command: String,
        background: Boolean = true,
    ): String? {
        return try {
            val intent = Intent(RUN_COMMAND_ACTION).apply {
                component = ComponentName(TERMUX_PKG, RUN_COMMAND_SERVICE)
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            }
            context.startForegroundService(intent)
            null
        } catch (e: SecurityException) {
            "Permission denied. Open Termux → Settings and enable \"Allow external apps\""
        } catch (e: Exception) {
            e.message ?: "Failed to communicate with Termux"
        }
    }

    fun installServer(context: Context): String? {
        // Use proot-distro with Ubuntu to get a full Linux environment
        // where onnxruntime wheels work natively
        val script = buildString {
            append("pkg update -y && pkg upgrade -y && ")
            append("pkg install -y proot-distro pulseaudio && ")
            append("if [ ! -d /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu ]; then proot-distro install ubuntu; fi && ")
            append("proot-distro login ubuntu -- bash -c '")
            append("apt update -y && apt upgrade -y && ")
            append("apt install -y python3 python3-pip python3-venv ffmpeg git && ")
            append("python3 -m venv ~/venv && ")
            append(". ~/venv/bin/activate && ")
            append("pip install fastapi uvicorn yt-dlp python-multipart \"audio-separator[cpu]\" && ")
            append("if [ -d ~/music-remover ]; then cd ~/music-remover && git pull; ")
            append("else git clone https://github.com/Ignema/youtube-music-remover.git ~/music-remover; fi && ")
            append("echo && ")
            append("echo ✅ Setup complete! You can now start the server from the app.")
            append("'")
        }
        return runCommand(context, script, background = false)
    }

    fun startServer(context: Context): String? {
        val command = buildString {
            append("proot-distro login ubuntu -- bash -c '")
            append(". ~/venv/bin/activate && ")
            append("cd ~/music-remover && ")
            append("uvicorn api.main:app --host 127.0.0.1 --port 8000")
            append("'")
        }
        // Run in background so the user stays in our app
        return runCommand(context, command, background = true)
    }

    fun stopServer(context: Context): String? {
        // Find and kill whatever is listening on port 8000
        val cmd = "fuser -k 8000/tcp 2>/dev/null || " +
            "kill \$(lsof -t -i:8000) 2>/dev/null || " +
            "pkill -f uvicorn 2>/dev/null || " +
            "pkill -f proot 2>/dev/null; " +
            "echo stopped"
        return runCommand(context, cmd, background = true)
    }

    fun updateServer(context: Context): String? {
        val script = buildString {
            append("proot-distro login ubuntu -- bash -c '")
            append(". ~/venv/bin/activate && ")
            append("cd ~/music-remover && git pull && ")
            append("pip install --upgrade fastapi uvicorn yt-dlp python-multipart \"audio-separator[cpu]\" && ")
            append("echo && ")
            append("echo ✅ Update complete! Restart the server to apply changes.")
            append("'")
        }
        return runCommand(context, script, background = false)
    }
}
