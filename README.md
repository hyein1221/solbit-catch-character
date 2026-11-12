# 솔빛 캐치앤 캐릭터 🌟

**Android APK 우선 / 유료 3D AI API 미사용 / 2.5D 풍선형 AR / 공동 보관함**

아이의 종이 그림을 촬영 또는 갤러리에서 선택 → 기기에서 직접 흰 배경 분리 및 연결된 외곽선 추출 → 볼록한 앞면·뒷면 메시 및 GLB 생성 → ARCore 바닥 평면에 캐릭터 자동 랜덤 배치 → 교사 공동 보관함에 공유하는 안드로이드 프로젝트입니다.

> **상태:** 구현 초안(개발용 소스). Android SDK가 없는 작성 환경에서 APK 빌드 및 실제 기기 검증을 수행하지 못했습니다. 아래 CI 빌드를 반드시 실행하고 오류가 있으면 수정해야 합니다. 완성된 설치 파일로 오인하지 마세요.

## 사용 기술
- Kotlin, Jetpack Compose, SceneView 4.67.0, ARCore, Google Filament.
- `BalloonModel.kt`: 휴대전화에서만 동작하는 비생성형 이미지→2.5D GLB 변환. **유료 AI API 호출 없음**.
- `CharacterRepository.kt`: 내부 저장소, Supabase Auth / PostgREST / 비공개 Storage.
- `MainActivity.kt`: 그림 촬영, 앨범 선택, 이름 부여, 3D 미리보기, AR 탐험, 공동 보관함, 관리자 삭제·복구.
- `supabase/schema.sql`: 승인된 계정만 접근하는 RLS 및 버킷 규칙.

## 알려진 제한 및 주의사항
1. 2.5D는 완전한 3D 복원이 아닙니다. 뒷면은 원본 그림의 단순 대응면이고, 실제 아이가 그린 뒷모습이 복원되지 않습니다.
2. 자동 분리는 **밝은 흰 종이, 충분한 조명, 비교적 연결된 하나의 주 캐릭터**를 가정합니다. 떨어진 장식, 손글씨, 그림 여러 개는 제거되거나 잘못 포함될 수 있습니다. 원본 사진은 업로드하지 않고 생성된 PNG·GLB만 저장합니다.
3. 바닥 인식은 환경(조명/반사/무늬)과 ARCore 기종에 영향을 받습니다. 부딪히지 않도록 교사가 기기를 조작하며 관찰합니다.
4. 1차 관리자 삭제는 `is_hidden=true`를 통한 **복구 가능한 보관함 삭제**입니다. 파일 영구 삭제는 아직 구현되지 않았습니다.
5. 기기 자체 보관함은 계정과 무관하게 동작합니다. 여러 교사 간 공유는 Supabase 설정·로그인·관리자 승인이 있어야 합니다.
6. 앱 서버·API 생성 요금은 없습니다. Supabase 무료 플랜은 이용량/비활성 정책이 있으며 무제한 무료가 아닙니다.
7. 디버그 APK는 기술 시험용입니다. 사용자 배포용은 별도의 서명된 릴리스 APK를 만들어 서명키를 보관해야 합니다.
8. **Galaxy ARCore 지원 기종과 Google Play Services for AR 설치**가 필요합니다.

## 빌드 방법 A: GitHub Actions (Android Studio 없이)
1. 해당 GitHub 저장소의 소스를 확인합니다. 이 저장소는 GitHub Actions로 APK를 빌드합니다.
2. GitHub > Actions > `Android APK (debug test)` > Run workflow. 또는 main에 올리면 자동 실행됩니다.
3. Build 성공 시 해당 실행 화면의 **Artifacts → solbit-catch-character-debug-apk** 다운로드.
4. ZIP을 열면 `app-debug.apk`가 있습니다. 갤럭시에 복사하여 설치·실행합니다.
5. 빌드가 실패하면 **Actions 로그**를 열어 어떤 Gradle/Kotlin 오류가 있는지 확인합니다. 이 소스는 아직 CI에서 성공 확인되지 않았습니다.

이 워크플로는 `gradle 9.6.0`, JDK 21, SDK 37, AGP 9.4.0을 사용합니다. `app/build.gradle.kts`가 추후 변경될 경우 버전도 맞춰 조정해야 합니다.

## 빌드 방법 B: Android Studio
- Android Studio 최신 버전 설치 후 폴더 열기. Android SDK 37/Build Tools 36, JDK 21 설정.
- Gradle Sync → Run(갤럭시 연결) 또는 Build APK(s).
- 배포용 APK는 Build > Generate Signed App Bundle / APK > APK에서 서명키를 생성해 제작합니다.

