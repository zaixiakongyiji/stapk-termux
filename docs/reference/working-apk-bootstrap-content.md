# 工作版 APK Bootstrap 完整内容清单

> **来源 APK**: `output/termux-app_debug_arm64-v8a1.apk`（当前 arm64 工作基线）
> 
> **大小**: 411 MiB (APK), 其中 libtermux-bootstrap.so = 234 MiB (压缩后)
> 
> **日期**: 2026-06-08
> 
> **状态**: ✅ 仍作为当前项目 bootstrap 基准；当前交付包应使用 `arm64-v8a` split APK，不使用 `universal` APK。

---

## 总览

| 指标 | 数值 |
|------|------|
| 总文件数 | 2,630 |
| 未压缩大小 | 531 MB |
| 压缩后大小 | 245 MB (ELF 嵌入) |
| 符号链接 | 1,160 条 |
| 顶层目录 | bin / lib / etc / share / var / tmp |

---

## 目录结构

```
bootstrap-aarch64.zip
├── SYMLINKS.txt          (1,160 条符号链接记录)
├── bin/                  (60 个可执行文件)
├── lib/                  (2,133 个文件，含 .so 库和 npm 包)
├── etc/                  (96 个配置文件)
├── share/                (240 个辅助数据文件)
├── var/                  (运行时目录占位)
└── tmp/                  (临时目录占位)
```

---

## 1. bin/ — 可执行文件 (60 个)

### 核心 Shell 与工具链

| 文件 | 大小 | 说明 |
|------|------|------|
| `bash` | 880 KB | Bash 5.x — Termux 默认 Shell |
| `coreutils` | 1,308 KB | GNU coreutils 合集 |
| `busybox` | (通过 coreutils 覆盖) | BusyBox 基础工具 |
| `tar` | 467 KB | GNU tar |
| `gzip` / `gunzip` | 112 KB / 2 KB | GNU gzip |
| `bzip2` | 29 KB | bzip2 压缩 |
| `xz` | (符号链接) | xz 压缩 |
| `zstd` | (符号链接) | zstd 压缩 |

### 系统管理

| 文件 | 大小 | 说明 |
|------|------|------|
| `apt` / `apt-get` | 38 KB / (链接) | APT 包管理 |
| `dpkg` / `dpkg-deb` | (链接) / 150 KB | dpkg 包管理 |
| `ps` | 117 KB | 进程查看 |
| `top` | (链接) | 任务管理器 |
| `free` | 20 KB | 内存查看 |
| `df` | 0.1 KB | 磁盘查看 |
| `vmstat` | 27 KB | 虚拟内存统计 |
| `netstat` | (链接) | 网络统计 |
| `ifconfig` | 66 KB | 网络配置 |
| `hostname` | 44 KB | 主机名 |

### Node.js 生态 (核心)

| 文件 | 大小 | 说明 |
|------|------|------|
| `node` | **44,614 KB** | Node.js LTS (v18.x) |
| `npm` | (符号链接) | npm 包管理器 |
| `npx` | 3 KB | npm 包执行器 |

### Git

| 文件 | 大小 | 说明 |
|------|------|------|
| `git` | (符号链接) | Git 主程序 |
| `git-shell` | 1,906 KB | Git Shell |
| `git-upload-pack` | 3,483 KB | Git 上传协议 |
| `git-receive-pack` | 3,483 KB | Git 接收协议 |
| `git-upload-archive` | 3,483 KB | Git 归档协议 |
| `scalar` | 1,965 KB | Git 大仓库扩展 |

### Termux 专用工具

| 文件 | 说明 |
|------|------|
| `termux-am` / `termux-am-socket` | Activity Manager 接口 |
| `termux-wake-lock` / `termux-wake-unlock` | 唤醒锁管理 |
| `termux-open` / `termux-open-url` | 文件/URL 打开 |
| `termux-setup-storage` | 存储权限设置 |
| `termux-setup-package-manager` | 包管理器初始化 |
| `termux-change-repo` | 镜像源切换 |
| `termux-info` | 系统信息 |
| `termux-backup` / `termux-restore` | 备份/恢复 |
| `termux-reset` | 环境重置 |
| `termux-fix-shebang` | Shebang 修复 |
| `termux-reload-settings` | 设置重载 |

