# Notice / 저작권과 라이선스

## English
Myspoti AI is an unofficial modified build of **Myspoti** by kota-zo (https://github.com/kota-zo/myspoti),
distributed under the **GNU General Public License, version 3** (see `LICENSE`). It is not affiliated with,
or endorsed by, Myspoti or Spotify.

- The original program, its name and its copyright notices belong to its authors and are kept in every build.
  Each release carries the complete source of the build (`Myspoti-AI-<version>-source.zip`), which includes the
  original's `LICENSE` and `THIRD_PARTY_NOTICES`.
- This repository holds only the changes: new files under `overlay/` and `patches/apply.py`, which makes small
  edits in the original's files. All of it is released under GPL-3.0. The new code was written for this patch
  set; it does not contain code of any other Myspoti modification.
- Gemini is a service of Google. The app sends the lyric lines of a song to Google's Gemini API, with the API key
  the user entered, only when translation or pronunciation by AI is used. The key is stored on the phone, encrypted.

If you pass the app on to other people, the GPL-3.0 asks you to keep the credit to Myspoti and the license
notices, to make the source of what you pass on available (the release's source archive does this), and to keep
the same license.

## 한국어
Myspoti AI는 kota-zo의 **Myspoti**(https://github.com/kota-zo/myspoti)를 수정한 비공식 빌드이고
**GNU GPL v3**(`LICENSE`)를 따라요. Myspoti, Spotify와는 관련이 없어요.

- 원본 프로그램과 이름, 저작권 표시는 원작자의 것이고 모든 빌드에 그대로 남겨요. 릴리스마다 그 빌드의 전체 소스
  (`Myspoti-AI-<버전>-source.zip`)를 같이 올려요. 여기에 원본의 `LICENSE`와 `THIRD_PARTY_NOTICES`가 들어 있어요.
- 이 레포에는 바뀐 부분만 있어요: `overlay/`의 새 파일들과, 원본 파일에 짧게 연결하는 `patches/apply.py`. 전부 같은
  GPL-3.0이에요. 새 코드는 이 패치를 위해 새로 작성했고, 다른 Myspoti 수정판의 코드는 들어 있지 않아요.
- Gemini는 Google의 서비스예요. AI 번역이나 AI 발음을 쓸 때만, 본인이 넣은 API 키로 곡의 가사 줄을 Google Gemini
  API에 보내요. 키는 폰에 암호화해서 저장돼요.

**남에게 앱을 나눠줄 때**는 GPL-3.0에 따라 ① Myspoti 크레딧과 라이선스 표시를 유지하고, ② 나눠주는 앱의 소스를 받을
수 있게 하고(릴리스의 소스 파일이 그 역할), ③ 같은 라이선스를 유지해야 해요.
