"""Builds android/app/src/main/assets/places.csv: every fuel station Opinet lists, at its real location.

    # 1. Opinet (한국석유공사) public downloads, no login: https://www.opinet.co.kr/user/opdown/opDownload.do
    #    "사업자 기본정보 (현재기준)" CSV and "사업자별 현재 판매가격" CSV
    # 2. OpenStreetMap extract of South Korea
    curl -LO https://download.geofabrik.de/asia/south-korea-latest.osm.pbf
    brew install osmium-tool && pip install shapely
    python3 tools/build_places.py 사업자_기본정보(주유소).csv 현재_판매가격(주유소).csv south-korea-latest.osm.pbf

Opinet's downloads have the official station list (name, brand, address, self-service, today's
prices) but no coordinates. The official position comes from data/opinet/api_stations.csv
(tools/fetch_opinet.py) when it has the station. Others are placed inside their own 시/군/구
boundary, most certain first:
  1. 이름: the OSM fuel station with the same name
  2. 주소: the OSM building with the same road address (도로명 + 건물번호)
  3. 도로: the OSM fuel station on the same road (within 60 m of it), nearest to the estimate below
  4. 보간: between the OSM buildings on the same road and side with the nearest numbers (road
     numbers count about 10 m each), moved onto an OSM fuel station within 150 m when there is one
The error of 3 and 4 is measured against the stations placed by 1, and every step against the
official positions, and written to docs/stations.md.
A station none of these finds is left out rather than put in the wrong place.
FOOD (drive-throughs, highway rest areas) and EAT (brand restaurants and cafes) still come from OSM.
"""
import csv
import json
import math
import os
import re
import subprocess
import sys
import tempfile
import time
from collections import Counter, defaultdict

from shapely.geometry import Point, shape
from shapely.strtree import STRtree

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "places.csv")
REPORT = os.path.join(ROOT, "docs", "stations.md")
OFFICIAL = os.path.join(ROOT, "data", "opinet", "api_stations.csv")

ROAD = re.compile(r"(\S+(?:로|길))\s+(\d+)(?:-(\d+))?")
ROAD_M = 100  # an OSM station this close to the road centre line counts as "on" it
DT = re.compile(r"(DT|D/T|드라이브)", re.IGNORECASE)
LEGAL = re.compile(r"㈜|\(주\)|주식회사|\(유\)|㈲|유한회사|\(합\)|\(사\)|\(재\)")
BRANDS = re.compile(r"HD현대오일뱅크|현대오일뱅크|오일뱅크|SK에너지|SK엔크린|SK|GS칼텍스|GS|S-OIL|S-oil|에쓰오일|NH-OIL|NH|농협|알뜰|ex|EX|셀프|self|SELF|직영", re.IGNORECASE)

# Opinet 지역 first word → names of the OSM province boundary it may fall in
SIDO = {
    "서울": ["서울"], "부산": ["부산"], "대구": ["대구"], "인천": ["인천"], "대전": ["대전"], "울산": ["울산"],
    "세종시": ["세종"], "경기": ["경기"], "강원": ["강원"], "충북": ["충청북", "충북"], "충남": ["충청남", "충남"],
    "전북": ["전라북", "전북"], "전남광주": ["광주", "전라남", "전남"], "전남": ["전라남", "전남"], "광주": ["광주"],
    "경북": ["경상북", "경북"], "경남": ["경상남", "경남"], "제주": ["제주"],
}


def read_csv(path):
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "cp949"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    return [{k.strip(): (v or "").strip() for k, v in row.items()} for row in csv.DictReader(text.splitlines())]


def osmium_export(pbf, tmp, name, filters, geometry="point,polygon"):
    filtered = os.path.join(tmp, name + ".pbf")
    seq = os.path.join(tmp, name + ".geojsonseq")
    subprocess.run(["osmium", "tags-filter", pbf, *filters, "-o", filtered, "-O"], check=True)
    subprocess.run(["osmium", "export", filtered, "-f", "geojsonseq", "--geometry-types=" + geometry,
                    "--add-unique-id=type_id", "-o", seq, "-O"], check=True)
    with open(seq, encoding="utf-8") as f:
        for line in f:
            yield json.loads(line.lstrip("\x1e"))


