package com.example.pisignage

import android.content.Context
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SocketManager {

    private var socket: Socket? = null
    private var supervisorJob = SupervisorJob()
    private val scope get() = CoroutineScope(supervisorJob + Dispatchers.IO)

    fun connect(context: Context, pairingCode: String) {
        val appContext = context.applicationContext

        // Guard on null, not connected(): the old check passed while the socket was still
        // CONNECTING, creating duplicate connections on reconnect. (AUDIT P2-4)
        if (socket != null) return

        socket = IO.socket(AppConfig.SOCKET_URL)

        socket?.on(Socket.EVENT_CONNECT) {
            Log.d("SOCKET", "✅ Connected")
            socket?.emit("join-tv", pairingCode)
        }

        socket?.on("start-playlist") {
            Log.d("SOCKET", "▶️ start-playlist received → instant sync")
            scope.launch {
                try {
                    PlaylistRepository.fetchAndSave(appContext, pairingCode)
                } catch (e: Exception) {
                    Log.e("SOCKET", "start-playlist sync failed", e)
                }
            }
        }

        socket?.on(Socket.EVENT_DISCONNECT) {
            Log.d("SOCKET", "❌ Disconnected")
        }

        socket?.connect()
    }

    fun disconnect() {
        supervisorJob.cancel()
        supervisorJob = SupervisorJob()
        socket?.off()
        socket?.disconnect()
        socket = null
    }
}