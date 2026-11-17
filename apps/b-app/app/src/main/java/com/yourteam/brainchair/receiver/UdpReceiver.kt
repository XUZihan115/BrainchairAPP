package com.yourteam.brainchair.receiver

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** JVM-testable networking, independent of Android service and UI. */
class UdpReceiver(val session: StreamSession, private val port: Int = 9000, private val discoveryPort: Int = 9001) {
    @Volatile var running = false; private set
    private var data: DatagramSocket? = null
    private var discovery: DatagramSocket? = null
    private val threads = mutableListOf<Thread>()
    var boundDataPort = 0; private set
    var boundDiscoveryPort = 0; private set
    @Synchronized fun start() {
        if (running) return
        try {
            data = socket(port); discovery = socket(discoveryPort)
            boundDataPort = data!!.localPort; boundDiscoveryPort = discovery!!.localPort
            running = true
            launch(data!!, false); launch(discovery!!, true)
            session.event("监听数据 $boundDataPort / 发现 $boundDiscoveryPort")
        } catch(e: Exception) { close(); throw e }
    }
    private fun socket(port: Int) = DatagramSocket(null).apply {
        try { receiveBufferSize = 1024 * 1024; soTimeout = 200; bind(InetSocketAddress(port)) }
        catch(e: Exception) { close(); throw e }
    }
    private fun launch(socket: DatagramSocket, isDiscovery: Boolean) {
        val thread = Thread({
            val storage = ByteArray(65535) // detect oversize datagrams; never accept a truncated prefix
            val packet = DatagramPacket(storage, storage.size)
            while(running) {
                try {
                    packet.length = storage.size; socket.receive(packet)
                    val socketReceivedNs=System.nanoTime()
                    val bytes = storage.copyOf(packet.length)
                    if (isDiscovery) {
                        try {
                            val reply = EegProtocol.announce(bytes, boundDataPort, "BrainChair B")
                            socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                            session.event("ANNOUNCE → ${packet.socketAddress}")
                        } catch (_: PacketError) { /* invalid discovery receives no response */ }
                    } else session.accept(bytes, "${packet.address.hostAddress}:${packet.port}",socketReceivedNs)
                } catch (_: SocketTimeoutException) { /* periodic timeout check below */ }
                catch(e: Exception) {
                    if (running) { session.event("网络故障 ${e.javaClass.simpleName}: ${e.message}"); close() }
                }
                session.checkTimeout()
            }
        }, if(isDiscovery) "eeg-discovery" else "eeg-data")
        thread.isDaemon = true; threads.add(thread); thread.start()
    }
    @Synchronized fun close() {
        running = false; data?.close(); discovery?.close(); data = null; discovery = null
        session.stop(); threads.clear()
    }
}
