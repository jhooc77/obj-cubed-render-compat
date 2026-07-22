# Runtime test matrix

이 문서는 자동 빌드로 확인할 수 없는 실제 GPU 렌더 결과의 최소 검증표입니다. 각 조합은 같은 obj-cubed 서버 리소스팩과 같은 셰이더팩으로 비교합니다.

## 공통 장면

1. OBJ 장비를 입은 플레이어를 1인칭·3인칭·인벤토리에서 확인합니다.
2. OBJ item display와 block display를 정지·걷기·달리기·점프 중 확인합니다.
3. 네 방향으로 설치한 OBJ 블록의 형상과 회전을 확인합니다.
4. 핫바·일반 인벤토리·크리에이티브 인벤토리에서 아이템을 확인하고 스크롤합니다.
5. 셰이더를 끄고 켠 뒤 같은 월드, 다른 월드, 서버에 재접속합니다.

## 조합

| ID | Minecraft | 렌더 경로 | 설치 파일 | 필수 확인 |
|---|---|---|---|---|
| F1 | 26.1 또는 26.1.1 | Iris 1.10.9 + Sodium 0.8.9 | 공식 Iris 옆에 `obj-cubed-iris-compat-26.1-universal-0.1.1.jar` | 셰이더 off/on, 공통 장면 |
| F2 | 26.1.2 | Iris 1.10.9 + Sodium 0.8.9–0.8.12 | 공식 Iris 옆에 동일한 동반 JAR | 기존 26.1 조합의 26.1.2 실행, 셰이더 off/on |
| F3 | 26.1.2 | Iris 1.11.2 + Sodium 0.9.1 | 공식 Iris 옆에 동일한 동반 JAR | 최신 26.1.2 조합, 셰이더 off/on, 공통 장면 |
| I1 | 26.1–26.1.2 | 전체 Iris 교체본 + Sodium 0.8.9 | `iris-fabric-1.10.9-objcubed.14+mc26.1.1.jar` | 공통 장면과 OBJ 표면의 블록 파괴 금 |
| V1 | 26.2 | Vulkan + Sodium 0.9.1 | `iris-fabric-1.11.2-objcubed-vulkan-beta.5+mc26.2.jar` | 공통 장면, 셰이더 교체, 화면 흔들림 |
| O1 | 26.1.2 | OptiFine K1 pre1 | 패처가 만든 개인용 `*-objcubed.jar` | 셰이더 off/on과 공통 장면 |
| O2 | 26.1.2 | OptiFine K1 pre2 | 패처가 만든 개인용 `*-objcubed.jar` | 셰이더 off/on과 공통 장면 |

F1/F2 동반 모드는 전체 Iris 교체본과 동시에 설치하지 않습니다. 동반 모드에서 블록 파괴 금은 바닐라 carrier 기준이며, OBJ 표면 투영은 I1 전체 교체본만 지원합니다.

OptiFine은 OpenGL subgroup 기능이 제공되는 GPU가 필요합니다. 공식 OptiFine 26.2가 나오기 전까지 O1/O2와 동등한 26.2 경로는 없습니다.

## Optional official OptiFine integration probe

OptiFine 자체는 저장소나 CI에 포함하지 않습니다. 공식 K1 pre1/pre2 JAR을 직접 받은 뒤 아래 명령으로 OptiFine의 실제 GLSL remap 출력과 obj-cubed 주입기의 연결을 재검증할 수 있습니다.

```text
./gradlew officialOptiFineProbe -PoptifineJar=/path/to/preview_OptiFine_26.1.2_HD_U_K1_pre2.jar
```
