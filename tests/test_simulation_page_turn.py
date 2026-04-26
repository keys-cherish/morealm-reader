"""
MoRealm 仿真翻页 (Simulation Page Turn) 自动化测试
====================================================
基于 UIAutomator2 的 atx-agent RPC 通信，利用：
  - U2 高速截图 API（内存级截图，非 adb screencap）
  - U2 swipe/click 底层 socket 指令（极速滑动/点击）
  - 异步 logcat crash 监听
  - 像素级截图对比（Compose Canvas 无法 dump XML）

测试目标：仿真翻页（贝塞尔曲线翻页）的正确性和稳定性
"""

import os
import sys
import io
import time

# Windows GBK 终端兼容：强制 UTF-8 输出
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
import hashlib
import threading
import subprocess
from datetime import datetime
from pathlib import Path
from io import BytesIO

import uiautomator2 as u2
from PIL import Image, ImageChops, ImageStat

# ── 配置 ──
PACKAGE = "com.morealm.app.debug"
ACTIVITY = "com.morealm.app.ui.navigation.MainActivity"
DEVICE_SERIAL = "emulator-5554"
ARTIFACTS_DIR = Path(__file__).parent.parent / "test-artifacts" / "simulation"
SCREEN_W, SCREEN_H = 1080, 1920

# 翻页手势参数
SWIPE_DURATION = 0.3          # 秒，模拟真实手指速度
SWIPE_START_X = int(SCREEN_W * 0.85)   # 右侧起始
SWIPE_END_X = int(SCREEN_W * 0.15)     # 左侧结束
SWIPE_Y = int(SCREEN_H * 0.5)          # 屏幕中部
# 回翻（上一页）
SWIPE_PREV_START_X = int(SCREEN_W * 0.15)
SWIPE_PREV_END_X = int(SCREEN_W * 0.85)

# 截图对比阈值
PIXEL_DIFF_THRESHOLD = 0.02   # 2% 像素差异认为页面发生了变化
CRASH_KEYWORDS = ["FATAL EXCEPTION", "ANR", "java.lang.RuntimeException",
                  "Process: com.morealm.app"]


class CrashMonitor:
    """异步 logcat crash 缓冲区监听器"""

    def __init__(self, device: u2.Device):
        self.device = device
        self.crashes = []
        self._stop = threading.Event()
        self._thread = None

    def start(self):
        """启动 crash 监听线程"""
        self._stop.clear()
        self._thread = threading.Thread(target=self._monitor, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=3)

    def _monitor(self):
        """通过 atx-agent 的 shell 接口读取 crash 缓冲区"""
        try:
            # 先清空 crash 缓冲区
            self.device.shell("logcat -b crash -c")
            while not self._stop.is_set():
                # 非阻塞读取 crash 缓冲区
                output = self.device.shell("logcat -b crash -d -t 50").output
                if output:
                    for keyword in CRASH_KEYWORDS:
                        if keyword in output:
                            timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
                            self.crashes.append({
                                "time": timestamp,
                                "keyword": keyword,
                                "log": output[:2000],
                            })
                            break
                    # 清空已读
                    self.device.shell("logcat -b crash -c")
                self._stop.wait(1.0)
        except Exception as e:
            print(f"[CrashMonitor] 异常: {e}")

    @property
    def has_crash(self):
        return len(self.crashes) > 0


def fast_screenshot(d: u2.Device) -> Image.Image:
    """
    U2 高速截图 — 通过 atx-agent HTTP API 在设备内存中截图并返回。
    比 adb shell screencap 快几个数量级。
    """
    return d.screenshot()


def screenshot_hash(img: Image.Image) -> str:
    """计算截图的 MD5 哈希，用于快速判断页面是否完全相同"""
    buf = BytesIO()
    img.save(buf, format="PNG")
    return hashlib.md5(buf.getvalue()).hexdigest()


