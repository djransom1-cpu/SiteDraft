package com.example.sitedraft.ui.canvas

import kotlin.math.*

// ProCalc's roof and rafter engines (ProCalcMultiplatform/Engines.kt), copied unchanged so Site Draft
// and ProCalc always give the same numbers. Change them in ProCalc first, then copy here.

/** Matches master's RoofCalcActivity formula exactly - a full framing material takeoff (rafter
 * counts, hip/valley members, ridge length corrected for hip/valley geometry, jack rafter
 * differences, sub-fascia footage, trapezoid-corrected sheathing area), not just a shingle
 * coverage calculator. leftEndType/rightEndType: 0=Gable, 1=Hip, 2=Valley. */
object RoofEngine {
    data class RoofResult(
        val run: Double,
        val rise: Double,
        val rafterToWall: Double,
        val tailLen: Double,
        val overallLen: Double,
        val correctedRidgeLen: Double,
        val commonCount: Int,
        val hipRun: Double,
        val hipPitch: Double,
        val hipLen: Double,
        val totalHips: Int,
        val totalValleys: Int,
        val commonDiff: Double,
        val totalSubFasciaLF: Double,
        val totalSqFt: Double,
        val sheetCount: Int
    )

    fun calculate(
        span: Double,
        bldgLen: Double,
        overhang: Double,
        pitch: Double,
        oc: Double,
        leftEndType: Int,
        rightEndType: Int,
        isTruss: Boolean,
        bothSides: Boolean
    ): RoofResult {
        val ridgeThick = 1.5
        val run = (span / 2.0) - (ridgeThick / 2.0)

        val diagFactor = sqrt(pitch.pow(2) + 144.0)
        val rafterToWall = (diagFactor / 12.0) * run
        val tailLen = (diagFactor / 12.0) * overhang
        val overallLen = rafterToWall + tailLen

        // Valley adds 1/2 span to the ridge, hip subtracts 1/2 span.
        var correctedRidgeLen = bldgLen
        if (leftEndType == 1) correctedRidgeLen -= (span / 2.0)
        if (leftEndType == 2) correctedRidgeLen += (span / 2.0)
        if (rightEndType == 1) correctedRidgeLen -= (span / 2.0)
        if (rightEndType == 2) correctedRidgeLen += (span / 2.0)
        if (correctedRidgeLen < 0) correctedRidgeLen = 0.0

        // "King Common" rule: always at least 1 common rafter at the junction even if the
        // ridge is fully consumed by hips.
        var effectiveCommonLen = bldgLen
        if (leftEndType == 1) effectiveCommonLen -= (span / 2.0)
        if (rightEndType == 1) effectiveCommonLen -= (span / 2.0)
        if (effectiveCommonLen < 0) effectiveCommonLen = 0.0
        var commonCount = (effectiveCommonLen / oc).toInt() + 1
        if (bothSides && !isTruss) commonCount *= 2

        val rise = run * (pitch / 12.0)
        val hipRun = run / cos(45.0 * (PI / 180.0))
        val hipPitch = rise / (hipRun / 12.0)
        val hipDiagFactor = sqrt(hipPitch.pow(2) + 144.0)
        val hipLen = (hipDiagFactor / 12.0) * hipRun

        var totalHips = 0
        if (leftEndType == 1) totalHips += 1
        if (rightEndType == 1) totalHips += 1
        if (bothSides) totalHips *= 2

        var totalValleys = 0
        if (leftEndType == 2) totalValleys += 1
        if (rightEndType == 2) totalValleys += 1
        if (bothSides) totalValleys *= 2

        val commonDiff = oc * (diagFactor / 12.0)

        val eavesLF = bldgLen * (if (bothSides || isTruss) 2.0 else 1.0)
        var rakesLF = 0.0
        if (leftEndType == 0) rakesLF += overallLen * (if (bothSides || isTruss) 2.0 else 1.0)
        if (rightEndType == 0) rakesLF += overallLen * (if (bothSides || isTruss) 2.0 else 1.0)
        val totalSubFasciaLF = eavesLF + rakesLF

        // Precision trapezoid: rectangle + triangle, accounting for a ridge shortened/lengthened
        // by hip/valley ends instead of assuming a simple rectangular roof plane.
        val rectLen = min(bldgLen, correctedRidgeLen)
        val triLen = abs(bldgLen - correctedRidgeLen)
        val areaPerSideSqIn = (overallLen * rectLen) + (overallLen * triLen / 2.0)
        var totalSqFt = areaPerSideSqIn / 144.0
        if (bothSides || isTruss) totalSqFt *= 2.0
        val sheetCount = ceil(totalSqFt / 32.0).toInt()

        return RoofResult(
            run, rise, rafterToWall, tailLen, overallLen, correctedRidgeLen, commonCount,
            hipRun, hipPitch, hipLen, totalHips, totalValleys, commonDiff,
            totalSubFasciaLF, totalSqFt, sheetCount
        )
    }
}

object RafterEngine {
    data class RafterResult(
        val run: Double, 
        val rise: Double, 
        val totRise: Double, 
        val rafterLen: Double, 
        val tailLen: Double, 
        val overall: Double, 
        val heel: Double,
        val pitch: Double,
        val hipRun: Double, 
        val hipPitch: Double, 
        val hipLen: Double, 
        val hipTailLen: Double,
        val hipOverall: Double,
        val hipRise: Double,
        val hipOARise: Double,
        val angleDeg: Double
    )

    fun calculate(
        span: Double, 
        pitchVal: Double, 
        ridge: Double = 1.5, 
        heel: Double = 4.0, 
        overhang: Double = 12.0,
        // Shed roof: [span] is the rafter's full run (one slope), so it isn't halved, and [ridge]
        // is the ledger/beam at the high end, taken off in full. Gable: run = span / 2 - ridge / 2.
        isShed: Boolean = false
    ): RafterResult {
        val run = if (isShed) span - ridge else (span / 2.0) - (ridge / 2.0)
        val rise = run * (pitchVal / 12.0)
        val diagFactor = sqrt(pitchVal.pow(2) + 144.0)
        val rafterLen = (diagFactor / 12.0) * run
        val tailLen = (diagFactor / 12.0) * overhang
        
        val hipRun = run / cos(45.0 * (PI / 180.0))
        val hipRise = rise // Rise is same for hip and common to reach same ridge
        val hipPitch = hipRise / (hipRun / 12.0)
        val hipDiagFactor = sqrt(hipPitch.pow(2) + 144.0)
        val hipLen = (hipDiagFactor / 12.0) * hipRun
        
        val hipTailRun = overhang / cos(45.0 * (PI / 180.0))
        val hipTailLen = (hipDiagFactor / 12.0) * hipTailRun
        
        val angleDeg = atan(pitchVal / 12.0) * (180.0 / PI)
        
        return RafterResult(
            run = run,
            rise = rise,
            totRise = rise + heel,
            rafterLen = rafterLen,
            tailLen = tailLen,
            overall = rafterLen + tailLen,
            heel = heel,
            pitch = pitchVal,
            hipRun = hipRun,
            hipPitch = hipPitch,
            hipLen = hipLen,
            hipTailLen = hipTailLen,
            hipOverall = hipLen + hipTailLen,
            hipRise = hipRise,
            hipOARise = hipRise + heel,
            angleDeg = angleDeg
        )
    }
}