### 其他工具

| 文件 | 大小 | 说明 |
|------|------|------|
| `curl` | 305 KB | URL 传输 |
| `nano` | 344 KB | 文本编辑器 |
| `less` / `more` | (链接) | 分页器 |
| `grep` | (链接) | 文本搜索 |
| `sed` | 146 KB | 流编辑器 |
| `gawk` | 557 KB | GNU AWK |
| `diff` | 210 KB | 差异比较 |
| `patch` | 157 KB | 补丁工具 |
| `find` | (链接) | 文件查找 |
| `hexdump` | 49 KB | 十六进制查看 |
| `gpgv` | (链接) | GPG 签名验证 |
| `unzip` | (链接) | ZIP 解压 |
| `mkfs` | 9 KB | 文件系统创建 |

---

## 2. lib/ — 库和 npm 包 (2,133 个文件)

### 2.1 系统共享库 (.so)

#### libc / 基础库

| 文件 | 说明 |
|------|------|
| `libc++_shared.so` | Android C++ 标准库 |
| `libandroid-support.so` | Android 兼容层 |
| `libandroid-glob.so` | glob() 兼容 |
| `libandroid-posix-semaphore.so` | POSIX 信号量 |
| `libandroid-selinux.so` | SELinux 兼容 |

#### termux-exec (LD_PRELOAD 注入)

| 文件 | 说明 |
|------|------|
| `libtermux-exec_nos_c_tre.so` | Termux exec 包装 |
| `libtermux-exec-direct-ld-preload.so` | 直接 LD_PRELOAD |
| `libtermux-exec-ld-preload.so` | LD_PRELOAD 入口 |
| `libtermux-exec-linker-ld-preload.so` | Linker LD_PRELOAD |

#### termux-core

| 文件 | 说明 |
|------|------|
| `libtermux-core_nos_c_tre.so` | Termux 核心 C |
| `libtermux-core_nos_cxx_tre.so` | Termux 核心 C++ |

#### 压缩库

| 文件 | 大小 | 说明 |
|------|------|------|
| `libz.so.1.3.2` | 110 KB | zlib 压缩 |
| `libbz2.so.1.0.8` | 78 KB | bzip2 |
| `liblzma.so.5.8.3` | 174 KB | xz/lzma |
| `libzstd.so.1.5.7` | 844 KB | zstd |
| `liblz4.so` | 156 KB | lz4 |

#### 加密与安全

| 文件 | 大小 | 说明 |
|------|------|------|
| `libcrypto.so.3` | 3,468 KB | OpenSSL 加密 |
| `libssl.so.3` | 734 KB | OpenSSL SSL/TLS |
| `libgcrypt.so` | 1,101 KB | GNU 加密库 |
| `libgpg-error.so` | 144 KB | GPG 错误处理 |
| `libassuan.so` | 120 KB | IPC 库 (GPG 用) |
| `libgmp.so` | 642 KB | 大数运算 |
| `libgmpxx.so` | 52 KB | GMP C++ 绑定 |
| `libmd.so` | 75 KB | 消息摘要 |
| `ossl-modules/legacy.so` | 223 KB | OpenSSL 旧算法 |

#### 网络

| 文件 | 大小 | 说明 |
|------|------|------|
| `libcurl.so` | 631 KB | libcurl |
| `libssh2.so` | 381 KB | SSH2 客户端 |
| `libnghttp2.so` | 240 KB | HTTP/2 |
| `libnghttp3.so` | 444 KB | HTTP/3 |
| `libngtcp2.so` | 454 KB | QUIC 传输 |
| `libngtcp2_crypto_ossl.so` | 92 KB | QUIC + OpenSSL |
| `libgnutls.so` | 2,203 KB | GNU TLS |
| `libgnutls-dane.so` | 46 KB | DANE 协议 |
| `libgnutlsxx.so` | 106 KB | GnuTLS C++ |
| `libidn2.so` | 253 KB | 国际化域名 |
| `libunistring.so` | 1,881 KB | Unicode 字符串 |
| `libtirpc.so` | 226 KB | RPC 库 |
| `libunbound.so` | 1,215 KB | DNS 解析器 |

