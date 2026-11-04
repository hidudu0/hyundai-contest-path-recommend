"""Fetches each station's official position from the Opinet API into data/opinet/api_stations.csv.

    # key: a line "opinet.key=..." in android/local.properties (gitignored)
    pip install pyproj
    python3 tools/fetch_opinet.py data/opinet/사업자_기본정보_주유소_*.csv

The key stops answering after 300 calls (measured, the limit is not published), so one call per
station would take over a month. aroundAll.do returns every station within 5 km, so a hexagon grid
of those circles is scanned first, the cells with the most stations in places.csv first.
detailById.do then fetches whatever the grid missed. Rerun each day: it continues where it stopped.
The API documents the key parameter as certkey, but only code= returns data.
"""
import csv
import json
import math
import os
import re
import sys
import time
import urllib.request
from collections import defaultdict

from pyproj import Transformer

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "data", "opinet", "api_stations.csv")
SCANNED = os.path.join(ROOT, "data", "opinet", "api_scanned_cells.txt")
PLACES = os.path.join(ROOT, "android", "app", "src", "main", "assets", "places.csv")

KATEC = ("+proj=tmerc +lat_0=38 +lon_0=128 +k=0.9999 +x_0=400000 +y_0=600000 +ellps=bessel "
         "+towgs84=-115.80,474.99,674.11,1.16,-2.31,-1.63,6.43 +units=m")
TO_KATEC = Transformer.from_crs("EPSG:4326", KATEC, always_xy=True)
TO_WGS84 = Transformer.from_crs(KATEC, "EPSG:4326", always_xy=True)

RADIUS = 5000  # the API maximum
COL_M, ROW_M = RADIUS * math.sqrt(3), RADIUS * 1.5  # circles on this hexagon grid leave no gaps


def key():
    with open(os.path.join(ROOT, "android", "local.properties"), encoding="utf-8") as f:
        return next(line.split("=", 1)[1].strip() for line in f if line.startswith("opinet.key="))


def call(path, code):
    req = urllib.request.Request(f"https://www.opinet.co.kr/api/{path}&code={code}", headers={"User-Agent": "Mozilla/5.0"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                return json.loads(r.read().decode("utf-8"))["RESULT"]["OIL"]
        except (OSError, ValueError):
            time.sleep(2 + attempt * 3)
    sys.exit("network error: Opinet did not answer")


def centre(row, col):
    return col * COL_M + (row % 2) * COL_M / 2, row * ROW_M


def plan(scanned):
    """Grid cells still worth a call, the one covering the most unscanned known stations first."""
    with open(PLACES, encoding="utf-8") as f:
        points = [TO_KATEC.transform(float(r["lon"]), float(r["lat"])) for r in csv.DictReader(f) if r["kind"] == "FUEL"]
    cells = defaultdict(set)
    for i, (x, y) in enumerate(points):
        near = round(y / ROW_M)
        for row in range(near - 1, near + 2):
            mid = round((x - (row % 2) * COL_M / 2) / COL_M)
            for col in range(mid - 1, mid + 2):
                if math.dist((x, y), centre(row, col)) <= RADIUS:
                    cells[row, col].add(i)
    left = set(range(len(points))) - {i for c in scanned for i in cells.get(c, ())}
    order = []
    while left:
        best = max(cells, key=lambda c: len(cells[c] & left))
        if not cells[best] & left:
            break
        order.append(best)
        left -= cells[best]
    return order


def main(basic_csv):
    ids = sorted({i.decode() for i in re.findall(rb"\bA\d{7}\b", open(basic_csv, "rb").read())})
    code = key()
    found, scanned = set(), set()
    if os.path.exists(OUT):
        with open(OUT, encoding="utf-8", newline="") as f:
            found = {r["id"] for r in csv.DictReader(f)}
    if os.path.exists(SCANNED):
        with open(SCANNED) as f:
            scanned = {tuple(map(int, line.split(","))) for line in f if line.strip()}
    cells = plan(scanned)
    print(f"{len(found & set(ids))}/{len(ids)} stations known, {len(cells)} cells to scan", flush=True)

    new_file = not os.path.exists(OUT)
    with open(OUT, "a", encoding="utf-8", newline="") as out, open(SCANNED, "a") as done:
        rows = csv.writer(out)
        if new_file:
            rows.writerow(["id", "lat", "lon"])

        def save(s):
            if s["UNI_ID"] not in found:
                found.add(s["UNI_ID"])
                lon, lat = TO_WGS84.transform(s["GIS_X_COOR"], s["GIS_Y_COOR"])
                rows.writerow([s["UNI_ID"], f"{lat:.6f}", f"{lon:.6f}"])
                out.flush()

        def limit_reached():
            print(f"limit reached: {len(found & set(ids))}/{len(ids)} stations known, {len(plan(scanned))} cells left", flush=True)

        for cell in cells:
            x, y = centre(*cell)
            stations = call(f"aroundAll.do?out=json&x={x:.1f}&y={y:.1f}&radius={RADIUS}&prodcd=B027&sort=2", code)
            if not stations and not call("avgAllPrice.do?out=json", code):  # an empty cell, or the key stopped?
                return limit_reached()
            for s in stations:
                save(s)
            scanned.add(cell)
            done.write("%d,%d\n" % cell)
            done.flush()

        no_answer = []
        for station in sorted(set(ids) - found):
            answer = call(f"detailById.do?out=json&id={station}", code)
            if answer:
                answer[0]["UNI_ID"] = station
                save(answer[0])
            elif call("avgAllPrice.do?out=json", code):
                no_answer.append(station)
            else:
                return limit_reached()
    print(f"finished: {len(found & set(ids))}/{len(ids)} stations known, no record for {no_answer}", flush=True)


if __name__ == "__main__":
    main(sys.argv[1])
