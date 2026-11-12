package kr.solbit.catchcharacter

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/**
 * 무료/오프라인 2.5D 풍선 메시 제작. 생성형 AI API를 사용하지 않습니다.
 * 밝은 단색 종이 배경과 연결된 그림 하나를 가정하며, 정교한 3D 복원이 아닙니다.
 */
object BalloonModel {
    data class Result(val glb: ByteArray, val previewPng: ByteArray, val triangles: Int)
    private const val GRID = 96

    fun create(source: Bitmap, heightMeters: Float = 1.1f, puff: Float = 0.16f): Result {
        val width = source.width
        val height = source.height
        val scale = min(1f, 640f / max(width, height))
        val w = max(4, (width * scale).roundToInt())
        val h = max(4, (height * scale).roundToInt())
        val bmp = Bitmap.createScaledBitmap(source, w, h, true)
        val pix = IntArray(w * h)
        bmp.getPixels(pix, 0, w, 0, 0, w, h)
        val candidates = BooleanArray(w * h)
        // 흰 종이 또는 투명 배경. 그림과 배경 색차를 구분하는 단순 휴리스틱.
        for (i in pix.indices) {
            val c = pix[i]
            val alpha = Color.alpha(c)
            val darkness = max(255 - Color.red(c), max(255 - Color.green(c), 255 - Color.blue(c)))
            candidates[i] = alpha > 90 && darkness > 43
        }
        val selected = largestConnectedArea(candidates, w, h)
        val foreground = selected.count { it }
        require(foreground >= 100) { "그림을 찾지 못했어요. 밝은 종이 위에서 그림을 크게 촬영해 주세요." }
        var minX = w; var minY = h; var maxX = 0; var maxY = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (selected[y * w + x]) {
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        val margin = max(4, ((maxX-minX).coerceAtLeast(maxY-minY)*0.035f).roundToInt())
        minX = (minX-margin).coerceAtLeast(0); minY = (minY-margin).coerceAtLeast(0)
        maxX = (maxX+margin).coerceAtMost(w-1); maxY = (maxY+margin).coerceAtMost(h-1)
        val cropW = maxX-minX+1; val cropH = maxY-minY+1
        val cropped = Bitmap.createBitmap(cropW, cropH, Bitmap.Config.ARGB_8888)
        val cropPixels = IntArray(cropW*cropH)
        for (y in 0 until cropH) for (x in 0 until cropW) {
            val oldIdx = (y+minY)*w + x+minX
            cropPixels[y*cropW+x] = if (selected[oldIdx]) pix[oldIdx] else Color.TRANSPARENT
        }
        cropped.setPixels(cropPixels, 0, cropW, 0, 0, cropW, cropH)
        val png = ByteArrayOutputStream().also { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        val n = GRID
        val gridSize = n + 1
        val plane = gridSize * gridSize
        val inside = BooleanArray(plane)
        for (gy in 0..n) for (gx in 0..n) {
            val x = ((gx.toFloat()/n)*(cropW-1)).roundToInt().coerceIn(0, cropW-1)
            val y = ((gy.toFloat()/n)*(cropH-1)).roundToInt().coerceIn(0, cropH-1)
            inside[gy*gridSize+gx] = Color.alpha(cropPixels[y*cropW+x]) > 100
        }
        // 4-neighbour 거리 변환: 경계에서 중심 쪽으로 부드럽게 볼록하게.
        val dist = IntArray(plane) { 1_000_000 }
        val queue = IntArray(plane)
        var tail = 0
        for (i in inside.indices) if (!inside[i]) { dist[i] = 0; queue[tail++] = i }
        var head = 0
        while (head < tail) {
            val cur = queue[head++]; val x = cur % gridSize; val y = cur / gridSize
            val near = intArrayOf(if(x>0)cur-1 else -1, if(x<n)cur+1 else -1,
                if(y>0)cur-gridSize else -1, if(y<n)cur+gridSize else -1)
            for (next in near) if(next>=0 && dist[next]>dist[cur]+1) {
                dist[next]=dist[cur]+1; queue[tail++]=next
            }
        }
        val maxDist = dist.indices.filter { inside[it] }.maxOfOrNull { dist[it] }?.coerceAtLeast(1) ?: 1
        val aspect = cropW.toFloat()/cropH
        val modelHeight = heightMeters
        val modelWidth = aspect * modelHeight
        val positions = ByteBuffer.allocate(plane*2*12).order(ByteOrder.LITTLE_ENDIAN)
        val normals = ByteBuffer.allocate(plane*2*12).order(ByteOrder.LITTLE_ENDIAN)
        val uvs = ByteBuffer.allocate(plane*2*8).order(ByteOrder.LITTLE_ENDIAN)
        fun bulge(i: Int): Float {
            if (!inside[i]) return 0f
            val t = (dist[i].toFloat()/maxDist).coerceIn(0f, 1f)
            return 0.028f + puff * sqrt(t)
        }
        for (face in 0..1) for (gy in 0..n) for (gx in 0..n) {
            val i = gy*gridSize+gx
            val x = (gx.toFloat()/n - 0.5f)*modelWidth
            val y = (1f-gy.toFloat()/n)*modelHeight
            val z = bulge(i) * if(face==0) 1f else -1f
            positions.putFloat(x).putFloat(y).putFloat(z)
            val x0 = (gy*gridSize+(gx-1).coerceAtLeast(0))
            val x1 = (gy*gridSize+(gx+1).coerceAtMost(n))
            val y0 = ((gy-1).coerceAtLeast(0)*gridSize+gx)
            val y1 = ((gy+1).coerceAtMost(n)*gridSize+gx)
            val dx = (bulge(x1)-bulge(x0)) * n / modelWidth * 0.5f
            val dy = (bulge(y0)-bulge(y1)) * n / modelHeight * 0.5f
            val s = if(face==0) 1f else -1f
            val inv = 1f/sqrt(dx*dx+dy*dy+1f)
            normals.putFloat(-dx*inv).putFloat(-dy*inv).putFloat(s*inv)
            uvs.putFloat(gx.toFloat()/n).putFloat(gy.toFloat()/n)
        }
        val indices = ArrayList<Int>(n*n*12)
        // 바닥을 뚫는 등 의도치 않은 메쉬 출력을 피하기 위해 실제 칠해진 구역만 메시 생성.
        val boundary = HashMap<Long, Int>()
        fun boundaryKey(a: Int, b: Int): Long {
            val low = min(a,b); val high=max(a,b)
            return (low.toLong() shl 32) or (high.toLong() and 0xffffffffL)
        }
        val oriented = HashMap<Long, Pair<Int,Int>>()
        fun addFrontTri(a: Int,b: Int,c: Int) {
            indices.add(a);indices.add(b);indices.add(c)
            indices.add(c+plane);indices.add(b+plane);indices.add(a+plane)
            for ((u,v) in listOf(a to b,b to c,c to a)) {
                val k=boundaryKey(u,v)
                boundary[k]=(boundary[k]?:0)+1
                oriented[k]=u to v
            }
        }
        for (y in 0 until n) for (x in 0 until n) {
            val a=y*gridSize+x; val b=a+1; val c=a+gridSize; val d=c+1
            // 화면 y 좌표는 아래쪽으로 증가하므로 시계 반대 방향으로 구성.
            if (inside[a] && inside[c] && inside[b]) addFrontTri(a,c,b)
            if (inside[b] && inside[c] && inside[d]) addFrontTri(b,c,d)
        }
        for ((edge, repeats) in boundary) if(repeats==1) {
            val (u,v) = oriented.getValue(edge)
            indices.add(u); indices.add(v+plane); indices.add(v)
            indices.add(u); indices.add(u+plane); indices.add(v+plane)
        }
        require(indices.size >= 36) { "캐릭터 외곽선을 충분히 인식하지 못했어요." }

        val posBytes=positions.array(); val normBytes=normals.array(); val uvBytes=uvs.array()
        val indexBytes=ByteBuffer.allocate(indices.size*4).order(ByteOrder.LITTLE_ENDIAN)
        indices.forEach { indexBytes.putInt(it) }
        val idxBytes=indexBytes.array()
        val binary = ByteArrayOutputStream()
        data class Part(val offset: Int,val length:Int)
        fun add(bytes: ByteArray):Part {
            while (binary.size()%4!=0) binary.write(0)
            val p=Part(binary.size(),bytes.size)
            binary.write(bytes)
            return p
        }
        val p=add(posBytes); val normal=add(normBytes); val uv=add(uvBytes); val idx=add(idxBytes); val tex=add(png)
        val views=JSONArray()
        fun view(part: Part, target: Int?=null): Int {
            val obj=JSONObject().put("buffer",0).put("byteOffset",part.offset).put("byteLength",part.length)
            if(target!=null)obj.put("target",target)
            views.put(obj);return views.length()-1
        }
        val posView=view(p,34962); val normView=view(normal,34962); val uvView=view(uv,34962)
        val idxView=view(idx,34963); val texView=view(tex)
        val accessors=JSONArray()
        fun accessor(view:Int, component:Int,count:Int,type:String,minA:JSONArray?=null,maxA:JSONArray?=null):Int {
            val o=JSONObject().put("bufferView",view).put("componentType",component).put("count",count).put("type",type)
            if(minA!=null)o.put("min",minA)
            if(maxA!=null)o.put("max",maxA)
            accessors.put(o);return accessors.length()-1
        }
        val positionAccessor=accessor(posView,5126,plane*2,"VEC3",
            JSONArray(listOf(-modelWidth/2,0f,-puff-0.03f)),
            JSONArray(listOf(modelWidth/2,modelHeight,puff+0.03f)))
        val normalAccessor=accessor(normView,5126,plane*2,"VEC3")
        val uvAccessor=accessor(uvView,5126,plane*2,"VEC2")
        val idxAccessor=accessor(idxView,5125,indices.size,"SCALAR")
        val gltf=JSONObject()
            .put("asset",JSONObject().put("version","2.0").put("generator","Solbit BalloonModel 0.1"))
            .put("scene",0)
            .put("scenes",JSONArray().put(JSONObject().put("nodes",JSONArray().put(0))))
            .put("nodes",JSONArray().put(JSONObject().put("mesh",0)))
            .put("meshes",JSONArray().put(JSONObject().put("primitives",JSONArray().put(
                JSONObject().put("attributes",JSONObject().put("POSITION",positionAccessor)
                    .put("NORMAL",normalAccessor).put("TEXCOORD_0",uvAccessor))
                    .put("indices",idxAccessor).put("material",0).put("mode",4)))))
            .put("materials",JSONArray().put(JSONObject().put("doubleSided",true)
                .put("pbrMetallicRoughness",JSONObject().put("baseColorTexture",JSONObject().put("index",0))
                    .put("metallicFactor",0).put("roughnessFactor",0.9))))
            .put("textures",JSONArray().put(JSONObject().put("source",0)))
            .put("images",JSONArray().put(JSONObject().put("bufferView",texView).put("mimeType","image/png")))
            .put("buffers",JSONArray().put(JSONObject().put("byteLength",binary.size())))
            .put("bufferViews",views).put("accessors",accessors)
        val jsonBytes=gltf.toString().toByteArray(Charsets.UTF_8)
        val jsonPad=(4-jsonBytes.size%4)%4
        val binBytes=binary.toByteArray();val binPad=(4-binBytes.size%4)%4
        val total=12+8+jsonBytes.size+jsonPad+8+binBytes.size+binPad
        val result=ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        result.putInt(0x46546c67).putInt(2).putInt(total)
        result.putInt(jsonBytes.size+jsonPad).putInt(0x4e4f534a).put(jsonBytes)
        repeat(jsonPad){result.put(0x20)}
        result.putInt(binBytes.size+binPad).putInt(0x004e4942).put(binBytes)
        repeat(binPad){result.put(0)}
        return Result(result.array(),png,indices.size/3)
    }

    private fun largestConnectedArea(candidates:BooleanArray,w:Int,h:Int):BooleanArray {
        val seen=BooleanArray(w*h)
        val buffer=IntArray(w*h)
        var best=IntArray(0)
        for (seed in candidates.indices) {
            if(!candidates[seed]||seen[seed])continue
            var left=0;var right=0
            buffer[right++]=seed;seen[seed]=true
            while(left<right){
                val i=buffer[left++];val x=i%w;val y=i/w
                val near=intArrayOf(if(x>0)i-1 else -1,if(x<w-1)i+1 else -1,
                    if(y>0)i-w else -1, if(y<h-1)i+w else -1)
                for(n in near)if(n>=0 && candidates[n] && !seen[n]){seen[n]=true;buffer[right++]=n}
            }
            if(right>best.size)best=buffer.copyOf(right)
        }
        val result=BooleanArray(w*h)
        best.forEach{result[it]=true}
        return result
    }
}
