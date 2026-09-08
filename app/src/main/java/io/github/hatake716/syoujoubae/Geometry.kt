package io.github.hatake716.syoujoubae

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The public precomputed skeleton contract: uint32 counts, float32 nm XYZ, uint32 edges. */
object Geometry {
    const val MAX_BYTES = 64 * 1024 * 1024
    val origin = floatArrayOf(375f, -208f, 218f)
    const val unit = 375f

    fun coordinate(x: Float, y: Float, z: Float): FloatArray =
        floatArrayOf((x / 1000f - origin[0]) / unit, (-z / 1000f - origin[1]) / unit, (y / 1000f - origin[2]) / unit)

    fun skeleton(bytes: ByteArray): FloatArray {
        if (bytes.size < 8 || bytes.size > MAX_BYTES) throw IOException("神経形態のファイルサイズが不正です")
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val nv = b.int.toLong() and 0xffffffffL
        val ne = b.int.toLong() and 0xffffffffL
        if (nv == 0L || nv > 2_000_000 || ne > 4_000_000 || 8L + nv * 12L + ne * 8L > bytes.size)
            throw IOException("神経形態の構造が不正です")
        if (nv * 12L + ne * 48L > 96L * 1024 * 1024)
            throw IOException("この形態は端末向けの描画容量を超えています。接続一覧をご利用ください。")
        val vertices = FloatArray(nv.toInt() * 3)
        for (i in 0 until nv.toInt()) {
            val x = b.float; val y = b.float; val z = b.float
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) throw IOException("座標が不正です")
            val p = coordinate(x,y,z)
            p.copyInto(vertices, i*3)
        }
        val result = FloatArray(ne.toInt() * 6)
        for (i in 0 until ne.toInt()*2) {
            val index = b.int.toLong() and 0xffffffffL
            if (index >= nv) throw IOException("神経形態の接続先が不正です")
            vertices.copyInto(result, i*3, index.toInt()*3, index.toInt()*3+3)
        }
        return result
    }

    fun bounds(vertices: FloatArray): Pair<FloatArray, Float> {
        if (vertices.isEmpty()) return FloatArray(3) to 1f
        val lo = FloatArray(3) { Float.POSITIVE_INFINITY }
        val hi = FloatArray(3) { Float.NEGATIVE_INFINITY }
        for (i in vertices.indices step 3) for (axis in 0..2) {
            lo[axis] = minOf(lo[axis], vertices[i+axis]); hi[axis] = maxOf(hi[axis], vertices[i+axis])
        }
        val center = FloatArray(3) { (lo[it]+hi[it])/2f }
        val radius = kotlin.math.sqrt((0..2).sumOf { ((hi[it]-lo[it])/2f).toDouble().let { d -> d*d } }).toFloat()
        return center to maxOf(radius, 0.02f)
    }
}
