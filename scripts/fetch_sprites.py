"""
Scrapes playorna.com/codex/monsters/ and downloads every monster sprite.

Usage:
    pip install requests beautifulsoup4
    python fetch_sprites.py [--out ./sprites]

Sprites are saved as:
    <out>/<slug>.png

where <slug> matches the monster's codex URL path segment.
"""

import argparse
import os
import time
import re
import requests
from bs4 import BeautifulSoup

BASE = "https://playorna.com"
LIST_URL = BASE + "/codex/monsters/"
HEADERS = {"User-Agent": "Mozilla/5.0 OrnaAutoBot/1.0"}
DELAY = 0.4   # polite delay between requests


def all_monster_slugs() -> list[tuple[str, str]]:
    """Return list of (slug, name) by paginating the monster codex."""
    results = []
    page = 1
    while True:
        url = f"{LIST_URL}?p={page}" if page > 1 else LIST_URL
        resp = requests.get(url, headers=HEADERS, timeout=10)
        resp.raise_for_status()
        soup = BeautifulSoup(resp.text, "html.parser")

        # Monster links look like /codex/monsters/<slug>/
        links = soup.select("a[href^='/codex/monsters/']")
        found = 0
        for a in links:
            href = a["href"]
            m = re.match(r"^/codex/monsters/([^/]+)/$", href)
            if m:
                slug = m.group(1)
                name = a.get_text(strip=True)
                if slug and name:
                    results.append((slug, name))
                    found += 1

        print(f"  Page {page}: {found} monsters")

        # Check for next page
        next_link = soup.find("a", string=re.compile(r"Next page", re.I))
        if not next_link:
            break
        page += 1
        time.sleep(DELAY)

    # Deduplicate preserving order
    seen = set()
    unique = []
    for slug, name in results:
        if slug not in seen:
            seen.add(slug)
            unique.append((slug, name))
    return unique


def sprite_url_for_slug(slug: str) -> str:
    """
    Convert a codex slug to the expected sprite URL.
    Pattern: /static/img/monsters/<slug_underscored>1.png
    Falls back by trying variant numbers 1–3.
    """
    base_name = slug.replace("-", "_")
    return f"{BASE}/static/img/monsters/{base_name}1.png"


def download_sprite(slug: str, name: str, out_dir: str) -> bool:
    dest = os.path.join(out_dir, f"{slug}.png")
    if os.path.exists(dest):
        return True  # already downloaded

    url = sprite_url_for_slug(slug)
    try:
        resp = requests.get(url, headers=HEADERS, timeout=10)
        if resp.status_code == 404:
            # Try without trailing "1" (some sprites have no variant number)
            url2 = f"{BASE}/static/img/monsters/{slug.replace('-', '_')}.png"
            resp = requests.get(url2, headers=HEADERS, timeout=10)
        if resp.status_code == 200 and resp.headers.get("content-type", "").startswith("image"):
            with open(dest, "wb") as f:
                f.write(resp.content)
            return True
        print(f"    MISSING: {name} ({url})")
        return False
    except Exception as e:
        print(f"    ERROR: {name} — {e}")
        return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="./sprites", help="Output directory")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    print("Fetching monster list…")
    monsters = all_monster_slugs()
    print(f"Total unique monsters: {len(monsters)}")

    ok = 0
    for i, (slug, name) in enumerate(monsters, 1):
        print(f"[{i}/{len(monsters)}] {name}", end=" … ")
        if download_sprite(slug, name, args.out):
            print("ok")
            ok += 1
        time.sleep(DELAY)

    print(f"\nDone. Downloaded {ok}/{len(monsters)} sprites → {args.out}/")

    # Write a name map JSON for the Android app
    import json
    name_map = {slug: name for slug, name in monsters}
    with open(os.path.join(args.out, "monsters.json"), "w") as f:
        json.dump(name_map, f, indent=2)
    print("Wrote monsters.json")


if __name__ == "__main__":
    main()
