# 相机界面前后台生命周期

实体相机由 `MainActivity` 的 `STARTED/STOPPED` 生命周期管理。`ON_PAUSE` 只暂停
预览渲染，不作为释放设备的依据；画中画、分屏等仍可见的场景不因失去焦点而关闭设备。
界面完全不可见并进入 `ON_STOP` 后，由 `CameraViewModel.onCameraUiStopped` 取消
待执行的打开、错误恢复和启动预热任务，并调用控制器的有序关闭流程。

后台状态下，所有经过 `requestCameraOpen` 的打开及重开请求均被阻止，初始化等待
结束后也再次核对可见性和 Surface 身份。保留仍有效的 `SurfaceTexture` 用于前台恢复；
Surface 真正销毁时仍由原来的身份校验清除，旧 Surface 的回调不得关闭新预览。

返回前台先恢复可见标记，再通过现有 `ON_RESUME` 和 Surface 通知恢复预览。
后台暂停标记独立于异步更新的 `isPreviewActive`，因此快速切回时即使旧状态仍为
active，也会提交恢复请求；控制器的设备关闭状态机负责等待旧设备关闭后打开新会话。

录像复用 `Camera2Controller.closeCameraInternal` 的正常停止和异步保存逻辑，完全
退后台时结束录像，保留 `isProcessing` 直到保存回调完成，不改用强制终止路径。
当前没有承载实体相机录像的后台前台服务，因此不以跳过关闭来维持后台录像。

屏幕捕获画中画属于独立的 `ScreenCapturePipActivity`、MediaProjection 和前台服务。
释放主界面的实体相机不停止该管线，也不使用全局画中画标记来延长主界面相机的占用。

排查期间曾用 `CameraLifecycle` 的 info 日志记录 `start`、`stop`、`restore` 和
`open-deferred`。修复验证后已移除这些临时日志，生命周期控制及必要错误日志保留。

验证重点：普通预览退后台超过系统回收宽限时间、快速切回、Surface 重建、录像时退
后台后保存、保存过程中返回前台，以及独立屏幕捕获画中画的进入、恢复和关闭。

2026-10-31 已在 vivo V2329A 完整覆盖安装 release 并通过 ADB 检查普通预览：
退桌面后同秒由应用 PID 主动断开相机，等待超过五秒未再出现策略禁用；返回前台后
相机服务为 `ACTIVE`。另三次确认 `stop` 后立即切回，间隔约 225／203／566ms，
均记录 `restore` 并重新连接，最终预览仍为 `ACTIVE`。本次未自动录制视频或操作
MediaProjection 授权，录像保存与画中画的端到端效果仍需实机交互确认。
