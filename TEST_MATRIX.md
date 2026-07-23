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
| F1 | 26.1 | Iris 1.10.9 + Sodium 0.8.x | `obj-cubed-iris-compat-mc26.1-iris1.10.9-0.2.1.jar` | 셰이더 off/on, 공통 장면 |
| F2 | 26.1.1 | Iris 1.10.9 + Sodium 0.8.x | `obj-cubed-iris-compat-mc26.1.1-iris1.10.9-0.2.1.jar` | 셰이더 off/on, 공통 장면 |
| F3 | 26.1.2 | Iris 1.10.9 + Sodium 0.8.9–0.8.12 | `obj-cubed-iris-compat-mc26.1.2-iris1.10.9-0.2.1.jar` | 셰이더 off/on, 공통 장면 |
| F4 | 26.1.2 | Iris 1.11.2 + Sodium 0.9.1 | `obj-cubed-iris-compat-mc26.1.2-iris1.11.2-0.2.1.jar` | 셰이더 off/on, 공통 장면 |
| F5 | 26.2 | Iris 1.11.2 + Sodium 0.9.1 + OpenGL | `obj-cubed-iris-compat-mc26.2-iris1.11.2-0.2.1.jar` | 셰이더 off/on, 공통 장면 |
| V1 | 26.2 | Vulkan + Sodium 0.9.1 | `obj-cubed-iris-vulkan-integrated-mc26.2-iris1.11.2-beta.5.jar` | 공통 장면, 셰이더 교체, 화면 흔들림 |
| O1 | 26.1.2 | OptiFine K1 pre1 | pre1 전용 패처가 만든 개인용 `*-objcubed.jar` | 셰이더 off/on과 공통 장면 |
| O2 | 26.1.2 | OptiFine K1 pre2 | pre2 전용 패처가 만든 개인용 `*-objcubed.jar` | 셰이더 off/on과 공통 장면 |

F1–F5는 해당 행의 JAR 하나만 공식 Iris 및 Sodium과 함께 설치합니다. 동반 모드에서 블록 파괴 금은 바닐라 carrier 기준입니다.

OptiFine은 OpenGL subgroup 기능이 제공되는 GPU가 필요합니다. 공식 OptiFine 26.2가 나오기 전까지 O1/O2와 동등한 26.2 경로는 없습니다.

## Optional official OptiFine integration probe

OptiFine 자체는 저장소나 CI에 포함하지 않습니다. 공식 K1 pre1/pre2 JAR을 직접 받은 뒤 아래 명령으로 OptiFine의 실제 GLSL remap 출력과 obj-cubed 주입기의 연결을 재검증할 수 있습니다.

```text
./gradlew officialOptiFineProbe -PoptifineJar=/path/to/preview_OptiFine_26.1.2_HD_U_K1_pre2.jar
```
