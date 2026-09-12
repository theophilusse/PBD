#!/usr/bin/env python3
"""
Downloads PBR materials from ambientCG.com and converts them into this
project's .pbdmat format, with textures placed under
src/main/resources/materials/ (see PbdPaths.java for that layout).

Standalone script - no Blender, no JVM. Written from ambientCG's OFFICIAL
v2 API documentation (https://docs.ambientcg.com/api/v2/) and cross-
checked against a few independent open-source downloaders that already
use this API (cgrip, ambientcg-downloader - see their READMEs), NOT
tested against the live API directly (this development environment has
no network access to ambientcg.com) - the request/response shape below
should be correct, but the first real run is this script's actual test.
If a response field is missing or renamed, the error messages below are
written to say so specifically rather than fail silently.

Usage:
    python scrape_ambientcg.py <assetId> [assetId...]
    python scrape_ambientcg.py --category PavingStones --limit 20
    python scrape_ambientcg.py --list drop_material_map.txt

Examples:
    python scrape_ambientcg.py Wood066 Fabric045 Leather035
    python scrape_ambientcg.py --category Metal --limit 10 --resolution 2K
"""

import argparse
import io
import json
import os
import sys
import urllib.request
import urllib.parse
import zipfile

API_BASE = "https://ambientCG.com/api/v2"

# This project's own .pbdmat map-name -> ambientCG's map-name-in-filename
# substring. ambientCG filenames look like "Wood066_2K-JPG_Color.jpg",
# "..._NormalGL.jpg", "..._Roughness.jpg", "..._Displacement.jpg" -
# matched by substring rather than an exact suffix, since resolution/
# format tokens vary (2K vs 4K, JPG vs PNG) and a substring match is
# robust to that without needing to parse the filename's full grammar.
MAP_NAME_SUBSTRINGS = {
    "texture": "Color",
    "normalMap": "NormalGL",
    "roughnessMap": "Roughness",
    "displacementMap": "Displacement",
}

DEFAULT_OUTPUT_DIR = os.path.join(
    os.path.dirname(__file__), "..", "src", "main", "resources", "materials"
)


def fetch_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": "pbd-benchmark-scraper/1.0"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def list_asset_ids(category, limit):
    url = f"{API_BASE}/full_json?type=Material&category={urllib.parse.quote(category)}&sort=Popular&limit={limit}"
    data = fetch_json(url)
    # Field name for the asset list has changed between API iterations
    # (see docs.ambientcg.com/updates/) - try the couple of names
    # actually documented/observed rather than assuming just one.
    assets = data.get("foundAssets") or data.get("assets") or []
    return [a.get("assetId") for a in assets if a.get("assetId")]


def fetch_asset_metadata(asset_id):
    url = f"{API_BASE}/full_json?id={urllib.parse.quote(asset_id)}&include=downloadData"
    data = fetch_json(url)
    assets = data.get("foundAssets") or data.get("assets") or []
    if not assets:
        raise RuntimeError(f"'{asset_id}' not found in API response - check the exact asset ID on ambientcg.com")
    return assets[0]


def pick_download_folder(asset, preferred_resolution):
    """downloadFolders is keyed by a resolution+format string like
    "2K-JPG" (per the Rust binding's DownloadFolder struct) - preferred_resolution
    matches by substring (e.g. "2K") so "2K-JPG" and "2K-PNG" both match,
    picking JPG over PNG when both are available (smaller files) unless
    only PNG exists."""
    folders = asset.get("downloadFolders") or {}
    if not folders:
        raise RuntimeError(f"No downloadFolders in API response for '{asset.get('assetId')}' - "
                            "the API's response shape may have changed, see this script's own docstring")
    candidates = [k for k in folders if preferred_resolution in k]
    if not candidates:
        available = ", ".join(sorted(folders.keys()))
        raise RuntimeError(f"No '{preferred_resolution}' folder for '{asset.get('assetId')}' - available: {available}")
    candidates.sort(key=lambda k: 0 if "JPG" in k.upper() else 1)
    return folders[candidates[0]]


