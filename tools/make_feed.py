#!/usr/bin/env python3
"""Generate the Atom / JSON Feed subscription sources for every released APK.

One entry per historical version, newest first. Each entry's enclosure /
attachment points straight at that version's APK file on jsDelivr, never at a
Release HTML page. CI calls this after each build so a fresh version is added
without dropping the older ones.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone

APK_TYPE = "application/vnd.android.package-archive"
LATEST_ALIAS = "kandeqing-latest.apk"
NAME_RE = re.compile(r"^kandeqing-v([0-9][0-9.]*)\.apk$")


def utc(ts):
    return datetime.fromtimestamp(int(ts), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def version_key(v):
    try:
        return tuple(int(x) for x in v.strip().split("."))
    except Exception:
        return (0,)


def release_dates(repo, token):
    out = {}
    if not token:
        return out
    try:
        req = urllib.request.Request(
            "https://api.github.com/repos/%s/releases?per_page=100" % repo,
            headers={"Authorization": "Bearer " + token,
                     "Accept": "application/vnd.github+json",
                     "User-Agent": "kdq-feed"},
        )
        for r in json.load(urllib.request.urlopen(req, timeout=60)):
            out[r["tag_name"].lstrip("v")] = r["published_at"]
    except Exception as e:
        print("release dates unavailable:", type(e).__name__, file=sys.stderr)
    return out


def commit_date(path):
    try:
        ts = subprocess.check_output(
            ["git", "log", "-1", "--format=%ct", "--", path],
            stderr=subprocess.DEVNULL,
        ).decode().strip()
        if ts:
            return utc(ts)
    except Exception:
        pass
    try:
        return utc(os.path.getmtime(path))
    except Exception:
        return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def collect(apk_dir, repo, token):
    dates = release_dates(repo, token)
    items = []
    for name in sorted(os.listdir(apk_dir)):
        if not name.endswith(".apk") or name == LATEST_ALIAS:
            continue
        m = NAME_RE.match(name)
        if not m:
            continue
        ver = m.group(1).rstrip(".")
        path = os.path.join(apk_dir, name)
        raw = open(path, "rb").read()
        if not raw.startswith(b"PK"):
            continue
        date = dates.get(ver) or commit_date(path)
        url = ("https://cdn.jsdelivr.net/gh/%s@main/apk/%s"
               % (repo, urllib.parse.quote(name, safe="")))
        items.append({
            "ver": ver,
            "name": name,
            "date": date,
            "size": len(raw),
            "sha": hashlib.sha256(raw).hexdigest(),
            "url": url,
            "legacy": ver == "2.0",
        })
    items.sort(key=lambda x: (version_key(x["ver"]), x["ver"]), reverse=True)
    return items


def atom(items, repo, feed_atom):
    now = items[0]["date"] if items else datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    latest = "https://cdn.jsdelivr.net/gh/%s@main/apk/%s" % (repo, LATEST_ALIAS)
    head = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>看得清 KanDeQing · 全部历史安装包</title>
  <subtitle>共 %d 个版本，最新在最前；每条条目直接指向该版本 APK 文件，不指向 Release 网页。永久最新直链：%s</subtitle>
  <id>https://github.com/%s/releases</id>
  <link rel="self" type="application/atom+xml" href="%s" />
  <updated>%s</updated>
  <author><name>zjh1934</name></author>
""" % (len(items), latest, repo, feed_atom, now)
    parts = [head]
    for it in items:
        title = ("kandeqing-v%s.apk · 看得清 %s%s"
                 % (it["ver"], it["ver"], "（球球时期）" if it["legacy"] else ""))
        summary = ("%s · 看得清 %s · %d 字节 · SHA256 %s%s"
                   % (it["name"], it["ver"], it["size"], it["sha"],
                      " · 球球时期安装包" if it["legacy"] else ""))
        parts.append("""  <entry>
    <title>%s</title>
    <id>tag:github.com,2026:%s:%s</id>
    <updated>%s</updated>
    <summary type="text">%s</summary>
    <link rel="alternate" type="%s" href="%s" />
    <link rel="enclosure" type="%s" length="%d" href="%s" />
    <content type="text">%s</content>
  </entry>
""" % (title, repo, it["ver"], it["date"], summary,
       APK_TYPE, it["url"], APK_TYPE, it["size"], it["url"], it["url"]))
    parts.append("</feed>\n")
    return "".join(parts)


def jsonfeed(items, repo, feed_json):
    latest = "https://cdn.jsdelivr.net/gh/%s@main/apk/%s" % (repo, LATEST_ALIAS)
    return {
        "version": "https://jsonfeed.org/version/1.1",
        "title": "看得清 KanDeQing · 全部历史安装包",
        "home_page_url": "https://github.com/" + repo,
        "feed_url": feed_json,
        "description": ("共 %d 个版本，最新在最前；每条附件直接指向该版本 APK 文件，"
                        "不指向 Release 网页。永久最新直链：%s" % (len(items), latest)),
        "items": [{
            "id": it["ver"],
            "title": ("kandeqing-v%s.apk · 看得清 %s%s"
                      % (it["ver"], it["ver"], "（球球时期）" if it["legacy"] else "")),
            "date_modified": it["date"],
            "content_text": ("看得清 %s 安装包 · %d 字节 · SHA256 %s"
                             % (it["ver"], it["size"], it["sha"])),
            "url": it["url"],
            "attachments": [{
                "url": it["url"],
                "mime_type": APK_TYPE,
                "size_in_bytes": it["size"],
                "title": it["name"],
            }],
            "_kandeqing": {
                "version": it["ver"],
                "sha256": it["sha"],
                "size": it["size"],
                "file": it["name"],
                "legacy": it["legacy"],
            },
        } for it in items],
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk_dir")
    ap.add_argument("out_dir")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "zjh1934/kandeqing"))
    ap.add_argument("--token", default=os.environ.get("GH_TOKEN", ""))
    a = ap.parse_args()

    repo = a.repo
    feed_atom = "https://cdn.jsdelivr.net/gh/%s@main/docs/feed.atom" % repo
    feed_json = "https://cdn.jsdelivr.net/gh/%s@main/docs/feed.json" % repo
    items = collect(a.apk_dir, repo, a.token)
    if not items:
        raise SystemExit("no APK found in " + a.apk_dir)
    os.makedirs(a.out_dir, exist_ok=True)

    text_atom = atom(items, repo, feed_atom)
    text_json = json.dumps(jsonfeed(items, repo, feed_json),
                           ensure_ascii=False, indent=2) + "\n"
    for name in ("feed.atom", "subscribe.atom"):
        with open(os.path.join(a.out_dir, name), "w", encoding="utf-8") as fp:
            fp.write(text_atom)
    for name in ("feed.json", "subscribe.json"):
        with open(os.path.join(a.out_dir, name), "w", encoding="utf-8") as fp:
            fp.write(text_json)
    print("feed: %d versions | newest %s | oldest %s"
          % (len(items), items[0]["ver"], items[-1]["ver"]))


if __name__ == "__main__":
    main()
