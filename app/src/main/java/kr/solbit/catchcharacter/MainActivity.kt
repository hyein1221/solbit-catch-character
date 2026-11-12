package kr.solbit.catchcharacter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.ar.core.Anchor
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.SceneView
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.hypot
import kotlin.random.Random

private val deepBlue = Color(0xff23334c)
private val lavender = Color(0xfff5f1ff)
private val pastelPink = Color(0xffffe3ef)
private val pastelYellow = Color(0xffffefad)
private val sky = Color(0xffe8f5ff)
private enum class Page { HOME, GALLERY, LOGIN, AR, PREVIEW, ADMIN }

class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=android.graphics.Color.rgb(246,241,255)
        window.navigationBarColor=android.graphics.Color.rgb(246,241,255)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility=android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(
                primary=deepBlue,secondary=Color(0xff8d6cd5),background=Color(0xfffaf8ff),surface=Color.White
            )) { MainApplication() }
        }
    }
}

@Composable
private fun MainApplication(){
    val context= LocalContext.current
    val scope= rememberCoroutineScope()
    val local=remember{CharacterRepository(context)}
    val cloud=remember{CloudRepository(context)}
    var page by remember { mutableStateOf(Page.HOME) }
    var gallery by remember { mutableStateOf(local.local()) }
    var admin by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CharacterItem?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var tempUri by remember { mutableStateOf<Uri?>(null) }
    var registered by remember { mutableStateOf(cloud.isSignedIn) }

    suspend fun reload(){
        if(cloud.available && cloud.isSignedIn){
            admin=runCatching{cloud.isAdmin()}.getOrDefault(false)
            gallery=try{
                cloud.list(includeHidden=admin && showHidden).let { remote ->
                    remote + local.local().filter { own -> remote.none { it.id == own.id } }
                }
            }catch(e:Exception){
                status="공유 보관함 접속 실패: ${e.message}"; local.local()
            }
        }else gallery=local.local()
    }
    LaunchedEffect(registered,showHidden){ reload() }

    fun importPicture(uri:Uri){
        busy=true;status="그림을 분석하고 통통한 캐릭터로 변환하고 있어요…"
        scope.launch{
            try{
                val item=withContext(Dispatchers.IO){
                    val bitmap=context.contentResolver.openInputStream(uri).use{input->
                        requireNotNull(input){"사진을 열 수 없어요"}
                        val bytes=input.readBytes()
                        val opts=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
                        val options=BitmapFactory.Options().apply{
                            inSampleSize=generateSequence(1){it*2}.takeWhile{it<=64}
                                .lastOrNull{maxOf(opts.outWidth,opts.outHeight)/it>=1024}?:1
                        }
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
                    } ?: error("사진 형식을 읽지 못했어요")
                    val model=BalloonModel.create(bitmap)
                    local.save(name.trim().ifBlank{"솔빛 캐릭터"},model)
                }
                selected=item
                gallery=local.local()
                if(cloud.available && cloud.isSignedIn){
                    try{cloud.publish(item);status="캐릭터가 공동 보관함에 등록되었어요!";reload()}
                    catch(e:Exception){status="기기 저장 완료, 공유 등록 실패: ${e.message}"}
                }else status="캐릭터가 기기에 저장되었어요. 공동 공유는 로그인 후 사용할 수 있어요."
                page=Page.PREVIEW
            }catch(e:Exception){status="변환 실패: ${e.message}"}
            finally{busy=false}
        }
    }
    val galleryPicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){ uri->
        if(uri!=null)importPicture(uri)
    }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()){ok->
        if(ok)tempUri?.let{importPicture(it)} else status="촬영을 취소했어요."
    }
    fun shoot(){
        val file=File(context.cacheDir,"photos/${UUID.randomUUID()}.jpg")
        file.parentFile?.mkdirs()
        tempUri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
        camera.launch(tempUri!!)
    }
    val photoPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ granted ->
        if(granted)shoot() else status="그림 촬영을 위해 카메라 권한을 허용해주세요."
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){ granted->
        if(granted)page=Page.AR else status="AR 탐험을 시작하려면 카메라 권한을 허용해주세요."
    }
    fun startAr(){
        if(gallery.none{!it.hidden}){status="등록된 캐릭터가 없어요. 먼저 그림을 등록해주세요.";return}
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
            page=Page.AR
        else permission.launch(Manifest.permission.CAMERA)
    }

    Column(Modifier.fillMaxSize().background(Color(0xfffaf8ff))) {
        if(page!=Page.AR){
            Row(Modifier.fillMaxWidth().background(lavender).padding(horizontal=18.dp, vertical=14.dp),
                verticalAlignment=Alignment.CenterVertically){
                if(page!=Page.HOME)TextButton(onClick={page=Page.HOME}){Text("← 처음")}
                Column(Modifier.weight(1f)){
                    Text("솔빛 캐치앤 캐릭터",fontWeight=FontWeight.ExtraBold,fontSize=19.sp,color=deepBlue)
                    Text("내가 그린 친구들을 현실에서 만나요 ✨",fontSize=11.sp,color=deepBlue.copy(alpha=.7f))
                }
            }
        }
        if(status.isNotEmpty() && page!=Page.AR){
            Surface(color=sky,modifier=Modifier.fillMaxWidth()) {
                Text(status,fontSize=12.sp,color=deepBlue,modifier=Modifier.padding(horizontal=16.dp,vertical=9.dp))
            }
        }
        if(busy)LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
        when(page){
            Page.HOME-> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement=Arrangement.spacedBy(14.dp)){
                Spacer(Modifier.height(10.dp))
                Card(colors=CardDefaults.cardColors(containerColor=pastelYellow),shape=RoundedCornerShape(25.dp)) {
                    Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Text("🌟",fontSize=42.sp)
                        Text("우리 반 캐릭터가\n진짜 교실에 나타난다면?",fontSize=25.sp,fontWeight=FontWeight.Black,color=deepBlue,lineHeight=32.sp)
                        Text("그림 촬영 → 풍선 입체화 → AR 자유 탐험",fontSize=13.sp,color=deepBlue)
                        Button(onClick={startAr},modifier=Modifier.fillMaxWidth(),enabled=!busy){Text("AR 탐험 시작하기")}
                    }
                }
                Button(onClick={page=Page.GALLERY},modifier=Modifier.fillMaxWidth()) {Text("공동 캐릭터 보관함  (${gallery.count{!it.hidden}})")}
                OutlinedButton(onClick={page=Page.GALLERY},modifier=Modifier.fillMaxWidth()) {Text("새 캐릭터 등록하기")}
                if(cloud.available){
                    OutlinedButton(onClick={
                        if(registered){cloud.signOut();registered=false;admin=false;status="로그아웃했어요."}
                        else page=Page.LOGIN
                    },modifier=Modifier.fillMaxWidth()){
                        Text(if(registered) "계정 로그아웃" else "교사 계정 로그인 · 캐릭터 공유")
                    }
                    if(admin)OutlinedButton(onClick={page=Page.ADMIN},modifier=Modifier.fillMaxWidth()){
                        Text("관리자: 삭제 · 복구")
                    }
                }else Text("현재 오프라인 시험 모드예요. 여러 선생님과 공유하려면 Supabase 설정이 필요해요.",
                    fontSize=12.sp,color=Color.Gray)
                Text("※ 아이들이 카메라 화면만 보며 걷지 않도록 교사가 안전한 공간에서 함께 탐험해 주세요.",
                    fontSize=12.sp,color=Color.Gray)
            }
            Page.GALLERY,Page.ADMIN ->Column(Modifier.fillMaxSize().padding(16.dp)){
                Text(if(page==Page.ADMIN)"캐릭터 삭제 및 복구" else "우리들의 캐릭터",fontSize=23.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                if(page==Page.GALLERY){
                    OutlinedTextField(value=name,onValueChange={name=it.take(60)},modifier=Modifier.fillMaxWidth(),
                        label={Text("캐릭터 이름 (선택)")},singleLine=true)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        Button(onClick={
                            if(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
                                shoot() else photoPermission.launch(Manifest.permission.CAMERA)
                        },enabled=!busy){Text("📷 그림 촬영")}
                        OutlinedButton(onClick={galleryPicker.launch("image/*")},enabled=!busy){Text("앨범에서 선택")}
                    }
                    Spacer(Modifier.height(14.dp))
                }else{
                    Row(verticalAlignment=Alignment.CenterVertically){
                        Text("숨긴 캐릭터도 보기",modifier=Modifier.weight(1f))
                        Switch(checked=showHidden,onCheckedChange={showHidden=it})
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically){
                    Text("등록된 캐릭터 ${gallery.size}개",fontSize=13.sp,color=Color.Gray,modifier=Modifier.weight(1f))
                    TextButton(onClick={scope.launch{busy=true;try{reload()}finally{busy=false}}}){Text("새로고침")}
                }
                LazyColumn(verticalArrangement=Arrangement.spacedBy(9.dp)){
                    items(gallery,key={it.id}){item->
                        Card(shape=RoundedCornerShape(17.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                            Row(Modifier.fillMaxWidth().clickable{selected=item;page=Page.PREVIEW}.padding(10.dp),
                                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                val bmp=remember(item.id){local.preview(item)}
                                if(bmp!=null)Image(bmp.asImageBitmap(),null,Modifier.size(70.dp),contentScale=ContentScale.Fit)
                                else Text("🎨",fontSize=30.sp)
                                Column(Modifier.weight(1f)){
                                    Text(item.name,fontWeight=FontWeight.Bold,color=deepBlue)
                                    Text(if(item.hidden)"삭제됨 · 복구 가능" else "풍선형 2.5D 캐릭터",fontSize=11.sp,color=Color.Gray)
                                }
                                if(page==Page.ADMIN && admin && item.remoteModelPath!=null){
                                    TextButton(onClick={scope.launch{
                                        busy=true
                                        try{cloud.setHidden(item,!item.hidden);reload();status=if(item.hidden)"복구 완료" else "삭제 완료(복구 가능)"}
                                        catch(e:Exception){status="권한 또는 서버 오류: ${e.message}"}
                                        finally{busy=false}
                                    }}){Text(if(item.hidden)"복구" else "삭제")}
                                } else Text("›",fontSize=24.sp,color=Color.Gray)
                            }
                        }
                    }
                }
            }
            Page.LOGIN ->Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("교사 계정",fontSize=24.sp,fontWeight=FontWeight.Bold)
                Text("한 선생님이 등록한 캐릭터를 다른 선생님도 볼 수 있어요.",fontSize=13.sp)
                OutlinedTextField(value=email,onValueChange={email=it},label={Text("이메일")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(value=password,onValueChange={password=it},label={Text("비밀번호 (6자 이상)")},singleLine=true,
                    visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                Button(onClick={scope.launch{
                    busy=true
                    try{cloud.signIn(email,password);registered=true;reload();page=Page.HOME;status="로그인 완료!"}
                    catch(e:Exception){status="로그인 실패: ${e.message}"}finally{busy=false}
                }},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("로그인")}
                OutlinedButton(onClick={scope.launch{
                    busy=true
                    try{status=cloud.signUp(email,password);registered=cloud.isSignedIn;if(registered)page=Page.HOME}
                    catch(e:Exception){status="가입 실패: ${e.message}"}finally{busy=false}
                }},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("처음 사용하는 선생님 · 계정 만들기")}
                Text("공유 보관함은 로그인한 교사만 이용할 수 있으며, 관리자는 서버에서 별도 지정합니다.",fontSize=12.sp,color=Color.Gray)
            }
            Page.PREVIEW->{
                val item=selected
                if(item!=null){
                    Column(Modifier.fillMaxSize()){
                        Text("${item.name} · 3D 미리보기",modifier=Modifier.padding(16.dp),fontWeight=FontWeight.Bold)
                        Box(Modifier.weight(1f).fillMaxWidth()){
                            val engine=rememberEngine();val loader=rememberModelLoader(engine)
                            SceneView(modifier=Modifier.fillMaxSize(),engine=engine,modelLoader=loader){
                                key(item.id){
                                    rememberModelInstance(loader,fileLocation=Uri.fromFile(item.modelFile).toString())?.let{
                                        ModelNode(modelInstance=it,scaleToUnits=1.0f)
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(14.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            OutlinedButton(onClick={page=Page.GALLERY},modifier=Modifier.weight(1f)){Text("보관함")}
                            Button(onClick={startAr},modifier=Modifier.weight(1f)){Text("AR에서 만나기")}
                        }
                    }
                }
            }
            Page.AR -> ArExplorer(gallery.filter{!it.hidden},onExit={page=Page.HOME})
        }
    }
}

private data class ARPlacement(val id:String,val anchor:Anchor,val item:CharacterItem)

@Composable
private fun ArExplorer(characters:List<CharacterItem>,onExit:()->Unit){
    val placements=remember{mutableStateListOf<ARPlacement>()}
    var lastSpawn by remember{mutableLongStateOf(0L)}
    var goal by remember{mutableIntStateOf(minOf(4,characters.size))}
    DisposableEffect(Unit){onDispose{placements.forEach{it.anchor.detach()}}}
    Box(Modifier.fillMaxSize()){
        val engine=rememberEngine(); val loader=rememberModelLoader(engine)
        ARSceneView(
            modifier=Modifier.fillMaxSize(),
            engine=engine,modelLoader=loader,
            planeRenderer=true,
            onSessionUpdated=updated@{session,frame ->
                if(placements.size>=goal || System.currentTimeMillis()-lastSpawn<700L)return@updated
                if(frame.camera.trackingState!=TrackingState.TRACKING)return@updated
                val planes=session.getAllTrackables(Plane::class.java).filter{
                    it.trackingState==TrackingState.TRACKING && it.type==Plane.Type.HORIZONTAL_UPWARD_FACING
                        && it.subsumedBy==null && it.extentX>=0.6f && it.extentZ>=0.6f
                }
                var found:Anchor?=null
                for(attempt in 0 until 20){
                    if(planes.isEmpty())break
                    val plane=planes.random()
                    val dx=Random.nextFloat()*plane.extentX*0.8f-plane.extentX*0.4f
                    val dz=Random.nextFloat()*plane.extentZ*0.8f-plane.extentZ*0.4f
                    val pose=plane.centerPose.compose(Pose.makeTranslation(dx,0f,dz))
                    val cam=frame.camera.pose
                    val dist=hypot((pose.tx()-cam.tx()).toDouble(),(pose.tz()-cam.tz()).toDouble())
                    if(!plane.isPoseInPolygon(pose) || dist<1.1 || dist>5.5)continue
                    if(placements.any{p->
                        hypot((pose.tx()-p.anchor.pose.tx()).toDouble(),(pose.tz()-p.anchor.pose.tz()).toDouble())<0.85
                    })continue
                    found=runCatching{plane.createAnchor(pose)}.getOrNull()
                    if(found!=null)break
                }
                if(found!=null){
                    placements.add(ARPlacement(UUID.randomUUID().toString(),found,characters[placements.size%characters.size]))
                    lastSpawn=System.currentTimeMillis()
                }
            }
        ) {
            placements.forEach { placement ->
                key(placement.id){
                    AnchorNode(anchor=placement.anchor){
                        rememberModelInstance(loader,fileLocation=Uri.fromFile(placement.item.modelFile).toString())?.let { model ->
                            ModelNode(modelInstance=model,scaleToUnits=1.0f)
                        }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .background(Color.Black.copy(alpha=.42f)).padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Text("🌟 솔빛 캐치앤 캐릭터",fontSize=19.sp,fontWeight=FontWeight.Black,color=Color.White)
            Text(if(placements.size<goal)"바닥을 천천히 비춰 주세요 · ${placements.size}/$goal"
                else "캐릭터를 천천히 둘러보세요!",fontSize=12.sp,color=Color.White)
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick={
                placements.forEach{it.anchor.detach()};placements.clear();lastSpawn=0L
            },modifier=Modifier.weight(1f)){Text("다시 배치")}
            OutlinedButton(onClick=onExit,modifier=Modifier.weight(1f),
                colors=ButtonDefaults.outlinedButtonColors(containerColor=Color.White)){
                Text("탐험 종료")
            }
        }
    }
}
