#!/usr/bin/env bash
set -euo pipefail

export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${1:-v1.0.20}"
RELEASE_DIR="$ROOT_DIR/build/release-$VERSION"
SRC_APK="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
DST_APK="$RELEASE_DIR/OpalYuyanIme_${VERSION}.apk"
SHA_FILE="$RELEASE_DIR/SHA256SUMS.txt"
NOTES_FILE="$RELEASE_DIR/RELEASE_NOTES.md"

if [[ ! -f "$SRC_APK" ]]; then
  echo "ERROR: Release APK not found at $SRC_APK" >&2
  exit 1
fi

mkdir -p "$RELEASE_DIR"
cp -f "$SRC_APK" "$DST_APK"

(
  cd "$RELEASE_DIR"
  shasum -a 256 "OpalYuyanIme_${VERSION}.apk" > "$SHA_FILE"
)

APK_SIZE="$(stat -f%z "$DST_APK" 2>/dev/null || stat -c%s "$DST_APK")"
APK_SHA="$(awk '{print $1}' "$SHA_FILE")"

cat > "$NOTES_FILE" <<EOF
# OpalYuyanIme ${VERSION}

**OpalDesk 可选同签名语燕输入法插件（首个正式 Release）**

## 核心特性
- **主包零膨胀、按需安装**：与 OpalDesk 主程序解耦，默认不占用主包体积；安装后通过同签名 X.509 SHA-256 双向验签自动识别，支持在 OpalDesk 输入法设置中（含本机 DeX 桌面与外接屏）自由开关启用。
- **跨屏零遮挡输入**：在 DeX 虚拟屏上以 \`0×0\` 透明模式运行，实时通过同签名 Binder 将候选词与 \`CursorAnchorInfo\` 光标坐标回传至 OpalDesk 合成器层，解决外接屏/小窗/旋转裁剪问题。
- **全场景键鼠与触屏适配**：支持全拼、小鹤双拼、自然码、搜狗双拼、微软双拼与九宫格，兼容触屏点击、无线投屏与蓝牙/USB 实体键盘（\`Shift\` 单击中英切换、\`Ctrl+Space\`、\`-/=\` 翻页）。
- **主屏智能退让**：切回手机主屏打字时自动退让并还原原系统输入法，切回 DeX 窗口自动无缝接管。

## 安装说明
1. 下载本页附件 \`OpalYuyanIme_${VERSION}.apk\` 并安装（与 OpalDesk 官方包使用同一签名证书）。
2. 在系统「设置 → 语言与输入法 → 管理输入法」中启用 **Opal 语燕输入法**。
3. 打开 OpalDesk →「输入法设置」，即可自由开启或关闭在 DeX 桌面（含本机桌面）中使用内置语燕输入法。

## 校验信息
- **包名**：\`io.github.lengsukq.opaldesk.ime\`
- **文件大小**：\`${APK_SIZE}\` bytes
- **SHA-256**：\`${APK_SHA}\`
EOF

echo "==> Checking gh auth ..."
gh auth status

echo "==> Publishing GitHub Release ${VERSION} to lengsukq/OpalYuyanIme ..."
if gh release view "$VERSION" --repo lengsukq/OpalYuyanIme >/dev/null 2>&1; then
  gh release upload "$VERSION" "$DST_APK" "$SHA_FILE" --repo lengsukq/OpalYuyanIme --clobber
  gh release edit "$VERSION" --repo lengsukq/OpalYuyanIme --title "OpalYuyanIme ${VERSION}" --notes-file "$NOTES_FILE" --latest
else
  gh release create "$VERSION" "$DST_APK" "$SHA_FILE" \
    --repo lengsukq/OpalYuyanIme \
    --title "OpalYuyanIme ${VERSION}" \
    --notes-file "$NOTES_FILE" \
    --latest
fi

echo "==> Published Release Info:"
gh release view "$VERSION" --repo lengsukq/OpalYuyanIme --json url,tagName,name,assets
