# 길목 필름 콘티 (Higgsfield)

README 맨 위 필름(약 25초)과 확장 필름(약 12초). 멀미 케어 필름과 같은 방식: 차 안·도로 장면은 AI 생성, 센터 화면 속 앱은 Pleos 에뮬레이터 실제 녹화를 합성, 음성 문구는 앱이 실제로 말하는 문장을 자막으로.

## 만드는 순서

1. Claude chat(또는 Cowork)에서 Higgsfield 연결 후 아래 "Claude chat에 붙여 넣을 요청"을 그대로 보냄. 무제한 생성은 Claude Code에서는 막혀 있고 Claude chat·Cowork에서만 됨(2026-11-11 확인).
2. 생성이 끝나면 같은 계정이라 Claude Code에서 `higgsfield generate list --json`으로 결과를 받아 합성함(센터 화면에 녹화 합성, 자막, 이어 붙이기 → `docs/img/explainer.webp`, `docs/img/explainer_food.webp`).
3. 센터 화면에 들어갈 녹화(저장소 밖, gitignore): `시연영상/CRAVE_Gilmok_screen_warning.mp4`(경고·경유지 추가), `_danger.mp4`(위험 자동 경유지), `_food.mp4`(DT·음식점).

## 공통

- 같은 차: 진회색 최신 SUV, 로고·엠블럼 없음
- 같은 운전자: 30대 한국인 남성, 남색 재킷 / 동승자: 30대 한국인 여성, 크림색 니트
- 해 질 녘 한국 고속도로, 따뜻한 빛, 영화 같은 색감, 글자·로고 없음
- 이미지: Nano Banana Pro, 16:9, 2k / 영상: Seedance 2.0, 시작 이미지 = 해당 이미지, 5초, 720p, 소리 없음

## 메인 필름 (경고등 → 버튼 한 번 → 가던 길 그대로)

| # | 길이 | 장면 | 자막 |
|---|---|---|---|
| 1 | 3초 | 해 질 녘 고속도로를 달리는 SUV, 항공샷 | |
| 2 | 2.5초 | 계기판 주유 경고등이 켜짐 | |
| 3 | 2.5초 | 운전자가 계기판을 보고 걱정 | "기름이 없네… 제일 가까운 주유소 찍어야 하나?" |
| 4 | 3초 | SUV가 IC 램프로 빠져나가 반대 방향으로 돌아감 (차가운 톤) | 가장 가까운 주유소 = 고속도로 밖으로 나갔다 오기 · +18분 |
| 5 | 4초 | 뒷좌석에서 본 대시보드, 센터 화면 = 길목 경고 단계 추천 (녹화 합성) | 🔈 가는 길에 S-OIL 버드에너지 광주제일이 있어요. 거의 돌아가지 않아요. |
| 6 | 2초 | 손가락이 센터 화면의 초록 버튼을 누름 | 버튼 한 번 → 내비 경유지 |
| 7 | 3초 | SUV가 본선에서 바로 휴게소 주유소로 들어감 | 가던 길 그대로 · +0분 |
| 8 | 2.5초 | 주유 중, 커피를 든 운전자가 여유롭게 웃음 | |
| 9 | 3초 | 석양 속으로 달려가는 SUV + 제목 | **길목** · 가는 길에 있는 주유소를, 내비에 · 팀 CRAVE · Pleos Connect |

## 확장 필름 (배고픈데 들렀다 갈까?)

| # | 길이 | 장면 | 자막 |
|---|---|---|---|
| E1 | 3초 | 조수석 동승자가 배를 만지며 운전자에게 말함 | "배고픈데 뭐 먹고 갈까?" |
| E2 | 4초 | 센터 화면 = DT · 음식점 탭, +0분 이천휴게소 → 경유지 추가 (녹화 합성, 5번과 같은 구도) | 가는 길 DT · 음식점, 덜 돌아가는 순서 |
| E3 | 3초 | 드라이브스루 창구에서 종이봉투를 받음 | 버튼 한 번 · +0분 |
| E4 | 2초 | 동승자가 감자튀김을 먹으며 웃음 | |

## Claude chat에 붙여 넣을 요청

