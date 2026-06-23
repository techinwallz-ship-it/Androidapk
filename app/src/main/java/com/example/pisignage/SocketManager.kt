package com.example.pisignage

import android.content.Context
import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SocketManager {

    private var socket: Socket? = null

    fun connect(context: Context, pairingCode: String) {
        val appContext = context.applicationContext

        if (socket?.connected() == true) return

        socket = IO.socket("https://api.inwallz.in")

        socket?.on(Socket.EVENT_CONNECT) {
            Log.d("SOCKET", "✅ Connected")
            socket?.emit("join-tv", pairingCode)
        }

        socket?.on("start-playlist") {
            Log.d("SOCKET", "▶️ start-playlist received → instant sync")

            // Launch on IO to avoid NetworkOnMainThreadException
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Single fetch call (no nested duplicate)
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
        socket?.off()
        socket?.disconnect()
        socket = null
    }
}