def centre(geom):
    g = shape(geom)
    return g if g.geom_type == "Point" else g.representative_point()


def core(name):
    """평창주유소 / GS칼텍스 평창주유소 / ㈜지에스이앤알 평창주유소 → 평창 (direction suffixes kept)."""
    s = LEGAL.sub(" ", name)
    words = s.split()
    station = [w for w in words if "주유소" in w]
    s = station[-1] if station else s
    s = BRANDS.sub("", s).replace("주유소", "")
    return re.sub(r"[\s·.,\-_]", "", s)


def short_name(name):
    """Display name: the station part of the official name, without the operating company."""
    s = re.sub(r"\s+", " ", LEGAL.sub(" ", name)).strip()
    words = s.split()
    station = [i for i, w in enumerate(words) if "주유소" in w]
    return " ".join(words[station[-1]:]) if station else s


BRAND_NAME = {"SK": "SK에너지", "GS": "GS칼텍스", "SOIL": "S-OIL", "HD": "HD현대오일뱅크", "NH": "NH-OIL", "ALD": "알뜰주유소"}


def area_stem(name):
    """옥산휴게소 / 서산 휴게소 (서울 방향) → 옥산 / 서산: the rest area's own name, without direction."""
    return re.sub(r"\(.*?\)|휴게소|간이|\s", "", name)


def rest_area(name):
    """언양(서울)주유소 / 언양휴게소(서울방향) → ("언양", "서울"); None without a direction."""
    m = re.search(r"([^()]+?)\s*\(([^)]+)\)", LEGAL.sub(" ", name))
    if not m:
        return None
    stem = re.sub(r"(휴게소|주유소|충전소|알뜰|셀프|\s)", "", m.group(1))
    stem = stem.rstrip("상하") if len(stem) > 2 else stem
    direction = re.sub(r"방향|\s", "", m.group(2))
    return (stem, direction) if stem and direction not in ("상", "하", "") else None


def brand_of(text):
    """SK / GS / SOIL / HD / NH / ALD (알뜰) / None, from an Opinet 상표 or OSM name·brand·operator."""
    t = text.upper()
    for code, words in (("ALD", ["알뜰", "EX)"]), ("SOIL", ["S-OIL", "S OIL", "에쓰오일", "에스오일"]),
                        ("HD", ["오일뱅크", "OILBANK"]), ("NH", ["NH", "농협"]), ("GS", ["GS"]), ("SK", ["SK"])):
        if any(w in t for w in words):
            return code
    return None


def dist_m(a, b):
    k = math.cos(math.radians((a.y + b.y) / 2))
    return math.hypot((a.x - b.x) * k, a.y - b.y) * 111_195


def regions(pbf, tmp):
    """(sido short name, 시/군/구 name) → boundary polygon."""
    provinces, districts = [], []
    for f in osmium_export(pbf, tmp, "admin", ["r/boundary=administrative"]):
        p = f["properties"]
        level, name = p.get("admin_level"), p.get("name:ko") or p.get("name") or ""
        if level == "4":
            provinces.append((name, shape(f["geometry"])))
        elif level in ("6", "7"):
            districts.append((name, level, shape(f["geometry"])))
    out = {}
    for name, level, poly in districts:
        pt = poly.representative_point()
        province = next((n for n, pp in provinces if pp.contains(pt)), "")
        for short, prefixes in SIDO.items():
            if any(province.startswith(x) for x in prefixes):
                # a 시 (level 6) wins over a 구 with the same name inside another 시
                if (short, name) not in out or level == "6":
                    out[(short, name)] = poly
    for name, poly in provinces:
        if name.startswith("세종"):
            out[("세종시", "세종시")] = poly
    return out


