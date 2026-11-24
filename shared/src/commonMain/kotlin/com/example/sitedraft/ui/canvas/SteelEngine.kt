package com.example.sitedraft.ui.canvas

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Dan's Structural Engineering Suite steel engine (Structural-Calculator, js/steel_engine.js and
 * js/aisc_database.js, commit 330c630), translated to Kotlin line for line for Site Draft (Nov 22
 * 2026), the way ProCalc's roof engine was brought in: same formulas, same database, same checks.
 *
 * Brought over: the AISC shape table (W, HSS, Pipe, angles), the single-span beam solver (exact
 * piecewise shear, moment and deflection under uniform and point loads; AISC 360 Chapter F flexure
 * with Zx, Chapter G shear; L/360 live and L/240 total deflection; ASD or LRFD), the column
 * (Chapter E buckling, Design Guide 1 base plate bearing and bending) and the "lightest that
 * passes" searches. Left in the suite for now: cantilever and two-span beams, wind uplift and the
 * anchor-rod checks (no uplift on a basement beam or post).
 */
object SteelEngine {
    /** One AISC shape: weight plf; A in^2; d, bf, tf, tw in; I in^4; S, Z in^3; r in. Zx/Zy null where the table has none. */
    data class Shape(
        val name: String, val type: String, val weight: Double, val A: Double, val d: Double, val bf: Double, val tf: Double, val tw: Double,
        val Ix: Double, val Sx: Double, val Zx: Double?, val rx: Double, val Iy: Double, val Sy: Double, val Zy: Double?, val ry: Double
    )

