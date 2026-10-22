-- Lab8 Mail — schema MySQL/MariaDB (localhost)
-- Tạo DB rồi import file này trong phpMyAdmin, hoặc chạy:
--   mysql -u root -h 127.0.0.1 -P 3307 < sql/schema.sql

CREATE DATABASE IF NOT EXISTS lab8_mail
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE lab8_mail;

CREATE TABLE IF NOT EXISTS accounts (
  id INT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  password VARCHAR(255) NOT NULL,
  email VARCHAR(128) NOT NULL,
  display_name VARCHAR(128) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Mỗi lần gửi thư = 1 bản ghi trong bảng emails
CREATE TABLE IF NOT EXISTS emails (
  id INT AUTO_INCREMENT PRIMARY KEY,
  sender_user VARCHAR(64) NOT NULL,
  from_name VARCHAR(128) NULL,
  from_email VARCHAR(255) NOT NULL,
  to_email VARCHAR(255) NOT NULL,
  subject VARCHAR(500) NOT NULL,
  body TEXT NOT NULL,
  from_ip VARCHAR(64) NULL,
  folder VARCHAR(16) NOT NULL DEFAULT 'SENT',
  recipient_user VARCHAR(64) NULL,
  sent_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmail_delivered TINYINT(1) NOT NULL DEFAULT 1,
  message_id VARCHAR(255) NULL,
  INDEX idx_sender (sender_user),
  INDEX idx_recipient (recipient_user),
  INDEX idx_sent_at (sent_at),
  UNIQUE INDEX ux_emails_message_id (message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
