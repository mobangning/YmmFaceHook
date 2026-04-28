# YmmFaceHook - 运满满人脸验证辅助模块

LSPosed/Xposed 模块 — 运满满司机端人脸采集视频帧注入 + 四层活体检测绕过。

## 原理

模块直接解码本地视频文件注入到人脸采集管线，不依赖任何第三方虚拟摄像头App。

## 四层防护绕过

| 层级 | Hook 目标 | 效果 |
|------|----------|------|
| 第1层 视频帧注入 | `ImageReader` / `CameraCaptureSession` / `CaptureRequest` | 预录视频帧替代真实摄像头，消除摩尔纹 |
| 第2层 三色活体绕过 | `View.setBackgroundColor` + 色相关类方法 | 监测炫彩/RGB色阶段，强制返回通过 |
| 第3层 通用活体绕过 | `ClassLoader.loadClass` + `libsafexEx.so` native 方法 | 自动发现并 Hook 所有活体检测类 |
| 第4层 环境伪装 | `SystemProperties` / `File` / `Runtime.exec` | 隐藏 Root、BL 解锁、Xposed 痕迹 |

## 使用方法

1. 录制人脸视频：640×480+、H.264、10-15秒、含自然眨眼微动
2. 放到手机 `/sdcard/DCIM/face.mp4`
3. 安装模块 APK → LSPosed 勾选 `com.xiwei.logistics` → 重启
4. 直接打开运满满进入刷脸（不需要打开任何其他App）

## 验证

```bash
adb logcat -s "YmmFace"
```

## 注意

- **不要**同时打开 cn.andorid.player 或任何占用摄像头的App
- 如果刷脸界面黑屏属正常（预览被dummy接收），人脸分析走的是视频帧
- 日志中出现 `━━━ 活体类` 的具体类名可反馈做精准Hook