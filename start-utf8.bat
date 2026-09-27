@echo off
chcp 65001 >nul 2>&1
setlocal

REM ============================================================
REM  谛听后端启动脚本（Windows）
REM  用法：把 start.bat 与 diting.env 放在同一目录，双击运行
REM  追加 spring 参数：start.bat --server.port=8081
REM ============================================================

set "HERE=%~dp0"

REM ---- 前置检查：java 是否可用 ----
where java >nul 2>&1
if errorlevel 1 (
  echo [错误] PATH 中找不到 java，请安装 JDK 17 并把 java 加入 PATH
  pause
  exit /b 1
)

REM ---- 环境变量文件 ----
set "ENV_FILE=%HERE%diting.env"
if defined DITING_ENV_FILE set "ENV_FILE=%DITING_ENV_FILE%"
if not exist "%ENV_FILE%" (
  echo [错误] 找不到环境变量文件: %ENV_FILE%
  echo        请把它与 start.bat 放同一目录，或先 set DITING_ENV_FILE=完整路径
  pause
  exit /b 1
)

REM ---- 读取 diting.env（每行 NAME=value，# 开头为注释）----
for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ENV_FILE%") do set "%%A=%%B"

REM ---- 门禁 1：禁止 test profile（会暴露 /api/test/* 手动推进端点）----
echo %SPRING_PROFILES_ACTIVE%| findstr /i "test" >nul
if not errorlevel 1 (
  echo [错误] SPRING_PROFILES_ACTIVE 含 test，测试端点会被暴露到线上，拒绝启动
  pause
  exit /b 1
)

REM ---- 门禁 2：缺了会启动即失败的两个变量 ----
if not defined DITING_DB_PASSWORD (
  echo [错误] 缺少 DITING_DB_PASSWORD（数据库密码）
  pause
  exit /b 1
)
if not defined DITING_JWT_SECRET (
  echo [错误] 缺少 DITING_JWT_SECRET（JWT 签名密钥）
  pause
  exit /b 1
)

REM ---- 门禁 3：JWT 密钥必须 >= 32 字节（jjwt 会拒绝更短的）----
set "TMPJWT=%DITING_JWT_SECRET%"
set /a JWT_LEN=0 >nul
:jwt_loop
if not defined TMPJWT goto jwt_done
set "TMPJWT=%TMPJWT:~1%"
set /a JWT_LEN+=1 >nul
goto jwt_loop
:jwt_done
if %JWT_LEN% LSS 32 (
  echo [错误] DITING_JWT_SECRET 只有 %JWT_LEN% 字节，jjwt 要求至少 32 字节
  echo        生成方法：openssl rand -base64 48
  pause
  exit /b 1
)

REM ---- 定位 jar ----
set "JAR="
if defined DITING_JAR set "JAR=%DITING_JAR%"
if not defined JAR if exist "%HERE%diting-server-0.0.1-SNAPSHOT.jar" set "JAR=%HERE%diting-server-0.0.1-SNAPSHOT.jar"
if not defined JAR if exist "%HERE%target\diting-server-0.0.1-SNAPSHOT.jar" set "JAR=%HERE%target\diting-server-0.0.1-SNAPSHOT.jar"
if not defined JAR (
  echo [错误] 找不到 jar（diting-server-0.0.1-SNAPSHOT.jar）
  echo        先构建：mvn -DskipTests package
  echo        或先 set DITING_JAR=完整路径
  pause
  exit /b 1
)

echo ============================================================
echo   数据库账号 : %DITING_DB_USERNAME%
echo   JWT 密钥   : %JWT_LEN% 字节
echo   启动 jar   : %JAR%
echo   启动日志里不应出现 NOT_CONFIGURED （OSS/听悟/OCR/字体）告警
echo ============================================================
echo.

java -jar "%JAR%" %*

echo.
echo [结束] 进程已退出，退出码 %errorlevel%
pause
endlocal
