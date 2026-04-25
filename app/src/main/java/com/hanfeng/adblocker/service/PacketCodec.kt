package com.HanFeng.service

import com.HanFeng.model.PacketInfo

object PacketCodec {
    fun parse(packet: ByteArray): PacketInfo? {
        return parse(packet, packet.size)
    }

    fun parse(packet: ByteArray, length: Int): PacketInfo? {
        if (packet.isEmpty()) return null
        return when ((packet[0].toInt() ushr 4) and 0x0F) {
            4 -> parseIpv4(packet, length)
            6 -> parseIpv6(packet, length)
            else -> null
        }
    }

    fun buildUdpResponse(request: PacketInfo, responsePayload: ByteArray): ByteArray {
        return if (request.version == 4) buildIpv4UdpResponse(request, responsePayload) else buildIpv6UdpResponse(request, responsePayload)
    }

    private fun parseIpv4(packet: ByteArray, length: Int): PacketInfo? {
        if (length < 20) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (length < ihl) return null
        val fragmentOffset = ((packet[6].toInt() and 0x1F) shl 8) or (packet[7].toInt() and 0xFF)
        if (fragmentOffset != 0) return null
        val protocol = packet[9].toInt() and 0xFF
        val src = packet.copyOfRange(12, 16)
        val dst = packet.copyOfRange(16, 20)
        val transport = packet.copyOfRange(ihl, length)
        return when (protocol) {
            17 -> {
                if (transport.size < 8) return null
                PacketInfo(4, src, dst, protocol, readShort(transport, 0), readShort(transport, 2), transport.copyOfRange(8, transport.size))
            }
            6 -> {
                if (transport.size < 20) return null
                PacketInfo(4, src, dst, protocol, readShort(transport, 0), readShort(transport, 2), ByteArray(0))
            }
            else -> PacketInfo(4, src, dst, protocol, 0, 0, ByteArray(0))
        }
    }

    private fun parseIpv6(packet: ByteArray, length: Int): PacketInfo? {
        if (length < 40) return null
        val nextHeader = packet[6].toInt() and 0xFF
        val src = packet.copyOfRange(8, 24)
        val dst = packet.copyOfRange(24, 40)
        val transport = packet.copyOfRange(40, length)
        return when (nextHeader) {
            17 -> {
                if (transport.size < 8) return null
                PacketInfo(6, src, dst, nextHeader, readShort(transport, 0), readShort(transport, 2), transport.copyOfRange(8, transport.size))
            }
            6 -> {
                if (transport.size < 20) return null
                PacketInfo(6, src, dst, nextHeader, readShort(transport, 0), readShort(transport, 2), ByteArray(0))
            }
            else -> PacketInfo(6, src, dst, nextHeader, 0, 0, ByteArray(0))
        }
    }

    private fun buildIpv4UdpResponse(request: PacketInfo, responsePayload: ByteArray): ByteArray {
        val totalLength = 20 + 8 + responsePayload.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[1] = 0
        writeShort(packet, 2, totalLength)
        writeShort(packet, 4, 0)
        writeShort(packet, 6, 0)
        packet[8] = 64
        packet[9] = 17
        request.destinationAddress.copyInto(packet, 12)
        request.sourceAddress.copyInto(packet, 16)
        writeShort(packet, 20, request.destinationPort)
        writeShort(packet, 22, request.sourcePort)
        writeShort(packet, 24, 8 + responsePayload.size)
        writeShort(packet, 26, 0)
        responsePayload.copyInto(packet, 28)
        val ipChecksum = checksum(packet, 0, 20)
        writeShort(packet, 10, ipChecksum)
        val udpChecksum = udpChecksumIpv4(packet, responsePayload.size)
        writeShort(packet, 26, udpChecksum)
        return packet
    }

    private fun buildIpv6UdpResponse(request: PacketInfo, responsePayload: ByteArray): ByteArray {
        val payloadLength = 8 + responsePayload.size
        val packet = ByteArray(40 + payloadLength)
        packet[0] = 0x60
        writeShort(packet, 4, payloadLength)
        packet[6] = 17
        packet[7] = 64
        request.destinationAddress.copyInto(packet, 8)
        request.sourceAddress.copyInto(packet, 24)
        writeShort(packet, 40, request.destinationPort)
        writeShort(packet, 42, request.sourcePort)
        writeShort(packet, 44, payloadLength)
        writeShort(packet, 46, 0)
        responsePayload.copyInto(packet, 48)
        val udpChecksum = udpChecksumIpv6(packet, responsePayload.size)
        writeShort(packet, 46, udpChecksum)
        return packet
    }

    private fun udpChecksumIpv4(packet: ByteArray, payloadSize: Int): Int {
        val pseudo = ByteArray(12 + 8 + payloadSize)
        packet.copyOfRange(12, 20).copyInto(pseudo, 0)
        pseudo[8] = 0
        pseudo[9] = 17
        writeShort(pseudo, 10, 8 + payloadSize)
        packet.copyOfRange(20, packet.size).copyInto(pseudo, 12)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun udpChecksumIpv6(packet: ByteArray, payloadSize: Int): Int {
        val pseudo = ByteArray(40 + 8 + payloadSize)
        packet.copyOfRange(8, 24).copyInto(pseudo, 0)
        packet.copyOfRange(24, 40).copyInto(pseudo, 16)
        pseudo[35] = (8 + payloadSize).toByte()
        pseudo[39] = 17
        packet.copyOfRange(40, packet.size).copyInto(pseudo, 40)
        return checksum(pseudo, 0, pseudo.size)
    }

    private fun checksum(buffer: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        while (index < offset + length - 1) {
            sum += readShort(buffer, index)
            index += 2
        }
        if (length % 2 == 1) {
            sum += (buffer[offset + length - 1].toInt() and 0xFF shl 8).toLong()
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun readShort(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }

    private fun writeShort(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }
}
