#!/usr/bin/env python3
"""Generate the Atom / JSON Feed subscription sources for the APK.

Every entry points straight at the APK file (jsDelivr), never at a Release
HTML page. CI calls this after each build so version, size and SHA256 stay
in sync with the published package.
"""
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime, timezone

REPO = os.environ.get("GITHUB_REPOSITORY", "zjh1934/kandeqing")
APK_URL = "https://cdn.jsdelivr.net/gh/%s@main/apk/kandeqing-latest.apk" % REPO
FEED_ATOM = "https://cdn.jsdelivr.net/gh/%s@main/docs/subscribe.atom" % REPO
FEED_JSON = "https://cdn.jsdelivr.net/gh/%s@main/docs/subscribe.json" % REPO


def commit_date():
    try:
        epoch = subprocess.check_output(
            ["git", "log", "-1", "--format=%ct"], stderr=subprocess.DEVNULL
        ).decode().strip()
        return datetime.fromtimestamp(int(epoch), timezone.utc).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        )
    except Exception:
        return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def main():
    if len(sys.argv) < 4:
        raise SystemExit("usage: make_feed.py <apk> <version> <outdir> [date]")
    apk, ver, outdir = sys.argv[1], sys.argv[2], sys.argv[3]
    date = sys.argv[4] if len(sys.argv) > 4 else commit_date()
    raw = open(apk, "rb").read()
    size = len(raw)
    sha = hashlib.sha256(raw).hexdigest()
    os.makedirs(outdir, exist_ok=True)

    atom = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>看得清 KanDeQing · 安装包更新</title>
  <subtitle>每条条目直接指向 APK 文件，不指向 Release 网页。</subtitle>
  <id>https://github.com/{repo}/releases</id>
  <link rel="self" type="application/atom+xml" href="{feed_atom}" />
  <link rel="alternate" type="text/html" href="https://github.com/{repo}" />
  <updated>{date}</updated>
  <author><name>zjh1934</name></author>
  <entry>
    <title>看得清 {ver}</title>
    <id>tag:github.com,2026:{repo}:{ver}</id>
    <updated>{date}</updated>
    <summary type="text">看得清 {ver} · {size} 字节 · SHA256 {sha}</summary>
    <link rel="alternate" type="application/vnd.android.package-archive" href="{url}" />
    <link rel="enclosure" type="application/vnd.android.package-archive" length="{size}" href="{url}" />
    <content type="text">{url}</content>
  </entry>
</feed>
""".format(repo=REPO, ver=ver, date=date, size=size, sha=sha,
           url=APK_URL, feed_atom=FEED_ATOM)

    feed = {
        "version": "https://jsonfeed.org/version/1.1",
        "title": "看得清 KanDeQing · 安装包更新",
        "home_page_url": "https://github.com/" + REPO,
        "feed_url": FEED_JSON,
        "description": "条目附件直接指向 APK 文件，不指向 Release 网页。",
        "items": [{
            "id": ver,
            "title": "看得清 " + ver,
            "date_modified": date,
            "content_text": "看得清 %s 安装包 · %d 字节 · SHA256 %s" % (ver, size, sha),
            "url": APK_URL,
            "attachments": [{
                "url": APK_URL,
                "mime_type": "application/vnd.android.package-archive",
                "size_in_bytes": size,
                "title": "kandeqing-latest.apk",
            }],
            "_kandeqing": {"version": ver, "sha256": sha, "size": size},
        }],
    }

    with open(os.path.join(outdir, "subscribe.atom"), "w", encoding="utf-8") as fp:
        fp.write(atom)
    with open(os.path.join(outdir, "subscribe.json"), "w", encoding="utf-8") as fp:
        fp.write(json.dumps(feed, ensure_ascii=False, indent=2) + "\n")
    print("feed: %s | %d bytes | sha256 %s | %s" % (ver, size, sha, date))


if __name__ == "__main__":
    main()