#### Node.js 运行时依赖（bootstrap 必须包含）

| 文件 | 大小 | 说明 |
|------|------|------|
| `libicudata.so.78.3` | **~30 MB** | ICU 字符数据 — Node.js 国际化必需 |
| `libicui18n.so.78.3` | **~3.5 MB** | ICU 国际化 — Node.js 必需 |
| `libicuuc.so.78.3` | **~3 MB** | ICU 核心 — Node.js 必需 |
| `libicuio.so.78.3` | ~100 KB | ICU I/O |
| `libicutu.so.78.3` | ~250 KB | ICU 工具 |
| `libicutest.so.78.3` | ~100 KB | ICU 测试 |
| `libsqlite3.so.0` | **~2 MB** | SQLite3 — npm 内部使用 |
| `libsqlite3.so.3.53.1` | (同 .so.0) | SQLite3 版本化 |
| `libcares.so` | ~200 KB | c-ares DNS — Node.js 网络必需 |

**以及对应的无版本符号链接：**
- `libicudata.so` → `libicudata.so.78` → `libicudata.so.78.3`
- `libicui18n.so` → `libicui18n.so.78` → `libicui18n.so.78.3`
- `libicuuc.so` → `libicuuc.so.78` → `libicuuc.so.78.3`
- `libsqlite3.so` → `libsqlite3.so.0`
- `libsqlite3.53.1.so` → `libsqlite3.so.3.53.1`

**合计 ICU + SQLite + c-ares: 约 39 MB，23 个 .so 文件**

#### 终端 / 文本处理

| 文件 | 大小 | 说明 |
|------|------|------|
| `libncursesw.so.6.5` | 429 KB | ncurses 宽字符 |
| `libreadline.so.8.3` | 367 KB | GNU readline |
| `libhistory.so.8.3` | 52 KB | readline 历史 |
| `libpcre2-8.so` | 636 KB | PCRE2 正则 8-bit |
| `libpcre2-16.so` | 597 KB | PCRE2 正则 16-bit |
| `libpcre2-32.so` | 572 KB | PCRE2 正则 32-bit |
| `libpcre2-posix.so` | 42 KB | PCRE2 POSIX 兼容 |

#### 系统工具依赖

| 文件 | 大小 | 说明 |
|------|------|------|
| `libapt-pkg.so` | 2,555 KB | APT 包管理核心 |
| `libapt-private.so` | 423 KB | APT 私有接口 |
| `libprocps.so` | 171 KB | procps 系统信息 |
| `libsmartcols.so` | 116 KB | 表格格式化 |
| `libacl.so` | 55 KB | ACL 权限 |
| `libattr.so` | 23 KB | 扩展属性 |
| `libcap-ng.so` | 37 KB | Linux capabilities |
| `libmpfr.so` | 330 KB | 多精度浮点 |
| `libiconv.so` | 168 KB | 字符编码转换 |
| `libcharset.so` | 11 KB | 字符集检测 |
| `libxxhash.so.0.8.3` | 65 KB | xxHash 高速哈希 |
| `liblsof.so` | 102 KB | lsof 插件库 |
| `libdrop_ambient.so` | 12 KB | capability 丢弃 |

#### 事件库

| 文件 | 大小 | 说明 |
|------|------|------|
| `libevent-2.1.so` | 352 KB | libevent 核心 |
| `libevent_core-2.1.so` | 291 KB | libevent core |
| `libevent_extra-2.1.so` | 58 KB | libevent extra |
| `libevent_pthreads-2.1.so` | 25 KB | libevent pthreads |

#### 其他插件目录

```
lib/
├── engines-3/
│   ├── capi.so           (OpenSSL engine)
│   └── loader_attic.so   (OpenSSL engine loader)
└── gawk/                 (GNU AWK 插件)
    ├── filefuncs.so
    ├── fnmatch.so
    ├── fork.so
    ├── inplace.so
    ├── intdiv.so
    ├── ordchr.so
    ├── readdir.so
    ├── readfile.so
    ├── revoutput.so
    ├── revtwoway.so
    ├── rwarray.so
    └── time.so
```

