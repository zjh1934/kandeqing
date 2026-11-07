"""
看得清 · 推广物料生成

用法：
    python3 make_qr.py

以后拿到国内短链（蒲公英等），只改下面这两行，重跑一次，
二维码、大字卡片、下载页会全部同步更新，不用碰任何 Java 代码。

依赖：pip3 install qrcode pillow
字体：本脚本优先用文泉驿微米黑；找不到会退回系统默认字体（中文可能显示成方块，
      那时候装一下 fonts-wqy-microhei 即可）。
"""
import os
import base64
import io
import qrcode
from qrcode.image.pil import PilImage
from PIL import Image, ImageDraw, ImageFont

DOWNLOAD_URL = "https://zjh1934.github.io/kandeqing/"
APK_URL = "https://github.com/zjh1934/kandeqing/releases/latest"
APP_NAME = "看得清"

HERE = os.path.dirname(os.path.abspath(__file__))
FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
]
FONT = next((p for p in FONT_CANDIDATES if os.path.exists(p)), None)
if FONT is None:
    print("⚠️ 没找到中文字体，中文会显示成方块，请安装 fonts-wqy-microhei")

BLUE = (21, 101, 192)
DARK = (26, 32, 40)
GREY = (90, 100, 112)
WHITE = (255, 255, 255)
LIGHT = (244, 248, 253)

def font(size):
    if FONT:
        return ImageFont.truetype(FONT, size)
    return ImageFont.load_default()

def make_qr(url, path, box=900):
    qr = qrcode.QRCode(version=None, error_correction=qrcode.constants.ERROR_CORRECT_H,
                       box_size=10, border=2)
    qr.add_data(url)
    qr.make(fit=True)
    img = qr.make_image(image_factory=PilImage).convert("RGB")
    img = img.resize((box, box), Image.NEAREST)
    img.save(path)
    print("  二维码 →", path)
    return img

def wrap(draw, text, f, max_w):
    """按像素宽度粗暴折行，中文够用"""
    lines, cur = [], ""
    for ch in text:
        if ch == "\n":
            lines.append(cur)
            cur = ""
            continue
        if draw.textlength(cur + ch, font=f) > max_w:
            lines.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        lines.append(cur)
    return lines

def card(qr_img, out):
    """A5 竖版大字卡片，可直接打印或当图片发微信"""
    W, H = 1240, 1748
    im = Image.new("RGB", (W, H), WHITE)
    d = ImageDraw.Draw(im)

    d.rectangle([0, 0, W, 250], fill=BLUE)
    d.text((60, 46), APP_NAME, font=font(112), fill=WHITE)
    d.text((66, 176), "手机上的字看不清？", font=font(46), fill=(225, 236, 255))

    y = 310
    f_big = font(52)
    bullets = [
        "按住小球  →  画面放大",
        "点一下字  →  念给你听",
        "不联网 · 不上传 · 免费",
    ]
    for b in bullets:
        d.ellipse([62, y + 18, 84, y + 40], fill=BLUE)
        d.text((108, y), b, font=f_big, fill=DARK)
        y += 96

    qs = 560
    q = qr_img.resize((qs, qs), Image.NEAREST)
    qx, qy = (W - qs) // 2, y + 60
    d.rounded_rectangle([qx - 22, qy - 22, qx + qs + 22, qy + qs + 22],
                        radius=24, fill=WHITE, outline=(210, 222, 238), width=3)
    im.paste(q, (qx, qy))

    y = qy + qs + 50
    d.text((W // 2, y), "微信扫一扫，下载安装", font=font(50), fill=BLUE, anchor="ma")
    y += 92
    d.text((W // 2, y), "装完开一次「无障碍」就行", font=font(40), fill=GREY, anchor="ma")

    d.rectangle([0, H - 170, W, H], fill=LIGHT)
    for i, line in enumerate(wrap(d, "扫不了？让家人打开：" + DOWNLOAD_URL, font(28), W - 100)):
        d.text((50, H - 150 + i * 38), line, font=font(28), fill=GREY)
    im.save(out)
    print("  卡片   →", out)

def html(qr_png_bytes, out):
    b64 = base64.b64encode(qr_png_bytes).decode()
    doc = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{APP_NAME} · 下载</title>
<style>
  * {{ box-sizing: border-box; }}
  body {{ margin:0; padding:24px 18px 60px; background:#F4F8FD; color:#1A2028;
         font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif; }}
  .wrap {{ max-width:560px; margin:0 auto; }}
  h1 {{ font-size:44px; margin:8px 0 4px; color:#1565C0; }}
  .sub {{ font-size:24px; color:#5A6470; margin:0 0 28px; }}
  .card {{ background:#fff; border:1px solid #DCE6F2; border-radius:20px;
           padding:26px 22px; margin-bottom:22px; }}
  .row {{ font-size:26px; line-height:1.7; margin:0 0 10px; }}
  .btn {{ display:block; text-align:center; background:#1565C0; color:#fff;
          text-decoration:none; font-size:32px; font-weight:bold;
          padding:22px 10px; border-radius:16px; margin:22px 0 12px; }}
  .alt {{ display:block; text-align:center; font-size:22px; color:#1565C0;
          margin-top:14px; word-break:break-all; }}
  img {{ display:block; width:min(100%,360px); margin:18px auto 6px; }}
  .cap {{ text-align:center; font-size:22px; color:#5A6470; }}
  .step {{ font-size:23px; line-height:1.8; margin:0; padding-left:26px; }}
  .note {{ font-size:20px; color:#5A6470; line-height:1.7; margin-top:18px; }}
</style>
</head>
<body>
<div class="wrap">
  <h1>{APP_NAME}</h1>
  <p class="sub">手机上的字看不清？按住小球放大，点一下字就念给你听。</p>

  <div class="card">
    <p class="row">按住小球　→　画面放大</p>
    <p class="row">点一下屏幕上的字　→　念给你听</p>
    <p class="row">不联网 · 不上传 · 免费</p>
    <a class="btn" href="{DOWNLOAD_URL}">点这里下载</a>
    <a class="alt" href="{APK_URL}">或直接下载安装包</a>
  </div>

  <div class="card">
    <img src="data:image/png;base64,{b64}" alt="下载二维码">
    <p class="cap">微信扫一扫也能装</p>
  </div>

  <div class="card">
    <p class="step">① 点上面按钮下载，或扫码<br>
    ② 提示「未知来源」时选「允许 / 继续」<br>
    ③ 装完打开，按提示开一次「无障碍」<br>
    ④ 屏幕边上出现小球，就能用了</p>
    <p class="note">打不开下载页的话，把页面转给家人帮忙装一下。
    安装过程不用登录、不用注册，也不联网。</p>
  </div>
</div>
</body>
</html>
"""
    open(out, "w", encoding="utf-8").write(doc)
    print("  下载页 →", out)

def main():
    print("下载页链接：", DOWNLOAD_URL)
    print("安装包直链：", APK_URL)
    qr_page = make_qr(DOWNLOAD_URL, os.path.join(HERE, "二维码-下载页.png"))
    make_qr(APK_URL, os.path.join(HERE, "二维码-安装包直链.png"))
    buf = io.BytesIO()
    qr_page.save(buf, format="PNG")
    card(qr_page, os.path.join(HERE, "大字卡片.png"))
    html(buf.getvalue(), os.path.join(HERE, "下载页.html"))
    print("完成。四个文件都在本目录，可直接打印 / 发微信 / 托管。")

if __name__ == "__main__":
    main()
