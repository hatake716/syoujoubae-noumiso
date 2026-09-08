package io.github.hatake716.syoujoubae

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class Reference(val title: String, val url: String, val kind: String)
data class Region(val key: String, val name: String, val english: String, val group: String, val tag: String,
    val summary: String, val mechanism: String, val observe: String, val limits: String, val sources: List<String>, val query: String)
data class MeshInfo(val id: Int, val name: String, val center: FloatArray, val min: FloatArray, val max: FloatArray) {
    val key get() = name.replace(Regex("\\([LR]\\)$"), "")
    val side get() = when { name.endsWith("(L)") -> "左"; name.endsWith("(R)") -> "右"; else -> "正中・共通" }
}
data class Neuron(val id: Long, val type: String, val instance: String, val superclass: String, val cellClass: String,
    val side: String, val status: String, val dimorphism: String, val brain: Boolean)
data class Connection(val id: Long, val weight: Int, val type: String)
data class Connections(val outputs: List<Connection>, val inputs: List<Connection>, val outWeight: Long, val inWeight: Long)
data class Skeleton(val id: Long, val lines: FloatArray, val fromCache: Boolean)
data class OverviewCell(val id: Long, val group: String)

class AtlasRepository(private val context: Context) : AutoCloseable {
    val regions: List<Region>
    val references: Map<String,Reference>
    val notes: List<String>
    val meshes: List<MeshInfo>
    val offlineIds: Set<Long>
    val previewCount: Int
    val overviewCells: List<OverviewCell>
    val stats: JSONObject
    private val db: SQLiteDatabase
    private val cache = File(context.cacheDir, "skeletons-v1")

    init {
        val json = JSONObject(assetText("regions.json"))
        regions = json.getJSONArray("regions").objects().map { r ->
            Region(r.getString("key"),r.getString("name"),r.getString("english"),r.getString("group"),r.getString("tag"),
                r.getString("summary"),r.getString("mechanism"),r.getString("observe"),r.getString("limits"),r.getJSONArray("sources").strings(),r.getString("query"))
        }
        references = json.getJSONObject("sources").let { s -> s.keys().asSequence().associateWith { key ->
            val r=s.getJSONObject(key); Reference(r.getString("title"),r.getString("url"),r.getString("kind")) } }
        notes = json.getJSONArray("notes").strings()
        meshes = JSONArray(assetText("meshes.json")).objects().map { m ->
            fun floats(key: String) = m.getJSONArray(key).let { a -> FloatArray(3) { a.getDouble(it).toFloat() } }
            MeshInfo(m.getInt("id"),m.getString("name"),floats("center"),floats("min"),floats("max"))
        }
        overviewCells = JSONArray(assetText("skeletons.json")).objects().map { OverviewCell(it.getLong("id"), it.getString("group")) }
        offlineIds = overviewCells.map { it.id }.toSet()
        previewCount = offlineIds.size
        stats = JSONObject(assetText("connections.json"))
        // Versioned, atomic install; failed/low-space copies can be retried without corrupting a database.
        val folder = File(context.noBackupFilesDir, "atlas").apply { mkdirs() }
        val target = File(folder, "male-cns-v1-${BuildConfig.VERSION_CODE}.db")
        if (!target.exists()) {
            val temp = File(folder, target.name+".tmp")
            try {
                temp.outputStream().buffered().use { output ->
                    val parts = context.assets.list("atlas/database")?.sorted() ?: throw IOException("データベースが見つかりません")
                    if (parts.isEmpty()) throw IOException("データベースが見つかりません")
                    for (part in parts) context.assets.open("atlas/database/$part").use { it.copyTo(output) }
                }
                SQLiteDatabase.openDatabase(temp.path,null,SQLiteDatabase.OPEN_READONLY).use { check ->
                    check.rawQuery("PRAGMA quick_check",null).use { c -> if (!c.moveToFirst() || c.getString(0)!="ok") throw IOException("データベースの検証に失敗しました") }
                }
                if (!temp.renameTo(target)) throw IOException("データベースを保存できませんでした")
                folder.listFiles()?.filter { it!=target }?.forEach { it.delete() }
            } catch (e: Exception) { temp.delete(); throw e }
        }
        db = SQLiteDatabase.openDatabase(target.path,null,SQLiteDatabase.OPEN_READONLY)
        cache.mkdirs()
    }