### 2.2 npm 包 (~1,927 个文件)

Bootstrap 内置了完整的 npm 包管理器（v9.x），安装在 `lib/node_modules/npm/` 下。

主要依赖包括：
- `@npmcli/*` — npm CLI 核心模块 (arborist, config, run-script 等)
- `pacote` — npm 包下载
- `cacache` — 内容寻址缓存
- `make-fetch-happen` — HTTP 请求
- `minipass` / `minizlib` — 流/压缩工具
- `tar` — tar 处理
- `semver` — 语义化版本
- `glob` / `tinyglobby` — 文件匹配
- `ini` / `nopt` — 配置解析
- `abbrev` / `proc-log` — 日志/缩写
- `ssri` — 完整性校验
- `json-parse-even-better-errors` — JSON 解析
- `npm-package-arg` — 包参数解析
- `npm-registry-fetch` — registry 请求
- 等等...

---

## 3. etc/ — 配置文件 (96 个文件)

### 核心配置

| 文件 | 说明 |
|------|------|
| `profile` | Termux 登录 Shell 配置 |
| `bash.bashrc` | Bash 全局配置 |
| `inputrc` | GNU readline 配置 |
| `nanorc` | nano 编辑器配置 |
| `motd` / `motd.sh` / `motd-playstore` | 登录欢迎信息 |
| `hosts` | 主机名解析 |
| `resolv.conf` | DNS 配置 |
| `netconfig` | 网络配置 |
| `bindresvport.blacklist` | 保留端口黑名单 |
| `termux-login.sh` | Termux 登录脚本 |

### profile.d/ — Shell 初始化片段

| 文件 | 说明 |
|------|------|
| `01-termux-bootstrap-second-stage-fallback.sh` | Bootstrap 第二阶段回退 |
| `gawk.sh` / `gawk.csh` | GNU AWK 环境变量 |
| `init-termux-properties.sh` | Termux 属性初始化 |

### apt/ — 包管理器配置

| 文件 | 说明 |
|------|------|
| `apt/sources.list` | 默认软件源 (packages.termux.dev) |
| `apt/apt.conf.d/` | APT 配置片段目录 |

### bash_completion.d/ — Bash 补全

| 文件 | 说明 |
|------|------|
| `git-completion.bash` | Git 命令行补全 |
| `git-prompt.sh` | Git 提示符 (显示分支) |
| `npm` | npm 命令行补全 |

### termux/mirrors/ — 镜像源列表 (71 个)

按地区分类:
- **chinese_mainland** (15 个)：清华 tuna、中科大 ustc、阿里云 aliyun、北外 bfsu、上交 sjtu 等
- **asia** (17 个)：多个亚洲镜像
- **europe** (17 个)：多个欧洲镜像
- **north_america** (多个)：北美镜像
- `default`：默认镜像配置

### alternatives/ — 替代方案

| 文件 | 说明 |
|------|------|
| `alternatives/README` | alternatives 系统说明 |

---

## 4. share/ — 辅助数据 (240 个文件)

### awk/ — GNU AWK 库 (29 个 .awk)

包含 `inplace.awk`、`join.awk`、`quicksort.awk`、`readfile.awk`、`shellquote.awk` 等实用库。

### bash-completion/completions/ — Bash 命令补全 (~100 个)

覆盖 `git`、`apt`、`curl`、`npm`、`man`、`nano`、`tmux`、`xz` 等命令的补全规则。

### ca-certificates/ — SSL 证书

用于 HTTPS 连接的根证书集合。

### git-core/templates/ — Git 模板

Git 仓库初始化时使用的默认模板（hooks 示例、默认描述等）。

### info/ — GNU Info 文档

### locale/ — 语言环境数据

### man/ — 手册页 (压缩)

### termux/ — Termux 属性

| 目录 | 说明 |
|------|------|
| `share/termux/properties/` | Termux 属性文件 |
| `share/termux/properties.sh` | 属性 Shell 导出 |

### misc/ — 杂项数据

包括终端能力数据库 (terminfo)、颜色配置等。

