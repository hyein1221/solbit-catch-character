package kr.solbit.catchcharacter

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Private local cache. Remote sync is available only after Supabase is configured and logged in. */
data class CharacterItem(
    val id: String, val name: String, val modelFile: File, val previewFile: File,
    val remoteModelPath: String? = null, val remotePreviewPath: String? = null,
    val hidden: Boolean = false
)

class CharacterRepository(private val context: Context) {
    private val dir = File(context.filesDir,"characters").apply{mkdirs()}
    private val index = File(dir,"local-index.json")

    fun local(): List<CharacterItem> {
        if (!index.exists()) return emptyList()
        return runCatching {
            val items=JSONArray(index.readText())
            (0 until items.length()).mapNotNull { n ->
                val j=items.getJSONObject(n); val id=j.getString("id")
                val model=File(dir,"$id.glb"); val image=File(dir,"$id.png")
                if(!model.exists()||!image.exists())null else CharacterItem(id,j.optString("name","캐릭터"),model,image)
            }
        }.getOrDefault(emptyList())
    }
    fun save(name:String, result:BalloonModel.Result): CharacterItem {
        val id=UUID.randomUUID().toString()
        val model=File(dir,"$id.glb"); val img=File(dir,"$id.png")
        model.writeBytes(result.glb);img.writeBytes(result.previewPng)
        val entries=runCatching{JSONArray(index.takeIf{it.exists()}?.readText()?:"[]")}.getOrDefault(JSONArray())
        entries.put(JSONObject().put("id",id).put("name",name.take(60)))
        index.writeText(entries.toString())
        return CharacterItem(id,name,model,img)
    }
    fun removeLocal(item:CharacterItem){
        val remaining=local().filter{it.id!=item.id}
        index.writeText(JSONArray().apply{remaining.forEach{put(JSONObject().put("id",it.id).put("name",it.name))}}.toString())
        item.modelFile.delete();item.previewFile.delete()
    }
    fun preview(item:CharacterItem)=BitmapFactory.decodeFile(item.previewFile.absolutePath)
}

class CloudRepository(private val context:Context) {
    val available = BuildConfig.SUPABASE_URL.startsWith("https://") && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()
    private val url=BuildConfig.SUPABASE_URL.trimEnd('/')
    private val key=BuildConfig.SUPABASE_PUBLISHABLE_KEY
    private val prefs=context.getSharedPreferences("account",Context.MODE_PRIVATE)
    private val dir=File(context.filesDir,"cloud-character-cache").apply{mkdirs()}
    val uid get()=prefs.getString("uid","")?:""
    val isSignedIn get()=prefs.getString("access_token","").orEmpty().isNotBlank()

    private data class Session(val access:String,val refresh:String,val userId:String)
    private fun storeSession(result:JSONObject){
        val access=result.getString("access_token")
        val refresh=result.optString("refresh_token")
        val id=result.optJSONObject("user")?.optString("id") ?: uid
        prefs.edit().putString("access_token",access).putString("refresh_token",refresh).putString("uid",id).apply()
    }
    fun signOut(){prefs.edit().clear().apply()}

    private fun request(method:String,path:String,body:ByteArray?=null,
        type:String="application/json", bearer:String?=null):ByteArray {
        check(available){"Supabase 설정이 없습니다. 앱 빌드 설정을 확인해주세요."}
        val conn=(URL(url+path).openConnection() as HttpURLConnection)
        conn.requestMethod=method
        conn.connectTimeout=15000;conn.readTimeout=25000
        conn.setRequestProperty("apikey",key)
        val token = bearer ?: prefs.getString("access_token", "").orEmpty()
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept","application/json")
        if(body!=null){
            conn.doOutput=true;conn.setRequestProperty("Content-Type",type)
            conn.outputStream.use{it.write(body)}
        }
        try{
            val code=conn.responseCode
            val bytes=(if(code in 200..299)conn.inputStream else conn.errorStream)?.use{it.readBytes()}?:ByteArray(0)
            if(code !in 200..299)throw IllegalStateException("서버 응답 $code: ${bytes.toString(Charsets.UTF_8).take(200)}")
            return bytes
        } finally {conn.disconnect()}
    }
    private fun json(method:String,path:String,obj:JSONObject?=null,bearer:String?=null):JSONObject =
        JSONObject(request(method,path,obj?.toString()?.toByteArray(Charsets.UTF_8),bearer=bearer).toString(Charsets.UTF_8))