def pixel_diff_ratio(img1: Image.Image, img2: Image.Image) -> float:
    """
    计算两张截图的像素差异比例（纯 Pillow，无 numpy）。
    返回 0.0~1.0，值越大差异越大。
    排除顶部状态栏和底部导航栏区域（只比较阅读内容区）。
    """
    crop_top, crop_bottom = 150, 1800
    c1 = img1.crop((0, crop_top, SCREEN_W, crop_bottom)).convert("L")
    c2 = img2.crop((0, crop_top, SCREEN_W, crop_bottom)).convert("L")
    if c1.size != c2.size:
        return 1.0
    diff = ImageChops.difference(c1, c2)
    # point(): 像素值 > 30 映射为 255，否则 0
    thresh = diff.point(lambda p: 255 if p > 30 else 0)
    stat = ImageStat.Stat(thresh)
    # mean[0] 范围 0~255，除以 255 得到变化像素占比
    return stat.mean[0] / 255.0


def save_screenshot(img: Image.Image, name: str):
    """保存截图到 artifacts 目录"""
    ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)
    path = ARTIFACTS_DIR / f"{name}.png"
    img.save(str(path))
    print(f"  [截图] {path.name}")
    return path


def ensure_reader_open(d: u2.Device) -> bool:
    """
    确保阅读器已打开。
    通过检查当前 activity 和 UI 特征判断。
    如果不在阅读器中，尝试从书架进入。
    """
    info = d.app_current()
    if info.get("package") != PACKAGE:
        print("[启动] 应用未在前台，启动中...")
        d.app_start(PACKAGE, ACTIVITY)
        time.sleep(3)

    # 检查是否在阅读器中 — 通过截图判断
    # 阅读器特征：全屏 Canvas，无明显 toolbar
    # 先尝试 dump 看看有没有书架元素
    try:
        xml = d.dump_hierarchy()
        # 如果能看到书架相关文本，说明不在阅读器
        if "书架" in xml or "Shelf" in xml or "shelf" in xml.lower():
            print("[导航] 当前在书架，尝试点击第一本书进入阅读器...")
            # 点击书架上第一本书 — Compose 列表通常在上半部分
            d.click(SCREEN_W // 2, SCREEN_H // 3)
            time.sleep(2)
            # 可能进入了详情页，查找"阅读"按钮
            xml2 = d.dump_hierarchy()
            if "阅读" in xml2 or "继续阅读" in xml2:
                # 尝试点击阅读按钮
                try:
                    d(textContains="阅读").click()
                except Exception:
                    d(textContains="继续阅读").click()
                time.sleep(2)
            return True
        # 如果 dump 中只有 View 节点（Compose Canvas），大概率在阅读器
        if "ComposeView" in xml:
            print("[状态] 已在阅读器中")
            return True
    except Exception as e:
        print(f"[dump] XML dump 异常（Compose Canvas 正常）: {e}")

    return True


def swipe_next_page(d: u2.Device):
    """仿真翻页 — 从右向左滑动（下一页）"""
    d.swipe(SWIPE_START_X, SWIPE_Y, SWIPE_END_X, SWIPE_Y,
            duration=SWIPE_DURATION)


def swipe_prev_page(d: u2.Device):
    """仿真翻页 — 从左向右滑动（上一页）"""
    d.swipe(SWIPE_PREV_START_X, SWIPE_Y, SWIPE_PREV_END_X, SWIPE_Y,
            duration=SWIPE_DURATION)


def tap_next_page(d: u2.Device):
    """点击右侧 1/3 区域翻到下一页"""
    d.click(int(SCREEN_W * 0.85), SWIPE_Y)


def tap_prev_page(d: u2.Device):
    """点击左侧 1/3 区域翻到上一页"""
    d.click(int(SCREEN_W * 0.15), SWIPE_Y)


# ══════════════════════════════════════════════════════════════
#  测试用例
# ══════════════════════════════════════════════════════════════

class SimulationPageTurnTest:
    """仿真翻页测试套件"""

    def __init__(self):
        print(f"[连接] 通过 atx-agent RPC 连接 {DEVICE_SERIAL}...")
        self.d = u2.connect(DEVICE_SERIAL)
        self.d.implicitly_wait(5)
        # 设置 atx-agent 的 HTTP 超时
        self.d.settings["operation_delay"] = (0, 0)  # 去掉操作间延迟
        self.d.settings["operation_delay_methods"] = []
        self.crash_monitor = CrashMonitor(self.d)
        self.results = []
        print(f"[设备] {self.d.info['productName']} "
              f"{self.d.info['displayWidth']}x{self.d.info['displayHeight']} "
              f"SDK {self.d.info['sdkInt']}")

    def setup(self):
        """测试前置：确保在阅读器中，启动 crash 监听"""
        self.crash_monitor.start()
        ensure_reader_open(self.d)
        # 等待页面渲染稳定
        time.sleep(1)

    def teardown(self):
        """测试后置：停止 crash 监听，输出报告"""
        self.crash_monitor.stop()
        self._print_report()

    def _record(self, name: str, passed: bool, detail: str = ""):
        status = "PASS" if passed else "FAIL"
        self.results.append({"name": name, "status": status, "detail": detail})
        icon = "✓" if passed else "✗"
        print(f"  [{icon}] {name}: {status} {detail}")

    def _print_report(self):
        print("\n" + "=" * 60)
        print("  仿真翻页测试报告")
        print("=" * 60)
        passed = sum(1 for r in self.results if r["status"] == "PASS")
        total = len(self.results)
        for r in self.results:
            icon = "✓" if r["status"] == "PASS" else "✗"
            print(f"  {icon} {r['name']}: {r['status']}")
            if r["detail"]:
                print(f"    {r['detail']}")
        print(f"\n  结果: {passed}/{total} 通过")
        if self.crash_monitor.has_crash:
            print(f"\n  ⚠ 检测到 {len(self.crash_monitor.crashes)} 次 Crash!")
            for c in self.crash_monitor.crashes:
                print(f"    [{c['time']}] {c['keyword']}")
        print("=" * 60)

        # 保存报告到文件
        report_path = ARTIFACTS_DIR / "test-report.txt"
        ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)
        with open(report_path, "w", encoding="utf-8") as f:
            f.write(f"仿真翻页测试报告 - {datetime.now().isoformat()}\n")
            f.write(f"结果: {passed}/{total}\n\n")
            for r in self.results:
                f.write(f"{'PASS' if r['status']=='PASS' else 'FAIL'} | {r['name']}\n")
                if r["detail"]:
                    f.write(f"     {r['detail']}\n")
            if self.crash_monitor.has_crash:
                f.write(f"\nCrash 记录:\n")
                for c in self.crash_monitor.crashes:
                    f.write(f"  [{c['time']}] {c['keyword']}\n")
                    f.write(f"  {c['log'][:500]}\n\n")

    # ── 测试 1: 滑动翻页 — 页面内容变化 ──
    def test_swipe_forward_changes_page(self):
        """滑动翻到下一页，验证页面内容发生变化"""
        print("\n[测试1] 滑动翻页 — 页面内容变化")
        before = fast_screenshot(self.d)
        save_screenshot(before, "t1-before-swipe")

        swipe_next_page(self.d)
        # 等待仿真翻页动画完成（贝塞尔动画 ~500ms）
        time.sleep(0.8)

        after = fast_screenshot(self.d)
        save_screenshot(after, "t1-after-swipe")

        diff = pixel_diff_ratio(before, after)
        passed = diff > PIXEL_DIFF_THRESHOLD
        self._record("滑动翻页-页面变化", passed,
                      f"像素差异: {diff:.4f} (阈值: {PIXEL_DIFF_THRESHOLD})")

    # ── 测试 2: 滑动回翻 — 能回到原页面 ──
    def test_swipe_backward_returns(self):
        """滑动回翻，验证能回到之前的页面"""
        print("\n[测试2] 滑动回翻 — 回到原页面")
        before = fast_screenshot(self.d)
        save_screenshot(before, "t2-before-back")

        swipe_prev_page(self.d)
        time.sleep(0.8)

        after = fast_screenshot(self.d)
        save_screenshot(after, "t2-after-back")

        diff = pixel_diff_ratio(before, after)
        passed = diff > PIXEL_DIFF_THRESHOLD
        self._record("滑动回翻-页面变化", passed,
                      f"像素差异: {diff:.4f}")

    # ── 测试 3: 点击翻页 ──
    def test_tap_forward(self):
        """点击右侧区域翻页"""
        print("\n[测试3] 点击翻页 — 右侧区域")
        before = fast_screenshot(self.d)
        save_screenshot(before, "t3-before-tap")

        tap_next_page(self.d)
        time.sleep(0.8)

        after = fast_screenshot(self.d)
        save_screenshot(after, "t3-after-tap")

        diff = pixel_diff_ratio(before, after)
        passed = diff > PIXEL_DIFF_THRESHOLD
        self._record("点击翻页-右侧", passed,
                      f"像素差异: {diff:.4f}")

    # ── 测试 4: 点击回翻 ──
    def test_tap_backward(self):
        """点击左侧区域回翻"""
        print("\n[测试4] 点击回翻 — 左侧区域")
        before = fast_screenshot(self.d)
        save_screenshot(before, "t4-before-tap-back")

        tap_prev_page(self.d)
        time.sleep(0.8)

        after = fast_screenshot(self.d)
        save_screenshot(after, "t4-after-tap-back")

        diff = pixel_diff_ratio(before, after)
        passed = diff > PIXEL_DIFF_THRESHOLD
        self._record("点击回翻-左侧", passed,
                      f"像素差异: {diff:.4f}")

    # ── 测试 5: 连续快速翻页 — 稳定性 ──
    def test_rapid_swipe_stability(self):
        """连续快速滑动翻页 10 次，检测 crash 和页面渲染"""
        print("\n[测试5] 连续快速翻页 — 稳定性 (10次)")
        screenshots = []
        initial = fast_screenshot(self.d)
        screenshots.append(initial)

        crash_before = len(self.crash_monitor.crashes)

        for i in range(10):
            swipe_next_page(self.d)
            # 不等动画完全结束就发起下一次（压力测试）
            time.sleep(0.3)
            if i % 3 == 2:
                # 每 3 次截一张图
                img = fast_screenshot(self.d)
                screenshots.append(img)
                save_screenshot(img, f"t5-rapid-{i}")

        # 等最后一次动画结束
        time.sleep(1.0)
        final = fast_screenshot(self.d)
        screenshots.append(final)
        save_screenshot(final, "t5-rapid-final")

        crash_after = len(self.crash_monitor.crashes)
        new_crashes = crash_after - crash_before

        # 验证：无 crash + 最终页面与初始不同
        diff = pixel_diff_ratio(initial, final)
        no_crash = new_crashes == 0
        page_changed = diff > PIXEL_DIFF_THRESHOLD

        self._record("快速翻页-无crash", no_crash,
                      f"新增crash: {new_crashes}")
        self._record("快速翻页-页面推进", page_changed,
                      f"首尾像素差异: {diff:.4f}")

    # ── 测试 6: 翻页动画中间态截图 — 验证贝塞尔曲线渲染 ──
    def test_simulation_mid_animation(self):
        """
        在翻页动画进行中截图，验证仿真翻页的中间态渲染。
        贝塞尔曲线翻页的特征：页面不是简单的平移，而是有卷曲效果。
        """
        print("\n[测试6] 仿真翻页中间态 — 贝塞尔曲线渲染")
        before = fast_screenshot(self.d)
        save_screenshot(before, "t6-before")

        # 使用慢速滑动，在中间截图
        # 手动分步执行 swipe 来捕获中间态
        sx, sy = SWIPE_START_X, SWIPE_Y
        ex, ey = SWIPE_END_X, SWIPE_Y

        # 通过 U2 的 touch 接口模拟慢速拖拽
        self.d.touch.down(sx, sy)
        time.sleep(0.05)

        mid_screenshots = []
        steps = 8
        for i in range(1, steps + 1):
            progress = i / steps
            cx = int(sx + (ex - sx) * progress)
            cy = sy
            self.d.touch.move(cx, cy)
            time.sleep(0.06)
            # 在中间几步截图
            if i in (2, 4, 6):
                img = fast_screenshot(self.d)
                mid_screenshots.append(img)
                save_screenshot(img, f"t6-mid-step{i}")

        self.d.touch.up(ex, ey)
        time.sleep(0.8)

        after = fast_screenshot(self.d)
        save_screenshot(after, "t6-after")

        # 验证中间态截图与前后都不同（说明有动画渲染）
        has_mid_state = False
        for idx, mid_img in enumerate(mid_screenshots):
            diff_before = pixel_diff_ratio(before, mid_img)
            diff_after = pixel_diff_ratio(after, mid_img)
            if diff_before > 0.01 and diff_after > 0.01:
                has_mid_state = True
                print(f"    中间态 step: diff_before={diff_before:.4f}, "
                      f"diff_after={diff_after:.4f}")

        self._record("仿真翻页-中间态渲染", has_mid_state,
                      f"捕获到 {len(mid_screenshots)} 张中间态截图")

    # ── 测试 7: 半途取消翻页 — 回弹 ──
    def test_cancel_swipe_snaps_back(self):
        """
        滑动不超过阈值（35%屏幕宽度）后松手，
        验证页面回弹到原位（内容不变）。
        """
        print("\n[测试7] 半途取消翻页 — 回弹")
        before = fast_screenshot(self.d)
        save_screenshot(before, "t7-before-cancel")

        # 只滑动 20% 屏幕宽度（不超过 35% 阈值）
        short_end_x = int(SCREEN_W * 0.70)
        self.d.swipe(SWIPE_START_X, SWIPE_Y, short_end_x, SWIPE_Y,
                     duration=0.2)
        # 等回弹动画完成
        time.sleep(1.0)

        after = fast_screenshot(self.d)
        save_screenshot(after, "t7-after-cancel")

        diff = pixel_diff_ratio(before, after)
        # 回弹后页面应该基本不变（差异很小）
        passed = diff < PIXEL_DIFF_THRESHOLD
        self._record("取消翻页-回弹", passed,
                      f"像素差异: {diff:.4f} (应 < {PIXEL_DIFF_THRESHOLD})")

    # ── 测试 8: 连续前进后退 — 一致性 ──
    def test_forward_backward_consistency(self):
        """翻到下一页再翻回来，验证回到原始页面"""
        print("\n[测试8] 前进后退一致性")
        original = fast_screenshot(self.d)
        save_screenshot(original, "t8-original")

        # 翻到下一页
        swipe_next_page(self.d)
        time.sleep(0.8)
        next_page = fast_screenshot(self.d)
        save_screenshot(next_page, "t8-next")

        # 翻回来
        swipe_prev_page(self.d)
        time.sleep(0.8)
        returned = fast_screenshot(self.d)
        save_screenshot(returned, "t8-returned")

        # 原始页和返回页应该非常接近
        diff = pixel_diff_ratio(original, returned)
        passed = diff < 0.05  # 允许时间戳等微小变化
        self._record("前进后退-一致性", passed,
                      f"原始vs返回 像素差异: {diff:.4f}")

    # ── 测试 9: 无 crash 全程检查 ──
    def test_no_crash_overall(self):
        """全程无 crash"""
        print("\n[测试9] 全程 Crash 检查")
        passed = not self.crash_monitor.has_crash
        detail = ""
        if not passed:
            detail = f"检测到 {len(self.crash_monitor.crashes)} 次 crash"
            # 保存 crash 现场截图
            crash_img = fast_screenshot(self.d)
            save_screenshot(crash_img, "t9-crash-scene")
        self._record("全程无crash", passed, detail)

    def run_all(self):
        """执行全部测试"""
        print("=" * 60)
        print("  MoRealm 仿真翻页自动化测试")
        print(f"  时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
        print(f"  设备: {DEVICE_SERIAL}")
        print(f"  包名: {PACKAGE}")
        print("=" * 60)

        self.setup()

        try:
            self.test_swipe_forward_changes_page()
            self.test_swipe_backward_returns()
            self.test_tap_forward()
            self.test_tap_backward()
            self.test_rapid_swipe_stability()
            self.test_simulation_mid_animation()
            self.test_cancel_swipe_snaps_back()
            self.test_forward_backward_consistency()
            self.test_no_crash_overall()
        except Exception as e:
            print(f"\n[异常] 测试执行中断: {e}")
            # 保存异常现场
            try:
                crash_img = fast_screenshot(self.d)
                save_screenshot(crash_img, "exception-scene")
            except Exception:
                pass
            import traceback
            traceback.print_exc()
        finally:
            self.teardown()


if __name__ == "__main__":
    test = SimulationPageTurnTest()
    test.run_all()
