#!/usr/bin/env bash
set -euo pipefail

# ── 配置 ────────────────────────────────────────────────────────────────────
JAVA_HOME_17="/opt/homebrew/opt/openjdk@17"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_TYPE="${BUILD_TYPE:-release}"
APK_OUT="$PROJECT_DIR/app/build/outputs/apk/$BUILD_TYPE"
PACKAGE="com.deniscerri.ytdl"

# ── 颜色 ────────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[deploy]${NC} $*"; }
success() { echo -e "${GREEN}[deploy]${NC} $*"; }
warn()    { echo -e "${YELLOW}[deploy]${NC} $*"; }
die()     { echo -e "${RED}[deploy]${NC} $*" >&2; exit 1; }

# ── 检查依赖 ────────────────────────────────────────────────────────────────
[[ -x "$JAVA_HOME_17/bin/java" ]] || die "Java 17 not found at $JAVA_HOME_17"
command -v adb &>/dev/null          || die "adb not found in PATH"

# ── 检查设备 ────────────────────────────────────────────────────────────────
DEVICES=()
while IFS= read -r line; do
    serial=$(echo "$line" | awk '{print $1}')
    [[ -n "$serial" ]] && DEVICES+=("$serial")
done < <(adb devices | tail -n +2 | grep "device$")

if [[ ${#DEVICES[@]} -eq 0 ]]; then
    die "No Android device connected (run: adb devices)"
elif [[ ${#DEVICES[@]} -gt 1 && -z "${ANDROID_SERIAL:-}" ]]; then
    info "Multiple devices detected:"
    for i in "${!DEVICES[@]}"; do
        echo -e "  ${CYAN}$((i+1)))${NC} ${DEVICES[$i]}"
    done
    printf "${CYAN}[deploy]${NC} Select device [1-${#DEVICES[@]}]: "
    read -r choice
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= ${#DEVICES[@]} )); then
        export ANDROID_SERIAL="${DEVICES[$((choice-1))]}"
    else
        die "Invalid choice: $choice"
    fi
    info "Using device: $ANDROID_SERIAL"
fi

ABI=$(adb shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r')
info "Device ABI: $ABI"

# ── 编译 ────────────────────────────────────────────────────────────────────
CAPITALIZED="$(echo "$BUILD_TYPE" | awk '{print toupper(substr($0,1,1)) substr($0,2)}')"
GRADLE_TASK="assemble${CAPITALIZED}"
info "Building $BUILD_TYPE APK ($GRADLE_TASK)..."
cd "$PROJECT_DIR"
JAVA_HOME="$JAVA_HOME_17" bash gradlew "$GRADLE_TASK" \
    --quiet \
    --console=plain \
    2>&1 | grep -E "^(> Task|FAILURE|error:)" || true

# 检查构建结果（gradlew 已 set -e 会自动 exit，这里再 double-check）
[[ -d "$APK_OUT" ]] || die "APK output directory not found: $APK_OUT"

# ── 选择 APK ────────────────────────────────────────────────────────────────
# 优先选匹配 ABI 的包，其次 universal
APK=$(ls "$APK_OUT"/*-"${ABI}"-${BUILD_TYPE}.apk 2>/dev/null | head -1 || true)
if [[ -z "$APK" ]]; then
    APK=$(ls "$APK_OUT"/*-universal-${BUILD_TYPE}.apk 2>/dev/null | head -1 || true)
fi
[[ -n "$APK" ]] || die "No suitable APK found in $APK_OUT"
info "APK: $(basename "$APK") ($(du -sh "$APK" | cut -f1))"

# ── 安装 ────────────────────────────────────────────────────────────────────
info "Installing on device..."
if ! adb install -r "$APK" 2>&1 | tee /tmp/adb_install.log | grep -q "Success"; then
    if grep -q "INSTALL_FAILED_UPDATE_INCOMPATIBLE" /tmp/adb_install.log; then
        warn "Signature mismatch — uninstalling existing app and reinstalling..."
        adb uninstall "$PACKAGE" >/dev/null
        adb install "$APK"
    else
        cat /tmp/adb_install.log >&2
        die "Installation failed"
    fi
fi
success "Installed successfully"

# ── 启动 App（可选）────────────────────────────────────────────────────────
if [[ "${1:-}" == "--launch" ]]; then
    info "Launching $PACKAGE..."
    LAUNCHER=$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PACKAGE" 2>/dev/null \
        | tail -1 | tr -d '\r' || true)
    if [[ -n "$LAUNCHER" ]]; then
        adb shell am start -n "$LAUNCHER" >/dev/null
        success "App launched: $LAUNCHER"
    else
        warn "Could not resolve launcher activity; skipping launch"
    fi
fi

success "Done."
