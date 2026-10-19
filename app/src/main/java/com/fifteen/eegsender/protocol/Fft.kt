package com.fifteen.eegsender.protocol

/**
 * 迭代式 radix-2 复数 FFT（就地变换）。
 *
 * 只在准备「人为降质」的预计算里用到，不在 5 ms 推流热路径上调用。但这不代表可以
 * 凑合：一次 prepare 要处理 62 个通道 × 16384 点，误差会随蝶形级数累积，因此旋转
 * 因子与蝶形运算全部用 Double 计算，只在读写数组时落回 Float。
 */
internal object Fft {

    /** 返回不小于 [n] 的最小 2 的幂。 */
    fun nextPowerOfTwo(n: Int): Int {
        require(n > 0) { "长度必须为正：$n" }
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    /**
     * 就地变换。[re] 与 [im] 长度必须相同且为 2 的幂。
     *
     * [inverse] 为 true 时使用正指数并除以元素个数，保证
     * `transform(re, im, true)` 恰好还原 `transform(re, im, false)` 之前的数组。
     */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        require(n == im.size) { "实部(${re.size})与虚部(${im.size})长度不一致" }
        require((n and (n - 1)) == 0) { "长度必须是 2 的幂：$n" }

        // 位反转置换，把输入重排成蝶形运算需要的顺序
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        val sign = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val stepRe = Math.cos(sign * 2.0 * Math.PI / len)
            val stepIm = Math.sin(sign * 2.0 * Math.PI / len)
            var base = 0
            while (base < n) {
                var wRe = 1.0
                var wIm = 0.0
                var k = 0
                while (k < half) {
                    val a = base + k
                    val b = a + half
                    val br = re[b].toDouble()
                    val bi = im[b].toDouble()
                    val tRe = br * wRe - bi * wIm
                    val tIm = br * wIm + bi * wRe
                    val ar = re[a].toDouble()
                    val ai = im[a].toDouble()
                    re[a] = (ar + tRe).toFloat()
                    im[a] = (ai + tIm).toFloat()
                    re[b] = (ar - tRe).toFloat()
                    im[b] = (ai - tIm).toFloat()
                    val nextRe = wRe * stepRe - wIm * stepIm
                    wIm = wRe * stepIm + wIm * stepRe
                    wRe = nextRe
                    k++
                }
                base += len
            }
            len = len shl 1
        }

        if (inverse) {
            val scale = 1.0 / n
            for (i in 0 until n) {
                re[i] = (re[i] * scale).toFloat()
                im[i] = (im[i] * scale).toFloat()
            }
        }
    }
}
