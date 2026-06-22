# Releasing & CI/CD

Endpoint Lens 배포(마켓플레이스 업로드) 및 GitHub Actions 자동화 설정 메모.

---

## 0. 현재 상태 (이미 되어 있는 것)

- `build.gradle.kts` 에 `signing` / `publishing` 블록 추가됨 (환경변수/시크릿을 읽음).
- `.github/workflows/release.yml` — `v*` 태그 푸시(또는 수동 실행) 시 빌드 → 서명 → 마켓 업로드.
- `.github/workflows/build.yml` — master 푸시/PR 마다 빌드 검증 + zip 아티팩트 업로드.
- `.gitignore` 에 서명 키 패턴(`*.pem *.crt *.p12 ...`) 추가 — 키 실수 커밋 방지.

### ⚠️ 아직 안 된 것 (배포 전 필수)

1. **Gradle wrapper 커밋** — `gradle/wrapper/gradle-wrapper.jar` + `gradle-wrapper.properties` 가 아직 커밋 안 됨.
   이게 없으면 GitHub Actions 에서 `./gradlew` 가 즉시 실패한다.
   ```bash
   git add gradle/wrapper/
   ```
2. **GitHub Secrets 4개 등록** (아래 2번 참고).
3. **서명 인증서 생성** (아래 1번 참고).

---

## 1. 서명 인증서 만들기 (self-signed, 1회만)

`PRIVATE_KEY` / `PRIVATE_KEY_PASSWORD` / `CERTIFICATE_CHAIN` 는 **발급기관이 없다**. 직접 만든다.
(JetBrains 가 발급하는 건 `PUBLISH_TOKEN` 하나뿐.)

repo 폴더 **바깥**(예: 홈 디렉터리)에서, **대화형**으로 직접 실행:

```bash
# 1) 암호화 개인키 생성 → passphrase 두 번 입력 (이 값이 PRIVATE_KEY_PASSWORD)
openssl genpkey -aes-256-cbc -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:4096

# 2) 그 키로 자체 서명 인증서 생성 → 1)의 passphrase 입력
openssl req -key private.pem -new -x509 -days 3650 -out chain.crt -subj "/CN=Endpoint Lens"
```

- Windows PowerShell 에는 openssl 이 PATH 에 없음. Git Bash 에서 실행하거나 풀경로 사용:
  `& "C:\Program Files\Git\usr\bin\openssl.exe" ...`
- 생성된 `private.pem` / `chain.crt` 는 **절대 커밋·공유 금지**. passphrase 와 함께 안전한 곳에 백업
  (분실 시 같은 키로 재서명 불가).

---

## 2. GitHub Secrets 등록 (1회만)

레포 → **Settings → Secrets and variables → Actions → New repository secret**.
이름은 아래와 **정확히 일치**해야 함 (대소문자 포함).

| Secret | 출처 / 값 |
|---|---|
| `PUBLISH_TOKEN` | JetBrains 발급: plugins.jetbrains.com → 프로필 → **My Tokens** → Generate |
| `PRIVATE_KEY` | `private.pem` 파일 **전체 내용** (`-----BEGIN ENCRYPTED PRIVATE KEY-----` ~ `END`) |
| `PRIVATE_KEY_PASSWORD` | 1번에서 정한 passphrase |
| `CERTIFICATE_CHAIN` | `chain.crt` 파일 **전체 내용** (`-----BEGIN CERTIFICATE-----` ~ `END`) |

여러 줄 값도 그대로 붙여넣으면 됨.

gh CLI 로도 가능:
```bash
gh secret set PUBLISH_TOKEN
gh secret set PRIVATE_KEY < private.pem
gh secret set CERTIFICATE_CHAIN < chain.crt
gh secret set PRIVATE_KEY_PASSWORD
```

> 참고: 이 4개는 **release.yml(배포)** 에서만 필요. **build.yml(빌드 검증)** 은 시크릿 없이 동작.

---

## 3. 릴리스 절차 (매 버전)

```bash
# 1) 버전 올리기 — 세 곳을 동일 버전으로 맞춘다
#    - build.gradle.kts : version = "1.3.0"
#    - src/main/resources/META-INF/plugin.xml : <change-notes> 상단에 1.3.0 추가
#    - README.md : Changelog 에 ### 1.3.0 추가
# 2) 커밋
git commit -am "release: v1.3.0"
# 3) 태그 푸시 → Actions 가 자동으로 빌드·서명·마켓 업로드
git tag v1.3.0
git push origin master --tags
```

- 진행 상황은 GitHub **Actions** 탭에서 확인.
- 기존 플러그인의 **업데이트**는 마켓 검수가 보통 빠르게(수십 분~수 시간) 자동 승인된다.
- **같은 버전 재업로드는 거부**된다. (1.2.0 은 이미 수동 업로드했으니, 자동배포 첫 테스트는 다음 버전 태그로 할 것.)

---

## 4. 수동 배포 (백업 방법)

자동화가 막히면 언제든 수동으로:

```bash
./gradlew buildPlugin   # build/distributions/endpoint-lens-<version>.zip 생성
```
→ plugins.jetbrains.com → Your Plugins → Endpoint Lens → **Upload update** → zip 선택.

---

## 빌드 환경 메모

- JDK 21 필요. 로컬 셸에 JAVA_HOME 없으면:
  `$env:JAVA_HOME = "C:\Users\mk.jang\.jdks\temurin-21.0.7"`
- 호환성 검증(선택): `./gradlew verifyPlugin`
