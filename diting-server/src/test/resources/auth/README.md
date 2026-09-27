# S02 鉴权测试资源

| 文件 | 用途 |
|---|---|
| `run_s02_auth_e2e.sh` | 端到端验证 AUTH-01..AUTH-09、后台权限矩阵、登录失败保护（40 项断言） |

## 前置条件

1. 一个**独立测试库**（如 `diting_s02_test`），按 S01 的 V1 基线 + V2 迁移建好：

   ```bash
   export MYSQL_PWD='<密码>'
   mysql -h127.0.0.1 -uroot -e "DROP DATABASE IF EXISTS diting_s02_test; CREATE DATABASE diting_s02_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
   sed -e '/^CREATE DATABASE/d' -e '/^USE /d' src/main/resources/sql/schema.sql | mysql -h127.0.0.1 -uroot -D diting_s02_test
   mysql -h127.0.0.1 -uroot -D diting_s02_test < src/main/resources/sql/migration/V2__S01_schema_foundation.sql
   ```

2. 用测试配置启动实例（密码与密钥都由环境变量注入，不要用真实配置）：

   ```bash
   DITING_ADMIN_INIT_USERNAME=ops_admin DITING_ADMIN_INIT_PASSWORD='<临时强密码>' \
   java -jar target/diting-server-0.0.1-SNAPSHOT.jar --server.port=8081 \
     --spring.datasource.url='jdbc:mysql://127.0.0.1:3306/diting_s02_test?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai' \
     --diting.jwt.secret='<测试专用强密钥>' \
     --diting.aliyun.access-key-id=test-key --diting.aliyun.access-key-secret=test-secret \
     --diting.aliyun.oss.bucket=test-bucket --diting.wechat.appid=test-appid --diting.wechat.secret=test-secret
   ```

   `application.yml` 里的微信/OSS 是占位值，不注入会让对象存储客户端拿占位串构造，真机联调前请勿指向真实资源。

3. 运行：

   ```bash
   S02_ADMIN_PASSWORD='<与第 2 步初始化一致的密码>' bash run_s02_auth_e2e.sh
   ```

   可选覆盖：`S02_BASE_URL`、`S02_ADMIN_USER`、`S02_OPERATOR_PASSWORD`。全部通过时退出码 0。

## 断言覆盖

- AUTH-01 密码正确/错误、AUTH-03 禁用账号（登录被拒 + 已签发 token 立即失效）
- AUTH-04 用户 token 访问后台 403、AUTH-05 后台 token 冒充同数字用户 403
- AUTH-06 他人/不存在的资源 404、AUTH-07 userinfo 与 bind-wechat 未登录 401
- AUTH-08 userinfo 不含密码哈希、AUTH-09 登录/分页/错误信封字段一致
- 后台权限矩阵（ADMIN/OPERATOR/INVESTOR）、密码强度策略、最后一个 ADMIN 保护、分页 page 从 1 起、缺参 400、登录失败保护 429

脚本按 macOS bash 3.2 的约束编写：所有请求都在单层命令替换里赋值，
不在嵌套 `$( )` 中传带转义引号的 JSON（bash 3.2 会吞掉转义，导致请求体变形）。
