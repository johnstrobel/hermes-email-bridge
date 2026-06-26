package com.hermes.mediacontrol.model

// networkByte upper nibble encodes connection type; lower nibble reserved for RSSI.
// See companion object for constants.
data class PhoneStatus(
    val battery:     Int = 0,         // 0–100
    val networkByte: Int = NET_OFFLINE,
) {
    companion object {
        const val NET_OFFLINE   = 0x00
        const val NET_WIFI      = 0x10
        const val NET_LTE       = 0x20
        const val NET_5G        = 0x30
        const val NET_3G        = 0x40
        const val NET_EDGE      = 0x50
        const val NET_ETHERNET  = 0x60
    }
}
