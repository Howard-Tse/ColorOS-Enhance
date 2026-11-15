# 发布准备

本文件只记录公开发布流程，不包含设备标识、私有签名或账号凭据。

## 维护者需要确定的内容

1. GitHub 账号为 [Howard-Tse](https://github.com/Howard-Tse)，公开作者名为 Howard；源码仓库为 [ColorOS-Enhance](https://github.com/Howard-Tse/ColorOS-Enhance)。
2. 维护者已选择 MIT，见根目录 [LICENSE](../LICENSE)。运行时依赖清单见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)，第三方原有许可证与署名继续保留。
3. 新应用 ID 已改为 `io.github.howard_tse.colorosenhance`。官方机器人将用户名转小写并把连字符换成下划线，因此对应 Howard-Tse；旧包用户采用[自动迁移流程](MIGRATION.md)，不能以覆盖安装代替迁移。
4. Git 提交作者名为 Howard，使用 GitHub 提供的 noreply 邮箱；仅在当前仓库配置。GitHub 网页邮件隐私设置不代替本地 Git 设置。见 [提交邮箱说明](https://docs.github.com/en/account-and-profile/how-tos/email-preferences/setting-your-commit-email-address)。
5. 安全备份正式签名及密码文件，并确定首个公开版本和反馈入口。不要把密码、PAT、私钥或备份发到 Issue。

GitHub 登录在本机完成，不需要把账号密码或访问令牌交给协作者。

## 源码仓库

- 内容包括源码、资源、单元测试、Gradle Wrapper、构建脚本、公开文档、第三方声明和选定的 LICENSE。
- 不上传 `.local/`、`.signing/`、缓存、构建目录、设备诊断记录或整个工作目录的压缩包。保留的历史 `docs/` 内容默认私有，仅允许明确列出的公开文档进入 Git。
- APK 放 Release 附件，不放源码 Git 历史；只发布签名及版本核对过的正式 APK。
- 提交前运行 `scripts/Audit-Publish.ps1`。补齐 LICENSE 后可用 `-ReleaseCheck` 检查发布前的额外缺项；再次人工检查 Git 暂存区、截图和 Release 文本。
- 创建仓库、提交、添加远程、推送及发布 Release 都应在维护者明确决定后执行；只操作本模块仓库。

## LSPosed 模块仓库

[官方提交说明](https://github.com/Xposed-Modules-Repo/submission)与[提交页面](https://modules.lsposed.org/submission/)截至 2026-11-15 要求如下，提交前应再次核对：

- 源码仍放自己的 GitHub 仓库。模块组织下的仓库主要用于说明和下载，官方要求不要在那里上传源码。
- 使用模块应用 ID 申请；包名域名应由维护者控制，或使用对应 GitHub 身份的 `io.github` 前缀。域名验证按官方要求添加 TXT 记录。
- 提交页面会生成 `[submission] <应用ID>` Issue；审核后接受仓库维护邀请，不是向 LSPosed 框架源码提交 PR。
- 模块仓库名称为应用 ID，description 为模块名，README 为功能／使用说明，SUMMARY 为简述，主页链接可指向源码或反馈入口。
- 至少创建一个包含 APK 附件的有效 Release；tag 按 `versionCode-versionName`，例如当前工作树对应 `69-1.30.0`，Release 标题用版本名，正文写变更说明。
- 在同一次 Release 发布中带上 APK；仅替换附件可能不会触发仓库机器人的更新流程。审核和收录时间以官方为准。

## 当前尚待完成

- 新包自动迁移和完整重启交接已通过真机验收；新图标的桌面深浅切换仍待验收。
- 自然触发并确认自动弹出胶囊卡片的位置修正。在确认前，1.30.0 标记为预发布版本。
- 完成 LSPosed 收录申请、接受维护邀请并按官方仓库规范补齐说明与 APK Release。源码仅上传个人源码仓库。

检查脚本是启发式筛查，不能替代对许可证、账号关联、二进制和新截图的人工复核。
