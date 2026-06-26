package com.hermes.mediacontrol.model

data class MediaState(
    val playing:   Boolean = false,
    val buffering: Boolean = false,
    val track:     String  = "",
    val artist:    String  = "",
    val volume:    Int     = 0,    // 0–100
    val inCall:    Boolean = false,
)