---

## 5. 符号链接 (SYMLINKS.txt, 1,160 条)

主要用于:
- `.so` 版本链接 (如 `libreadline.so.8` → `libreadline.so.8.3`)
- busybox 多命令 (如 `gunzip` → `gzip`, `unzip` → `busybox`)
- coreutils 多命令 (如 `md5sum` → `coreutils`)
- Termux 脚本链接

---

## 6. 与 APK 中其他组件的关系

### APK 结构 (arm64 工作基线，411 MB)

```
termux-app_debug_arm64-v8a1.apk
├── classes.dex (多个)              ~14 MB   Java 字节码
├── lib/arm64-v8a/
│   ├── libtermux-bootstrap.so      234 MB   ← 本清单所述 bootstrap
│   └── libtermux.so                9 KB     终端模拟器
├── assets/
│   ├── SillyTavern.tar             412 MB   SillyTavern payload
│   ├── payload-manifest.json       0.5 KB   Payload 元数据
│   └── stapk/                      12 个脚本
│       ├── stapk-init               5 KB    初始化脚本
│       ├── stapk-runtime            2.5 KB  受管运行时包装脚本
│       ├── stapk-start              0.3 KB  兼容启动入口
│       ├── stapk-stop               2.6 KB  停止脚本
│       ├── stapk-status             3.2 KB  状态检查
│       ├── stapk-update             3 KB    Git 更新
│       ├── stapk-backup             2 KB    备份
│       ├── stapk-restore            2 KB    恢复
│       ├── stapk-rollback           2 KB    Git 回滚
│       ├── stapk-report             2 KB    诊断报告
│       ├── stapk-open-url           0.5 KB  打开浏览器
│       └── stapk-list-backups       0.8 KB  列出备份
├── res/                            资源文件
└── AndroidManifest.xml             清单文件
```

### 运行时目录映射

APK 安装后，TermuxInstaller 将 `libtermux-bootstrap.so` 中的 zip 解压到（TermuxInstaller 会根据运行时包名自动修正路径）:

```
/data/data/com.stapk.termux/files/usr/
├── bin/    ← bootstrap 的 bin/
├── lib/    ← bootstrap 的 lib/
├── etc/    ← bootstrap 的 etc/
├── share/  ← bootstrap 的 share/
├── var/    ← bootstrap 的 var/
└── tmp/    ← bootstrap 的 tmp/
```

### 当前交付约定

- 手机安装包统一使用 `arm64-v8a` split APK。
- `universal` debug APK 会额外打入 `x86_64` bootstrap，体积会比 `arm64-v8a` 包明显更大，不作为手机测试基线。
- `assets/stapk/` 下所有脚本必须保持 `LF` 换行；如果出现 `CRLF`，设备侧会报 `pipefail` / `$'\r'` / `unexpected end of file`。

---

## ⚠️ 历史问题记录（已修复）

以下问题在 2026-05-29 的调试过程中发现并修复：

| 问题 | 现象 | 修复 |
|------|------|------|
| Bootstrap 缺 ICU/SQLite3/c-ares | node 无法启动，初始化失败 | 从本工作版 APK 提取完整 bootstrap 替换 |
| NDK 增量构建缓存 | 替换 zip 后 SO 未重新编译 | 清理 `.cxx` 和 `build/intermediates` 缓存 |
| Payload 文件名不匹配 | Java 找 `.tar.gz`，APK 中 aapt 解压为 `.tar` | `PAYLOAD_ASSET_FILES` 改为 `SillyTavern.tar` |
| 包名统一 | `com.termux` → `com.stapk.termux` | shebang/C/JNI 全部更新为 `com.stapk.termux` |
| stapk 脚本混入 bootstrap | 脚本在 `bin/` 和 `assets/` 两份 | 只保留 `assets/stapk/`，bootstrap 内不包含 |
| bootstrap 中的 Node 工具 shebang 仍指向旧包名 | `npm --version` 返回 `unknown`，SillyTavern 启动时在 `npm install` 阶段直接退出 | 应用启动时修复 `$PREFIX/bin` 与 corepack shim 中残留的 `com.termux` shebang |
