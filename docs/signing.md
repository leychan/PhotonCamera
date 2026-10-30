# 本地与 GitHub Actions 共用发布签名

仓库保存 `config/signing.age`，其中包含正式签名 keystore、别名、密钥库密码和私钥密码。本地与 GitHub Actions 解锁同一份密钥包打 Release 包。整个包由 age 的口令模式加密，换机器时只需输入一次主密码。

## 环境

需要 JDK 17+、[age](https://github.com/FiloSottile/age) 和本地交互终端，支持 Linux、macOS，以及 Windows 的 WSL。`.signing/` 所在文件系统必须支持 POSIX 权限和原子重命名；WSL 中请把仓库放在 Linux 文件系统中。

自动生成密钥、从文件解锁和 CI 还需要官方 `age-plugin-batchpass` 插件。可以使用 Go 1.27+ 安装固定版本工具，再将 Go 的 bin 目录加入 PATH：

```sh
go install filippo.io/age/cmd/age@v1.3.2 filippo.io/age/cmd/age-plugin-batchpass@v1.3.2
```

本地只进行交互式 pack / setup 时，也可以通过 apt 或 Homebrew 安装 age。脚本提示跟随 JDK 默认语言，提供中文和英文，优先使用 JAVA_HOME 指定的 JDK；age 使用自身的终端提示。

## 生成新的正式签名

仅在尚未生成正式签名时运行一次：

```sh
sh scripts/signing.sh init
```

脚本使用 JDK 的 keytool 创建 RSA 4096、SHA256withRSA、有效期 100 年的 PKCS12 密钥，默认别名为 `photon-release`（可用 `--alias` 指定）。签名密码与主密码分别随机生成，均有 256 位随机熵，不需要手工设置。

生成结果：

- `config/signing.age`：可纳入 Git 的加密密钥包。
- `.signing/master-passphrase`：主密码文件，仅保留在本机，权限为 600。
- `.signing/release.properties`、`.signing/material-*.keystore`：已解锁的本地签名配置和密钥，权限为 600。

脚本还会解密刚生成的密钥包，验证私钥后配置当前机器，并显示签名证书 SHA-256，不会打印随机密码。已有密钥包、主密码文件或本地配置时会拒绝重新初始化，防止意外替换发布身份。初始化完成后可直接构建：

```sh
./gradlew assembleDefaultRelease
```

请在仓库外的密码管理器中备份主密码，并独立备份加密密钥包。不要提交主密码文件、明文配置和原始密钥。新密钥不能直接覆盖使用其他证书签名的 APK，后续更新应持续使用这份正式密钥。

## 在持有原密钥的机器上封装

在仓库根目录执行，把路径和别名替换为现有发布签名的信息：

```sh
sh scripts/signing.sh pack --keystore /绝对路径/release.jks --alias 现有别名
```

依次输入现有密钥库密码、现有私钥密码（相同时直接回车），然后按 age 提示设置并确认主密码。主密码与原有签名密码相互独立。age 允许留空并生成随机口令；若使用该功能，务必在本地查看并保存生成的口令。

脚本先验证私钥和证书链并显示证书 SHA-256，再在内存中封装信息，交给 age 加密。只有加密成功后才更新 `config/signing.age`。请对照已有发布证书确认指纹，提交时只包含密钥包密文及相关代码，不提交原始密钥和明文密码。

手工设置的主密码应使用密码管理器生成和保存的长随机口令。不要把真实密码填写到聊天或命令参数，也不要把密码硬编码进脚本；请在自己的本地终端运行。若采用 age 自动生成口令，不要分享或录制该终端输出。

## 配置当前机器或新机器

拉取包含密钥包的仓库后执行：

```sh
sh scripts/signing.sh setup
```

也可以从安全保存的本机主密码文件解锁，不把密码放进命令参数：

```sh
sh scripts/signing.sh setup --passphrase-file /安全路径/master-passphrase
```

请在没有 Release 构建运行时执行配置。只需输入一次 age 主密码。解密成功且签名验证通过后，脚本写入：

- `.signing/material-*.keystore`：当前机器的签名密钥文件。
- `.signing/release.properties`：Gradle 使用的路径、别名、文件格式和原有签名密码。

目录权限为 `700`，密钥和配置文件权限为 `600`。整个 `.signing/` 目录已被 Git 忽略。脚本将密钥准备好后原子切换配置，错误密码、损坏密文或签名验证失败均不会替换现有配置。再次解锁成功后会删除被替换的旧密钥文件。

之后按原来的 Gradle 命令编译 Release 即可，不需要在每次构建时输入主密码。解锁后的配置和密钥存放在本机磁盘上，具有当前用户权限的程序可读取它们。Debug 仍使用仓库中的调试签名，无需解锁。

Gradle 会优先使用已有的 `RELEASE_*` 项目属性（包括用户目录 `gradle.properties` 和 CI 的 `ORG_GRADLE_PROJECT_*` 环境变量）；只要提供其中任意一项，就必须完整提供四项，不会与解锁配置混合。已有旧配置时，请删除相应的四项属性后再使用本地解锁配置。外部 `RELEASE_STORE_FILE` 保留相对 `app/` 的解析规则，脚本生成的路径相对仓库根目录解析。

## 重新封装与清除本地配置

重新封装相同私钥并更换主密码时：

```sh
sh scripts/signing.sh pack --keystore /绝对路径/release.jks --alias 现有别名 --force
```

提交更新后的 `config/signing.age`，在其他机器上重新执行 `setup` 即可。Git 历史仍保存旧密文，更换主密码不会撤销旧密码对旧密文的解密能力。请保留独立的加密备份；如果旧密码泄露，需要按实际发布渠道处理签名密钥泄露问题。

如需清除本机恢复的密钥和签名密码，请先在仓库外备份主密码，再在没有构建或签名脚本运行时删除整个 `.signing/` 目录。以后仍可通过密钥包和备份的主密码重新解锁。

## GitHub Actions

在仓库 **Settings → Secrets and variables → Actions → New repository secret** 创建唯一的签名 Secret：

- 名称：`RELEASE_SIGNING_PASSPHRASE`
- 值：`.signing/master-passphrase` 的内容。

如果安装并登录了 GitHub CLI，可以通过标准输入设置：

```sh
gh secret set RELEASE_SIGNING_PASSPHRASE --repo leychan/PhotonCamera < .signing/master-passphrase
```

旧的 `RELEASE_KEYSTORE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` Secrets 不再被工作流引用。应用服务用的 `BUILT_IN_API_KEY` / `BUILT_IN_API_KEY_GOOGLE` 与签名无关，继续按现有配置使用。

工作流安装固定版本的 age 和插件，通过步骤级环境变量把主密码交给解锁脚本，再运行各渠道 Release 构建。本地与 CI 均读取同一份密钥包。CI 缺少或设置了空密码时立即失败，不会使用调试签名或等待交互。解锁得到的签名密码会加入 GitHub 日志掩码，任务结束前无论成功失败都会清理 `.signing/`。

代码和密钥包推送后，在 **Actions → Android Release Build → Run workflow** 手动运行，成功后下载 `release-apk-default` 等产物。发布 GitHub Release 也会触发构建并自动上传 APK。手动运行只生成构建产物，不发布 Release。更换密钥包主密码时，请同步更新密码管理器中的备份和 GitHub Secret。