```
Higgsfield로 영상 소스를 만들어 줘. 내 무제한 생성(use_unlim: true)으로만 하고, 크레딧은 쓰지 마.
1단계: 아래 이미지 12장을 Nano Banana Pro(nano_banana_pro), 16:9, 2k로 각각 1장씩 생성.
2단계: 각 이미지를 시작 이미지로 Seedance 2.0(seedance_2_0), 5초, 720p, generate_audio false로 영상 생성. 영상 프롬프트는 각 항목의 "움직임".
결과는 번호를 붙여 목록으로 보여 줘.

1. 이미지: Cinematic film still. Aerial drone shot at golden-hour dusk of a modern Korean expressway through green hills, one dark grey modern SUV (no logos, no badges) driving in the right lane, warm low sun, light haze, teal-orange grade, photorealistic. No text.
   움직임: drone slowly follows the SUV forward along the expressway, smooth, steady.
2. 이미지: Cinematic close-up inside a modern car at dusk. Digital instrument cluster behind the steering wheel, one amber low-fuel warning icon (fuel pump symbol) glowing, fuel gauge near empty, driver's hands on the wheel in soft focus, warm sunset light, shallow depth of field, photorealistic. No readable text, no logos.
   움직임: the amber fuel warning icon blinks on and glows, very slight handheld motion, road light flickers across the cluster.
3. 이미지: Cinematic film still. Korean man in his 30s wearing a navy jacket driving a modern car at dusk, seen from the passenger side, he glances down at the dashboard with a slightly worried expression, highway outside, warm sunset light, shallow depth of field, photorealistic. No text, no logos.
   움직임: he glances down at the dashboard, frowns slightly and sighs, scenery passes outside, static camera.
4. 이미지: Cinematic high-angle film still. A dark grey modern SUV (no logos) leaving a Korean expressway onto a long looping interchange off-ramp, heading back in the opposite direction toward a distant town, dusk, slightly desaturated cool tone, photorealistic. No text.
   움직임: the SUV follows the looping off-ramp away from the expressway and turns back the opposite way, camera slowly pans to follow.
5. 이미지: Cinematic film still, shot from the back seat center looking forward between the front seats. Driver's left hand on the steering wheel on the left, a large landscape 16:9 touchscreen mounted in the center of a minimalist black dashboard, facing the camera squarely, the screen fully lit plain white, a Korean expressway ahead through the windshield at golden-hour dusk. Screen occupies the middle 40% of frame width, perfectly rectangular and frontal. Photorealistic. No logos, no text.
   움직임: locked-off static camera, absolutely no camera movement, the road moves outside the windshield, the white screen stays still and evenly lit.
6. 이미지: Cinematic close-up. A man's right index finger tapping a large teal-green rounded button on a car's center touchscreen, the rest of the screen a bright white app interface out of focus, warm dusk light in the cabin, shallow depth of field, photorealistic. No readable text, no logos.
   움직임: the finger reaches in and taps the green button once, the button flashes slightly brighter, static camera.
7. 이미지: Cinematic film still. A dark grey modern SUV (no logos) smoothly taking the short slip lane from a Korean expressway directly into a highway rest-area gas station on the same side, bright canopy lights, fuel pumps, dusk sky, other cars continuing on the expressway, photorealistic. No readable brand text.
   움직임: the SUV glides off the expressway into the rest-area gas station and slows under the canopy, camera tracks smoothly.
8. 이미지: Cinematic film still. At a Korean highway rest-area gas station at dusk under a glowing canopy, a Korean man in his 30s in a navy jacket stands relaxed next to his dark grey SUV with the fuel nozzle inserted, holding a paper coffee cup, slight smile, warm light, photorealistic. No readable brand text, no logos.
   움직임: he sips his coffee and smiles while the car refuels, gentle breeze, slow push-in.
9. 이미지: Cinematic wide film still. A dark grey modern SUV (no logos) seen from behind driving away on a Korean expressway into a glowing sunset, empty sky on the left third of the frame, warm golden light, lens flare, photorealistic. No text.
   움직임: the SUV drives away into the sunset, camera stays still, sun flare shimmers.
10. 이미지: Cinematic film still inside a modern car at dusk, seen from the driver side. A Korean woman in her 30s in the front passenger seat, cream knit sweater, hand on her stomach, turning to the driver and speaking with a playful hungry expression, highway passing outside, warm sunset light, photorealistic. No text, no logos.
   움직임: she rubs her stomach, turns to the driver and says something playfully, static camera.
11. 이미지: Cinematic film still. At a drive-thru window at dusk, a staff member's hands pass a plain brown paper takeout bag and a cup into the open driver window of a dark grey SUV, the Korean man driver in a navy jacket reaching out, warm window light, photorealistic. No logos, no readable text.
   움직임: the bag and cup are handed through the window and the driver takes them with a smile.
12. 이미지: Cinematic film still inside a moving car at sunset. A Korean woman in her 30s in a cream knit sweater in the passenger seat happily eating fries from a plain brown paper bag and smiling at the driver, Korean man in a navy jacket driving, highway outside, warm golden light, photorealistic. No logos, no text.
   움직임: she eats a fry and laughs, scenery passes outside, static camera.
```

E2(확장 센터 화면)는 5번 영상을 그대로 다시 쓰고 화면 내용만 DT · 음식점 녹화로 바꿈.
