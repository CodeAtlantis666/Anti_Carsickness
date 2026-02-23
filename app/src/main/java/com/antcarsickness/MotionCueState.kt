package com.antcarsickness

data class MotionCueState(
    val isVehicleLikely: Boolean,
    val confidence: Float,
    val cueX: Float,
    val cueY: Float,
    val turnRate: Float,
    val intensity: Float,
    val speedEstimate: Float,
    val linearAx: Float,
    val linearAy: Float,
    val linearAz: Float,
    val omegaZ: Float,
    val timestampMs: Long,
    val longitudinalAccel: Float,
    val lateralAccel: Float
)