    val AISC_DATABASE: List<Shape> = listOf(
        Shape("W4x13", "W", 13.0, 3.83, 4.16, 4.06, 0.345, 0.28, 11.3, 5.46, 6.28, 1.72, 3.86, 1.9, 2.96, 1.0),
        Shape("W5x16", "W", 16.0, 4.68, 5.01, 5.0, 0.36, 0.24, 21.4, 8.55, 9.71, 2.14, 7.51, 3.0, 4.67, 1.27),
        Shape("W5x19", "W", 19.0, 5.56, 5.15, 5.03, 0.43, 0.27, 26.2, 10.2, 11.7, 2.17, 9.13, 3.63, 5.69, 1.28),
        Shape("W6x9", "W", 9.0, 2.68, 5.9, 3.94, 0.215, 0.17, 16.4, 5.56, 6.23, 2.47, 2.19, 1.11, 1.76, 0.905),
        Shape("W6x12", "W", 12.0, 3.55, 6.03, 4.0, 0.28, 0.23, 22.1, 7.33, 8.3, 2.49, 2.99, 1.5, 2.38, 0.918),
        Shape("W6x15", "W", 15.0, 4.43, 5.99, 5.99, 0.26, 0.23, 29.1, 9.72, 10.8, 2.56, 9.32, 3.11, 4.79, 1.45),
        Shape("W6x16", "W", 16.0, 4.74, 6.28, 4.03, 0.405, 0.26, 32.1, 10.2, 11.7, 2.6, 4.43, 2.2, 3.47, 0.967),
        Shape("W6x20", "W", 20.0, 5.87, 6.2, 6.02, 0.365, 0.26, 41.4, 13.4, 14.9, 2.66, 13.3, 4.41, 6.78, 1.5),
        Shape("W6x25", "W", 25.0, 7.34, 6.38, 6.08, 0.455, 0.32, 53.4, 16.7, 18.9, 2.7, 17.1, 5.63, 8.67, 1.52),
        Shape("W8x10", "W", 10.0, 2.96, 7.89, 3.94, 0.205, 0.17, 30.8, 7.81, 8.87, 3.22, 2.09, 1.06, 1.69, 0.841),
        Shape("W8x13", "W", 13.0, 3.84, 7.99, 4.0, 0.255, 0.23, 39.6, 9.91, 11.4, 3.21, 2.73, 1.37, 2.18, 0.843),
        Shape("W8x15", "W", 15.0, 4.44, 8.11, 4.02, 0.315, 0.245, 48.0, 11.8, 13.6, 3.29, 3.41, 1.7, 2.71, 0.876),
        Shape("W8x18", "W", 18.0, 5.26, 8.14, 5.25, 0.33, 0.23, 61.9, 15.2, 17.0, 3.43, 7.97, 3.04, 4.71, 1.23),
        Shape("W8x21", "W", 21.0, 6.16, 8.28, 5.27, 0.4, 0.25, 75.3, 18.2, 20.4, 3.49, 9.77, 3.71, 5.76, 1.26),
        Shape("W8x24", "W", 24.0, 7.08, 7.93, 6.5, 0.4, 0.245, 82.7, 20.9, 23.2, 3.42, 18.3, 5.63, 8.63, 1.61),
        Shape("W8x28", "W", 28.0, 8.25, 8.06, 6.54, 0.465, 0.285, 98.0, 24.3, 27.2, 3.45, 21.7, 6.64, 10.2, 1.62),
        Shape("W8x31", "W", 31.0, 9.13, 8.0, 8.0, 0.435, 0.285, 110.0, 27.5, 30.4, 3.47, 37.1, 9.27, 14.1, 2.02),
        Shape("W8x35", "W", 35.0, 10.3, 8.12, 8.02, 0.495, 0.31, 127.0, 31.2, 34.7, 3.51, 42.6, 10.6, 16.3, 2.03),
        Shape("W8x40", "W", 40.0, 11.7, 8.25, 8.07, 0.56, 0.36, 146.0, 35.5, 39.8, 3.53, 49.1, 12.2, 18.7, 2.04),
        Shape("W8x48", "W", 48.0, 14.1, 8.5, 8.11, 0.685, 0.4, 184.0, 43.2, 49.0, 3.61, 60.9, 15.0, 23.2, 2.08),
        Shape("W8x58", "W", 58.0, 17.1, 8.75, 8.22, 0.81, 0.51, 228.0, 52.0, 60.1, 3.65, 75.1, 18.3, 28.5, 2.1),
        Shape("W8x67", "W", 67.0, 19.7, 9.0, 8.28, 0.935, 0.57, 272.0, 60.4, 70.9, 3.72, 88.6, 21.4, 33.5, 2.12),
        Shape("W10x12", "W", 12.0, 3.54, 9.87, 3.96, 0.21, 0.19, 53.8, 10.9, 12.6, 3.9, 2.18, 1.1, 1.75, 0.785),
        Shape("W10x15", "W", 15.0, 4.41, 9.99, 4.0, 0.27, 0.23, 68.9, 13.8, 16.0, 3.95, 2.89, 1.45, 2.3, 0.81),
        Shape("W10x17", "W", 17.0, 4.99, 10.1, 4.01, 0.33, 0.24, 81.9, 16.2, 18.7, 4.05, 3.56, 1.78, 2.82, 0.845),
        Shape("W10x19", "W", 19.0, 5.62, 10.2, 4.02, 0.395, 0.25, 96.3, 18.8, 21.6, 4.14, 4.29, 2.14, 3.38, 0.874),
        Shape("W10x22", "W", 22.0, 6.49, 10.2, 5.75, 0.36, 0.24, 118.0, 23.2, 26.0, 4.27, 11.4, 3.97, 6.21, 1.33),
        Shape("W10x26", "W", 26.0, 7.61, 10.3, 5.77, 0.44, 0.26, 144.0, 27.9, 31.3, 4.35, 14.1, 4.89, 7.62, 1.36),
        Shape("W10x30", "W", 30.0, 8.84, 10.5, 5.81, 0.51, 0.3, 170.0, 32.4, 36.6, 4.38, 16.7, 5.75, 8.98, 1.37),
        Shape("W10x33", "W", 33.0, 9.71, 9.73, 7.96, 0.435, 0.29, 171.0, 35.0, 38.8, 4.19, 36.6, 9.2, 14.0, 1.94),
        Shape("W10x39", "W", 39.0, 11.5, 9.92, 7.99, 0.53, 0.315, 209.0, 42.1, 46.8, 4.27, 45.0, 11.3, 17.2, 1.98),
        Shape("W10x45", "W", 45.0, 13.3, 10.1, 8.02, 0.62, 0.35, 248.0, 49.1, 54.9, 4.32, 53.4, 13.3, 20.4, 2.01),
        Shape("W10x49", "W", 49.0, 14.4, 9.98, 10.0, 0.56, 0.34, 272.0, 54.6, 60.4, 4.35, 93.4, 18.7, 28.3, 2.54),
        Shape("W10x54", "W", 54.0, 15.8, 10.1, 10.0, 0.615, 0.37, 303.0, 60.0, 66.6, 4.37, 103.0, 20.6, 31.2, 2.56),
        Shape("W10x60", "W", 60.0, 17.6, 10.2, 10.1, 0.68, 0.42, 341.0, 66.7, 74.6, 4.39, 116.0, 23.0, 35.0, 2.57),
        Shape("W10x68", "W", 68.0, 20.0, 10.4, 10.1, 0.77, 0.47, 394.0, 75.7, 85.3, 4.44, 134.0, 26.4, 40.3, 2.59),
        Shape("W10x77", "W", 77.0, 22.7, 10.6, 10.2, 0.87, 0.53, 455.0, 85.9, 97.6, 4.49, 154.0, 30.1, 46.3, 2.6),
        Shape("W10x88", "W", 88.0, 25.9, 10.8, 10.3, 0.99, 0.605, 534.0, 98.5, 113.0, 4.54, 179.0, 34.8, 53.8, 2.63),
        Shape("W10x100", "W", 100.0, 29.4, 11.1, 10.4, 1.12, 0.68, 623.0, 112.0, 130.0, 4.6, 207.0, 39.9, 62.2, 2.65),
        Shape("W10x112", "W", 112.0, 32.9, 11.4, 10.4, 1.25, 0.755, 716.0, 126.0, 147.0, 4.66, 236.0, 45.3, 71.1, 2.68),
        Shape("W12x14", "W", 14.0, 4.16, 11.9, 3.97, 0.225, 0.2, 88.6, 14.9, 17.4, 4.62, 2.36, 1.19, 1.9, 0.753),
        Shape("W12x16", "W", 16.0, 4.71, 12.0, 3.99, 0.265, 0.22, 103.0, 17.1, 20.1, 4.67, 2.82, 1.41, 2.26, 0.773),
        Shape("W12x19", "W", 19.0, 5.57, 12.2, 4.01, 0.35, 0.235, 130.0, 21.3, 24.7, 4.82, 3.76, 1.88, 2.99, 0.822),
        Shape("W12x22", "W", 22.0, 6.48, 12.3, 4.03, 0.425, 0.26, 156.0, 25.4, 29.3, 4.91, 4.66, 2.31, 3.66, 0.848),
        Shape("W12x26", "W", 26.0, 7.65, 12.2, 6.49, 0.38, 0.23, 204.0, 33.4, 37.2, 5.17, 17.3, 5.34, 8.3, 1.51),
        Shape("W12x30", "W", 30.0, 8.79, 12.3, 6.52, 0.44, 0.26, 238.0, 38.6, 43.1, 5.21, 20.3, 6.24, 9.66, 1.52),
        Shape("W12x35", "W", 35.0, 10.3, 12.5, 6.56, 0.52, 0.3, 285.0, 45.6, 51.2, 5.25, 24.5, 7.47, 11.6, 1.54),
        Shape("W12x40", "W", 40.0, 11.8, 11.9, 8.01, 0.515, 0.295, 307.0, 51.5, 57.5, 5.13, 44.1, 11.0, 16.8, 1.94),
        Shape("W12x45", "W", 45.0, 13.2, 12.1, 8.05, 0.575, 0.335, 350.0, 58.1, 64.7, 5.15, 50.0, 12.4, 19.0, 1.95),
        Shape("W12x50", "W", 50.0, 14.7, 12.2, 8.08, 0.64, 0.37, 394.0, 64.7, 72.4, 5.18, 56.3, 13.9, 21.4, 1.96),
        Shape("W12x53", "W", 53.0, 15.6, 12.1, 10.0, 0.575, 0.345, 425.0, 70.6, 77.9, 5.23, 95.8, 19.2, 29.1, 2.48),
        Shape("W12x58", "W", 58.0, 17.0, 12.2, 10.0, 0.64, 0.36, 475.0, 78.0, 86.4, 5.28, 107.0, 21.4, 32.5, 2.51),
        Shape("W12x65", "W", 65.0, 19.1, 12.1, 12.0, 0.605, 0.39, 533.0, 87.9, 96.8, 5.28, 174.0, 29.1, 44.1, 3.02),
        Shape("W12x72", "W", 72.0, 21.1, 12.25, 12.04, 0.67, 0.43, 597.0, 97.4, 108.0, 5.31, 195.0, 32.4, 49.2, 3.04),
        Shape("W12x79", "W", 79.0, 23.2, 12.4, 12.1, 0.735, 0.47, 662.0, 107.0, 119.0, 5.34, 216.0, 35.8, 54.5, 3.05),
        Shape("W12x87", "W", 87.0, 25.6, 12.5, 12.1, 0.81, 0.515, 740.0, 118.0, 133.0, 5.38, 241.0, 39.7, 60.8, 3.07),
        Shape("W12x96", "W", 96.0, 28.2, 12.7, 12.2, 0.9, 0.55, 833.0, 131.0, 149.0, 5.44, 270.0, 44.4, 68.2, 3.09),
        Shape("W12x106", "W", 106.0, 31.2, 12.9, 12.2, 0.99, 0.61, 933.0, 145.0, 166.0, 5.47, 301.0, 49.3, 76.1, 3.11),
        Shape("W12x120", "W", 120.0, 35.3, 13.1, 12.3, 1.105, 0.71, 1070.0, 163.0, 188.0, 5.51, 345.0, 56.0, 87.0, 3.13),
        Shape("W12x136", "W", 136.0, 39.9, 13.4, 12.4, 1.25, 0.79, 1240.0, 186.0, 216.0, 5.58, 398.0, 64.2, 101.0, 3.16),
        Shape("W12x152", "W", 152.0, 44.7, 13.7, 12.5, 1.4, 0.87, 1430.0, 209.0, 245.0, 5.65, 454.0, 72.7, 115.0, 3.19),
        Shape("W12x170", "W", 170.0, 50.0, 14.0, 12.6, 1.56, 0.96, 1650.0, 235.0, 279.0, 5.74, 517.0, 82.2, 131.0, 3.22),
        Shape("W12x190", "W", 190.0, 55.8, 14.4, 12.7, 1.735, 1.06, 1890.0, 263.0, 317.0, 5.82, 589.0, 92.9, 149.0, 3.25),
        Shape("W14x22", "W", 22.0, 6.49, 13.7, 5.0, 0.335, 0.23, 199.0, 29.0, 33.2, 5.54, 7.0, 2.8, 4.4, 1.04),
        Shape("W14x26", "W", 26.0, 7.68, 13.9, 5.03, 0.42, 0.255, 245.0, 35.3, 40.2, 5.65, 8.91, 3.54, 5.54, 1.08),
        Shape("W14x30", "W", 30.0, 8.85, 13.8, 6.73, 0.385, 0.27, 291.0, 42.0, 47.3, 5.73, 19.6, 5.82, 9.03, 1.49),
        Shape("W14x34", "W", 34.0, 10.0, 14.0, 6.75, 0.455, 0.285, 340.0, 48.6, 54.6, 5.83, 23.3, 6.91, 10.7, 1.53),
        Shape("W14x38", "W", 38.0, 11.2, 14.1, 6.77, 0.515, 0.31, 385.0, 54.6, 61.2, 5.87, 26.7, 7.88, 12.2, 1.55),
        Shape("W14x43", "W", 43.0, 12.6, 13.7, 8.0, 0.53, 0.305, 428.0, 62.6, 69.6, 5.82, 45.2, 11.3, 17.3, 1.89),
        Shape("W14x48", "W", 48.0, 14.1, 13.8, 8.03, 0.595, 0.34, 484.0, 70.2, 78.4, 5.85, 51.4, 12.8, 19.6, 1.91),
        Shape("W14x53", "W", 53.0, 15.6, 13.9, 8.06, 0.66, 0.37, 541.0, 77.8, 87.1, 5.89, 57.7, 14.3, 22.0, 1.92),
        Shape("W14x61", "W", 61.0, 17.9, 13.9, 10.0, 0.645, 0.375, 640.0, 92.1, 102.0, 5.98, 107.0, 21.5, 32.8, 2.45),
        Shape("W14x68", "W", 68.0, 20.0, 14.0, 10.0, 0.72, 0.415, 722.0, 103.0, 115.0, 6.01, 121.0, 24.1, 36.9, 2.46),
        Shape("W14x74", "W", 74.0, 21.8, 14.2, 10.1, 0.785, 0.45, 795.0, 112.0, 126.0, 6.04, 134.0, 26.6, 40.7, 2.48),
        Shape("W14x82", "W", 82.0, 24.0, 14.3, 10.1, 0.855, 0.51, 881.0, 123.0, 139.0, 6.05, 148.0, 29.3, 45.0, 2.48),
        Shape("W14x90", "W", 90.0, 26.5, 14.0, 14.5, 0.71, 0.44, 999.0, 143.0, 157.0, 6.14, 362.0, 49.9, 75.6, 3.7),
        Shape("W14x99", "W", 99.0, 29.1, 14.2, 14.6, 0.78, 0.485, 1110.0, 157.0, 173.0, 6.17, 402.0, 55.2, 83.6, 3.71),
        Shape("W14x109", "W", 109.0, 32.0, 14.3, 14.6, 0.86, 0.525, 1240.0, 173.0, 192.0, 6.22, 447.0, 61.2, 92.7, 3.73),
        Shape("W14x120", "W", 120.0, 35.3, 14.5, 14.7, 0.94, 0.59, 1380.0, 190.0, 212.0, 6.24, 495.0, 67.5, 103.0, 3.74),
        Shape("W14x132", "W", 132.0, 38.8, 14.7, 14.7, 1.03, 0.645, 1530.0, 209.0, 234.0, 6.28, 548.0, 74.5, 113.0, 3.76),
        Shape("W16x26", "W", 26.0, 7.68, 15.7, 5.5, 0.345, 0.25, 301.0, 38.4, 44.2, 6.26, 9.59, 3.49, 5.52, 1.12),
        Shape("W16x31", "W", 31.0, 9.12, 15.9, 5.53, 0.44, 0.275, 375.0, 47.2, 54.0, 6.41, 12.4, 4.49, 7.08, 1.17),
        Shape("W16x36", "W", 36.0, 10.6, 15.9, 6.99, 0.43, 0.295, 448.0, 56.5, 64.0, 6.51, 24.5, 7.0, 11.0, 1.52),
        Shape("W16x40", "W", 40.0, 11.8, 16.0, 7.0, 0.505, 0.305, 518.0, 64.7, 73.0, 6.63, 28.9, 8.25, 12.9, 1.57),
        Shape("W16x45", "W", 45.0, 13.3, 16.1, 7.04, 0.565, 0.345, 586.0, 72.7, 82.3, 6.65, 32.8, 9.34, 14.6, 1.57),
        Shape("W16x50", "W", 50.0, 14.7, 16.3, 7.07, 0.63, 0.38, 659.0, 81.0, 92.0, 6.68, 37.2, 10.5, 16.6, 1.59),
        Shape("W16x57", "W", 57.0, 16.8, 16.4, 7.12, 0.715, 0.43, 758.0, 92.2, 105.0, 6.72, 43.1, 12.1, 19.1, 1.6),
        Shape("W18x35", "W", 35.0, 10.3, 17.7, 6.0, 0.425, 0.3, 510.0, 57.6, 66.5, 7.04, 15.3, 5.12, 8.06, 1.22),
        Shape("W18x40", "W", 40.0, 11.8, 17.9, 6.02, 0.525, 0.315, 612.0, 68.4, 78.4, 7.21, 19.1, 6.35, 9.98, 1.27),
        Shape("W18x46", "W", 46.0, 13.5, 18.1, 6.06, 0.605, 0.36, 712.0, 78.8, 90.7, 7.26, 22.5, 7.43, 11.7, 1.29),
        Shape("W18x50", "W", 50.0, 14.7, 18.0, 7.5, 0.57, 0.355, 800.0, 88.9, 101.0, 7.38, 40.1, 10.7, 16.6, 1.65),
        Shape("W18x55", "W", 55.0, 16.2, 18.1, 7.53, 0.63, 0.39, 890.0, 98.3, 112.0, 7.41, 44.9, 11.9, 18.5, 1.66),
        Shape("W18x60", "W", 60.0, 17.6, 18.2, 7.56, 0.695, 0.415, 984.0, 108.0, 123.0, 7.47, 50.1, 13.3, 20.6, 1.68),
        Shape("W18x65", "W", 65.0, 19.1, 18.4, 7.59, 0.75, 0.45, 1070.0, 117.0, 133.0, 7.49, 54.8, 14.4, 22.5, 1.69),
        Shape("W18x71", "W", 71.0, 20.8, 18.5, 7.64, 0.81, 0.495, 1170.0, 127.0, 146.0, 7.5, 60.3, 15.8, 24.7, 1.7),
        Shape("W18x76", "W", 76.0, 22.3, 18.2, 11.0, 0.68, 0.425, 1330.0, 146.0, 163.0, 7.73, 152.0, 27.6, 42.2, 2.61),
        Shape("W18x86", "W", 86.0, 25.3, 18.4, 11.1, 0.77, 0.48, 1530.0, 166.0, 186.0, 7.77, 175.0, 31.6, 48.4, 2.63),
        Shape("W18x97", "W", 97.0, 28.5, 18.6, 11.1, 0.87, 0.535, 1750.0, 188.0, 211.0, 7.82, 201.0, 36.0, 55.3, 2.65),
        Shape("W21x44", "W", 44.0, 13.0, 20.7, 6.5, 0.45, 0.35, 843.0, 81.6, 95.4, 8.06, 20.7, 6.37, 10.0, 1.26),
        Shape("W21x48", "W", 48.0, 14.1, 20.6, 8.14, 0.43, 0.34, 959.0, 93.0, 107.0, 8.24, 38.7, 9.51, 14.9, 1.66),
        Shape("W21x50", "W", 50.0, 14.7, 20.8, 6.53, 0.535, 0.38, 984.0, 94.5, 110.0, 8.18, 24.9, 7.64, 12.0, 1.3),
        Shape("W21x55", "W", 55.0, 16.2, 20.8, 8.22, 0.522, 0.375, 1140.0, 110.0, 126.0, 8.4, 48.4, 11.8, 18.4, 1.73),
        Shape("W21x62", "W", 62.0, 18.3, 21.0, 8.24, 0.615, 0.4, 1330.0, 127.0, 144.0, 8.54, 57.5, 14.0, 21.8, 1.77),
        Shape("W21x68", "W", 68.0, 20.0, 21.1, 8.27, 0.685, 0.43, 1480.0, 140.0, 160.0, 8.6, 64.7, 15.7, 24.4, 1.8),
        Shape("W21x73", "W", 73.0, 21.5, 21.2, 8.3, 0.74, 0.455, 1600.0, 151.0, 172.0, 8.64, 70.6, 17.0, 26.6, 1.81),
        Shape("W21x83", "W", 83.0, 24.3, 21.4, 8.36, 0.835, 0.515, 1830.0, 171.0, 196.0, 8.67, 81.4, 19.5, 30.5, 1.83),
        Shape("W21x93", "W", 93.0, 27.3, 21.6, 8.42, 0.93, 0.58, 2070.0, 192.0, 221.0, 8.7, 92.9, 22.1, 34.7, 1.84),
        Shape("W24x55", "W", 55.0, 16.2, 23.6, 7.01, 0.505, 0.395, 1350.0, 114.0, 134.0, 9.14, 29.1, 8.3, 13.1, 1.34),
        Shape("W24x62", "W", 62.0, 18.2, 23.7, 7.04, 0.59, 0.43, 1550.0, 131.0, 153.0, 9.23, 34.5, 9.8, 15.4, 1.38),
        Shape("W24x68", "W", 68.0, 20.1, 23.7, 8.97, 0.585, 0.415, 1830.0, 154.0, 177.0, 9.55, 70.4, 15.7, 24.5, 1.87),
        Shape("W24x76", "W", 76.0, 22.4, 23.9, 8.99, 0.68, 0.44, 2100.0, 176.0, 200.0, 9.69, 82.5, 18.4, 28.6, 1.92),
        Shape("W24x84", "W", 84.0, 24.7, 24.1, 9.02, 0.77, 0.47, 2370.0, 196.0, 224.0, 9.79, 94.4, 20.9, 32.6, 1.95),
        Shape("W24x94", "W", 94.0, 27.7, 24.3, 9.07, 0.875, 0.515, 2700.0, 222.0, 254.0, 9.87, 109.0, 24.0, 37.6, 1.98),
        Shape("W24x104", "W", 104.0, 30.6, 24.1, 12.8, 0.75, 0.5, 3100.0, 258.0, 289.0, 10.1, 259.0, 40.7, 62.8, 2.91),
        Shape("W24x117", "W", 117.0, 34.4, 24.3, 12.8, 0.85, 0.55, 3540.0, 291.0, 327.0, 10.1, 298.0, 46.6, 71.9, 2.94),
        Shape("W24x131", "W", 131.0, 38.5, 24.5, 12.9, 0.96, 0.605, 4020.0, 329.0, 370.0, 10.2, 340.0, 52.9, 81.9, 2.97),
        Shape("W27x84", "W", 84.0, 24.8, 26.7, 10.0, 0.64, 0.46, 2850.0, 213.0, 244.0, 10.7, 106.0, 21.2, 33.2, 2.07),
        Shape("W27x94", "W", 94.0, 27.7, 26.9, 10.0, 0.745, 0.49, 3270.0, 243.0, 278.0, 10.9, 124.0, 24.8, 38.8, 2.12),
        Shape("W27x102", "W", 102.0, 30.0, 27.1, 10.0, 0.83, 0.515, 3620.0, 267.0, 305.0, 11.0, 139.0, 27.7, 43.3, 2.15),
        Shape("W27x114", "W", 114.0, 33.5, 27.3, 10.1, 0.93, 0.57, 4090.0, 299.0, 343.0, 11.1, 159.0, 31.6, 49.4, 2.18),
        Shape("W30x90", "W", 90.0, 26.4, 29.5, 10.4, 0.61, 0.47, 3610.0, 245.0, 283.0, 11.7, 115.0, 22.1, 35.0, 2.08),
        Shape("W30x99", "W", 99.0, 29.1, 29.7, 10.5, 0.67, 0.52, 3990.0, 269.0, 312.0, 11.7, 128.0, 24.5, 38.8, 2.1),
        Shape("W30x108", "W", 108.0, 31.7, 29.8, 10.5, 0.76, 0.545, 4470.0, 299.0, 346.0, 11.9, 146.0, 27.9, 44.1, 2.15),
        Shape("W30x116", "W", 116.0, 34.2, 30.0, 10.5, 0.85, 0.565, 4930.0, 329.0, 378.0, 12.0, 164.0, 31.3, 49.4, 2.19),
        Shape("W30x124", "W", 124.0, 36.5, 30.2, 10.5, 0.93, 0.585, 5360.0, 355.0, 408.0, 12.1, 180.0, 34.3, 54.2, 2.22),
        Shape("W33x118", "W", 118.0, 34.7, 32.9, 11.5, 0.74, 0.55, 5900.0, 359.0, 415.0, 13.0, 187.0, 32.6, 51.5, 2.32),
        Shape("W33x130", "W", 130.0, 38.3, 33.1, 11.5, 0.855, 0.58, 6710.0, 406.0, 467.0, 13.2, 218.0, 37.8, 59.7, 2.39),
        Shape("W33x141", "W", 141.0, 41.6, 33.3, 11.5, 0.96, 0.605, 7450.0, 448.0, 514.0, 13.4, 245.0, 42.6, 67.1, 2.43),
        Shape("W36x135", "W", 135.0, 39.7, 35.6, 12.0, 0.79, 0.6, 7800.0, 439.0, 509.0, 14.0, 225.0, 37.7, 59.7, 2.38),
        Shape("W36x150", "W", 150.0, 44.2, 35.9, 12.0, 0.94, 0.625, 9040.0, 504.0, 581.0, 14.3, 270.0, 45.1, 71.2, 2.47),
        Shape("W36x160", "W", 160.0, 47.0, 36.0, 12.0, 1.02, 0.65, 9750.0, 542.0, 624.0, 14.4, 295.0, 49.1, 77.6, 2.5),
        Shape("W36x182", "W", 182.0, 53.6, 36.3, 12.1, 1.18, 0.725, 11300.0, 623.0, 718.0, 14.5, 346.0, 57.4, 90.9, 2.54),
        Shape("HSS3x3x3/16", "HSS", 6.84, 1.89, 3.0, 3.0, 0.174, 0.174, 2.46, 1.64, 1.95, 1.14, 2.46, 1.64, 1.95, 1.14),
        Shape("HSS3.5x3.5x1/4", "HSS", 10.4, 2.87, 3.5, 3.5, 0.233, 0.233, 4.97, 2.84, 3.42, 1.32, 4.97, 2.84, 3.42, 1.32),
        Shape("HSS4x4x1/4", "HSS", 12.2, 3.37, 4.0, 4.0, 0.233, 0.233, 7.8, 3.9, 4.67, 1.52, 7.8, 3.9, 4.67, 1.52),
        Shape("HSS4x4x3/8", "HSS", 17.3, 4.78, 4.0, 4.0, 0.349, 0.349, 10.3, 5.15, 6.36, 1.47, 10.3, 5.15, 6.36, 1.47),
        Shape("HSS5x5x1/4", "HSS", 15.6, 4.3, 5.0, 5.0, 0.233, 0.233, 15.9, 6.36, 7.55, 1.92, 15.9, 6.36, 7.55, 1.92),
        Shape("HSS5x5x3/8", "HSS", 22.4, 6.18, 5.0, 5.0, 0.349, 0.349, 21.6, 8.65, 10.6, 1.87, 21.6, 8.65, 10.6, 1.87),
        Shape("HSS6x6x1/4", "HSS", 19.0, 5.24, 6.0, 6.0, 0.233, 0.233, 28.6, 9.54, 11.2, 2.34, 28.6, 9.54, 11.2, 2.34),
        Shape("HSS6x6x3/8", "HSS", 27.5, 7.58, 6.0, 6.0, 0.349, 0.349, 39.0, 13.0, 15.7, 2.27, 39.0, 13.0, 15.7, 2.27),
        Shape("HSS6x6x1/2", "HSS", 35.2, 9.74, 6.0, 6.0, 0.465, 0.465, 47.3, 15.8, 19.6, 2.2, 47.3, 15.8, 19.6, 2.2),
        Shape("HSS7x7x3/8", "HSS", 32.6, 8.98, 7.0, 7.0, 0.349, 0.349, 63.2, 18.1, null, 2.65, 63.2, 18.1, null, 2.65),
        Shape("HSS8x8x1/4", "HSS", 25.8, 7.1, 8.0, 8.0, 0.233, 0.233, 70.7, 17.7, 20.6, 3.16, 70.7, 17.7, 20.6, 3.16),
        Shape("HSS8x8x3/8", "HSS", 37.7, 10.4, 8.0, 8.0, 0.349, 0.349, 98.7, 24.7, 29.4, 3.08, 98.7, 24.7, 29.4, 3.08),
        Shape("HSS8x8x1/2", "HSS", 48.9, 13.5, 8.0, 8.0, 0.465, 0.465, 122.0, 30.6, 37.3, 3.01, 122.0, 30.6, 37.3, 3.01),
        Shape("HSS10x10x3/8", "HSS", 47.9, 13.3, 10.0, 10.0, 0.349, 0.349, 199.0, 39.9, 47.2, 3.87, 199.0, 39.9, 47.2, 3.87),
        Shape("HSS10x10x1/2", "HSS", 62.5, 17.3, 10.0, 10.0, 0.465, 0.465, 251.0, 50.1, 60.5, 3.81, 251.0, 50.1, 60.5, 3.81),
        Shape("HSS12x12x1/2", "HSS", 76.1, 21.0, 12.0, 12.0, 0.465, 0.465, 442.0, 73.7, 88.5, 4.59, 442.0, 73.7, 88.5, 4.59),
        Shape("HSS6x4x1/4", "HSS", 15.6, 4.3, 6.0, 4.0, 0.233, 0.233, 20.6, 6.87, 8.35, 2.19, 11.2, 5.6, 6.55, 1.61),
        Shape("HSS6x4x3/8", "HSS", 22.4, 6.18, 6.0, 4.0, 0.349, 0.349, 27.5, 9.17, 11.5, 2.11, 14.6, 7.3, 8.9, 1.54),
        Shape("HSS8x4x1/4", "HSS", 19.0, 5.24, 8.0, 4.0, 0.233, 0.233, 43.1, 10.8, 13.5, 2.87, 14.3, 7.15, 8.27, 1.65),
        Shape("HSS8x6x3/8", "HSS", 32.6, 8.98, 8.0, 6.0, 0.349, 0.349, 77.2, 19.3, 23.9, 2.93, 49.3, 16.4, 19.8, 2.34),
        Shape("HSS10x6x3/8", "HSS", 37.7, 10.4, 10.0, 6.0, 0.349, 0.349, 133.0, 26.6, 33.3, 3.58, 59.3, 19.8, 23.6, 2.39),
        Shape("HSS12x8x1/2", "HSS", 62.5, 17.3, 12.0, 8.0, 0.465, 0.465, 334.0, 55.7, 70.1, 4.39, 176.0, 44.0, 52.8, 3.19),
        Shape("Pipe 2 Std", "Pipe", 3.65, 1.07, 2.38, 2.38, 0.154, 0.154, 0.66, 0.56, 0.77, 0.79, 0.66, 0.56, 0.77, 0.79),
        Shape("Pipe 3 Std", "Pipe", 7.58, 2.23, 3.5, 3.5, 0.216, 0.216, 3.02, 1.72, 2.34, 1.16, 3.02, 1.72, 2.34, 1.16),
        Shape("Pipe 4 Std", "Pipe", 10.8, 3.17, 4.5, 4.5, 0.237, 0.237, 7.23, 3.21, 4.31, 1.51, 7.23, 3.21, 4.31, 1.51),
        Shape("Pipe 6 Std", "Pipe", 19.0, 5.58, 6.63, 6.63, 0.28, 0.28, 28.1, 8.49, 11.3, 2.25, 28.1, 8.49, 11.3, 2.25),
        Shape("Pipe 8 Std", "Pipe", 28.6, 8.4, 8.63, 8.63, 0.322, 0.322, 72.5, 16.8, 22.3, 2.94, 72.5, 16.8, 22.3, 2.94),
        Shape("L3x3x1/4", "L", 4.9, 1.44, 3.0, 3.0, 0.25, 0.25, 1.23, 0.57, 1.05, 0.93, 1.23, 0.57, 1.05, 0.93),
        Shape("L3.5x3.5x1/4", "L", 5.8, 1.69, 3.5, 3.5, 0.25, 0.25, 2.0, 0.78, 1.44, 1.09, 2.0, 0.78, 1.44, 1.09),
        Shape("L4x4x1/4", "L", 6.6, 1.94, 4.0, 4.0, 0.25, 0.25, 3.0, 1.05, 1.9, 1.24, 3.0, 1.05, 1.9, 1.24),
        Shape("L4x4x3/8", "L", 9.8, 2.86, 4.0, 4.0, 0.375, 0.375, 4.29, 1.52, 2.76, 1.23, 4.29, 1.52, 2.76, 1.23),
        Shape("L5x3x1/4", "L", 6.6, 1.94, 5.0, 3.0, 0.25, 0.25, 5.05, 1.43, 2.5, 1.61, 1.45, 0.63, 1.1, 0.86),
        Shape("L6x4x3/8", "L", 12.3, 3.61, 6.0, 4.0, 0.375, 0.375, 13.1, 3.07, 5.38, 1.9, 4.67, 1.48, 2.56, 1.14),
        Shape("L6x6x1/2", "L", 19.6, 5.75, 6.0, 6.0, 0.5, 0.5, 19.9, 4.67, 8.35, 1.86, 19.9, 4.67, 8.35, 1.86),
    )