def main(basic_csv, price_csv, pbf):
    stations = read_csv(basic_csv)
    prices = {r["고유번호"]: r for r in read_csv(price_csv)}
    with tempfile.TemporaryDirectory() as tmp:
        area = regions(pbf, tmp)

        wanted_roads = {m.group(1) for s in stations for m in [ROAD.search(s["주소"])] if m}
        by_address = defaultdict(list)  # (road, "12-3") → points
        on_road = defaultdict(list)  # road → (main number, point)
        for f in osmium_export(pbf, tmp, "addr", ["nwr/addr:housenumber"]):
            p = f["properties"]
            road, num = p.get("addr:street", ""), p.get("addr:housenumber", "")
            if road in wanted_roads and re.fullmatch(r"\d+(-\d+)?", num):
                pt = centre(f["geometry"])
                by_address[(road, num)].append(pt)
                on_road[road].append((int(num.split("-")[0]), pt))

        expressions = os.path.join(tmp, "roads.txt")
        with open(expressions, "w", encoding="utf-8") as f:
            f.write("\n".join("w/name=" + r for r in sorted(wanted_roads)))
        road_lines = defaultdict(list)
        for f in osmium_export(pbf, tmp, "roads", ["-e", expressions], "linestring"):
            if f["geometry"]["type"] == "LineString" and f["properties"].get("highway"):
                road_lines[f["properties"]["name"]].append(shape(f["geometry"]))

        carriageways = [shape(f["geometry"]) for f in osmium_export(pbf, tmp, "motorway", ["w/highway=motorway"], "linestring")
                        if f["geometry"]["type"] == "LineString"]
        lane_tree = STRtree(carriageways)
        towns = defaultdict(list)  # direction names (서울, 부산, 목포 …) → place points, bigger places first
        rank = {"city": 0, "town": 1, "village": 2}
        for f in osmium_export(pbf, tmp, "places", ["n/place=city,town,village"]):
            p = f["properties"]
            for n in {p.get("name", ""), p.get("name:ko", "")} - {""}:
                towns[re.sub(r"(특별시|광역시|특별자치시|시|군|읍|면)$", "", n)].append((rank.get(p.get("place"), 3), centre(f["geometry"])))
        osm_fuel, osm_brand, osm_names, osm_ids = [], [], [], []
        for f in osmium_export(pbf, tmp, "fuel", ["nwr/amenity=fuel"]):
            p = f["properties"]
            name = p.get("name:ko") or p.get("name") or ""
            if "충전소" not in name:
                osm_fuel.append((core(name), centre(f["geometry"])))
                osm_brand.append(brand_of(" ".join(p.get(k, "") for k in ("name", "brand", "operator"))))
                osm_names.append(name.strip())
                osm_ids.append(f["id"])
        fuel_tree = STRtree([pt for _, pt in osm_fuel])

        food, rest_areas = [], []
        for f in osmium_export(pbf, tmp, "food", ["nwr/highway=services", "nwr/amenity=fast_food,cafe,restaurant"]):
            p = f["properties"]
            name = (p.get("name:ko") or p.get("name") or "").replace(",", " ").strip()
            services = p.get("highway") == "services" and "졸음쉼터" not in name
            if services and area_stem(name):
                rest_areas.append((area_stem(name), rest_area(name), centre(f["geometry"])))
            drive = p.get("amenity") in ("fast_food", "cafe", "restaurant") and (p.get("drive_through") == "yes" or DT.search(name))
            if services or drive:
                pt = centre(f["geometry"])
                food.append(f"FOOD,{f['id']},{pt.y:.6f},{pt.x:.6f},{name or '휴게소'},{(p.get('brand') or '').replace(',', ' ')},,,,osm")
            elif name and p.get("brand"):  # ponytail: brands only, all ~94,000 named places would swamp the app; widen with a corridor pre-filter
                pt = centre(f["geometry"])
                food.append(f"EAT,{f['id']},{pt.y:.6f},{pt.x:.6f},{name},{p['brand'].replace(',', ' ')},,,,osm")

    def heading_to(pt, town):
        """How much the carriageway next to [pt] heads towards [town] (cosine); None if unknown."""
        if not towns.get(town):
            return None
        target = min(towns[town], key=lambda t: t[0])[1]
        near = [carriageways[i] for i in lane_tree.query(pt.buffer(0.003))]
        if not near:
            return None
        lane = min(near, key=lambda ln: ln.distance(pt))
        d = lane.project(pt)
        a, b = lane.interpolate(max(d - 0.0005, 0)), lane.interpolate(min(d + 0.0005, lane.length))
        k = math.cos(math.radians(pt.y))
        vx, vy = (b.x - a.x) * k, b.y - a.y
        tx, ty = (target.x - pt.x) * k, target.y - pt.y
        n = math.hypot(vx, vy) * math.hypot(tx, ty)
        return (vx * tx + vy * ty) / n if n else None

    def highway_stop(s, taken=None):
        """Step 1b: a highway station sits in the rest area of the same name (언양(서울)주유소 →
        언양휴게소). When there is one on each side, the one whose carriageway heads to the direction
        town is it. The OSM fuel station inside the rest area is used when there is one."""
        key, poly = rest_area(s["상호"]), s["_poly"]
        taken = claimed if taken is None else taken
        if not key:
            return None
        named = [(d, pt) for stem, d, pt in rest_areas if (key[0].endswith(stem) or stem.endswith(key[0]))
                 and len(stem) >= 2 and poly.buffer(0.01).contains(pt)]
        same_dir = [pt for d, pt in named if d and d[1] == key[1]]
        if len(same_dir) == 1:
            hit = same_dir[0]
        elif len(named) == 1:
            hit = named[0][1]
        elif len(named) == 2 and dist_m(named[0][1], named[1][1]) < 1500:
            h = [heading_to(pt, key[1]) for _, pt in named]
            if None in h or abs(h[0] - h[1]) < 0.5:
                return None
            hit = named[0][1] if h[0] > h[1] else named[1][1]
        else:
            return None
        near = [i for i in fuel_tree.query(hit.buffer(0.004)) if i not in taken and dist_m(osm_fuel[i][1], hit) <= 300]
        if near:
            i = min(near, key=lambda i: dist_m(osm_fuel[i][1], hit))
            taken.add(i)
            return osm_fuel[i][1]
        return hit

    # pass 1: name, then exact address — the certain ones
    claimed = set()  # OSM fuel stations already given to an Opinet station
    found, rest = {}, []
    for s in stations:
        words = s["지역"].split()
        poly = area.get((words[0], words[1] if len(words) > 1 else words[0]))
        m = ROAD.search(s["주소"])
        s["_poly"], s["_road"] = poly, m
        if poly is None:
            continue
        same_name = [i for i in fuel_tree.query(poly) if osm_fuel[i][0] and osm_fuel[i][0] == core(s["상호"]) and poly.contains(osm_fuel[i][1])]
        num = (m.group(2) + ("-" + m.group(3) if m.group(3) else "")) if m else ""
        by_addr = [pt for pt in by_address.get((m.group(1), num), []) if poly.contains(pt)] if m else []
        area_pt = highway_stop(s)
        if len(same_name) == 1 and not (by_addr and dist_m(by_addr[0], osm_fuel[same_name[0]][1]) > 500):
            found[s["고유번호"]] = (osm_fuel[same_name[0]][1], "이름")
            claimed.add(same_name[0])
        elif area_pt is not None:
            found[s["고유번호"]] = (area_pt, "휴게소")
        elif by_addr:
            found[s["고유번호"]] = (by_addr[0], "주소")
        else:
            rest.append(s)

    def estimate(s):
        """Between the nearest numbers on the same road and side (odd/even), inside the district."""
        m, poly = s["_road"], s["_poly"]
        if not m:
            return None
        n = int(m.group(2))
        side = [(k, pt) for k, pt in on_road.get(m.group(1), []) if k % 2 == n % 2 and poly.contains(pt)]
        lo = max((x for x in side if x[0] <= n), default=None, key=lambda x: x[0])
        hi = min((x for x in side if x[0] >= n), default=None, key=lambda x: x[0])
        if lo and hi and hi[0] - lo[0] <= 60 and dist_m(lo[1], hi[1]) <= (hi[0] - lo[0]) * 25 + 100:
            t = 0 if hi[0] == lo[0] else (n - lo[0]) / (hi[0] - lo[0])
            return Point(lo[1].x + (hi[1].x - lo[1].x) * t, lo[1].y + (hi[1].y - lo[1].y) * t)
        near = lo if lo and (not hi or n - lo[0] <= hi[0] - n) else hi
        return near[1] if near and abs(near[0] - n) <= 8 else None

    def on_same_road(s, taken):
        """Unclaimed OSM fuel stations in the district within ROAD_M of the station's road."""
        m, poly = s["_road"], s["_poly"]
        lines = road_lines.get(m.group(1), []) if m else []
        lines = [ln for ln in lines if ln.intersects(poly)]
        if not lines:
            return []
        return [i for i in fuel_tree.query(poly) if i not in taken and poly.contains(osm_fuel[i][1])
                and min(ln.distance(osm_fuel[i][1]) for ln in lines) * 111_195 <= ROAD_M]

    def by_road(s, taken):
        """Step 3: the OSM station on the road nearest the estimate, or the only one on that road."""
        est, cands = estimate(s), on_same_road(s, taken)
        if est and cands:
            i = min(cands, key=lambda i: dist_m(osm_fuel[i][1], est))
            return i if dist_m(osm_fuel[i][1], est) <= 300 else None
        return cands[0] if not est and len(cands) == 1 else None

    # how far off would 3 and 4 be? try them on the stations whose place is certain by name
    errors = defaultdict(list)
    for s in stations:
        got = found.get(s["고유번호"])
        if got and got[1] == "이름":
            est = estimate(s)
            if est:
                errors["보간"].append(dist_m(est, got[0]))
            i = by_road(s, claimed - {j for j, (c, pt) in enumerate(osm_fuel) if pt is got[0]})
            if i is not None:
                errors["도로"].append(dist_m(osm_fuel[i][1], got[0]))


    # the side-of-highway rule, checked on rest areas whose OSM name already says the direction
    side_checks = []
    for stem, d, pt in rest_areas:
        if not d:
            continue
        other = [q for st, dd, q in rest_areas if st == stem and q is not pt and 100 < dist_m(q, pt) < 1500]
        if len(other) == 1:
            h, g = heading_to(pt, d[1]), heading_to(other[0], d[1])
            if h is not None and g is not None and abs(h - g) >= 0.5:
                side_checks.append(h > g)

    # pass 2: same road, then interpolation
    for s in rest:
        if s["_poly"] is None:
            continue
        i = by_road(s, claimed)
        if i is not None:
            found[s["고유번호"]] = (osm_fuel[i][1], "도로")
            claimed.add(i)
            continue
        est = estimate(s)
        if est:
            near = [i for i in fuel_tree.query(est.buffer(0.002)) if i not in claimed and dist_m(osm_fuel[i][1], est) <= 150]
            if near:
                i = min(near, key=lambda i: dist_m(osm_fuel[i][1], est))
                found[s["고유번호"]] = (osm_fuel[i][1], "보간")
                claimed.add(i)
            else:
                found[s["고유번호"]] = (est, "보간")

    # official positions replace the matches; the difference shows how good the matching is
    official = {r["id"]: Point(float(r["lon"]), float(r["lat"])) for r in read_csv(OFFICIAL)} if os.path.exists(OFFICIAL) else {}
    off_by = defaultdict(list)
    for s in stations:
        pt = official.get(s["고유번호"])
        if pt:
            if s["고유번호"] in found:
                off_by[found[s["고유번호"]][1]].append(dist_m(found[s["고유번호"]][0], pt))
            found[s["고유번호"]] = (pt, "공식")

    placed, missed, how = [], [], Counter()
    for s in stations:
        got = found.get(s["고유번호"])
        if not got:
            missed.append((s, "시군구 경계 없음" if s["_poly"] is None else "같은 이름·주소·도로 없음"))
            continue
        pt, method = got
        how[method] += 1
        pr = prices.get(s["고유번호"], {})
        gas = pr.get("휘발유", "0")
        diesel = pr.get("경유", "0")
        placed.append(",".join([
            "FUEL", s["고유번호"], f"{pt.y:.6f}", f"{pt.x:.6f}", short_name(s["상호"]).replace(",", " "), s["상표"],
            "Y" if s["셀프여부"] == "셀프" else "N", "" if gas in ("0", "") else gas, "" if diesel in ("0", "") else diesel, "opinet",
        ]))

    # OSM stations Opinet could not be tied to: real places, but unverified and without prices
    near_placed = STRtree([pt for pt, _ in found.values()])
    extra = []
    for i, (c, pt) in enumerate(osm_fuel):
        if i in claimed or any(dist_m(pt, q) <= 100 for q in near_placed.geometries.take(near_placed.query(pt.buffer(0.0015)))):
            continue
        name = osm_names[i] or (BRAND_NAME.get(osm_brand[i], "") + " 주유소").strip()
        extra.append(",".join(["FUEL", osm_ids[i], f"{pt.y:.6f}", f"{pt.x:.6f}", name.replace(",", " "), BRAND_NAME.get(osm_brand[i], ""), "", "", "", "osm"]))

    with open(OUT, "w", encoding="utf-8") as f:
        f.write("kind,id,lat,lon,name,brand,self,gasoline,diesel,src\n" + "\n".join(sorted(placed) + sorted(extra) + sorted(set(food))) + "\n")
    with open(os.path.join(os.path.dirname(OUT), "prices_date.txt"), "w") as f:
        f.write(time.strftime("%Y-%m-%d", time.localtime(os.path.getmtime(price_csv))))

    def pct(xs, q):
        xs = sorted(xs)
        return xs[min(len(xs) - 1, int(q * len(xs)))] if xs else float("nan")

    total = len(stations)
    reasons = Counter(r for _, r in missed)
    by_sido = Counter(s["지역"].split()[0] for s, _ in missed)
    all_sido = Counter(s["지역"].split()[0] for s in stations)
    label = {"공식": "오피넷 API 공식 좌표", "이름": "같은 시군구, 같은 이름의 OSM 주유소", "주소": "같은 시군구, 같은 도로명주소의 OSM 건물",
             "휴게소": "고속도로: 같은 이름·방향의 OSM 휴게소 (안의 주유소)",
             "도로": f"같은 도로({ROAD_M} m 이내)의 OSM 주유소", "보간": "같은 도로·같은 쪽 건물번호 보간"}
    with open(REPORT, "w", encoding="utf-8") as f:
        f.write("# 주유소 데이터 (places.csv)\n\n")
        f.write(f"`tools/build_places.py`로 만든 결과입니다. 오피넷 공개 다운로드의 전국 주유소 {total:,}곳 중 "
                f"**{len(placed):,}곳({len(placed) / total:.1%})**에 실제 위치를 붙였습니다. "
                "이름·상표·셀프 여부·가격은 모두 오피넷 원본 그대로입니다.\n\n")
        f.write("| 위치를 찾은 방법 (확실한 순) | 곳 |\n|---|---|\n")
        for k in ("공식", "이름", "휴게소", "주소", "도로", "보간"):
            f.write(f"| {label[k]} | {how[k]:,} |\n")
        f.write("\n공식 좌표가 있는 주유소로 재 본 매칭 방식별 실제 오차입니다.\n\n"
                "| 방식 | 비교한 곳 | 중앙값 | 90% | 1 km 넘게 틀림 |\n|---|---|---|---|---|\n")
        for k, e in off_by.items():
            f.write(f"| {k} | {len(e):,} | {pct(e, .5):.0f} m | {pct(e, .9):.0f} m | {sum(x > 1000 for x in e):,} ({sum(x > 1000 for x in e) / len(e):.0%}) |\n")
        f.write("\n3·4번 방식의 정확도: 이름으로 위치가 확실한 곳에 같은 방식을 써 보고 실제 자리와 거리를 쟀습니다.\n\n"
                "| 방식 | 시험한 곳 | 중앙값 | 80% | 90% | 정확히 같은 주유소 (≤ 30 m) |\n|---|---|---|---|---|---|\n")
        for k in ("도로", "보간"):
            e = errors[k]
            f.write(f"| {k} | {len(e):,} | {pct(e, .5):.0f} m | {pct(e, .8):.0f} m | {pct(e, .9):.0f} m | "
                    f"{sum(x <= 30 for x in e) / max(len(e), 1):.0%} |\n")
        f.write("\n보간한 자리 150 m 안에 OSM 주유소가 있으면 그 자리로 옮깁니다. 고속도로 휴게소는 양쪽이 같은 이름일 때 "
                "휴게소 옆 차로가 방향 도시(예: \"(부산)\"의 부산) 쪽으로 가는 곳을 고릅니다. 이름에 방향이 적힌 OSM 휴게소로 "
                f"이 판정을 시험하면 {sum(side_checks)}/{len(side_checks)}곳이 맞습니다.\n")
        f.write("\n| 뺀 이유 | 곳 |\n|---|---|\n")
        for k, v in reasons.most_common():
            f.write(f"| {k} | {v:,} |\n")
        f.write("\n| 시도 | 전체 | 위치 찾음 |\n|---|---|---|\n")
        for k, v in all_sido.most_common():
            f.write(f"| {k} | {v:,} | {v - by_sido[k]:,} ({(v - by_sido[k]) / v:.0%}) |\n")
        f.write(f"\n오피넷과 이어지지 않은 OpenStreetMap 주유소 {len(extra):,}곳도 실제 자리 그대로 넣었습니다 "
                "(오피넷 주유소 100 m 안에 있는 것은 같은 곳으로 보고 뺌). 앱에는 \"가격 정보 없음\"으로 나오고, "
                "긴급 자동 경유지는 오피넷에서 확인된 주유소를 먼저 고릅니다.\n")
        f.write("\n가격은 오피넷 \"사업자별 현재 판매가격\" 내려받은 날 기준입니다 (앱 화면에 기준일 표시). "
                "위치를 못 찾은 오피넷 주유소는 틀린 자리에 두지 않으려고 뺐습니다. 도로 시작점에서 번호 × 10 m로 놓는 방식과 "
                "시군구 안 상표 소거 방식도 시험했지만 오차가 수 km라 쓰지 않았습니다.\n")
    print(f"방향 판정 {sum(side_checks)}/{len(side_checks)}")
    print(f"{OUT}: {len(placed)}/{total} 오피넷 주유소 {dict(how)} + OSM {len(extra)}, {sum(r.startswith('FOOD') for r in set(food))} DT·휴게소, {sum(r.startswith('EAT') for r in set(food))} 음식점; 빠짐 {dict(reasons)}; "
          + "; ".join(f"{k} 오차 중앙 {pct(e, .5):.0f} m / 90% {pct(e, .9):.0f} m (n={len(e)})" for k, e in errors.items() if e)
          + "; 공식 좌표 대비 " + "; ".join(f"{k} 중앙 {pct(e, .5):.0f} m, 1 km 초과 {sum(x > 1000 for x in e)}/{len(e)}" for k, e in off_by.items()))


if __name__ == "__main__":
    main(*sys.argv[1:4])
