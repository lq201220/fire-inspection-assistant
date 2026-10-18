CREATE TABLE saved_checklist_item (
 id VARCHAR(80) PRIMARY KEY,
 org_id VARCHAR(80) NOT NULL,
 unit_type VARCHAR(80) NOT NULL,
 title VARCHAR(200) NOT NULL,
 tip VARCHAR(1000) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_saved_item (org_id,unit_type,title)
);
