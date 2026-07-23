# Changelog

## 0.3.0-rc.1

- Minecraft 26.1–26.2 및 Iris 1.10.9–1.11.2용 일반 OpenGL 호환 모드를 단일 범용 JAR로 통합
- Minecraft 버전 감지 Mixin plugin으로 26.1 계열과 26.2 renderer hook을 선택 적용
- OptiFine 26.1.2 K1 pre1/pre2 패처를 입력 SHA 자동 감지 단일 JAR로 통합
- 공개 배포 구성을 일반 Iris 범용, 26.2 Vulkan 통합, OptiFine 자동 패처 세 JAR로 축소

## 0.2.1-rc.1

- 누락됐던 Minecraft 26.2 일반 OpenGL Iris 1.11.2/Sodium 0.9.1 개별 호환 JAR 추가
- Minecraft 26.2의 새 `ShaderManager` 리소스 로딩 경로에 Sodium terrain obj-cubed 디코더 주입
- 26.2 JAR에서 26.1 전용 `ShadowRenderer` 및 구 Sodium `ShaderLoader` 믹스인을 제외
- Vulkan 통합본 파일명을 일반 Iris 패치 모드와 명확히 구분되도록 변경

## 0.2.0-rc.1

- 비-Vulkan `26.1-universal` 배포를 제거하고 Minecraft 26.1/26.1.1/26.1.2와 Iris 1.10.9/1.11.2별 JAR 네 개로 분리
- 각 Fabric JAR의 `minecraft`, `iris`, `sodium` 의존성 범위를 해당 파일 조합으로 제한
- OptiFine 26.1.2 K1 pre1과 pre2 패처를 별도 JAR로 분리하고 각 공식 입력 해시만 허용
- Minecraft 26.2 Vulkan 통합 Iris JAR은 검증된 beta.5를 그대로 유지

## 0.1.1-rc.1

- 동반 모드의 obj-cubed 원본 텍스처 sampler를 OpenGL 기본값에 의존하지 않고 Iris albedo texture unit에 명시적으로 바인딩
- Iris 1.10.9/Sodium 0.8.9와 Iris 1.11.2/Sodium 0.9.1 CI 매트릭스 추가
- Iris 1.10.9/1.11.2의 서로 다른 `patchSodium` 시그니처를 한 동반 JAR에서 선택적으로 지원
- entity/block/Sodium GLSL 주입 자체 검사 추가
- OptiFine K1 pre1/pre2의 srg/notch 실제 JAR 바이트코드 재검증
- LGPL-3.0 전체 라이선스 본문 포함

## 0.1.0-rc.1

- Minecraft 26.1–26.1.2용 단일 Iris/Sodium Fabric 동반 모드 추가
- 검증된 Minecraft 26.1 Iris 1.10.9 전체 교체 빌드와 소스 브랜치 추가
- Iris 1.10.9/Sodium 0.8.9 및 Iris 1.11.2/Sodium 0.9.1 양쪽 컴파일 검증
- entity/equipment/display/block shader decoder 주입
- Sodium 비셰이더 terrain decoder 주입
- Iris shadow outline buffer 누적 방지
- OptiFine 26.1.2 K1 pre1/pre2 오프라인 패처 추가
- 검증된 Minecraft 26.2 Vulkan Iris beta.5 교체 빌드 릴리스 구성
