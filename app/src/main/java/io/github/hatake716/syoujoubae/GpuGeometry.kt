package io.github.hatake716.syoujoubae

import android.content.res.AssetManager
import android.opengl.GLES20.*
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class GpuBatch(val vertex: Int, val index: Int, val count: Int, val bytes: Int)

/** One reusable 1 MiB staging buffer; completed geometry lives only in GPU buffers. */
internal class GpuGeometry(private val assets: AssetManager) {
    private val staging = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.nativeOrder())

    private fun upload(target: Int, size: Int): Int {
        val name = IntArray(1)
        glGenBuffers(1, name, 0)
        glBindBuffer(target, name[0])
        staging.position(0); staging.limit(size)
        glBufferData(target, size, staging, GL_STATIC_DRAW)
        glBindBuffer(target, 0)
        if (glGetError() != GL_NO_ERROR) {
            glDeleteBuffers(1, name, 0)
            throw IOException("3D描画用のメモリを確保できませんでした")
        }
        return name[0]
    }

    fun mesh(path: String): List<GpuBatch> {
        val result = mutableListOf<GpuBatch>()
        try {
            DataInputStream(assets.open(path).buffered()).use { input ->
                fun uint() = Integer.reverseBytes(input.readInt())
                if (input.readInt() != 0x4d434e32) throw IOException("領域モデルの形式が不正です")
                val chunks = uint()
                if (chunks !in 1..1000) throw IOException("領域モデルの分割数が不正です")
                repeat(chunks) {
                    val nv = uint(); val ni = uint()
                    if (nv !in 1..60000 || ni !in 3..60000 || ni % 3 != 0) throw IOException("領域モデルの構造が不正です")
                    val data = ByteArray(nv * 16)
                    input.readFully(data)
                    val coordinates = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until nv) for (axis in 0..2) {
                        val offset = i * 16 + axis * 4
                        val value = (coordinates.getFloat(offset) - Geometry.origin[axis]) / Geometry.unit
                        if (!value.isFinite()) throw IOException("領域の座標が不正です")
                        coordinates.putFloat(offset, value)
                    }
                    staging.clear(); staging.put(data)
                    val vertex = upload(GL_ARRAY_BUFFER, data.size)
                    try {
                        val indices = ByteArray(ni * 2)
                        input.readFully(indices)
                        staging.clear(); staging.put(indices)
                        result += GpuBatch(vertex, upload(GL_ELEMENT_ARRAY_BUFFER, indices.size), ni, data.size + indices.size)
                    } catch (e: Exception) {
                        glDeleteBuffers(1, intArrayOf(vertex), 0); throw e
                    }
                }
                if (input.read() != -1) throw IOException("領域モデルの長さが不正です")
            }
            return result
        } catch (e: Exception) { delete(result); throw e }
    }

    fun lines(values: FloatArray): List<GpuBatch> {
        val result = mutableListOf<GpuBatch>()
        try {
            var offset = 0
            // Whole pairs of XYZ endpoints, at most 1 MiB in each upload.
            while (offset < values.size) {
                val count = minOf(262140, values.size - offset)
                staging.clear()
                staging.asFloatBuffer().put(values, offset, count)
                result += GpuBatch(upload(GL_ARRAY_BUFFER, count * 4), 0, count / 3, count * 4)
                offset += count
            }
            return result
        } catch (e: Exception) { delete(result); throw e }
    }

    fun delete(batches: List<GpuBatch>) {
        for (batch in batches) {
            glDeleteBuffers(1, intArrayOf(batch.vertex), 0)
            if (batch.index != 0) glDeleteBuffers(1, intArrayOf(batch.index), 0)
        }
    }
}
