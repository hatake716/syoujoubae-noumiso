package io.github.hatake716.syoujoubae

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class GeometryTest {
    private fun sample(index:Int=1):ByteArray = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(2);putInt(1)
        putFloat(375000f);putFloat(218000f);putFloat(208000f)
        putFloat(750000f);putFloat(218000f);putFloat(208000f)
        putInt(0);putInt(index)
    }.array()
    @Test fun officialNanometresBecomeOneContinuousEdgeInAtlasCoordinates() {
        assertArrayEquals(floatArrayOf(0f,0f,0f,1f,0f,0f),Geometry.skeleton(sample()),0.00001f)
    }
    @Test fun sourceAxesPreserveDistancesWithoutMirroring() {
        val x=Geometry.coordinate(750000f,218000f,208000f)
        val y=Geometry.coordinate(375000f,593000f,208000f)
        val z=Geometry.coordinate(375000f,218000f,583000f)
        assertArrayEquals(floatArrayOf(1f,0f,0f),x,0.00001f)
        assertArrayEquals(floatArrayOf(0f,0f,1f),y,0.00001f)
        assertArrayEquals(floatArrayOf(0f,-1f,0f),z,0.00001f)
    }
    @Test(expected=IOException::class) fun rejectsTruncatedDownload() {Geometry.skeleton(sample().copyOf(39))}
    @Test(expected=IOException::class) fun rejectsOutOfBoundsEdge() {Geometry.skeleton(sample(2))}
    @Test(expected=IOException::class) fun rejectsNonFiniteCoordinate() {
        val b=sample();ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putFloat(8,Float.NaN);Geometry.skeleton(b)
    }
    @Test fun deltaCodedConnectionsPreservePartnerIdsAndWeights() {
        val out=ByteArrayOutputStream()
        GZIPOutputStream(out).use {it.write(byteArrayOf(10,3,127,1,0x80.toByte(),1,0xac.toByte(),2))}
        assertEquals(listOf(10L to 3,137L to 1,265L to 300),AtlasRepository.decodeAdjacency(out.toByteArray()))
    }
    @Test(expected=IOException::class) fun rejectsTruncatedConnectionPair() {
        val out=ByteArrayOutputStream();GZIPOutputStream(out).use {it.write(byteArrayOf(10))}
        AtlasRepository.decodeAdjacency(out.toByteArray())
    }
}
