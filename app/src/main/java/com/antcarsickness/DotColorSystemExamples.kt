package com.antcarsickness

import android.graphics.Color

object DotColorSystemExamples {

    /**
     * 示例用例：
     * 在不同背景颜色上模拟状态，输出 fill/stroke/halo 与 contrast。
     * 可在调试入口手动调用并查看日志。
     */
    fun buildExampleLogs(): List<String> {
        val backgrounds = listOf(
            Color.rgb(8, 8, 8),
            Color.rgb(40, 52, 68),
            Color.rgb(120, 120, 120),
            Color.rgb(210, 210, 210),
            Color.rgb(245, 250, 255)
        )
        val states = listOf(
            DotVisualState.IDLE,
            DotVisualState.ACCELERATING,
            DotVisualState.BRAKING,
            DotVisualState.TURNING,
            DotVisualState.CRUISING,
            DotVisualState.JERK
        )

        val params = DotColorParams(minContrast = 4.5f)
        val out = ArrayList<String>()
        for (bg in backgrounds) {
            val bgHex = String.format("#%02X%02X%02X", Color.red(bg), Color.green(bg), Color.blue(bg))
            for (state in states) {
                val style = DotColorSystem.chooseDotColor(bg, state, params)
                val fillHex = String.format(
                    "#%02X%02X%02X",
                    Color.red(style.fill),
                    Color.green(style.fill),
                    Color.blue(style.fill)
                )
                val strokeHex = style.stroke?.let {
                    String.format("#%02X%02X%02X", Color.red(it), Color.green(it), Color.blue(it))
                } ?: "null"
                val haloHex = style.halo?.let {
                    String.format("#%02X%02X%02X", Color.red(it.color), Color.green(it.color), Color.blue(it.color))
                } ?: "null"
                out += "bg=$bgHex state=$state fill=$fillHex stroke=$strokeHex halo=$haloHex met=${style.metContrast} ratio=${"%.2f".format(style.contrastValue)}"
            }
        }
        return out
    }
}
