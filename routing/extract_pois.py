#!/usr/bin/env python3
"""Turn `osmium export -f geojsonseq` output into the compact places file the app reads.

Output: gzip TSV, one place per line: id, category, lat, lon, name, phone
Categories: food, fuel, mechanic, tyres, hospital. Map data (c) OpenStreetMap contributors, ODbL.
Usage: extract_pois.py places.geojsonseq out.tsv.gz
"""
import gzip
import json
import sys


def category(t):
    amenity, shop = t.get("amenity", ""), t.get("shop", "")
    if amenity in ("restaurant", "cafe", "fast_food", "food_court"):
        return "food"
    if amenity == "fuel":
        return "fuel"
    if amenity in ("hospital", "clinic"):
        return "hospital"
    if shop == "tyres" or t.get("service:vehicle:tyres") == "yes":
        return "tyres"
    if shop in ("car_repair", "motorcycle_repair") or (shop == "motorcycle" and t.get("service:vehicle:repair", t.get("repair")) != "no"):
        return "mechanic"
    return None


def centre(geom):
    kind, c = geom["type"], geom["coordinates"]
    if kind == "Point":
        return c[1], c[0]
    if kind == "Polygon":
        ring = c[0]
    elif kind == "MultiPolygon":
        ring = c[0][0]
    elif kind == "LineString":
        ring = c
    else:
        return None
    return sum(p[1] for p in ring) / len(ring), sum(p[0] for p in ring) / len(ring)


def clean(s):
    return " ".join(str(s).split())[:80]


def main(src, dst):
    seen, rows = set(), []
    with open(src, encoding="utf-8") as f:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            feat = json.loads(line)
            tags = feat.get("properties", {})
            cat = category(tags)
            pos = centre(feat["geometry"]) if feat.get("geometry") else None
            if not cat or not pos:
                continue
            pid = str(feat.get("id") or tags.get("@id") or "")
            if not pid or pid in seen:
                continue
            seen.add(pid)
            name = clean(tags.get("name:en") or tags.get("name") or tags.get("brand") or "")
            phone = clean(tags.get("phone") or tags.get("contact:phone") or "").split(";")[0]
            rows.append(f"{pid}\t{cat}\t{pos[0]:.5f}\t{pos[1]:.5f}\t{name}\t{phone}")
    with gzip.open(dst, "wt", encoding="utf-8") as out:
        out.write("\n".join(rows) + "\n")
    print(f"{len(rows)} places -> {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
