-- 谛听数据库初始化脚本（MySQL 8）
CREATE DATABASE IF NOT EXISTS diting DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE diting;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(32) NULL,
    password_hash VARCHAR(100) NULL,
    wechat_openid VARCHAR(64) NULL,
    unionid VARCHAR(64) NULL,
    nickname VARCHAR(32) NOT NULL DEFAULT '',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_username (username),
    UNIQUE KEY uk_openid (wechat_openid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS recognition_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    task_id VARCHAR(64) NULL,
    oss_object VARCHAR(255) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    duration_sec INT NULL,
    speaker_mapping_confirmed TINYINT(1) NOT NULL DEFAULT 0,
    error_msg VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at DATETIME NULL,
    KEY idx_user (user_id),
    KEY idx_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS sentences (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL,
    speaker_id INT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    text TEXT NOT NULL,
    speech_speed INT NULL,
    flagged TINYINT(1) NOT NULL DEFAULT 0,
    rule_ref VARCHAR(255) NULL,
    KEY idx_task (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
