CREATE TABLE sys_user (
 id VARCHAR(80) PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 name VARCHAR(80) NOT NULL, role VARCHAR(20) NOT NULL, org_id VARCHAR(80) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE, token_version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX ix_user_org (org_id, role)
);
CREATE TABLE auth_session (
 id VARCHAR(80) PRIMARY KEY, user_id VARCHAR(80) NOT NULL, expires_at TIMESTAMP NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE, token_version INT NOT NULL,
 FOREIGN KEY (user_id) REFERENCES sys_user(id), INDEX ix_session_user (user_id,expires_at)
);
CREATE TABLE supervised_unit (
 id VARCHAR(80) PRIMARY KEY, org_id VARCHAR(80) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
 data LONGTEXT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 CHECK (JSON_VALID(data)), INDEX ix_unit_org (org_id,active)
);
CREATE TABLE inspect_task (
 id VARCHAR(80) PRIMARY KEY, unit_id VARCHAR(80) NOT NULL, org_id VARCHAR(80) NOT NULL,
 inspector_id VARCHAR(80) NOT NULL, reviewer_id VARCHAR(80) NOT NULL, rechecker_id VARCHAR(80) NOT NULL,
 status VARCHAR(20) NOT NULL, version INT NOT NULL DEFAULT 0, data LONGTEXT NOT NULL,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 FOREIGN KEY (unit_id) REFERENCES supervised_unit(id),
 FOREIGN KEY (inspector_id) REFERENCES sys_user(id), FOREIGN KEY (reviewer_id) REFERENCES sys_user(id), FOREIGN KEY (rechecker_id) REFERENCES sys_user(id),
 CHECK (JSON_VALID(data)), INDEX ix_task_scope (org_id,status), INDEX ix_task_inspector (inspector_id,status)
);
CREATE TABLE checklist_item (
 id VARCHAR(120) PRIMARY KEY, task_id VARCHAR(80) NOT NULL, data LONGTEXT NOT NULL,
 FOREIGN KEY (task_id) REFERENCES inspect_task(id), CHECK (JSON_VALID(data)), INDEX ix_check_task (task_id)
);
CREATE TABLE issue_record (
 id VARCHAR(80) PRIMARY KEY, task_id VARCHAR(80) NOT NULL, item_id VARCHAR(120) NOT NULL,
 status VARCHAR(20) NOT NULL, data LONGTEXT NOT NULL,
 FOREIGN KEY (task_id) REFERENCES inspect_task(id), FOREIGN KEY (item_id) REFERENCES checklist_item(id),
 CHECK (JSON_VALID(data)), INDEX ix_issue_task (task_id,status)
);
CREATE TABLE risk_event (
 id VARCHAR(80) PRIMARY KEY, unit_id VARCHAR(80) NOT NULL, task_id VARCHAR(80) NOT NULL,
 issue_id VARCHAR(80) NULL, state VARCHAR(20) NOT NULL, data LONGTEXT NOT NULL,
 FOREIGN KEY (unit_id) REFERENCES supervised_unit(id), FOREIGN KEY (task_id) REFERENCES inspect_task(id),
 FOREIGN KEY (issue_id) REFERENCES issue_record(id), CHECK (JSON_VALID(data)), INDEX ix_risk_task (task_id,state)
);
CREATE TABLE file_attachment (
 id VARCHAR(80) PRIMARY KEY, task_id VARCHAR(80) NOT NULL, uploader_id VARCHAR(80) NOT NULL,
 original_name VARCHAR(255) NOT NULL, stored_name VARCHAR(120) NOT NULL UNIQUE,
 content_type VARCHAR(80) NOT NULL, byte_size BIGINT NOT NULL, purpose VARCHAR(20) NOT NULL,
 bound_id VARCHAR(80) NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (task_id) REFERENCES inspect_task(id), FOREIGN KEY (uploader_id) REFERENCES sys_user(id), INDEX ix_file_task (task_id,bound_id)
);
CREATE TABLE review_record (
 id VARCHAR(80) PRIMARY KEY, task_id VARCHAR(80) NOT NULL, issue_id VARCHAR(80) NULL,
 actor_id VARCHAR(80) NOT NULL, data LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (task_id) REFERENCES inspect_task(id), FOREIGN KEY (issue_id) REFERENCES issue_record(id),
 FOREIGN KEY (actor_id) REFERENCES sys_user(id), CHECK (JSON_VALID(data)), INDEX ix_review_task (task_id,created_at)
);
CREATE TABLE rectification_record (
 id VARCHAR(80) PRIMARY KEY, task_id VARCHAR(80) NOT NULL, issue_id VARCHAR(80) NOT NULL,
 actor_id VARCHAR(80) NOT NULL, data LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (task_id) REFERENCES inspect_task(id), FOREIGN KEY (issue_id) REFERENCES issue_record(id),
 FOREIGN KEY (actor_id) REFERENCES sys_user(id), CHECK (JSON_VALID(data)), INDEX ix_rect_issue (issue_id,created_at)
);
CREATE TABLE recheck_record (
 id VARCHAR(80) PRIMARY KEY, task_id VARCHAR(80) NOT NULL, issue_id VARCHAR(80) NOT NULL,
 actor_id VARCHAR(80) NOT NULL, data LONGTEXT NOT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (task_id) REFERENCES inspect_task(id), FOREIGN KEY (issue_id) REFERENCES issue_record(id),
 FOREIGN KEY (actor_id) REFERENCES sys_user(id), CHECK (JSON_VALID(data)), INDEX ix_recheck_issue (issue_id,created_at)
);
CREATE TABLE operate_log (
 id VARCHAR(80) PRIMARY KEY, org_id VARCHAR(80) NOT NULL, task_id VARCHAR(80) NULL,
 actor_id VARCHAR(80) NOT NULL, action VARCHAR(80) NOT NULL, data LONGTEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY (actor_id) REFERENCES sys_user(id), CHECK (JSON_VALID(data)), INDEX ix_log_org (org_id,created_at), INDEX ix_log_task (task_id,created_at)
);
