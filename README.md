# obj-cubed render compatibility

Godlander의 obj-cubed 리소스팩을 Iris, Sodium, OptiFine 셰이더 경로에서 디코딩하기 위한 호환 프로젝트입니다. Minecraft 26.1 계열은 작은 Fabric 동반 모드를, Minecraft 26.2 Vulkan은 Iris 전체 포크를 사용합니다.

## 지원 표

| Minecraft | 렌더러 | 배포 파일 | 상태 |
|---|---|---|---|
| 26.1–26.1.2 | Fabric + Iris 1.10.9–1.11.2 + Sodium 0.8.9–0.9.1 | `obj-cubed-iris-compat-26.1-universal-*.jar` | 컴파일 및 양쪽 Iris API 검증 완료, 게임 내 최종 검증 필요 |
| 26.1–26.1.2 | Fabric + Sodium 0.8.9, Iris 전체 교체 | `iris-fabric-1.10.9-objcubed.14+mc26.1.1.jar` | 실제 게임·셰이더·obj-cubed·블록 파괴 검증 완료 |
| 26.2 | Fabric + Sodium 0.9.1 + Vulkan | `iris-fabric-1.11.2-objcubed-vulkan-beta.5+mc26.2.jar` | 실제 게임·셰이더·obj-cubed 검증 완료 |
| 26.1.2 | OptiFine K1 pre1/pre2 | `obj-cubed-optifine-patcher-*.jar` | 오프라인 패치 및 바이트코드 검증 완료, 게임 내 최종 검증 필요 |
| 26.2 | OptiFine | 없음 | 공식 OptiFine 26.2가 아직 없어 제작 불가 |

`26.1-universal`은 Minecraft 26.1 계열만 한 JAR로 묶습니다. 26.2 Vulkan은 Iris의 렌더 파이프라인 자체를 바꿔야 하므로 같은 동반 JAR에 안전하게 합칠 수 없습니다.

## Fabric / Iris 26.1 설치

1. Minecraft 버전에 맞는 공식 Iris와 Sodium을 설치합니다.
2. `obj-cubed-iris-compat-26.1-universal-*.jar`를 같은 `mods` 폴더에 넣습니다.
3. 기존 `iris-*-objcubed*.jar` 전체 교체본이 있다면 중복 설치하지 않습니다.
4. 게임을 완전히 재시작하고 서버 리소스팩을 다시 받습니다.

동반 모드는 엔티티, 장비, 아이템 디스플레이, 블록과 Sodium 비셰이더 지형 경로를 주입합니다. 블록 파괴 금을 OBJ 표면에 투영하려면 확장 vertex format이 필요한 전체 Iris 포크가 필요하며, 동반 모드에서는 바닐라 carrier 기준 표시를 유지합니다.

블록 파괴 금까지 필요한 경우 공식 Iris 대신 `iris-fabric-1.10.9-objcubed.14+mc26.1.1.jar`를 사용하세요. 이 파일과 동반 모드를 동시에 설치하면 안 됩니다. 전체 포크 소스는 `iris-26.1-full` 브랜치에 있습니다.

## Fabric / Iris 26.2 Vulkan 설치

공식 Iris 대신 릴리스의 `iris-fabric-1.11.2-objcubed-vulkan-beta.5+mc26.2.jar`를 설치합니다. 공식 Iris와 동시에 넣으면 안 됩니다. Sodium 0.9.1과 Minecraft 26.2가 필요합니다.

이 빌드는 Vulkan 셰이더팩, 셰이더 on/off와 월드 재접속, GUI/아이템/손/엔티티, obj-cubed 장비·디스플레이·블록 경로를 대상으로 한 포크입니다. 소스는 이 저장소의 `iris-26.2-vulkan` 브랜치에 있습니다.

## OptiFine 26.1.2 설치

OptiFine은 재배포가 허용되지 않으므로 패치된 OptiFine 자체를 배포하지 않습니다.

1. OptiFine 공식 사이트에서 `26.1.2 HD U K1 pre1` 또는 `pre2`를 받습니다.
2. 패처와 공식 OptiFine JAR을 같은 폴더에 둡니다.
3. 다음 명령을 실행합니다.

```text
java -jar obj-cubed-optifine-patcher-0.1.0.jar preview_OptiFine_26.1.2_HD_U_K1_pre2.jar
```

4. 생성된 `*-objcubed.jar`를 평소 OptiFine 설치 파일처럼 직접 실행합니다.

패처는 입력 SHA-256을 확인하며 공식 K1 pre1/pre2만 허용합니다. 원본 JAR은 수정하지 않습니다. 생성된 OptiFine JAR은 개인 사용만 하고 재배포하지 마세요.

## 빌드

Java 25가 필요합니다.

```text
./gradlew build
```

출력:

- `build/libs/obj-cubed-iris-compat-26.1-universal-*.jar`
- `optifine-patcher/build/libs/obj-cubed-optifine-patcher-*.jar`

## 라이선스와 출처

호환 코드의 기반은 [Iris](https://github.com/IrisShaders/Iris)와 [obj-cubed](https://github.com/Godlander/objmc)이며 LGPL-3.0-only로 배포합니다. OptiFine은 이 저장소나 릴리스에 포함되지 않습니다.