## 여러 선생님과 공유하기: Supabase 연결
1. Supabase 프로젝트 생성 (무료 플랜 가능).
2. SQL Editor에서 `supabase/schema.sql` 실행.
3. 프로젝트 URL과 **publishable key**를 GitHub 저장소 Settings > Secrets and variables > Actions에 각각 `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`로 추가하고 재빌드. **service_role/secret key를 APK에 넣지 마세요.**
4. 앱에서 선생님은 이메일로 가입하고 필요하다면 인증 메일 확인.
5. Supabase Authentication > Users에서 이메일의 사용자 UUID 확인 후 SQL Editor에서 승인:
   - `insert into public.app_members (uid,role) values ('교사UUID'::uuid,'teacher');`
   - 관리자: `insert into public.app_members (uid,role) values ('관리자UUID'::uuid,'admin');`
6. 승인된 계정으로 로그인 → 등록된 캐릭터가 다른 교사의 갤럭시에서도 보이는지 확인. 앱의 캐릭터 배치 위치는 각 휴대전화에서 독립적입니다.

정책상 비승인 계정은 사진/모델 조회와 업로드가 불가능합니다. 앱에서 로그인은 성공해도 보관함 접속이 거부될 수 있으며 이는 **관리자 승인 대기** 상태입니다.

## 우선 검증할 테스트
1. `앱 실행 → 갤러리에서 흰 종이 그림 선택 → 2.5D GLB 생성 → 미리보기에서 회전`.
2. 카메라 권한 허용 → 실제 바닥 인식 → 첫 캐릭터 생성 후 다른 각도에서 관찰.
3. 캐릭터 3개 이상 등록 후 자동 랜덤 배치 및 `다시 배치`.
4. 계정 A에서 등록한 캐릭터를 계정 B에서 새로고침해 확인.
5. 일반 계정은 삭제 불가능, 관리자 계정은 삭제/복구 가능한지 확인.
6. 사진 분석 오류/앱 재실행/오프라인 AR/네트워크 실패 시 화면 확인.

## 폴더
```
app/src/main/java/kr/solbit/catchcharacter/
  BalloonModel.kt         이미지 → 실시간 입체 GLB
  CharacterRepository.kt 저장·공동 보관함 인증 및 공유
  MainActivity.kt         화면·카메라·AR 공간 인식
supabase/schema.sql        RLS/Storage
.github/workflows/android-apk.yml   디버그 APK 빌드
```

## 이름 및 버전
- 앱 이름: **솔빛 캐치앤 캐릭터**
- 애플리케이션 ID: `kr.solbit.catchcharacter`
- 버전: `0.1.0` (기능 검증용)
- 앱 디자인: 파스텔 크림/라벤더, 단순 교사용 조작 버튼.

## 주변 교사에게 배포할 서명된 APK (디버그 검증 후)
1. PC에서 Java `keytool`로 서명키를 한 번 생성합니다: `keytool -genkeypair -v -keystore solbit-release.jks -alias solbit -keyalg RSA -keysize 3072 -validity 10000`. **키와 비밀번호를 분실하지 마세요.**
2. 해당 JKS 파일을 Base64로 인코딩한 문자열을 GitHub Actions Secret `RELEASE_KEYSTORE_BASE64`에 등록합니다. Windows는 PowerShell에서 `[Convert]::ToBase64String([IO.File]::ReadAllBytes('solbit-release.jks'))`를 사용할 수 있습니다.
3. Secrets `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`도 등록합니다.
4. GitHub Actions > `Android signed release APK` > `Run workflow`를 실행하고 Artifact의 릴리스 APK를 내려받습니다.
5. 이후 배포 업데이트에도 **동일한 서명키 + 동일한 applicationId**를 유지하고 `versionCode`를 증가시켜야 합니다.
6. **서명키 파일이나 비밀번호는 GitHub 저장소에 올리지 마세요.**

## 공개 저장소의 유아 작품 보호
- 이전 대화에서 제공한 실제 아동의 그림을 원본·미리보기·데모 GLB 형태로 GitHub 공개 저장소에 게시하지 않습니다.
- 앱을 설치한 뒤 `새 캐릭터 등록하기`에서 직접 촬영해 변환하세요. 공유 보관함은 접근 권한이 적용된 비공개 Supabase Storage를 사용합니다.
- 저장소에 Supabase service_role, 관리자 비밀번호, signing keystore, 아동 사진을 커밋하지 마세요.