    suspend fun signIn(email:String,password:String):Unit=withContext(Dispatchers.IO){
        val o=json("POST","/auth/v1/token?grant_type=password",JSONObject()
            .put("email",email.trim()).put("password",password),bearer="")
        storeSession(o)
    }
    suspend fun signUp(email:String,password:String):String=withContext(Dispatchers.IO){
        val o=json("POST","/auth/v1/signup",JSONObject().put("email",email.trim()).put("password",password),bearer="")
        if(o.has("access_token"))storeSession(o)
        if(isSignedIn)"가입 후 로그인되었어요." else "가입 요청 완료. 이메일 인증이 필요할 수 있어요."
    }
    private fun ensureFreshToken(){
        val refresh=prefs.getString("refresh_token","").orEmpty()
        if(refresh.isBlank())return
        // refreshToken이 필요해질 때만 호출: 저장된 토큰 만료 판단이 아닌 재시도 로직에서 사용
        val o=json("POST","/auth/v1/token?grant_type=refresh_token",JSONObject().put("refresh_token",refresh),bearer="")
        storeSession(o)
    }
    private fun authorized(method:String,path:String,body:ByteArray?=null,type:String="application/json"):ByteArray{
        return try{ request(method,path,body,type) }
        catch(e:IllegalStateException){
            if(e.message?.contains("401")!=true)throw e
            ensureFreshToken();request(method,path,body,type)
        }
    }
    suspend fun isAdmin():Boolean=withContext(Dispatchers.IO){
        if(!isSignedIn)return@withContext false
        val res=authorized("GET","/rest/v1/app_members?uid=eq.$uid&select=role")
        val a=JSONArray(res.toString(Charsets.UTF_8))
        a.length()>0 && a.getJSONObject(0).optString("role")=="admin"
    }
    private fun upload(path:String,file:File,contentType:String){
        val safePath=path.split('/').joinToString("/"){java.net.URLEncoder.encode(it,"UTF-8")}
        authorized("POST","/storage/v1/object/characters/$safePath",file.readBytes(),contentType)
    }
    suspend fun publish(item:CharacterItem):Unit=withContext(Dispatchers.IO){
        check(isSignedIn){"공동 보관함에 등록하려면 로그인이 필요해요."}
        val model="$uid/${item.id}.glb";val preview="$uid/${item.id}.png"
        upload(model,item.modelFile,"model/gltf-binary")
        upload(preview,item.previewFile,"image/png")
        val obj=JSONObject().put("id",item.id).put("name",item.name)
            .put("created_by",uid).put("model_path",model).put("preview_path",preview)
            .put("is_hidden",false)
        authorized("POST","/rest/v1/characters",JSONArray().put(obj).toString().toByteArray())
    }
    private fun signedUrl(path:String):String {
        val encoded=path.split('/').joinToString("/"){java.net.URLEncoder.encode(it,"UTF-8")}
        val o=JSONObject(authorized("POST","/storage/v1/object/sign/characters/$encoded",
            JSONObject().put("expiresIn",300).toString().toByteArray()).toString(Charsets.UTF_8))
        val signed=o.getString("signedURL")
        return when {
            signed.startsWith("http://") || signed.startsWith("https://") -> signed
            signed.startsWith("/storage/v1/") -> url + signed
            signed.startsWith("/object/") -> url + "/storage/v1" + signed
            else -> url + "/storage/v1/" + signed.trimStart('/')
        }
    }
    private fun downloadSigned(path:String,target:File){
        if(target.exists()&&target.length()>100)return
        val signed=signedUrl(path)
        val conn=(URL(signed).openConnection() as HttpURLConnection)
        conn.connectTimeout=15000;conn.readTimeout=30000
        try{
            check(conn.responseCode in 200..299){"파일 다운로드 실패: ${conn.responseCode}"}
            val temp=File(target.parentFile,target.name+".tmp")
            temp.outputStream().use{out->conn.inputStream.use{it.copyTo(out)}}
            check(temp.length()>50){"파일이 비어 있습니다"}
            if(!temp.renameTo(target)){temp.copyTo(target,overwrite=true);temp.delete()}
        } finally{conn.disconnect()}
    }
    suspend fun list(includeHidden:Boolean=false): List<CharacterItem> = withContext(Dispatchers.IO){
        check(isSignedIn){"공유 보관함 이용을 위해 로그인해 주세요."}
        val query=if(includeHidden) "" else "&is_hidden=eq.false"
        val bytes=authorized("GET","/rest/v1/characters?select=id,name,model_path,preview_path,is_hidden&order=created_at.desc$query")
        val a=JSONArray(bytes.toString(Charsets.UTF_8))
        val items=mutableListOf<CharacterItem>()
        for(i in 0 until a.length()){
            val obj=a.getJSONObject(i);val id=obj.getString("id")
            val model=File(dir,"$id.glb");val png=File(dir,"$id.png")
            try{
                downloadSigned(obj.getString("model_path"),model)
                downloadSigned(obj.getString("preview_path"),png)
                items+=CharacterItem(id,obj.getString("name"),model,png,
                    obj.getString("model_path"),obj.getString("preview_path"),obj.optBoolean("is_hidden"))
            }catch(e:Exception){
                // 캐릭터 한 개의 다운로드 실패가 다른 캐릭터 목록 전체를 막지 않도록 함
                android.util.Log.w("CloudRepository","캐릭터 캐시 실패: $id",e)
            }
        }
        items
    }
    suspend fun setHidden(item:CharacterItem,hidden:Boolean):Unit=withContext(Dispatchers.IO){
        check(isAdmin()){ "관리자만 삭제·복구할 수 있어요." }
        authorized("PATCH","/rest/v1/characters?id=eq.${item.id}",
            JSONObject().put("is_hidden",hidden).toString().toByteArray())
    }
}
