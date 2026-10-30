# 多台机器共享发布签名

仓库保存 `config/signing.age`，其中包含现有 keystore、签名别名、密钥库密码和私钥密码。整个包由 age 的口令模式加密，换机器时只需输入一次 age 解密密码。脚本不生成新的签名私钥。

## 环境

需要 JDK 17+、[age](https://github.com/FiloSottile/age) 和本地交互终端，支持 Linux、macOS，以及 Windows 的 WSL。`.signing/` 所在文件系统必须支持 POSIX 权限和原子重命名；WSL 中请把仓库放在 Linux 文件系统中。

安装 age：

```sh
# Debian / Ubuntu
sudo apt install age

# macOS
brew install age
```

脚本提示跟随 JDK 默认语言，提供中文和英文；age 使用自身的终端提示。

## 在持有原密钥的机器上封装

在仓库根目录执行，把路径和别名替换为现有发布签名的信息：

```sh
sh scripts/signing.sh pack --keystore /绝对路径/release.jks --alias 现有别名
```

依次输入现有密钥库密码、现有私钥密码（相同时直接回车），然后按 age 提示设置并确认主密码。主密码与原有签名密码相互独立。age 允许留空并生成随机口令；若使用该功能，务必在本地查看并保存生成的口令。

脚本先验证私钥和证书链并显示证书 SHA-256，再在内存中封装信息，交给 age 加密。只有加密成功后才更新 `config/signing.age`。请对照已有发布证书确认指纹，提交时只包含密钥包密文及相关代码，不提交原始密钥和明文密码。

主密码应使用密码管理器生成和保存的长随机口令。不要把真实密码填写到聊天、脚本、命令参数或环境变量中；请在自己的本地终端运行。若采用 age 自动生成口令，不要分享或录制该终端输出。

## 配置当前机器或新机器

拉取包含密钥包的仓库后执行：

```sh
sh scripts/signing.sh setup
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

如需清除本机恢复的密钥和签名密码，可在没有构建或签名脚本运行时删除整个 `.signing/` 目录。以后仍可通过密钥包重新解锁。

## GitHub Actions

现有工作流继续使用 `RELEASE_KEYSTORE`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 四项 GitHub Secrets，无需调整。主密码无需提供给 CI；本地与 CI 应使用同一份原始签名密钥。
