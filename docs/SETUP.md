# 설치와 설정 (처음 한 번)

## 1. 공개 레포 만들기
앱의 업데이트 확인이 로그인 없이 GitHub 릴리스를 읽기 때문에 **Public** 이어야 해요.
이 폴더 전체를 올려요. `.github` 폴더(점으로 시작해서 숨겨져 있을 수 있어요)가 꼭 같이 올라가야 해요.

## 2. 서명 키
받은 키로 서명한 앱끼리만 서로 업데이트돼요. **키 파일은 따로 백업**하고 레포에는 올리지 마세요. 잃어버리면 앱을 지우고 새로 설치해야 해요.
새로 만들기: `keytool -genkeypair -v -keystore release.jks -alias myspoti -keyalg RSA -keysize 2048 -validity 10000`
base64: `base64 -w 0 release.jks` (Windows: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard`)

레포 **Settings → Secrets and variables → Actions**에 4개 등록: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
이전에 쓰던 같은 키를 그대로 쓰면 기존 Myspoti AI 위에 업데이트로 설치돼요.

## 3. 권한
**Settings → Actions → General → Workflow permissions** → *Read and write permissions*.

## 4. 첫 빌드
**Actions → Build Myspoti AI → Run workflow**. 수십 분 걸려요. 끝나면 **Releases**에 APK가 올라와요. 폰은 `…-ARMv8.apk`를 설치해요.
이 빌드는 제가 컴파일해 보지 못한 상태예요. 첫 빌드가 실패할 수 있어요. 그때는 실행 로그(실패한 단계)를 보여주세요.

## 5. 앱에서
가사 화면 ⋮ 메뉴 → **Gemini API 키**에 [Google AI Studio](https://aistudio.google.com/apikey) 키를 붙여넣고, 같은 메뉴에서 AI 발음과 다시 번역을 써요.
음량 미세조절은 플레이어의 재생 기기("다음에서 듣기") 창에 있어요.

## 6. 그 뒤로는
매일 새벽 원본의 새 릴리스를 확인해요. 새 버전이 있으면 알아서 빌드·배포해요. 앱에서는 **설정 → 업데이트 확인**을 누르면 돼요(앱을 열 때도 1시간에 한 번 자동 확인).

## 알아둘 것
- 빌드가 실패해도 이 워크플로는 Issue를 만들거나 알림을 보내지 않아요. 다음 날 다시 시도해요. GitHub이 본인 계정으로 보내는 실패 메일은 GitHub 설정(Notifications → Actions)에서 끌 수 있어요.
- 레포에 60일간 활동이 없으면 GitHub이 예약 실행을 꺼요. 워크플로가 45일마다 빈 커밋을 남겨서 막아요.
- Gemini 모델 이름(`GeminiClient.kt`의 `MODELS`)을 Google이 없애면 다음 후보로 넘어가요. 전부 없어지면 그 목록만 고치면 돼요.
- 패치가 안 맞아 빌드가 멈추면 Actions 로그의 `Myspoti AI patch report`에 어느 연결이 안 맞았는지 나와요.
