# 그림 다시 만들기

`figures.html`의 각 `<section id>`가 `docs/img/<id>.png` 한 장입니다. 숫자를 바꾸면 여기서 고치고 다시 캡처합니다.

1. 저장소 루트에서 `python3 -m http.server 8765`
2. 브라우저(폭 1100 이상)로 `http://127.0.0.1:8765/docs/figs/figures.html?x2` 열기 (2배 크기)
3. 섹션별로 요소 캡처 → `docs/img/<id>.png`

경쟁 앱 실제 화면(`compare_naver_map.png`, `compare_kakaonavi.png`, `compare_tmap.png`)은 2026-11-11 21시 휴대폰 앱 주행 안내 화면에서 상태 표시줄을 잘라 쓴 것입니다. `gilmok_portrait.png`는 `gilmok_screen.png`의 연료 패널과 추천 카드를 세로로 붙인 것입니다.
네이버지도 비교(`naver_*_map.png`)는 2026-11-11 21시 네이버지도 자동차 길찾기 화면을 잘라 쓴 것입니다.