    private fun assetText(name:String) = context.assets.open("atlas/$name").bufferedReader().use { it.readText() }
    fun count(brainOnly: Boolean): Int = db.rawQuery("SELECT COUNT(*) FROM neurons"+if(brainOnly) " WHERE brain=1" else "",null).use { it.moveToFirst(); it.getInt(0) }
    fun search(query:String, brainOnly:Boolean, offset:Int=0): List<Neuron> {
        val needle = query.trim().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")
        val where = if (brainOnly) "brain=1 AND " else ""
        val sql = "SELECT id,type,instance,superclass,class,side,status,dimorphism,brain FROM neurons WHERE ${where}(CAST(id AS TEXT)=? OR type LIKE ? ESCAPE '\\' OR instance LIKE ? ESCAPE '\\' OR class LIKE ? ESCAPE '\\' OR superclass LIKE ? ESCAPE '\\') ORDER BY CASE WHEN type=? COLLATE NOCASE THEN 0 ELSE 1 END,type COLLATE NOCASE,id LIMIT 80 OFFSET ?"
        return db.rawQuery(sql,arrayOf(query.trim(),"%$needle%","%$needle%","%$needle%","%$needle%",query.trim(),offset.toString())).use { c ->
            buildList { while(c.moveToNext()) add(Neuron(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getInt(8)==1)) }
        }
    }
    fun neuron(id:Long):Neuron? = db.rawQuery("SELECT id,type,instance,superclass,class,side,status,dimorphism,brain FROM neurons WHERE id=?",arrayOf(id.toString())).use { c ->
        if(c.moveToFirst()) Neuron(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getInt(8)==1) else null
    }
    fun connections(id:Long):Connections {
        return db.rawQuery("SELECT outputs,inputs,wout,win FROM connections WHERE id=?",arrayOf(id.toString())).use { c ->
            if (!c.moveToFirst()) return@use Connections(emptyList(),emptyList(),0,0)
            fun decode(column:Int):List<Connection> {
                if(c.isNull(column)) return emptyList()
                val pairs = decodeAdjacency(c.getBlob(column))
                // Resolve all names, bounded batches below SQLite's argument limit.
                val names = mutableMapOf<Long,String>()
                pairs.chunked(400).forEach { batch ->
                    val placeholders=batch.joinToString(",") { "?" }
                    db.rawQuery("SELECT id,type FROM neurons WHERE id IN ($placeholders)",batch.map { it.first.toString() }.toTypedArray()).use { nc ->
                        while(nc.moveToNext()) names[nc.getLong(0)]=nc.getString(1)
                    }
                }
                return pairs.map { Connection(it.first,it.second,names[it.first]?:"未分類") }.sortedWith(compareByDescending<Connection> { it.weight }.thenBy { it.id })
            }
            Connections(decode(0),decode(1),c.getLong(2),c.getLong(3))
        }
    }
    fun skeleton(id:Long):Skeleton {
        require(id>0)
        if(id in offlineIds) return Skeleton(id,Geometry.skeleton(context.assets.open("atlas/skeletons/$id.bin").use { it.readBytes() }),true)
        val dest = File(cache,"$id.bin")
        if(dest.isFile) {
            try { dest.setLastModified(System.currentTimeMillis());return Skeleton(id,Geometry.skeleton(dest.readBytes()),true) }
            catch (_:IOException) { dest.delete() }
        }
        val url=URL("https://storage.googleapis.com/flyem-male-cns/v1.0/segmentation/skeletons-malecns/skeletons-precomputed/$id")
        val conn=url.openConnection() as HttpURLConnection
        conn.connectTimeout=15_000;conn.readTimeout=30_000;conn.instanceFollowRedirects=false
        val temp=File(cache,"$id.part")
        try {
            val code=conn.responseCode
            if(code==404) throw IOException("配布元にこの細胞の形態がありません。接続情報は表示できます。")
            if(code!=200) throw IOException("配布元に接続できません（HTTP $code）。再試行してください。")
            if(conn.contentLengthLong>Geometry.MAX_BYTES) throw IOException("この神経形態は端末向けの上限64MBを超えています。")
            conn.inputStream.buffered().use { input -> temp.outputStream().buffered().use { output ->
                val bytes=ByteArray(16384);var total=0
                while(true) { val n=input.read(bytes); if(n<0) break;total+=n
                    if(total>Geometry.MAX_BYTES) throw IOException("神経形態のサイズ上限を超えました")
                    output.write(bytes,0,n)
                }
            } }
            val geometry=Geometry.skeleton(temp.readBytes())
            if(!temp.renameTo(dest)) throw IOException("神経形態を保存できませんでした")
            trimCache()
            return Skeleton(id,geometry,false)
        } finally { conn.disconnect();temp.delete() }
    }
    private fun trimCache() {
        val files=cache.listFiles()?.filter { it.extension=="bin" }?.sortedBy { it.lastModified() } ?: return
        var bytes=files.sumOf { it.length() }
        for(f in files) if(bytes>192L*1024*1024) {val size=f.length();if(f.delete()) bytes-=size}
    }
    fun clearCache() { cache.listFiles()?.filter { it.extension=="bin" }?.forEach { it.delete() } }
    fun cacheSize():Long = cache.listFiles()?.sumOf { it.length() } ?: 0
    override fun close() = db.close()

    companion object {
        fun decodeAdjacency(blob:ByteArray):List<Pair<Long,Int>> {
            val bytes=GZIPInputStream(ByteArrayInputStream(blob)).use { it.readBytes() }
            var p=0;var previous=0L
            fun varint():Long { var value=0L;var shift=0
                while(p<bytes.size && shift<=35) { val b=bytes[p++].toInt() and 255;value=value or ((b and 127).toLong() shl shift)
                    if(b and 128==0) return value;shift+=7 }
                throw IOException("接続データが破損しています")
            }
            return buildList { while(p<bytes.size) { previous+=varint();val weight=varint();if(previous>0xffffffffL || weight>Int.MAX_VALUE || weight<=0) throw IOException("接続データの値が不正です");add(previous to weight.toInt()) } }
        }
    }
}
private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.strings() = (0 until length()).map { getString(it) }