    fun shape(name: String): Shape? = AISC_DATABASE.firstOrNull { it.name == name }

    data class PointLoad(val P_dl: Double, val P_ll: Double, val pos_ft: Double)

    data class BeamResult(
        val method: String, val sectionName: String, val L_ft: Double,
        val w_dl_plf: Double, val w_ll_plf: Double,
        val R1_service_kips: Double, val R2_service_kips: Double,
        val M_max_kipft: Double, val M_factored_kipft: Double,
        val V_demand_kips: Double, val V_allowable_kips: Double, val shearRatio: Double, val passShear: Boolean,
        val bendingStress_ksi: Double, val allowableStress_ksi: Double, val stressRatio: Double,
        val delta_live_in: Double, val delta_total_in: Double, val L360_in: Double, val L240_in: Double,
        val passStress: Boolean, val passLiveDeflect: Boolean, val passTotalDeflect: Boolean, val isPass: Boolean
    )

    /**
     * analyzeSteelBeam, single span. Loads by tributary width ([tribLeft_ft] + [tribRight_ft] at
     * [dl_psf] / [ll_psf]) or direct ([w_dl_plf] / [w_ll_plf] when [directLoads]); the shape's own
     * weight is added to the dead load when [includeSelfWeight].
     */
    fun analyzeSteelBeam(
        section: Shape, L_ft: Double, Fy: Double = 50.0, method: String = "ASD",
        tribLeft_ft: Double = 0.0, tribRight_ft: Double = 0.0, dl_psf: Double = 0.0, ll_psf: Double = 0.0,
        directLoads: Boolean = false, w_dl_plf_in: Double = 0.0, w_ll_plf_in: Double = 0.0,
        includeSelfWeight: Boolean = true, pointLoadsIn: List<PointLoad> = emptyList(),
        deflectLimitLive: Double = 360.0, deflectLimitTotal: Double = 240.0
    ): BeamResult {
        val L1_ft = if (L_ft > 0) L_ft else 20.0
        val L1_in = L1_ft * 12
        val selfWeight_plf = if (includeSelfWeight) section.weight else 0.0
        val w_dl_plf: Double
        val w_ll_plf: Double
        if (directLoads) {
            w_dl_plf = w_dl_plf_in + selfWeight_plf
            w_ll_plf = w_ll_plf_in
        } else {
            w_dl_plf = (dl_psf * tribLeft_ft) + (dl_psf * tribRight_ft) + selfWeight_plf
            w_ll_plf = (ll_psf * tribLeft_ft) + (ll_psf * tribRight_ft)
        }
        val w_service_plf = w_dl_plf + w_ll_plf
        val w_service_kft = w_service_plf / 1000
        val w_factored_plf = (1.2 * w_dl_plf) + (1.6 * w_ll_plf)
        val w_factored_kft = w_factored_plf / 1000
        val pointLoads = pointLoadsIn.ifEmpty { listOf(PointLoad(0.0, 0.0, L1_ft / 2)) }

        var R1_dl_kips = 0.0; var R1_ll_kips = 0.0
        var R2_dl_kips = 0.0; var R2_ll_kips = 0.0
        val E = 29000.0

        // 1. Reactions (single span)
        R1_dl_kips += (w_dl_plf * L1_ft / 1000) / 2
        R2_dl_kips += (w_dl_plf * L1_ft / 1000) / 2
        R1_ll_kips += (w_ll_plf * L1_ft / 1000) / 2
        R2_ll_kips += (w_ll_plf * L1_ft / 1000) / 2
        for (pt in pointLoads) {
            val a = min(max(pt.pos_ft, 0.0), L1_ft)
            val b = L1_ft - a
            R1_dl_kips += pt.P_dl * (b / L1_ft)
            R2_dl_kips += pt.P_dl * (a / L1_ft)
            R1_ll_kips += pt.P_ll * (b / L1_ft)
            R2_ll_kips += pt.P_ll * (a / L1_ft)
        }
        val R1_service_kips = R1_dl_kips + R1_ll_kips
        val R2_service_kips = R2_dl_kips + R2_ll_kips
        val R1_factored_kips = (1.2 * R1_dl_kips) + (1.6 * R1_ll_kips)

        // 2. Exact piecewise V(x), M(x), delta(x)
        val numSteps = 400
        var max_M_service_kipft = 0.0
        var max_M_factored_kipft = 0.0
        var max_V_service_kips = 0.0
        var max_V_factored_kips = 0.0
        var max_delta_live_in = 0.0
        var max_delta_total_in = 0.0
        val w_ll_kin = (w_ll_plf / 1000) / 12
        val w_total_kin = (w_service_plf / 1000) / 12
        for (i in 0..numSteps) {
            val x_ft = (i.toDouble() / numSteps) * L1_ft
            val x_in = x_ft * 12
            var Vx_serv = R1_service_kips - (w_service_kft * x_ft)
            var Mx_serv = (R1_service_kips * x_ft) - (0.5 * w_service_kft * x_ft.pow(2))
            var Vx_fact = R1_factored_kips - (w_factored_kft * x_ft)
            var Mx_fact = (R1_factored_kips * x_ft) - (0.5 * w_factored_kft * x_ft.pow(2))
            for (pt in pointLoads) {
                val a_ft = min(max(pt.pos_ft, 0.0), L1_ft)
                val P_serv = pt.P_dl + pt.P_ll
                val P_fact = (1.2 * pt.P_dl) + (1.6 * pt.P_ll)
                if (x_ft >= a_ft) {
                    Vx_serv -= P_serv; Mx_serv -= P_serv * (x_ft - a_ft)
                    Vx_fact -= P_fact; Mx_fact -= P_fact * (x_ft - a_ft)
                }
            }
            max_M_service_kipft = max(max_M_service_kipft, abs(Mx_serv))
            max_M_factored_kipft = max(max_M_factored_kipft, abs(Mx_fact))
            max_V_service_kips = max(max_V_service_kips, abs(Vx_serv))
            max_V_factored_kips = max(max_V_factored_kips, abs(Vx_fact))
            var dx_live = 0.0
            var dx_total = 0.0
            if (L1_in > 0 && section.Ix > 0) {
                val unif = (x_in * (L1_in - x_in) * (L1_in.pow(2) + (x_in * (L1_in - x_in)))) / (24 * E * section.Ix)
                dx_live += w_ll_kin * unif
                dx_total += w_total_kin * unif
                for (pt in pointLoads) {
                    val a_in = min(max(pt.pos_ft, 0.0), L1_ft) * 12
                    val b_in = L1_in - a_in
                    val f = if (x_in <= a_in) (b_in * x_in * (L1_in.pow(2) - b_in.pow(2) - x_in.pow(2))) / (6 * E * section.Ix * L1_in)
                    else (a_in * (L1_in - x_in) * (2 * L1_in * x_in - x_in.pow(2) - a_in.pow(2))) / (6 * E * section.Ix * L1_in)
                    dx_live += pt.P_ll * f
                    dx_total += (pt.P_dl + pt.P_ll) * f
                }
            }
            max_delta_live_in = max(max_delta_live_in, abs(dx_live))
            max_delta_total_in = max(max_delta_total_in, abs(dx_total))
        }

        // 3. AISC 360-16 checks
        val Zx = section.Zx ?: (1.12 * section.Sx)
        val Sx = section.Sx
        val Mn_kipft = min(Fy * Zx, 1.6 * Fy * Sx) / 12
        val M_design_kipft: Double
        val M_capacity_kipft: Double
        val allowableStress_ksi: Double
        val bendingStress_ksi: Double
        if (method == "ASD") {
            M_design_kipft = max_M_service_kipft
            M_capacity_kipft = Mn_kipft / 1.67
            allowableStress_ksi = (M_capacity_kipft * 12) / Sx
            bendingStress_ksi = (M_design_kipft * 12) / Sx
        } else {
            M_design_kipft = max_M_factored_kipft
            M_capacity_kipft = 0.90 * Mn_kipft
            allowableStress_ksi = M_capacity_kipft
            bendingStress_ksi = M_design_kipft
        }
        val stressRatio = M_design_kipft / M_capacity_kipft
        val Aw = section.d * section.tw
        val Vn = 0.6 * Fy * Aw * 1.0
        val V_allowable = if (method == "ASD") Vn / 1.67 else Vn
        val V_demand = if (method == "ASD") max_V_service_kips else max_V_factored_kips
        val shearRatio = V_demand / V_allowable
        val L360 = L1_in / deflectLimitLive
        val L240 = L1_in / deflectLimitTotal
        val passLive = max_delta_live_in <= L360
        val passTotal = max_delta_total_in <= L240
        val passStress = stressRatio <= 1.0
        val passShear = shearRatio <= 1.0
        return BeamResult(
            method, section.name, L1_ft, w_dl_plf, w_ll_plf, R1_service_kips, R2_service_kips,
            max_M_service_kipft, max_M_factored_kipft, V_demand, V_allowable, shearRatio, passShear,
            bendingStress_ksi, allowableStress_ksi, stressRatio, max_delta_live_in, max_delta_total_in, L360, L240,
            passStress, passLive, passTotal, passStress && passShear && passLive && passTotal
        )
    }

