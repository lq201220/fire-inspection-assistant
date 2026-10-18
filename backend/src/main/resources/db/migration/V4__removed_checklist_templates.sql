CREATE TABLE removed_checklist_template (
 org_id VARCHAR(80) NOT NULL,
 unit_type VARCHAR(80) NOT NULL,
 template_id VARCHAR(120) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY (org_id,unit_type,template_id)
);
