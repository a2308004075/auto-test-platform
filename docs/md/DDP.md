# auto-test-platform 开发环境准备 SOP

> 版本：v1.1  
> 适用对象：编程初学者 / 项目新成员  
> 操作系统：Windows 11  
> 开发工具：IntelliJ IDEA（前端 + 后端共用）

---

## 目录

- [一、总览：你需要准备什么](#一总览你需要准备什么)
- [二、基础软件安装](#二基础软件安装)
  - [2.1 JDK 1.8 安装与验证](#21-jdk-18-安装与验证)
  - [2.2 Node.js 安装](#22-nodejs-安装)
  - [2.3 Git 安装与配置](#23-git-安装与配置)
  - [2.4 IntelliJ IDEA 安装与配置](#24-intellij-idea-安装与配置)
- [三、中间件安装](#三中间件安装)
  - [选择安装方式](#选择安装方式)
  - [方式 A：Docker 容器化安装（需管理员权限）](#方式-adocker-容器化安装需管理员权限)
    - [A.1 Docker Desktop 安装](#a1-docker-desktop-安装)
    - [A.2 MySQL 8.0 启动](#a2-mysql-80-启动)
    - [A.3 Redis 7.x 启动](#a3-redis-7x-启动)
    - [A.4 RabbitMQ 启动](#a4-rabbitmq-启动)
    - [A.5 Qdrant 启动](#a5-qdrant-启动)
    - [A.6 XXL-Job 启动（可选，Phase 3 再用）](#a6-xxl-job-启动可选phase-3-再用)
    - [A.7 一键启动所有中间件](#a7-一键启动所有中间件)
  - [方式 B：原生 Windows 安装（无需管理员权限）](#方式-b原生-windows-安装无需管理员权限)
    - [B.1 MySQL 8.0 安装](#b1-mysql-80-安装)
    - [B.2 Redis 安装](#b2-redis-安装)
    - [B.3 RabbitMQ 安装](#b3-rabbitmq-安装)
    - [B.4 Qdrant 安装](#b4-qdrant-安装)
    - [B.5 各中间件访问信息汇总](#b5-各中间件访问信息汇总)
- [四、后端项目工程搭建](#四后端项目工程搭建)
  - [4.1 Maven 多模块项目结构](#41-maven-多模块项目结构)
  - [4.2 Maven POM 配置](#42-maven-pom-配置)
  - [4.3 包结构创建顺序](#43-包结构创建顺序)
  - [4.4 IDEA 后端项目导入](#44-idea-后端项目导入)
  - [4.5 验证后端启动](#45-验证后端启动)
- [五、前端项目工程搭建](#五前端项目工程搭建)
  - [5.1 使用 Vite 创建 Vue 3 项目](#51-使用-vite-创建-vue-3-项目)
  - [5.2 安装核心依赖](#52-安装核心依赖)
  - [5.3 前端项目目录结构](#53-前端项目目录结构)
  - [5.4 IDEA 前端项目导入](#54-idea-前端项目导入)
  - [5.5 验证前端启动](#55-验证前端启动)
- [六、开发规范与编码配置](#六开发规范与编码配置)
- [七、环境验证清单](#七环境验证清单)
- [八、常见问题排查](#八常见问题排查)

---

## 一、总览：你需要准备什么

本项目采用**前后端分离**架构，开发环境分为两大部分：

| 类别 | 技术栈 | 说明 |
|------|--------|------|
| **前端** | Vue 3 + TypeScript + Vite + Element Plus 2.x | 独立的 SPA 工程，用 IDEA 打开 |
| **后端** | Java 1.8 + Spring Boot 2.7（单体应用） | Maven 工程，用 IDEA 打开 |
| **中间件** | MySQL 8.0 + Redis 7.x + RabbitMQ + Qdrant | Docker 容器 或 原生 Windows 安装（二选一）；Qdrant 为知识库模块的向量数据库 |

**你需要依次完成以下步骤：**

```
① 安装基础软件（JDK、Node.js、Git、IDEA）
② 安装中间件（Docker 方式 或 原生 Windows 方式，二选一）
③ 搭建后端 Spring Boot 工程
④ 搭建前端 Vue 3 工程
⑤ 配置开发规范（编码、格式化等）
⑥ 验证全部环境就绪
```

---

## 二、基础软件安装

### 2.1 JDK 1.8 安装与验证

后端使用 Java 1.8 版本，推荐安装 JDK 1.8.0_451 或更新版本。

#### 第 1 步：下载 JDK

**方式一：Oracle JDK（官方）**

```
https://www.oracle.com/java/technologies/javase/javase8u211-later-archive-downloads.html
```

> ⚠️ Oracle JDK 8 较新更新版本需要登录 Oracle 账号才能下载（免费注册）。

**方式二（推荐）：免费 OpenJDK 发行版（无需登录，国内下载更快）**

| 发行版 | 说明 | 国内下载地址 |
|--------|------|-------------|
| **Adoptium Temurin** | Eclipse 基金会维护，社区主流，完全兼容 Oracle JDK | 清华 TUNA：https://mirrors.tuna.tsinghua.edu.cn/Adoptium/8/jdk/x64/windows/ （选择 `.msi` 或 `.zip` 文件） |
| **Amazon Corretto 8** | 亚马逊维护，免费商用，长期支持 | https://corretto.aws/corretto-8-Downloads.html （选择 `Windows x64 JDK` `.msi` 或 `.zip`） |
| **Alibaba Dragonwell 8** | 阿里巴巴维护，针对国内优化，免费商用 | https://dragonwell-jdk.io/ （选择 Windows x64 版本） |

> **说明**：以上三个发行版均完全兼容 Java 8 标准，可无缝替代 Oracle JDK，后端代码无需任何修改。**推荐优先使用 Adoptium Temurin**，清华镜像下载速度最快。

#### 第 2 步：安装

- 如果下载的是 `.msi` 安装包，双击运行，选择安装路径为 `D:\software\jdk-1.8`
- 如果下载的是 `.zip` 压缩包，解压到 `D:\software\jdk-1.8`

#### 第 3 步：配置环境变量

1. 右键「此电脑」→「属性」→「高级系统设置」→「环境变量」
2. 新建系统变量：
   ```
   变量名：JAVA_HOME
   变量值：D:\software\jdk-1.8
   ```
3. 编辑 `Path` 变量，添加：
   ```
   %JAVA_HOME%\bin
   ```

#### 第 4 步：验证安装

打开 PowerShell，执行：

```powershell
java -version
javac -version
```

应显示 `java version "1.8.0_xxx"` 或类似版本号（如 Temurin 可能显示 `openjdk version "1.8.0_xxx"`，这是正常的）。

```powershell
echo $env:JAVA_HOME
```

应显示 `D:\software\jdk-1.8`。

---

### 2.2 Node.js 安装

前端使用 Vue 3 + Vite，需要 Node.js 运行环境。

**步骤：**

1. 访问 Node.js 官网下载 **LTS 版本**（推荐 20.x 或更新）：
   ```
   https://nodejs.org/
   ```
   选择 `Windows Installer (.msi)` 下载并安装。

   > **国内加速下载**：如果官网下载速度慢，可使用以下国内镜像：
   > - 华为云镜像：https://mirrors.huaweicloud.com/nodejs/ （选择对应版本目录下的 `.msi` 文件）
   > - 淘宝镜像：https://npmmirror.com/mirrors/node/ （选择对应版本目录下的 `.msi` 文件）
   > - 清华 TUNA 镜像：https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/ （选择对应版本目录下的 `.msi` 文件）

2. 安装时勾选「Automatically install necessary tools」选项。

3. **验证安装**：打开 PowerShell，执行：
   ```powershell
   node -v
   npm -v
   ```
   应分别显示 `v20.x.x` 和 `10.x.x` 或更高版本号。

4. **配置 npm 国内镜像**（加速依赖下载）：
   ```powershell
   npm config set registry https://registry.npmmirror.com
   ```

5. **安装 pnpm**（推荐的包管理器，速度更快）：
   ```powershell
   npm install -g pnpm
   pnpm -v
   ```

---

### 2.3 Git 安装与配置

#### 第 1 步：下载 Git

**官方地址**：
```
https://git-scm.com/downloads
```
选择 `Windows` 版本下载并安装。

> **国内加速下载**：如果官网下载速度慢，可使用以下国内镜像：
> - 淘宝镜像：https://registry.npmmirror.com/binary.html?path=git-for-windows/ （选择最新版本目录下的 `Git-*-64-bit.exe` 文件）
> - 华为云镜像：https://mirrors.huaweicloud.com/git-for-windows/ （选择最新版本目录下的 `Git-*-64-bit.exe` 文件）
> - 清华 TUNA 镜像：https://mirrors.tuna.tsinghua.edu.cn/github-release/git-for-windows/git/ （选择最新版本的 `.exe` 安装包）

#### 第 2 步：验证安装

```powershell
git --version
```

#### 第 3 步：配置用户信息

如未配置过：
```powershell
git config --global user.name "你的姓名"
git config --global user.email "你的邮箱@example.com"
```

#### 第 4 步：配置换行符（Windows 开发必备）

```powershell
git config --global core.autocrlf true
```

---

### 2.4 IntelliJ IDEA 安装与配置

你的系统中已安装 IDEA 2025.2.1，以下是项目所需的配置调整。

#### 2.4.1 安装必要插件

打开 IDEA → `File` → `Settings` → `Plugins`，搜索并安装以下插件：

| 插件名 | 用途 | 必要性 |
|--------|------|--------|
| **Vue.js** | Vue 3 前端开发支持（语法高亮、代码补全） | 必装 |
| **Lombok** | 简化 Java 实体类代码 | 必装 |
| **MyBatisX** | MyBatis Mapper 跳转与代码补全 | 必装 |
| **Rainbow Brackets** | 彩色括号匹配，提升代码可读性 | 推荐 |
| **.env files support** | 环境变量文件语法高亮 | 推荐 |

#### 2.4.2 配置 JDK 1.8

1. 打开 IDEA → `File` → `Project Structure` → `SDKs`
2. 点击 `+` → `Add JDK`，选择 JDK 1.8 安装目录（`D:\software\jdk-1.8`）
3. 在 `Project` 选项卡中，将 `SDK` 设置为 JDK 1.8，`Language level` 选择 `8`

#### 2.4.3 配置文件编码（重要！）

1. 打开 IDEA → `File` → `Settings` → `Editor` → `File Encodings`
2. 将以下三项全部设置为 **UTF-8**：
   - `Global Encoding`：UTF-8
   - `Project Encoding`：UTF-8
   - `Default encoding for properties files`：UTF-8
3. 勾选 `Transparent native-to-ascii conversion`

#### 2.4.4 配置 Maven

IDEA 自带 Maven，但建议使用外部 Maven 以获得更好的控制：

1. 下载 Maven：
   - **官方地址**：https://maven.apache.org/download.cgi （选择 `Binary zip archive`）
   - **国内加速下载**（推荐）：
     - 阿里云镜像：https://mirrors.aliyun.com/apache/maven/maven-3/ （选择最新 3.9.x 版本目录下的 `-bin.zip` 文件）
     - 清华 TUNA 镜像：https://mirrors.tuna.tsinghua.edu.cn/apache/maven/maven-3/ （选择最新 3.9.x 版本目录下的 `-bin.zip` 文件）
     - 华为云镜像：https://mirrors.huaweicloud.com/apache/maven/maven-3/ （选择最新 3.9.x 版本目录下的 `-bin.zip` 文件）
2. 解压到 `D:\software\apache-maven-3.9.x`（路径不要有中文和空格）
3. 配置环境变量：
   ```
   变量名：MAVEN_HOME
   变量值：D:\software\apache-maven-3.9.x
   
   Path 中添加：
   %MAVEN_HOME%\bin
   ```
4. 编辑 Maven 配置文件 `D:\software\apache-maven-3.9.x\conf\settings.xml`，添加阿里云镜像（加速依赖下载）：
   ```xml
   <mirrors>
     <mirror>
       <id>aliyun</id>
       <name>Aliyun Maven Mirror</name>
       <url>https://maven.aliyun.com/repository/public</url>
       <mirrorOf>central</mirrorOf>
     </mirror>
   </mirrors>
   ```
5. 在 IDEA 中配置：`File` → `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Maven`
   - `Maven home path`：`D:\software\apache-maven-3.9.x`
   - `User settings file`：`D:\software\apache-maven-3.9.x\conf\settings.xml`（勾选 Override）
   - `Local repository`：`D:\software\maven-repo`（勾选 Override，自定义仓库路径）

6. **验证 Maven**：
   ```powershell
   mvn -version
   ```
   应显示 Maven 版本号和 Java 1.8 信息。

---

## 三、中间件安装

本项目依赖 MySQL、Redis、RabbitMQ 等中间件。提供两种安装方式，请根据自身情况选择其一：

### 选择安装方式

| | 方式 A：Docker 容器化 | 方式 B：原生 Windows 安装 |
|------|----------------------|-------------------------|
| **管理员权限** | ✅ 需要 | ❌ 不需要 |
| **WSL 2** | ✅ 需要 | ❌ 不需要 |
| **安装难度** | 低（一条命令搞定） | 中（需逐个下载安装） |
| **管理便利性** | 高（统一启停、易重置） | 一般（各自独立运行） |
| **推荐场景** | 有管理员权限、长期开发 | 无管理员权限、受限环境 |

> **如果你没有管理员权限或无法开启 WSL，请直接跳到 [方式 B：原生 Windows 安装](#方式-b原生-windows-安装无需管理员权限)。**

---

## 方式 A：Docker 容器化安装（需管理员权限）

以下使用 **Docker** 统一管理中间件，避免在本机逐一安装，干净且易于重置。

### A.1 Docker Desktop 安装

**步骤：**

1. 访问 Docker 官网下载 Docker Desktop for Windows：
   ```
   https://www.docker.com/products/docker-desktop/
   ```

   > **国内加速下载**：如果官网下载速度慢，可使用以下国内镜像：
   > - 阿里云镜像：https://mirrors.aliyun.com/docker-toolbox/windows/docker-for-windows/ （选择最新版本 `.exe` 文件）
   > - 中科大镜像：https://mirrors.ustc.edu.cn/docker-ce/ （需进入对应目录查找安装包）

2. 安装前确保：
   - Windows 11 已启用 **WSL 2**（Windows Subsystem for Linux 2）
   - 如未启用，在 PowerShell（管理员）执行：
     ```powershell
     wsl --install
     ```
   - 安装完成后**重启电脑**。

3. 运行 Docker Desktop 安装程序，按提示完成安装。

4. 启动 Docker Desktop，等待左下角显示绿色 `Engine running` 状态。

5. **验证安装**：
   ```powershell
   docker --version
   docker compose version
   ```

6. **配置 Docker 国内镜像加速**（推荐）：
   - 打开 Docker Desktop → `Settings` → `Docker Engine`
   - 在 JSON 配置中添加：
     ```json
     {
       "registry-mirrors": [
         "https://mirror.ccs.tencentyun.com",
         "https://docker.mirrors.ustc.edu.cn"
       ]
     }
     ```
   - 点击 `Apply & Restart`。

---

### A.2 MySQL 8.0 启动

在项目根目录 `D:\develop\auto-test-platform` 下创建 `docker` 文件夹，用于存放 Docker 配置。

先单独启动 MySQL 验证：

```powershell
docker run -d `
  --name pp-mysql `
  -p 3306:3306 `
  -e MYSQL_ROOT_PASSWORD=pp2024 `
  -e MYSQL_DATABASE=auto_test_platform `
  -v pp-mysql-data:/var/lib/mysql `
  mysql:8.0
```

**连接验证**：
```powershell
docker exec -it pp-mysql mysql -uroot -ppp2024 -e "SELECT VERSION();"
```

应显示 MySQL 8.0.x 版本信息。

> **说明**：`pp2024` 是开发环境密码，仅用于本地开发，请勿用于生产环境。

---

### A.3 Redis 7.x 启动

```powershell
docker run -d `
  --name pp-redis `
  -p 6379:6379 `
  -v pp-redis-data:/data `
  redis:7-alpine
```

**连接验证**：
```powershell
docker exec -it pp-redis redis-cli PING
```

应返回 `PONG`。

---

### A.4 RabbitMQ 启动

```powershell
docker run -d `
  --name pp-rabbitmq `
  -p 5672:5672 `
  -p 15672:15672 `
  -e RABBITMQ_DEFAULT_USER=admin `
  -e RABBITMQ_DEFAULT_PASS=admin123 `
  -v pp-rabbitmq-data:/var/lib/rabbitmq `
  rabbitmq:3-management-alpine
```

**验证**：打开浏览器访问 `http://localhost:15672`，使用 `admin / admin123` 登录管理界面。

> 端口说明：`5672` 是 AMQP 协议端口（程序连接用），`15672` 是 Web 管理界面端口。

---

### A.5 Qdrant 启动

Qdrant 是知识库模块（RAG 检索增强问答）使用的向量数据库。后端通过 REST API 与 Qdrant 交互，无需额外 SDK。

采用单容器模式（内嵌存储，适合开发环境）：

```powershell
docker run -d `
  --name pp-qdrant `
  -p 6333:6333 `
  -p 6334:6334 `
  -v pp-qdrant-data:/qdrant/storage `
  qdrant/qdrant
```

**验证**：健康检查接口返回 `OK` 即启动成功：

```powershell
curl http://localhost:6333/readyz
```

> 端口说明：`6333` 是 REST API 端口（后端 HTTP 调用），`6334` 是 gRPC 端口。

---

### A.6 XXL-Job 启动（可选，Phase 3 再用）

XXL-Job 用于分布式定时调度（测试计划定时执行），在项目 Phase 3 阶段才需要。现阶段可以先不部署。

```powershell
# Phase 3 时再执行
docker run -d `
  --name pp-xxl-job `
  -p 8090:8080 `
  -e PARAMS="--spring.datasource.url=jdbc:mysql://host.docker.internal:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai --spring.datasource.username=root --spring.datasource.password=pp2024" `
  xuxueli/xxl-job-admin:2.4.1
```

---

### A.7 一键启动所有中间件

为了方便日常开发，在项目中创建统一的 Docker Compose 文件，一键启停所有中间件。

在项目根目录下创建 `docker/docker-compose.yml`：

```yaml
version: "3.8"

services:
  mysql:
    image: mysql:8.0
    container_name: pp-mysql
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: pp2024
      MYSQL_DATABASE: auto_test_platform
      TZ: Asia/Shanghai
    volumes:
      - pp-mysql-data:/var/lib/mysql
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci

  redis:
    image: redis:7-alpine
    container_name: pp-redis
    ports:
      - "6379:6379"
    volumes:
      - pp-redis-data:/data

  rabbitmq:
    image: rabbitmq:3-management-alpine
    container_name: pp-rabbitmq
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: admin
      RABBITMQ_DEFAULT_PASS: admin123
    volumes:
      - pp-rabbitmq-data:/var/lib/rabbitmq

  qdrant:
    image: qdrant/qdrant
    container_name: pp-qdrant
    ports:
      - "6333:6333"
      - "6334:6334"
    volumes:
      - pp-qdrant-data:/qdrant/storage

volumes:
  pp-mysql-data:
  pp-redis-data:
  pp-rabbitmq-data:
  pp-qdrant-data:
```

**一键启动**：
```powershell
cd D:\develop\auto-test-platform\docker
docker compose up -d
```

**一键停止**：
```powershell
cd D:\develop\auto-test-platform\docker
docker compose down
```

**查看运行状态**：
```powershell
docker compose ps
```

**各中间件访问信息汇总**：

| 中间件 | 地址 | 端口 | 账号 / 密码 |
|--------|------|------|-------------|
| MySQL | localhost | 3306 | root / pp2024 |
| Redis | localhost | 6379 | 无密码 |
| RabbitMQ 管理界面 | http://localhost:15672 | 15672 | admin / admin123 |
| Qdrant | localhost | 6333 | 无认证（REST API） |

---

## 方式 B：原生 Windows 安装（无需管理员权限）

以下所有中间件均采用**下载 ZIP + 解压运行**的方式安装，**无需管理员权限**，无需 Docker / WSL。所有工具统一安装到 `D:\software\` 目录下（路径中不要有中文和空格）。

> **约定**：MySQL root 密码统一为 `pp2024`，与方式 A 保持一致，便于后端配置文件共用。

---

### B.1 MySQL 8.0 安装

#### 第 1 步：下载

访问 MySQL Community Server 下载页面：

```
https://dev.mysql.com/downloads/mysql/
```

- 选择 `Windows (x86, 64-bit), ZIP Archive`
- 下载 8.0.x 版本（如 `mysql-8.0.xx-winx64.zip`）
- 文件大小约 200MB+

> **国内加速下载**：如果官网下载速度慢，可使用以下国内镜像：
> - 华为云镜像：https://mirrors.huaweicloud.com/mysql/Downloads/MySQL-8.0/ （选择 `mysql-8.0.xx-winx64.zip`）
> - 清华 TUNA 镜像：https://mirrors.tuna.tsinghua.edu.cn/mysql/downloads/MySQL-8.0/ （选择 `mysql-8.0.xx-winx64.zip`）
> - 中科大镜像：https://mirrors.ustc.edu.cn/mysql-ftp/Downloads/MySQL-8.0/ （选择 `mysql-8.0.xx-winx64.zip`）

> **免登录提示**：页面底部有 `No thanks, just start my download.` 链接，点击即可跳过 Oracle 账号登录。

#### 第 2 步：解压

将 ZIP 解压到 `D:\software\mysql-8.0`。解压后目录结构大致如下：

```
D:\software\mysql-8.0\
├── bin\
├── lib\
├── share\
├── LICENSE
├── README
└── ...
```

#### 第 3 步：创建配置文件

在 `D:\software\mysql-8.0\` 下新建 `my.ini` 文件，写入以下内容：

```ini
[mysqld]
port=3306
basedir=D:/tools/mysql-8.0
datadir=D:/tools/mysql-8.0/data
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
authentication_policy=caching_sha2_password

[client]
port=3306
default-character-set=utf8mb4
```

> **注意**：路径中使用正斜杠 `/`，不要用反斜杠 `\`。

#### 第 4 步：初始化数据库

打开 PowerShell，执行：

```powershell
D:\software\mysql-8.0\bin\mysqld --initialize-insecure --console
```

该命令会：
- 自动创建 `data` 目录
- 初始化系统数据库
- 创建 root 用户，**初始密码为空**

> 终端输出的最后一行会显示临时信息，例如 `root@localhost` 并提示密码为空。这是正常的。

#### 第 5 步：设置 root 密码

先启动 MySQL（见第 6 步），然后在另一个 PowerShell 窗口中执行：

```powershell
D:\software\mysql-8.0\bin\mysql -uroot --skip-password -e "ALTER USER 'root'@'localhost' IDENTIFIED BY 'pp2024';"
```

#### 第 6 步：启动 MySQL

```powershell
D:\software\mysql-8.0\bin\mysqld --console
```

> **说明**：此命令在前台运行 MySQL，窗口关闭则 MySQL 停止。建议开一个独立的 PowerShell 窗口运行。
>
> **停止 MySQL**：在另一个 PowerShell 窗口执行：
> ```powershell
> D:\software\mysql-8.0\bin\mysqladmin -uroot -ppp2024 shutdown
> ```

#### 第 7 步：验证连接

打开另一个 PowerShell 窗口（MySQL 保持运行），执行：

```powershell
D:\software\mysql-8.0\bin\mysql -uroot -ppp2024 -e "SELECT VERSION();"
```

应显示 `8.0.x` 版本信息。

#### 创建项目数据库

```powershell
D:\software\mysql-8.0\bin\mysql -uroot -ppp2024 -e "CREATE DATABASE IF NOT EXISTS auto_test_platform DEFAULT CHARACTER SET utf8mb4;"
```

---

### B.2 Redis 安装

Redis 官方不提供 Windows 原生版本。推荐使用社区维护的 Windows 移植版。

#### 第 1 步：下载

从以下地址下载 Redis for Windows（社区移植版）：

```
https://github.com/tporadowski/redis/releases
```

- 选择最新稳定版（如 5.0.14.1 或更新）
- 下载 `Redis-x64-x.x.x.zip`（ZIP 格式，非 MSI）

> **国内加速下载**：如果 GitHub 下载速度慢，可使用 GitHub 加速代理：
> - ghfast 加速：将下载链接前缀替换为 `https://ghfast.top/`，例如：
>   ```
>   https://ghfast.top/https://github.com/tporadowski/redis/releases/download/v5.0.14.1/Redis-x64-5.0.14.1.zip
>   ```
> - ghproxy 加速：将下载链接前缀替换为 `https://gh-proxy.com/`，例如：
>   ```
>   https://gh-proxy.com/https://github.com/tporadowski/redis/releases/download/v5.0.14.1/Redis-x64-5.0.14.1.zip
>   ```

> **备选方案**：如果上述链接不可用，也可以使用 **Memurai**（Redis 兼容的 Windows 原生服务器）：
> ```
> https://www.memurai.com/get-memurai
> ```
> Memurai Developer 版免费，API 完全兼容 Redis，可无缝替代。

#### 第 2 步：解压

将 ZIP 解压到 `D:\software\redis`。

#### 第 3 步：启动 Redis

```powershell
D:\software\redis\redis-server.exe
```

> Redis 默认监听 `localhost:6379`，无需额外配置。如需修改端口，可编辑同目录下的 `redis.windows.conf` 文件。
>
> 同样建议开一个独立 PowerShell 窗口运行。

#### 第 4 步：验证连接

在另一个 PowerShell 窗口执行：

```powershell
D:\software\redis\redis-cli.exe PING
```

应返回 `PONG`。

---

### B.3 RabbitMQ 安装

RabbitMQ 依赖 **Erlang 运行时环境**，需要先安装 Erlang，再安装 RabbitMQ。两者都使用 ZIP 解压方式，无需管理员权限。

> **⚠️ 版本兼容关系（非常重要）**
>
> RabbitMQ 与 Erlang 版本必须匹配，版本不兼容会导致启动失败（例如 `incompatible_feature_flags` / `unknown_instruction` 错误）。
>
> | RabbitMQ 版本 | 兼容的 Erlang 版本 | 说明 |
> |--------------|-------------------|------|
> | **3.13.x** | **26.0 ~ 26.2.x** | 推荐组合，与 Spring Boot 2.7 兼容性最好 |
> | **4.0.x ~ 4.1.x** | **26.2 ~ 27.x** | 需要 Erlang 26.2 起步 |
> | **4.2.x ~ 4.3.x** | **27.0 ~ 27.x** | 需要 Erlang 27 起步，**不支持 Erlang 28/29** |
>
> **本项目推荐**：RabbitMQ `3.13.x` + Erlang `26.2.x`。如果你已确定使用 RabbitMQ 4.3.4，则必须搭配 Erlang `27.x`（如 27.3.x），**切勿使用 Erlang 28 或 29**。

#### 第 1 步：下载 Erlang

```
https://www.erlang.org/downloads
```

- 根据上面表格选择对应的 Erlang 大版本
- 选择 `Windows 64-bit Binary File`（如 `OTP-26.2.x_win64.exe` 或 `OTP-27.x.x_win64.exe`）
- 这是一个自解压安装包，运行后选择安装到 `D:\software\erlang`
> 如果 .exe 安装器要求管理员权限，可以尝试从 https://github.com/erlang/otp/releases 下载 `otp_win64_x.x.zip` 手动解压到 `D:\software\erlang`。GitHub 下载加速可参考上方 Redis 章节中的 GitHub 加速代理方法。

#### 第 2 步：下载 RabbitMQ

```
https://github.com/rabbitmq/rabbitmq-server/releases
```

- 根据上面表格选择 RabbitMQ 版本
- 下载 `rabbitmq-server-windows-x86_64-x.x.x.zip`（注意是 ZIP 格式，不是 exe）

> **国内加速下载**：如果 GitHub 下载速度慢，可使用 GitHub 加速代理（方法同 Redis 章节）：
> - ghfast 加速：在下载链接前加 `https://ghfast.top/`
> - ghproxy 加速：在下载链接前加 `https://gh-proxy.com/`
> 
> 例如：
> ```
> https://ghfast.top/https://github.com/rabbitmq/rabbitmq-server/releases/download/v3.13.x/rabbitmq-server-windows-x86_64-3.13.x.zip
> ```

#### 第 3 步：解压

将 ZIP 解压到 `D:\software\rabbitmq`。解压后关键目录为 `D:\software\rabbitmq\sbin\`。

#### 第 4 步：启用管理插件

在 PowerShell 中执行（需要先设置 Erlang 路径）：

```powershell
$env:ERLANG_HOME = "D:\software\erlang"
D:\software\rabbitmq\sbin\rabbitmq-plugins.bat enable rabbitmq_management
```

#### 第 5 步：启动 RabbitMQ

```powershell
$env:ERLANG_HOME = "D:\software\erlang"
D:\software\rabbitmq\sbin\rabbitmq-server.bat
```

> **提示**：由于每次启动都需要设置 `ERLANG_HOME`，建议在 `D:\software\` 下创建启动脚本 `start-rabbitmq.bat`：
> ```bat
> @echo off
> set ERLANG_HOME=D:\software\erlang
> D:\software\rabbitmq\sbin\rabbitmq-server.bat
> ```
> 以后双击此脚本即可启动 RabbitMQ。

#### 第 6 步：验证

打开浏览器访问 `http://localhost:15672`，使用 `admin / admin123` 登录管理界面。

> **账号说明**：RabbitMQ 默认创建 `guest / guest` 账号，但仅限 localhost 访问。如需自定义账号，可通过管理界面的 Admin 选项卡创建 `admin / admin123` 用户并赋予 Administrator 角色，或通过命令行：
> ```powershell
> D:\software\rabbitmq\sbin\rabbitmqctl.bat add_user admin admin123
> D:\software\rabbitmq\sbin\rabbitmqctl.bat set_user_tags admin administrator
> D:\software\rabbitmq\sbin\rabbitmqctl.bat set_permissions -p / admin ".*" ".*" ".*"
> ```

> **端口说明**：`5672` 是 AMQP 协议端口（程序连接用），`15672` 是 Web 管理界面端口。

---

### B.4 Qdrant 安装

Qdrant 是知识库模块（RAG 检索增强问答）使用的向量数据库。后端通过 REST API 与 Qdrant 交互，无需额外 SDK 依赖。

> **说明**：Qdrant 官方提供 Windows 版二进制，单文件运行，非常轻量。

#### 第 1 步：下载

访问 Qdrant GitHub Releases 页面：

```
https://github.com/qdrant/qdrant/releases
```

选择最新版本（如 `v1.x.x`），下载 `qdrant-x86_64-pc-windows-msvc.zip` 压缩包。

> **国内加速下载**：如果 GitHub 下载速度慢，可使用代理加速（在原下载地址前加代理前缀）：
> - ghfast 代理：https://ghfast.top/https://github.com/qdrant/qdrant/releases/latest/download/qdrant-x86_64-pc-windows-msvc.zip
> - ghproxy 代理：https://ghproxy.net/https://github.com/qdrant/qdrant/releases/latest/download/qdrant-x86_64-pc-windows-msvc.zip

> 官方 Windows 安装说明：https://qdrant.tech/documentation/guides/installation/#windows

#### 第 2 步：解压

将 ZIP 解压到 `D:\software\qdrant`。解压后目录结构大致如下：

```
D:\software\qdrant\
├── qdrant.exe
├── config.yaml
└── ...
```

#### 第 3 步：启动 Qdrant

```powershell
D:\software\qdrant\qdrant.exe
```

> **提示**：项目中已提供启动脚本 `docs\script\start-qdrant.bat`，双击即可启动（自动检测安装路径，找不到 `qdrant.exe` 时会提示改用 Docker 方式）。

#### 第 4 步：验证

健康检查接口返回 `OK` 即启动成功：

```powershell
curl http://localhost:6333/readyz
```

> **端口说明**：`6333` 是 REST API 端口（后端 HTTP 调用），`6334` 是 gRPC 端口。后端连接配置位于 `application.yml` 的 `knowledge.qdrant` 段（默认 `localhost:6333`）。

---

### B.5 各中间件访问信息汇总

无论使用方式 A 还是方式 B，各中间件的访问信息完全一致：

| 中间件 | 地址 | 端口 | 账号 / 密码 |
|--------|------|------|-------------|
| MySQL | localhost | 3306 | root / pp2024 |
| Redis | localhost | 6379 | 无密码 |
| RabbitMQ 管理界面 | http://localhost:15672 | 15672 | admin / admin123 |
| Qdrant | localhost | 6333 | 无认证（REST API） |

**日常启停顺序**（方式 B）：

```
启动顺序：① MySQL → ② Redis → ③ RabbitMQ → ④ Qdrant
停止顺序：④ Qdrant → ③ RabbitMQ → ② Redis → ① MySQL
```

> 也可直接使用项目提供的一键脚本：`docs\script\start-all-middleware.bat` / `stop-all-middleware.bat`（已包含 Qdrant 启停步骤）。

> 每个中间件建议在独立的 PowerShell 窗口中运行，方便查看日志和随时停止。

---

## 四、后端项目工程搭建

### 4.1 Maven 多模块项目结构

后端采用 Maven 多模块聚合结构，将代码按职责拆分到三个子模块中。目标目录结构如下：

```
auto-test-platform/                      ← 项目根目录（已有）
├── backend/                             ← 后端工程（Maven 多模块聚合）
│   ├── pom.xml                          ← 父 POM（packaging=pom, 聚合三模块）
│   ├── platform-api/                    ← 契约层（DTO/响应/异常/基类）
│   │   ├── pom.xml
│   │   └── src/main/java/com/platform/
│   │       ├── common/                  ← 公共模块（ApiResponse/ErrorCode/BaseEntity/JsonUtils）
│   │       └── {feature}/dto/           ← 各功能模块的请求/响应 DTO
│   ├── platform-data/                   ← 持久层（Entity + Mapper）
│   │   ├── pom.xml
│   │   └── src/main/java/com/platform/
│   │       └── {feature}/              ← 各功能模块的 entity + mapper
│   └── platform-server/                 ← 应用层（Controller/Service/Config/引擎）
│       ├── pom.xml
│       ├── src/main/java/com/platform/
│       │   ├── PostmanPlatformApplication.java
│       │   ├── common/config/           ← MyBatisPlusConfig, RedisConfig, RabbitMQConfig
│       │   ├── common/exception/        ← GlobalExceptionHandler
│       │   ├── auth/                    ← 认证模块（M1）
│       │   ├── project/                 ← 项目管理模块（M2/M3）
│       │   ├── environment/             ← 环境配置模块
│       │   ├── apidoc/                  ← 接口文档模块（M4）
│       │   ├── keyword/                 ← 关键字管理模块（M5/M6/M7）
│       │   ├── tool/                    ← 工具方法模块
│       │   ├── action/                  ← Action 模块
│       │   ├── execution/               ← 执行与报告模块（M8/M9/M10）
│       │   └── filter/                  ← 全局过滤器
│       └── src/main/resources/
│           ├── application.yml
│           ├── application-dev.yml
│           ├── application-prod.yml
│           └── db/migration/
├── frontend/                            ← 前端工程根目录（新建）
│   ├── package.json
│   └── src/
├── docker/                              ← Docker 配置（已创建）
└── docs/                                ← 文档（已有）
```

> **多模块依赖方向**：`platform-server` → `platform-data` → `platform-api`  
> **Maven groupId**：`com.postman`（POM 配置）；**Java 包路径**：`com.platform`（源码包声明）

---

### 4.2 Maven POM 配置

以下是 `backend/pom.xml` 父 POM 的核心配置（聚合三子模块，统一版本管理）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- Spring Boot 父 POM -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.7.18</version>
        <relativePath/>
    </parent>

    <groupId>com.postman</groupId>
    <artifactId>auto-test-platform</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>auto-test-platform</name>
    <description>项目管理平台 - 多模块聚合</description>

    <!-- 聚合子模块 -->
    <modules>
        <module>platform-api</module>
        <module>platform-data</module>
        <module>platform-server</module>
    </modules>

    <!-- 版本统一管理 -->
    <properties>
        <java.version>1.8</java.version>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <spring-boot.version>2.7.18</spring-boot.version>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
        <flyway.version>8.5.13</flyway.version>
        <mysql.version>8.0.33</mysql.version>
        <jjwt.version>0.11.5</jjwt.version>
        <hutool.version>5.8.34</hutool.version>
        <okhttp.version>4.12.0</okhttp.version>
        <groovy.version>3.0.9</groovy.version>
        <jgit.version>5.13.3.202401111512-r</jgit.version>
    </properties>

    <!-- 依赖管理：子模块声明即可，版本由父 POM 统一锁定 -->
    <dependencyManagement>
        <dependencies>
            <!-- 平台内部模块 -->
            <dependency>
                <groupId>com.postman</groupId>
                <artifactId>platform-api</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.postman</groupId>
                <artifactId>platform-data</artifactId>
                <version>${project.version}</version>
            </dependency>

            <!-- MyBatis-Plus（3.5.7：兼容 Java 1.8） -->
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-boot-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
            <!-- Flyway（数据库版本迁移） -->
            <dependency>
                <groupId>org.flywaydb</groupId>
                <artifactId>flyway-core</artifactId>
                <version>${flyway.version}</version>
            </dependency>
            <dependency>
                <groupId>org.flywaydb</groupId>
                <artifactId>flyway-mysql</artifactId>
                <version>${flyway.version}</version>
            </dependency>
            <!-- JJWT -->
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-api</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-impl</artifactId>
                <version>${jjwt.version}</version>
                <scope>runtime</scope>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-jackson</artifactId>
                <version>${jjwt.version}</version>
                <scope>runtime</scope>
            </dependency>
            <!-- OkHttp（HTTP 客户端） -->
            <dependency>
                <groupId>com.squareup.okhttp3</groupId>
                <artifactId>okhttp</artifactId>
                <version>${okhttp.version}</version>
            </dependency>
            <!-- Hutool 工具库 -->
            <dependency>
                <groupId>cn.hutool</groupId>
                <artifactId>hutool-all</artifactId>
                <version>${hutool.version}</version>
            </dependency>
            <!-- Groovy 脚本引擎（工具方法沙箱执行） -->
            <dependency>
                <groupId>org.codehaus.groovy</groupId>
                <artifactId>groovy</artifactId>
                <version>${groovy.version}</version>
            </dependency>
            <!-- JGit（测试代码库 Git 克隆/拉取，5.x 最后支持 Java 8 的版本） -->
            <dependency>
                <groupId>org.eclipse.jgit</groupId>
                <artifactId>org.eclipse.jgit</artifactId>
                <version>${jgit.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

> **子模块说明**：  
> - `platform-api`（契约层）：仅依赖 lombok、validation-api、jackson、spring-context、mybatis-plus-annotation  
> - `platform-data`（持久层）：依赖 platform-api + mybatis-plus-boot-starter + mysql-connector-java  
> - `platform-server`（应用层）：依赖 platform-api + platform-data + spring-boot-starter-web/security/redis/amqp + flyway + jjwt + okhttp + hutool + groovy；包含 spring-boot-maven-plugin 打包插件

---

### 4.3 包结构创建顺序

请按以下顺序在 IDEA 中创建包结构（后面开发时也会按此顺序逐步推进）：

| 顺序 | 包名 | 说明 |
|------|--------|------|
| 1 | common | 公共模块（响应格式、异常体系、基类实体、工具类、通用配置） |
| 2 | auth | 认证模块（登录、JWT、用户管理、系统配置） |
| 3 | project | 项目管理模块（项目 CRUD、仪表板） |
| 4 | environment | 环境配置模块（环境 CRUD、连接测试） |
| 5 | repository | 测试代码库模块（仓库 CRUD、JGit 拉取、拉取历史） |
| 6 | apidoc | 接口文档模块（接口 CRUD、Swagger 导入、接口调试） |
| 7 | keyword | 关键字管理模块（接口关键字 CRUD） |
| 8 | tool | 工具方法模块（工具方法 CRUD、沙箱执行） |
| 9 | action | Action 模块（Action CRUD、流程画布、调试） |
| 10 | execution | 执行与报告模块（自动化套件、自动化用例、计划、执行、报告） |
| 11 | filter | 全局过滤器（CORS） |

**在 IDEA 中创建包的步骤**：

1. 右键点击 `platform-server/src/main/java/com/platform` → `New` → `Package`
2. 填写包名（如 `auth`）
3. 点击 `Create`

> 详细的 Controller、Service、Mapper、Entity 等类文件将在正式开发阶段（Phase 0）逐步生成。

---

### 4.4 IDEA 后端项目导入

1. 打开 IDEA → `File` → `Open`
2. 选择 `D:\develop\auto-test-platform\backend` 目录
3. 选择 `Open as Project`
4. 等待 IDEA 自动下载 Maven 依赖（右下角进度条，首次可能需要 5-10 分钟）
5. 确认 `Project Structure` → `Project SDK` 设置为 JDK 1.8

---

### 4.5 验证后端启动

在 Phase 0 开发完成后，你应该能够：

1. 在 IDEA 中运行 `PostmanPlatformApplication` 启动类
2. 访问 `http://localhost:8080/actuator/health` 确认应用健康
3. 确认 RabbitMQ、MySQL、Redis 均已启动并可访问

---

## 五、前端项目工程搭建

### 5.1 使用 Vite 创建 Vue 3 项目

打开 PowerShell，执行：

```powershell
cd D:\develop\auto-test-platform
npm create vite@latest frontend -- --template vue-ts
```

选择 `Vue` 框架和 `TypeScript` 模板。

---

### 5.2 安装核心依赖

```powershell
cd D:\develop\auto-test-platform\frontend

# 安装基础依赖
pnpm install

# 安装 UI 组件库
pnpm add element-plus @element-plus/icons-vue

# 安装状态管理
pnpm add pinia

# 安装路由
pnpm add vue-router@4

# 安装 HTTP 客户端
pnpm add axios

# 安装图表库
pnpm add echarts

# 安装代码编辑器
pnpm add monaco-editor

# 安装流程画布引擎
pnpm add @antv/x6 @antv/x6-plugin-selection @antv/x6-plugin-snapline

# 安装开发工具
pnpm add -D @types/node unplugin-auto-import unplugin-vue-components
```

---

### 5.3 前端项目目录结构

```
frontend/
├── public/                    ← 静态资源（图标）
├── src/
│   ├── api/                   ← 后端 API 接口封装
│   │   ├── request.ts         ← Axios 实例与拦截器
│   │   ├── auth.ts
│   │   ├── project.ts
│   │   ├── user.ts
│   │   ├── apidoc.ts
│   │   ├── environment.ts
│   │   ├── keyword.ts
│   │   ├── tool.ts
│   │   ├── action.ts
│   │   ├── autoSuite.ts
│   │   ├── autoCase.ts
│   │   ├── plan.ts
│   │   ├── execution.ts
│   │   └── settings.ts
│   ├── assets/                ← 图片、图标等资源
│   ├── components/            ← 公共组件（Breadcrumb、Hamburger）
│   ├── composables/           ← 组合式函数（useExecutionWebSocket）
│   ├── layouts/               ← 页面布局
│   │   ├── Layout.vue         ←   主布局编排容器
│   │   └── components/        ←   Sidebar、Navbar、AppMain、TagsView
│   ├── router/                ← 路由配置
│   │   └── index.ts
│   ├── stores/                ← Pinia 状态管理
│   │   ├── index.ts           ←   统一导出
│   │   └── modules/           ←   按功能分模块（app、user、project、permission、tagsView）
│   ├── styles/                ← 全局样式
│   │   ├── global.less
│   │   ├── variables.less
│   │   ├── sidebar.less
│   │   ├── scrollbar.less
│   │   ├── transition.less
│   │   └── element-plus.less
│   ├── utils/                 ← 工具函数
│   ├── views/                 ← 页面组件
│   │   ├── auth/              ← 登录页
│   │   ├── project/           ← 项目相关页面
│   │   ├── api/               ← 接口文档页面
│   │   ├── environment/       ← 环境配置页面
│   │   ├── keywords/          ← 接口关键字页面
│   │   ├── tool/              ← 工具方法页面
│   │   ├── action/            ← Action 关键字页面
│   │   ├── cases/             ← 自动化套件/自动化用例页面
│   │   ├── execution/         ← 测试计划/执行记录页面
│   │   └── settings/          ← 系统设置页面
│   ├── App.vue                ← 根组件
│   └── main.ts                ← 入口文件
├── index.html
├── package.json
├── pnpm-lock.yaml
├── tsconfig.json
├── vite.config.ts
└── env.d.ts
```

---

### 5.4 IDEA 前端项目导入

1. 打开 IDEA → `File` → `Open`
2. 选择 `D:\develop\auto-test-platform\frontend` 目录
3. 选择 `Open as Project`
4. IDEA 会自动识别为 Vue 项目（需已安装 Vue.js 插件）
5. 打开内置终端（`Alt + F12`）或外部 PowerShell，运行：
   ```powershell
   cd D:\develop\auto-test-platform\frontend
   pnpm dev
   ```

> **提示**：你也可以在一个 IDEA 窗口中同时管理前后端项目。方法是打开后端项目后，通过 `File` → `New` → `Module from Existing Sources` 导入前端目录。但建议初期分开两个 IDEA 窗口，避免混淆。

---

### 5.5 验证前端启动

在 PowerShell 中执行：
```powershell
cd D:\develop\auto-test-platform\frontend
pnpm dev
```

浏览器应自动打开 `http://localhost:5173`，显示 Vite + Vue 的默认欢迎页面。

---

## 六、开发规范与编码配置

### 6.1 文件编码

**所有文件统一使用 UTF-8 编码**，这是项目强制规范。

- IDEA 已配置（§2.4.3）
- 前端 `vite.config.ts` 中无需额外配置（Vite 默认 UTF-8）
- Git 已配置（§2.3）

### 6.2 后端代码规范

| 规范项 | 要求 |
|--------|------|
| 缩进 | 4 个空格（IDEA 默认） |
| 类命名 | 大驼峰：`UserController`、`ApiEndpointService` |
| 方法/变量 | 小驼峰：`getUserById`、`projectName` |
| 常量 | 全大写下划线：`MAX_RETRY_COUNT` |
| 包名 | 全小写：`com.platform.auth.controller` |
| RESTful 路径 | 全小写短横线：`/api/v1/test-suites` |
| 日志框架 | SLF4J + Lombok `@Slf4j` 注解 |

### 6.3 前端代码规范

| 规范项 | 要求 |
|--------|------|
| 缩进 | 2 个空格 |
| 组件文件名 | 大驼峰：`UserManagement.vue` |
| 组合式函数 | `use` 前缀：`useAuth.ts` |
| CSS 类名 | 短横线：`.sidebar-menu` |
| 变量/函数 | 小驼峰：`userInfo`、`fetchProjects` |
| 常量 | 全大写：`API_BASE_URL` |

---

## 七、环境验证清单

完成所有步骤后，逐项检查以下清单。全部打 ✓ 即表示开发环境准备就绪：

### 基础软件

- [ ] `java -version` → 显示 Java 1.8.x
- [ ] `node -v` → 显示 v20.x 或更高
- [ ] `pnpm -v` → 显示版本号
- [ ] `git --version` → 显示版本号
- [ ] `mvn -version` → 显示 Maven 3.9.x + Java 1.8
- [ ] IDEA 已安装 Vue.js、Lombok、MyBatisX 插件
- [ ] IDEA 文件编码已设置为 UTF-8

### 中间件（方式 A：Docker 容器）

- [ ] `docker compose ps` → 所有容器状态为 `Up`
- [ ] MySQL 连接测试：`docker exec -it pp-mysql mysql -uroot -ppp2024 -e "SELECT 1;"`
- [ ] Redis 连接测试：`docker exec -it pp-redis redis-cli PING` → 返回 `PONG`
- [ ] RabbitMQ 管理界面：http://localhost:15672 可访问
- [ ] Qdrant 健康检查：`curl http://localhost:6333/readyz` → 返回 `OK`

### 中间件（方式 B：原生 Windows 安装）

- [ ] MySQL 连接测试：`D:\software\mysql-8.0\bin\mysql -uroot -ppp2024 -e "SELECT VERSION();"` → 显示 8.0.x
- [ ] Redis 连接测试：`D:\software\redis\redis-cli.exe PING` → 返回 `PONG`
- [ ] RabbitMQ 管理界面：http://localhost:15672 可访问
- [ ] Qdrant 健康检查：`curl http://localhost:6333/readyz` → 返回 `OK`

### 后端工程

- [ ] `backend/` 目录已创建，包含父 `pom.xml` 和三个子模块（platform-api / platform-data / platform-server）
- [ ] 各子模块包结构已创建（common / auth / project / api / keyword / execution）
- [ ] IDEA 中 Maven 依赖下载完成（无红色错误标记）

### 前端工程

- [ ] `frontend/` 目录已创建
- [ ] `pnpm dev` 能正常启动开发服务器
- [ ] 浏览器访问 `http://localhost:5173` 正常显示

---

## 八、常见问题排查

### Q1：Docker Desktop 启动失败，提示 WSL 2 未安装

**解决**：以管理员身份打开 PowerShell，执行：
```powershell
wsl --install
```
安装完成后重启电脑。

> **如果没有管理员权限**，无法启用 WSL，请改用 [方式 B：原生 Windows 安装](#方式-b原生-windows-安装无需管理员权限)，完全不依赖 Docker 和 WSL。

### Q2：Maven 下载依赖很慢或超时

**解决**：确认已配置阿里云镜像（§2.4.4 第 4 步）。如果还是慢，尝试更换镜像地址：
```xml
<url>https://maven.aliyun.com/repository/public</url>
```

### Q3：IDEA 提示 JDK 版本不匹配

**解决**：`File` → `Project Structure` → `Project` → 确认 SDK 为 JDK 1.8，Language level 为 8。同时检查 `Modules` 选项卡中每个子模块的 Language level 也是 8。

### Q4：MySQL 启动失败

**方式 A（Docker）**：查看日志定位原因：
```powershell
docker logs pp-mysql
```
常见原因是端口 3306 被占用，可以先停掉本机 MySQL 服务：
```powershell
net stop mysql
```

**方式 B（原生安装）**：常见原因及解决方法：
- **端口被占用**：检查 3306 端口是否已被其他 MySQL 实例使用：
  ```powershell
  netstat -ano | findstr :3306
  ```
  如有其他 MySQL 服务占用，可停止它或修改 `my.ini` 中的端口号。
- **data 目录已存在**：如果之前初始化过，重新初始化前需要先删除 `data` 目录：
  ```powershell
  Remove-Item -Recurse -Force D:\software\mysql-8.0\data
  D:\software\mysql-8.0\bin\mysqld --initialize-insecure --console
  ```
- **启动后立即闪退**：检查 `my.ini` 中的路径是否正确（使用正斜杠 `/`），以及 `basedir` 和 `datadir` 目录是否存在。

### Q5：前端 `pnpm dev` 报端口占用

**解决**：在 `vite.config.ts` 中修改端口：
```typescript
export default defineConfig({
  server: {
    port: 5174  // 改为其他未被占用的端口
  }
})
```

### Q6：PowerShell 执行 docker run 多行命令报错

**解决**：PowerShell 中多行命令使用反引号 `` ` `` 作为换行符（不是 `\`）。确保反引号后面没有多余的空格。也可以将所有参数写在一行中。

### Q7：RabbitMQ 启动失败（方式 B）

**常见原因及解决方法**：
- **Erlang 与 RabbitMQ 版本不兼容**：如果日志中出现 `incompatible_feature_flags`、`horus`、`extraction_denied` 或 `unknown_instruction` 等关键字，说明 Erlang 版本与 RabbitMQ 不匹配。请参考 §B.3 中的版本兼容表重新安装对应版本。例如 RabbitMQ 4.3.4 必须使用 Erlang 27.x，**不能使用 Erlang 28/29**。
- **找不到 Erlang**：确认 `$env:ERLANG_HOME` 已正确设置，且路径下有 `bin\erl.exe`：
  ```powershell
  Test-Path "$env:ERLANG_HOME\bin\erl.exe"
  ```
  如返回 `True` 则路径正确。
- **管理界面无法访问**：确认已启用管理插件：
  ```powershell
  D:\software\rabbitmq\sbin\rabbitmq-plugins.bat enable rabbitmq_management
  ```
- **端口被占用**：检查 5672 和 15672 端口：
  ```powershell
  netstat -ano | findstr ":5672 :15672"
  ```

### Q8：没有管理员权限，无法设置系统环境变量

**解决**：本文档中方式 B 的所有操作均**不需要系统级环境变量**。对于 RabbitMQ 等需要环境变量的程序，使用 PowerShell 会话级变量即可：

```powershell
# 仅在当前 PowerShell 窗口有效
$env:ERLANG_HOME = "D:\software\erlang"
$env:JAVA_HOME = "D:\software\jdk-1.8"
```

如果需要永久生效（非管理员），可以通过用户级环境变量设置（不需要管理员权限）：

```powershell
[Environment]::SetEnvironmentVariable("ERLANG_HOME", "D:\software\erlang", "User")
```

### Q9：Redis 在 Windows 上没有官方版本怎么办

**解决**：推荐使用以下替代方案（按优先级排列）：
1. **tporadowski 社区版**（https://github.com/tporadowski/redis/releases）—— 基于 Redis 5.0 移植，ZIP 解压即用，适合开发环境
2. **Memurai**（https://www.memurai.com/get-memurai）—— Redis 协议的 Windows 原生实现，Developer 版免费，性能更好且持续维护

两者都与 Redis 协议兼容，后端代码无需做任何修改。

---

> **下一步**：环境准备完成后，我们将按照 HLD 文档中的 Phase 0（项目基础设施搭建）开始正式开发。