    data class ColumnResult(
        val sectionName: String, val L_ft: Double, val KLr_max: Double, val Fcr_ksi: Double,
        val P_allowable_kips: Double, val P_applied: Double, val capacityRatio: Double,
        val fp_concrete_psi: Double, val Fp_allow_psi: Double, val tp_req_in: Double, val tp_provided: Double,
        val slendernessPass: Boolean, val capacityPass: Boolean, val passConcreteBearing: Boolean, val passPlateBending: Boolean,
        val isPass: Boolean
    )

    /** analyzeSteelColumn: Chapter E buckling (ASD) and the Design Guide 1 base plate; no uplift. */
    fun analyzeSteelColumn(
        section: Shape, L_ft: Double, P_axial_kips: Double, K: Double = 1.0,
        bp_width: Double = 12.0, bp_length: Double = 12.0, bp_thick: Double = 0.75, bp_fy: Double = 36.0, fc_psi: Double = 3000.0
    ): ColumnResult {
        val L_in = L_ft * 12
        val KL_in = K * L_in
        val r_min = min(section.rx, section.ry)
        val KLr_max = KL_in / r_min
        val E = 29000.0
        val Fy = 50.0
        val Fe_ksi = (PI * PI * E) / KLr_max.pow(2)
        val Fcr_ksi = if (KLr_max <= 4.71 * sqrt(E / Fy)) 0.658.pow(Fy / Fe_ksi) * Fy else 0.877 * Fe_ksi
        val P_allowable_kips = (Fcr_ksi * section.A) / 1.67
        val capacityRatio = P_axial_kips / P_allowable_kips
        // Base plate (Design Guide 1)
        val A1 = bp_width * bp_length
        val fp_psi = (P_axial_kips * 1000) / A1
        val fp_ksi = fp_psi / 1000
        val Fp_allow_psi = 0.35 * fc_psi
        val col_d = section.d
        val col_bf = col_d // the suite reads section.b, which the table doesn't have: falls back to d
        val m = (bp_length - (0.95 * col_d)) / 2
        val n = (bp_width - (0.80 * col_bf)) / 2
        val l = max(max(m, n), 0.1)
        val tp_req = l * sqrt((3.33 * fp_ksi) / bp_fy)
        val slenderness = KLr_max <= 200
        val capacity = capacityRatio <= 1.0
        val bearing = fp_psi <= Fp_allow_psi
        val plate = bp_thick >= tp_req
        return ColumnResult(section.name, L_ft, KLr_max, Fcr_ksi, P_allowable_kips, P_axial_kips, capacityRatio,
            fp_psi, Fp_allow_psi, tp_req, bp_thick, slenderness, capacity, bearing, plate, slenderness && capacity && bearing && plate)
    }

    /** findLightestSteelBeam among [candidates] (W shapes, lightest first, by default). */
    fun findLightestSteelBeam(
        candidates: List<Shape> = AISC_DATABASE.filter { it.type == "W" }.sortedBy { it.weight },
        analyze: (Shape) -> BeamResult
    ): Pair<Shape, BeamResult>? {
        for (s in candidates) { val r = analyze(s); if (r.isPass) return s to r }
        return null
    }

    /** findLightestSteelColumn among [candidates] (W, HSS, Pipe, lightest first, by default). */
    fun findLightestSteelColumn(
        candidates: List<Shape> = AISC_DATABASE.filter { it.type == "W" || it.type == "HSS" || it.type == "Pipe" }.sortedBy { it.weight },
        analyze: (Shape) -> ColumnResult
    ): Pair<Shape, ColumnResult>? {
        for (s in candidates) { val r = analyze(s); if (r.isPass) return s to r }
        return null
    }
}
