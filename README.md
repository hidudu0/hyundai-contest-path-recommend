# ⛽ 길목 : 현재 경로 기반 주유소 추천

### 거리 대신, 들렀다 가면 늘어나는 시간으로 고릅니다

**길목은 연료가 모자라면 가는 길에서 가장 덜 돌아가는 주유소를 Pleos 내비 경유지에 넣습니다.**

<!-- 맨 위 필름(Higgsfield) 자리: docs/img/explainer.webp 가 생기면 여기에 넣는다 -->

<img src="docs/img/at_a_glance.svg" width="100%" alt="한눈에: ① 연료·연비·탱크 용량을 차에서 읽어 안전하게 갈 수 있는 거리 계산 → ② 가는 길 주유소마다 들렀다 가면 몇 분 늦어지는지 계산 → ③ 경고 단계는 추천과 음성 안내, 위험 단계는 내비 경유지에 자동 추가. 측정 중앙값: 길목 우회 +0.2분(직선 최단 주유소 +7.1분), 최저가 모드 1회 2,882원 절약">

**[APK](apk/CRAVE_Gilmok_release.apk) · [바로 실행](#7-실행-방법)**

<sub>현대자동차그룹 차량용 앱 개발 공모전 (Pleos Connect) · 팀 CRAVE</sub>

[1 왜 차 안에서](#1-왜-차-안에서) · [2 핵심 아이디어](#2-핵심-아이디어) · [3 동작 구조](#3-동작-구조) · [4 실제 화면](#4-실제-화면) · [5 확장](#5-확장-dt음식점) · [6 사업성](#6-사업성) · [7 실행](#7-실행-방법) · [8 부록](#8-부록)

---

## 1. 왜 차 안에서

<img src="docs/img/phone_vs_car.svg" width="100%" alt="휴대폰 앱과 길목 비교: 남은 연료(별도 장치 없이 얻기 어려움 / 차에서 바로 읽음), 주유 시점(운전자가 판단 / 남은 연료로 판단), 주유소 고르기(운전자가 검색·비교 / 늘어나는 시간으로 자동 선택), 경유지 넣기(화면 조작이나 음성 요청 / 내비에 바로 추가 + 음성 안내)">

<img src="docs/img/competitors.png" width="100%" alt="같은 주유소 안내, 기준이 다름 (휴대폰 앱 실제 화면): 네이버지도는 경로 주변 주유소를 가격순 목록으로, 카카오내비는 경로상 최저가 1곳을 추천하고 누르면 경유지, 티맵은 말로 요청하면 추천 목록을 띄우고 말로 골라 경유지. 세 앱 모두 들렀다 가면 몇 분 늦어지는지 보여 주지 않음. 길목은 가는 길 +0분 주유소를 자동 경유지로">

### 다른 서비스와 비교

<img src="docs/img/service_compare.svg" width="100%" alt="기능 비교(차 신호, 주유 시점, 가는 길 후보, 늘어나는 시간 = 고르는 기준, 자동 경유지 = 조치, 상태): 네이버지도(휴대폰, 경로 주변 가격순)·카카오내비(휴대폰, 경로상 최저가)는 가는 길 후보만, 티맵 음성 안내(에이닷, 2025)는 가는 길 후보와 말로 요청하는 경유지(일부), 티맵 주행중 어디갈까(DT·음식점, 2026)는 가는 길 후보만, 현대차 순정 내비(ccNC, 주변 검색·유가)는 공개 자료에서 확인 못 함, 현대차 EV 경로 플래너(전기차 전용, 2025)는 차 신호·충전 시점·가는 길 후보와 충전 장소 제안(일부), Google 지도(해외)는 가는 길 후보와 늘어나는 시간, 네이버 오토앱(Pleos Connect, 2026)은 차 신호와 연료 부족 시 추천(일부), 길목(시제품)은 다섯 가지 모두 · 겹침: 네이버 오토앱도 차에서 남은 연료를 읽고 주유소를 추천, Google 지도는 늘어나는 시간을, EV 경로 플래너는 배터리로 충전 장소를 보여 줌 · 다름: 길목은 연료차의 남은 거리로 주유 시점을 정하고 가는 길 주유소를 늘어나는 시간으로 골라 위험 단계면 경유지를 자동으로 넣음">

<sub>출처: [네이버 보도자료](https://navercorp.com/media/pressReleasesDetail?seq=10034430) · [Google 지도 블로그](https://maps.googleblog.com/2016/02/easily-add-pit-stops-to-your-trips-all.html) · [현대차 EV 경로 플래너(뉴시스)](https://mobile.newsis.com/view/NISX20250421_0003146983) · [현대차 내비 매뉴얼](https://ownersmanual.hyundai.com/ivi/STD_GEN5/AVNT/KOR/Korean/006_Navigation_tpeg.html) · [티맵 음성 경유지](https://v.daum.net/v/20250917144211317) · [티맵 주행중 어디갈까](https://m.ceoscoredaily.com/page/view/2026051914561180669) · 네이버지도·카카오내비·티맵 화면 2026-11-11</sub>

## 2. 핵심 아이디어

<img src="docs/img/detour_idea.svg" width="100%" alt="직선으로 가까운 곳이 아니라 덜 늦어지는 곳: 같은 휴게소도 내 방향은 +1분, 반대 방향은 IC로 나갔다 와야 해서 +12분">

<img src="docs/img/naver_compare.jpg" width="100%" alt="네이버지도 실제 화면 비교: 경부선 위 주행 중 위치에서 대구시청까지, 직선 143 m인 삼랑진농협주유소를 경유하면 1시간 16분 87 km(고속도로에서 나가 되돌아옴), 길목이 고른 HC주유소를 경유하면 58분 69 km, 18분 18 km 차이">

<sub>측정 근거 → [docs/savings.md](docs/savings.md)</sub>

## 3. 동작 구조

<img src="docs/img/stages.svg" width="100%" alt="남은 거리로 단계를 정함. SafeRange = 남은 연료 × 연비 × 0.8, ΔT = 들렀다 가면 늘어나는 시간, ΔD = 늘어나는 거리. 여유는 조용히 대기, 경고(SafeRange 150 km 이하)는 추천과 음성 안내, 위험(50 km 이하)은 자동 경유지와 음성. 연료 바는 꽉 찬 상태에서 줄어들며 초록·노랑·빨강. 모드: 최소 우회 ΔT 3분·ΔD 2 km, 최저가 ΔT 10분·ΔD 3 km, DT·음식점은 ΔT 순">

<img src="docs/img/architecture.svg" width="100%" alt="구조도: 읽기(Pleos Vehicle SDK registerFuelPercent·registerCurrentFuelEconomy, AAOS INFO_FUEL_CAPACITY·INFO_FUEL_TYPE, Pleos NaviHelper getCurrentLocationInfo·getDestinationInfo) → 계산(순수 Kotlin core: SafeRange, 단계, 코리도, ΔT·ΔD, 선택, 내장 주유소·DT·음식점 데이터와 OSRM) → 행동(NaviHelper addWaypoint·requestRouteWithWaypoints, TextToSpeech ON_DEVICE, Leaflet 지도)">

<sub>ΔT·ΔD 한도는 실제 경로 8개로 정함 → [docs/calibration.md](docs/calibration.md)</sub>

## 4. 실제 화면

<img src="docs/img/demo.webp" width="100%" alt="Pleos 에뮬레이터 실제 녹화 17초: 경고 단계 추천 → 경유지 추가 → 주유 후 초록 → 위험 단계 자동 경유지">

**경고 → 버튼 한 번 → 내비 경유지 · 위험이면 자동** · Pleos Connect Emulator v2.1.0

## 5. 확장: DT·음식점

<img src="docs/img/demo_food.webp" width="100%" alt="DT·음식점 탭 실제 녹화 9초: 탭 한 번에 가는 길 DT·음식점이 덜 돌아가는 순서(이천휴게소 +0분, 페리카나 Chicken +0분)로 뜨고, 경유지 추가 버튼 한 번에 내비 경유지">

**"배고픈데 들렀다 갈까?" → 탭 한 번 → 버튼 한 번** · 거리 순이 아니라 우회 시간 순

<img src="docs/img/extension_map.svg" width="100%" alt="공통 엔진(경로, 가는 길 후보 수집, ΔT·ΔD 계산, 경유지 추가) 위에 구현됨: 주유소 6,904곳, DT·휴게소 983곳, 음식점·카페 브랜드 매장 5,819곳. 확장: 전기차 충전소(SafeRange를 배터리·전비로), 주문·픽업(도착 시간에 맞춰 미리 주문)">

## 6. 사업성

<img src="docs/img/business_flow.png" width="100%" alt="B2B 구조: 팀 CRAVE가 길목 엔진(남은 연료 → 단계, 가는 길 ΔT 계산, 경유지 자동 설정)을 공급하고 현대차그룹이 Pleos Connect 내비 기본 기능으로 탑재. CRAVE → 현대차그룹: Pleos 앱 → 내비 기본 기능, 앱 마켓 입점 후 엔진 라이선스(협의). 현대차그룹 → CRAVE: 차량 신호·내비 경유지 연동·배포(Vehicle SDK, NaviHelper, Pleos App Market). 운전자는 무료(1대 연 1.4시간, 3.5만원 절약, 가정), 주유소·DT는 인카페이먼트 결제 연동 시 리터당 수수료(가정, 2030년 연 23억원 시나리오)">

<img src="docs/img/target_mau.png" width="100%" alt="예상 MAU 상한: 국내 등록 차량 2,667.6만 대 → 연료차 2,352.4만 대(88%) → 2030년 Pleos 탑재 국내 연료차 242만 대(가정) → MAU 209만(그 달 1회 이상 이용 86.5%, 월 주유 2.8회 + DT 0.3회) · 지금 설치 가능한 차 5.3만 대">

<img src="docs/img/retention.png" width="100%" alt="Retention: 1개월 90%, 3개월 86%, 6개월 81%, 12개월 72%, 24개월 56% · 그 달 다시 이용 91%, DAU/MAU 약 12%, 2030년 연 주유 거래액 4.2조원">

<sub>MAU·Retention은 출시 전 모델 추정치이며 출시 후 실측값으로 바꿉니다. (가정)은 공개 자료가 없어 팀이 정한 값입니다.</sub>

<details>
<summary>수익 모델과 사업성 지표</summary>

사용자에게는 무료로 제공하고 광고비로 추천 순서를 바꾸지 않습니다.

| 지표 | 값 | 기준 |
|---|---|---|
| 차량 1대 연간 가치 | 1.4시간, 3.5만원 | 월 1회 이용(가정) |
| 2030년 연간 절약 총량 | 약 340만 시간, 약 840억원 | 242만대(가정), 월 1회 이용(가정) |
| 연간 주유 거래액 | 1대 174만원, 2030년 4.2조원 | 연 11,242 km, 12 km/L(앱 기본값), 1,855원/L |
| 제휴 수익 시나리오 | 1대 연 937원, 2030년 23억원, 국내 전체 220억원 | 결제 연동 10%(가정), 리터당 10원(가정) |
| DAU/MAU | 약 12% | 활성 차량당 월 3.6회 이용 |

</details>

<details>
<summary>타깃 고객과 시장 규모 표</summary>

**차를 쓰는 모든 운전자**입니다. 주유가 필요한 차부터 지원하고, 충전소를 지원하면 전기·LPG 차로 넓힙니다. 먼 길일수록 일찍 알려 줍니다.

| 목적지까지 거리 | 추천이 시작되는 남은 연료 (탱크 50 L, 연비 12 km/L) |
|---|---|
| 20 km | 2.1 L (4%) |
| 100 km | 10.4 L (21%) |
| 150 km 이상 | 15.6 L (31%) |

| 시장 | 규모 | 기준 |
|---|---|---|
| 국내 등록 차량 | 2,667.6만대 | 2026년 6월 말 |
| 휘발유·경유·하이브리드 | 2,352.4만대 (88%) | 2026년 6월 말 |
| 지금 설치 가능한 차 | 최대 약 5.3만대 | 그랜저 6–10월 + 아반떼 9–10월 국내 판매 합 |
| 2030년 국내 탑재 연료차 | 약 242만대 | 그룹 목표 2,000만대 × 국내 판매 비중 17.3% × 연료차 70%(가정) |

| 시점 | 탑재 연료차 | MAU 상한 | 월 이용 횟수 상한 |
|---|---|---|---|
| 2028년 말 (그랜저·아반떼) | 약 43만대 (가정) | 37만 | 135만회 |
| 2030년 말 (Pleos 탑재 전체) | 약 242만대 (가정) | 209만 | 759만회 |
| 국내 연료차 전체 | 2,352만대 | 2,034만 | 7,370만회 |

Retention은 운행 빈도와 주유·DT 횟수로 그 달 다시 이용할 확률 91%를 구하고, 월 이탈 2%(가정)를 넣었습니다.

</details>

### 사용자 확보와 확대 계획

<img src="docs/img/roadmap.svg" width="100%" alt="로드맵(연도는 가정): 지금 프로토타입, 2027 상반기 상용 준비(시동 시 자동 감시, 실시간 가격·교통), 2027 하반기 Pleos App Market, 2028 네이버지도·카카오내비·티맵 제휴, 2029 충전소 추가와 인카페이먼트 결제, 2030 내비 기본 기능. 최종 목표: 앱을 열지 않아도 내비가 연료 부족을 판단해 최적 주유소 경유 경로를 자동으로 설정">

앱 마켓으로 탑재 차량 운전자를 먼저 확보하고, 적용 차종 확대와 내비 제휴로 스마트폰 내비 이용자까지 넓힙니다. 네이버지도와 카카오내비는 이미 플레오스 커넥트에 입점해 있어 먼저 제안하고, 스마트폰 내비 점유율 1위인 티맵(71.9%, 네이버지도 14.5%, 카카오내비 9.2%)까지 넓힙니다. 앱 마켓 입점과 제휴는 심사·협의가 필요하며 확정된 사항이 아닙니다.

<details>
<summary>계산 근거와 출처</summary>

- **주유:** 자가용 승용차 월평균 3.9회, 주평균 운행 5.5일 ([KTDB 자동차이용실태조사 2012](https://www.ktdb.go.kr/site/www/dowonload/car/2012_survey01.pdf)). 운행일당 16.3% × 평균 운행 17.4일 = 월 2.8회. 1회 주유량 33.0 L로 소비량을 환산한 값은 월 2.4회입니다.
- **운행 빈도:** [나우앤서베이 2020, 1,505명](https://www.datasom.co.kr/news/articleView.html?idxno=104444). 그 달 이용 확률 = 1 − (1 − 운행일당 이용 확률 18.05%)^월 운행일.
- **DT:** 공식 통계가 없어 역산했습니다. [스타벅스 2025년 매출 3조2,380억원, 매장 2,114곳](https://v.daum.net/v/btFjkI4qkz) ÷ 객단가 8,000원(가정) × DT 주문 40%(가정) = 매장당 일 210대. [스타벅스 DT 500곳](https://ceoscoredaily.com/page/view/2024122015353652370) + [맥드라이브 300곳](https://www.hankyung.com/article/202507211177i)에 적용하고, 다른 브랜드 30%(가정)를 더해 월 664만대 ÷ 2,352만대 = 0.3회.
- **시장:** [등록 대수](https://www.mt.co.kr/estate/2026/07/30/2026072915093667802), [플레오스 커넥트 2,000만대](https://zdnet.co.kr/view/?no=20260430015244), [앱 마켓 18종](https://www.fnnews.com/news/202609070949054267), [주행거리](https://www.fnnews.com/news/202606291005221113)
- **판매:** 현대차 [6월](https://www.hyundaimotorgroup.com/ko/news/hyundai-motor-company-june-2026-sales), [7월](https://www.hyundaimotorgroup.com/ko/news/hyundai-motor-company-july-2026-sales), [8월](https://www.hyundaimotorgroup.com/ko/news/hyundai-motor-company-august-2026-sales), [9월](https://www.hyundaimotorgroup.com/ko/news/hyundai-motor-company-september-2026-sales), [10월](https://www.hyundaimotorgroup.com/ko/news/hyundai-motor-company-october-2026-sales), [현대차 2025](https://www.hyundaimotorgroup.com/ko/news/hyundai-motor-company-sales-4138180-units-2025), [기아 2025](https://www.hyundaimotorgroup.com/ko/news/kia-2025-sales-3135803-units)
- **내비:** [순정 내비 68%](https://www.consumerinsight.co.kr/autoinsight/abview?no=3294&id=pr4_list), [앱 점유율](http://www.breaknews.com/1248191), [지도 앱 MAU](https://www.igaworks.com/blog/outstanding-0618), [네이버](https://www.kukinews.com/article/view/kuk202606260026), [티맵](https://www.hankyung.com/article/202509179328i), [카카오내비](https://www.inews24.com/view/1963419)
- **기타:** [Retention(Braze, AppsFlyer 재인용)](https://www.braze.com/resources/articles/mobile-app-retention.md), [Stream(OneSignal 재인용)](https://getstream.io/blog/app-retention-guide/), [인카페이먼트](https://www.genesis.com/kr/ko/members/my-genesis-app/mobility-services/in-car-payment.html), [OSRM 정책](https://github.com/Project-OSRM/osrm-backend/wiki/Demo-server)

</details>

## 7. 실행 방법

**APK:** [apk/CRAVE_Gilmok_release.apk](apk/CRAVE_Gilmok_release.apk)

```bash
adb install -r apk/CRAVE_Gilmok_release.apk
adb shell am start -n ai.gilmok.app/.MainActivity
```

왼쪽 아래 버튼으로 신호를 고릅니다.

| 버튼 | 동작 |
|---|---|
| 시연 · 여유 | 판교에서 부산시청까지 1초에 1 km. 연료 30%로 경고 단계 추천, 그대로 두면 위험 단계 자동 경유지 |
| 시연 · 긴급 | 연료 9%로 바로 위험 단계, 자동 경유지 + 음성 |
| 실차 | Vehicle SDK와 내비 신호 사용. 내비에서 목적지를 정하면 계산 |

**테스트 환경:** Pleos Connect Emulator v2.1.0 (API 34, arm64, 2560×1440) · macOS Apple Silicon · Android SDK 34 · AGP 9.4.1 · Gradle 9.8.2 · Kotlin 2.4 · Pleos SDK Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1

**필요한 설정**
- 경로와 지도 타일에 네트워크가 필요합니다. 주유소 목록과 가격은 앱에 들어 있습니다.
- 시연이 "신호를 기다리는 중"에서 멈추면 `emulator -avd <AVD 이름> -dns-server 8.8.8.8`로 다시 시작합니다.
- 에뮬레이터 차량은 전기차라 실차 모드의 연료는 25% 가정값으로 표시합니다.

## 8. 부록

### 한계

- 앱을 직접 열어야 동작합니다. 시동 시 자동 시작은 아직 없습니다.
- 기어·속도 신호는 아직 쓰지 않아 주행·정차를 구분하지 않습니다.
- 음식점은 OSM에 브랜드가 표시된 매장 5,819곳만 넣었고(이름 있는 곳 약 94,000곳 중), 영업시간은 확인하지 않습니다.
- 오피넷 주유소 6,312곳은 위치를 못 붙여 빠졌고, 공식 좌표가 없는 곳은 매칭 오차가 남아 있습니다([docs/stations.md](docs/stations.md)).
- 가격은 내려받은 날 기준이고, ΔT는 실시간 교통을 반영하지 않습니다(OSRM).
- 실제 차량이 아닌 Pleos 에뮬레이터에서 확인했습니다.

<details>
<summary>소스 빌드와 데이터 갱신</summary>

```bash
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :core:test :app:assembleDebug     # 엔진 단위 테스트 + 디버그 APK
./gradlew :core:calibrate                    # (네트워크) 한도 값 근거 → docs/calibration.md
./gradlew :core:savings                      # (네트워크) 절약 측정 → docs/savings.md
```

release 빌드는 `android/local.properties`에 서명 정보를 넣고 `./gradlew :app:assembleRelease` (키·비밀번호는 커밋하지 않음):

```
release.storeFile=/path/to/release.jks
release.storePassword=…
release.keyAlias=…
release.keyPassword=…
```

주유소 데이터 다시 만들기 (OpenStreetMap 한국 추출본 약 290 MB):

```bash
curl -LO https://download.geofabrik.de/asia/south-korea-latest.osm.pbf
brew install osmium-tool && pip install shapely
python3 tools/build_places.py data/opinet/사업자_기본정보_주유소_20261104.csv data/opinet/현재_판매가격_주유소_20261104.csv south-korea-latest.osm.pbf
```

공식 좌표는 `android/local.properties`에 `opinet.key=…`를 넣고 받습니다. 키가 300회 호출 뒤 응답을 멈춰서, 매일 다시 실행하면 멈춘 곳부터 이어 받습니다.

```bash
pip install pyproj
python3 tools/fetch_opinet.py data/opinet/사업자_기본정보_주유소_20261104.csv
```

</details>

<details>
<summary>폴더 구조</summary>

```
android/core/                       순수 Kotlin 엔진 + 단위 테스트 + calibrate·savings
android/app/                        Pleos 앱: 차량 신호, 내비, 음성, 시연 주행, 화면, 지도
android/app/src/main/assets/        places.csv (주유소·DT·음식점), map.html (Leaflet 지도)
data/opinet/                        오피넷 원본 CSV, API 공식 좌표
tools/                              build_places.py, fetch_opinet.py
docs/                               calibration.md, savings.md, stations.md, 그림
apk/CRAVE_Gilmok_release.apk      제출용 release APK
```

</details>

### 데이터 출처

| 무엇 | 출처 | 규모 |
|---|---|---|
| 주유소 정보·가격·공식 좌표 | [오피넷](https://www.opinet.co.kr/user/opdown/opDownload.do), 한국석유공사 (저작권은 한국석유공사에 있으며, 수익 목적 이용은 사전 협의 필요) | 전국 10,315곳, 가격 기준일 2026-11-04 |
| 주유소 위치 | 오피넷 API 공식 좌표 296곳 + 주소 매칭 ([방식과 정확도](docs/stations.md)) | 오피넷 4,003곳 + OSM 주유소 2,901곳 |
| 지도·도로·DT·음식점 | © OpenStreetMap contributors (ODbL) | DT·휴게소 983곳, 브랜드 음식점·카페 5,819곳 |
| 경로 | OSRM (Project OSRM 공개 서버) | 실시간 |
| 지도 라이브러리 | Leaflet 1.9.4 (BSD-2-Clause, `android/app/src/main/assets/leaflet/LICENSE`) | |
