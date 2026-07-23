# obj-cubed render compatibility

Godlander의 obj-cubed 리소스팩을 Iris, Sodium, OptiFine 셰이더 경로에서 디코딩하기 위한 호환 프로젝트입니다. 비-Vulkan 환경은 Minecraft와 Iris 버전에 맞춘 개별 호환 JAR을 사용하고, Minecraft 26.2 Vulkan만 Iris가 포함된 통합 JAR을 사용합니다.

## 지원 표

| Minecraft | 렌더러 | 배포 파일 | 상태 |
|---|---|---|---|
| 26.1 | Fabric + Iris 1.10.9 + Sodium 0.8.x | `obj-cubed-iris-compat-mc26.1-iris1.10.9-*.jar` | 자동 검증 완료, 게임 내 최종 검증 필요 |
| 26.1.1 | Fabric + Iris 1.10.9 + Sodium 0.8.x | `obj-cubed-iris-compat-mc26.1.1-iris1.10.9-*.jar` | 자동 검증 완료, 게임 내 최종 검증 필요 |
| 26.1.2 | Fabric + Iris 1.10.9 + Sodium 0.8.x | `obj-cubed-iris-compat-mc26.1.2-iris1.10.9-*.jar` | Sodium 0.8.9–0.8.12 자동 검증 완료, 게임 내 최종 검증 필요 |
| 26.1.2 | Fabric + Iris 1.11.2 + Sodium 0.9.x | `obj-cubed-iris-compat-mc26.1.2-iris1.11.2-*.jar` | 자동 검증 완료, 게임 내 최종 검증 필요 |
| 26.2 | Fabric + Sodium 0.9.1 + Vulkan | `iris-fabric-1.11.2-objcubed-vulkan-beta.5+mc26.2.jar` | 실제 게임·셰이더·obj-cubed 검증 완료 |
| 26.1.2 | OptiFine K1 pre1 | `obj-cubed-optifine-patcher-mc26.1.2-k1-pre1-*.jar` | pre1 전용 오프라인 패처, 자동 검증 완료 |
| 26.1.2 | OptiFine K1 pre2 | `obj-cubed-optifine-patcher-mc26.1.2-k1-pre2-*.jar` | pre2 전용 오프라인 패처, 자동 검증 완료 |
| 26.2 | OptiFine | 없음 | 공식 OptiFine 26.2가 아직 없어 제작 불가 |

비-Vulkan JAR은 서로 통합하지 않습니다. 파일명에 적힌 Minecraft와 Iris 버전이 현재 설치 조합과 정확히 일치해야 합니다.

## Fabric / Iris 26.1 설치

1. Minecraft 버전에 맞는 공식 Iris와 Sodium을 설치합니다.
2. Minecraft와 Iris 버전이 파일명에 정확히 적힌 `obj-cubed-iris-compat-*.jar` 하나를 같은 `mods` 폴더에 넣습니다.
3. 다른 Minecraft/Iris용 호환 JAR이나 기존 `iris-*-objcubed*.jar` 전체 교체본을 중복 설치하지 않습니다.
4. 게임을 완전히 재시작하고 서버 리소스팩을 다시 받습니다.

동반 모드는 엔티티, 장비, 아이템 디스플레이, 블록과 Sodium 비셰이더 지형 경로를 주입합니다. 블록 파괴 금을 OBJ 표면에 투영하려면 확장 vertex format이 필요한 전체 Iris 포크가 필요하며, 동반 모드에서는 바닐라 carrier 기준 표시를 유지합니다.

## Fabric / Iris 26.2 Vulkan 설치

공식 Iris 대신 릴리스의 `iris-fabric-1.11.2-objcubed-vulkan-beta.5+mc26.2.jar`를 설치합니다. 공식 Iris와 동시에 넣으면 안 됩니다. Sodium 0.9.1과 Minecraft 26.2가 필요합니다.

이 빌드는 Vulkan 셰이더팩, 셰이더 on/off와 월드 재접속, GUI/아이템/손/엔티티, obj-cubed 장비·디스플레이·블록 경로를 대상으로 한 포크입니다. 소스는 이 저장소의 `iris-26.2-vulkan` 브랜치에 있습니다.

## OptiFine 26.1.2 설치

OptiFine은 재배포가 허용되지 않으므로 패치된 OptiFine 자체를 배포하지 않습니다.

1. OptiFine 공식 사이트에서 `26.1.2 HD U K1 pre1` 또는 `pre2`를 받습니다.
2. 공식 파일이 pre1이면 pre1 패처를, pre2이면 pre2 패처를 같은 폴더에 둡니다.
3. 다음 명령을 실행합니다.

```text
java -jar obj-cubed-optifine-patcher-mc26.1.2-k1-pre2-0.2.0.jar preview_OptiFine_26.1.2_HD_U_K1_pre2.jar
```

4. 생성된 `*-objcubed.jar`를 평소 OptiFine 설치 파일처럼 직접 실행합니다.

각 패처는 입력 SHA-256을 확인하며 파일명에 적힌 공식 K1 빌드 하나만 허용합니다. 원본 JAR은 수정하지 않습니다. 생성된 OptiFine JAR은 개인 사용만 하고 재배포하지 마세요.

## 검증 범위

- Fabric 동반 모드는 Minecraft 26.1/26.1.1/26.1.2와 Iris 1.10.9/1.11.2별로 개별 빌드하며, 각 JAR 내부 의존성도 정확한 Minecraft·Iris 버전으로 제한합니다.
- entity/block/Sodium 주입기는 빌드마다 GLSL main 래핑, 입력 재지정, BLOCK/ENTITY 분리와 Sodium UV 보정을 자체 검사합니다.
- OptiFine 패처는 공식 K1 pre1/pre2 양쪽에서 `srg`와 `notch` 클래스 모두를 패치하며, 각 경로의 반환점 네 곳에 브리지 호출이 삽입되는 것을 확인했습니다.
- 실제 화면 검증 항목은 [TEST_MATRIX.md](TEST_MATRIX.md)에 있습니다.

## 빌드

Java 25가 필요합니다.

```text
./gradlew build
```

출력:

- `build/libs/obj-cubed-iris-compat-mc<버전>-iris<버전>-*.jar`
- `optifine-patcher/build/libs/obj-cubed-optifine-patcher-mc26.1.2-k1-<pre1|pre2>-*.jar`

## 라이선스와 출처

호환 코드의 기반은 [Iris](https://github.com/IrisShaders/Iris)와 [obj-cubed](https://github.com/Godlander/objmc)이며 LGPL-3.0-only로 배포합니다. OptiFine은 이 저장소나 릴리스에 포함되지 않습니다.