def download_and_extract(folder, dest_dir):
    """Each downloadFolder holds one or more downloadFiles, typically a
    single ZIP (per ambientCG's own docs: zipContent lists what's inside
    it) - downloaded to memory and extracted directly, no temp file
    needed for material-sized archives (a few MB, not the multi-GB range
    where that would matter)."""
    files = folder.get("downloadFiles") or []
    if not files:
        raise RuntimeError("downloadFolder has no downloadFiles - API response shape may have changed")
    # downloadLink is ambientCG's own recommended link (logs stats, then
    # redirects) - urllib follows redirects by default, so this just works.
    zip_url = files[0].get("downloadLink") or files[0].get("rawLink")
    if not zip_url:
        raise RuntimeError("No downloadLink/rawLink on the download file entry")

    req = urllib.request.Request(zip_url, headers={"User-Agent": "pbd-benchmark-scraper/1.0"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        zip_bytes = resp.read()

    os.makedirs(dest_dir, exist_ok=True)
    extracted = []
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        for name in zf.namelist():
            if name.endswith("/"):
                continue
            target = os.path.join(dest_dir, os.path.basename(name))
            with zf.open(name) as src, open(target, "wb") as out:
                out.write(src.read())
            extracted.append(target)
    return extracted


def write_pbdmat(asset_id, extracted_files, dest_dir):
    """Matches extracted filenames to this project's .pbdmat fields by
    substring (see MAP_NAME_SUBSTRINGS) - robust to the exact resolution/
    format tokens in the filename without needing to parse them."""
    lines = [f"material {asset_id} {{", "  color     = (0.8, 0.8, 0.8)", "  shininess = 8", "  specular  = 0.1"]
    for field, substr in MAP_NAME_SUBSTRINGS.items():
        match = next((f for f in extracted_files if substr.lower() in os.path.basename(f).lower()), None)
        if match:
            lines.append(f"  {field} = {os.path.basename(match)}")
    lines.append("}")
    lines.append("")

    pbdmat_path = os.path.join(dest_dir, f"{asset_id}.pbdmat")
    with open(pbdmat_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return pbdmat_path


def process_asset(asset_id, output_dir, resolution):
    print(f"[{asset_id}] Fetching metadata...")
    asset = fetch_asset_metadata(asset_id)
    folder = pick_download_folder(asset, resolution)
    print(f"[{asset_id}] Downloading and extracting...")
    extracted = download_and_extract(folder, output_dir)
    print(f"[{asset_id}] Extracted {len(extracted)} file(s)")
    pbdmat_path = write_pbdmat(asset_id, extracted, output_dir)
    print(f"[{asset_id}] Wrote {pbdmat_path}")
    return pbdmat_path


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("asset_ids", nargs="*", help="ambientCG asset IDs, e.g. Wood066")
    parser.add_argument("--category", help="Download the top N assets from this category instead of specific IDs")
    parser.add_argument("--limit", type=int, default=10, help="How many assets to fetch with --category (default 10)")
    parser.add_argument("--resolution", default="1K", help="Preferred resolution substring, e.g. 1K/2K/4K (default 1K)")
    parser.add_argument("--output", default=DEFAULT_OUTPUT_DIR, help="Output directory (default: project materials/)")
    args = parser.parse_args()

    ids = list(args.asset_ids)
    if args.category:
        print(f"Listing top {args.limit} assets in category '{args.category}'...")
        ids.extend(list_asset_ids(args.category, args.limit))

    if not ids:
        parser.error("Give at least one asset ID, or --category")

    output_dir = os.path.abspath(args.output)
    print(f"Output directory: {output_dir}")
    print(f"{len(ids)} asset(s) to process: {', '.join(ids)}")
    print()

    succeeded, failed = [], []
    for asset_id in ids:
        try:
            process_asset(asset_id, output_dir, args.resolution)
            succeeded.append(asset_id)
        except (RuntimeError, OSError, urllib.error.URLError) as e:
            print(f"[{asset_id}] FAILED: {e}", file=sys.stderr)
            failed.append(asset_id)
        print()

    print("=== Summary ===")
    print(f"Succeeded: {len(succeeded)} ({', '.join(succeeded) if succeeded else 'none'})")
    print(f"Failed:    {len(failed)} ({', '.join(failed) if failed else 'none'})")
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()
