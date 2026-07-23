# obj-cubed render compatibility

Godlander의 obj-cubed 리소스팩을 Iris, Sodium, OptiFine 셰이더 경로에서 디코딩하기 위한 호환 프로젝트입니다. 일반 OpenGL Iris는 Minecraft 26.1부터 26.2까지 같은 범용 동반 모드를 사용하고, Minecraft 26.2 Vulkan만 Iris가 포함된 전체 교체본을 사용합니다.

## 배포 파일

| 대상 | 배포 파일 | 상태 |
|---|---|---|
| Minecraft 26.1–26.2 + 공식 Iris 1.10.9–1.11.2 + Sodium 0.8.9–0.9.1 | `obj-cubed-iris-compat-mc26.1-26.2-iris-universal-*.jar` | 버전별 API·실제 Sodium shader 자동 검증 완료, 게임 내 최종 검증 필요 |
| Minecraft 26.2 + Vulkan + Sodium 0.9.1 | `obj-cubed-iris-vulkan-integrated-mc26.2-iris1.11.2-beta.5.jar` | 실제 게임·셰이더·obj-cubed 검증 완료 |
| 호환되는 OptiFine 빌드 | `obj-cubed-optifine-patcher-universal-*.jar` | 버전명·SHA 제한 없이 실제 클래스/메서드 구조 검사 |

## Fabric / Iris OpenGL 설치

1. 현재 Minecraft 버전에 맞는 공식 Iris와 Sodium을 설치합니다.
2. `obj-cubed-iris-compat-mc26.1-26.2-iris-universal-*.jar` 하나를 같은 `mods` 폴더에 넣습니다.
3. 과거 버전별 `obj-cubed-iris-compat-*.jar`와 26.2 Vulkan 통합본은 함께 넣지 않습니다.
4. 게임을 완전히 재시작하고 서버 리소스팩을 다시 받습니다.

범용 모드는 실행 중인 Minecraft 버전을 감지합니다. 26.1–26.1.2에서는 기존 Iris shadow/Sodium loader 경로를, 26.2에서는 새 Minecraft `ShaderManager` 경로만 선택합니다. Iris 1.10.9와 1.11.2의 서로 다른 `patchSodium` 시그니처도 같은 JAR 안에서 선택합니다.

동반 모드는 엔티티, 장비, 아이템 디스플레이, 블록과 Sodium 비셰이더 지형 경로를 주입합니다. 블록 파괴 금을 OBJ 표면에 투영하려면 확장 vertex format이 필요한 전체 Iris 포크가 필요하며, 동반 모드에서는 바닐라 carrier 기준 표시를 유지합니다.

## Fabric / Iris 26.2 Vulkan 설치

공식 Iris 대신 `obj-cubed-iris-vulkan-integrated-mc26.2-iris1.11.2-beta.5.jar`를 설치합니다. 공식 Iris 및 일반 Iris 범용 모드와 동시에 넣으면 안 됩니다. Sodium 0.9.1과 Minecraft 26.2가 필요합니다.

이 빌드는 Vulkan 셰이더팩, 셰이더 on/off와 월드 재접속, GUI/아이템/손/엔티티, obj-cubed 장비·디스플레이·블록 경로를 대상으로 한 포크입니다. 소스는 이 저장소의 `iris-26.2-vulkan` 브랜치에 있습니다.

## OptiFine 설치

OptiFine은 재배포가 허용되지 않으므로 패치된 OptiFine 자체를 배포하지 않습니다.

1. 사용할 공식 OptiFine JAR을 받습니다.
2. 범용 패처와 공식 OptiFine JAR을 같은 폴더에 둡니다.
3. 다음 명령을 실행합니다.

```text
java -jar obj-cubed-optifine-patcher-universal-0.3.1.jar preview_OptiFine_26.1.2_HD_U_K1_pre2.jar
```

4. 생성된 `*-objcubed.jar`를 평소 OptiFine 설치 파일처럼 직접 실행합니다.

패처는 버전명이나 SHA-256 화이트리스트를 사용하지 않습니다. 입력 JAR 안에서 호환되는 `ShadersCompatibility.remap(...)` 클래스와 정적 메서드 구조를 직접 확인합니다. 따라서 pre3나 이후 빌드도 같은 내부 계약을 유지하면 패처 수정 없이 바로 처리하며, 계약이 바뀐 빌드는 손상된 결과물을 만들지 않고 명확한 오류로 중단합니다. 입력 SHA-256은 감사용으로 출력만 합니다. 원본 JAR은 수정하지 않습니다. 생성된 OptiFine JAR은 개인 사용만 하고 재배포하지 마세요.

## 검증 범위

- 일반 Iris 범용 JAR은 Minecraft 26.1/26.1.1/26.1.2/26.2, Iris 1.10.9/1.11.2, Sodium 0.8.9/0.8.12/0.9.1 조합을 각각 컴파일합니다.
- 각 조합의 실제 Sodium terrain vertex shader에 obj-cubed 디코더가 주입되는지 검사합니다.
- 범용 JAR 내부에 구버전과 26.2 믹스인이 모두 존재하고 버전 선택 플러그인이 한쪽만 활성화하는지 검사합니다.
- OptiFine 패처는 SHA와 버전명 대신 호환 클래스, 정적 `remap` descriptor, `srg`/`notch` layout을 검사합니다.
- 실제 화면 검증 항목은 [TEST_MATRIX.md](TEST_MATRIX.md)에 있습니다.

## 빌드

Java 25가 필요합니다.

```text
./gradlew build
```

출력:

- `build/libs/obj-cubed-iris-compat-mc26.1-26.2-iris-universal-*.jar`
- `optifine-patcher/build/libs/obj-cubed-optifine-patcher-universal-*.jar`

## 라이선스와 출처

호환 코드의 기반은 [Iris](https://github.com/IrisShaders/Iris)와 [obj-cubed](https://github.com/Godlander/objmc)이며 LGPL-3.0-only로 배포합니다. OptiFine은 이 저장소나 릴리스에 포함되지 않습니다.
