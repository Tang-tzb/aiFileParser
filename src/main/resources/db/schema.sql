-- ============================================================
-- aiFileParser 阶段 2：动态表单管理模块 DDL
-- 库：aifileparser  字符集：utf8mb4
-- 用法：在目标 MySQL 中执行本脚本（手动执行，未接入 spring.sql.init）
-- ============================================================

-- ---------------- 表单定义表 ----------------
CREATE TABLE IF NOT EXISTS form_definition
(
    id
    BIGINT
    NOT
    NULL
    COMMENT
    '主键ID(雪花算法)',
    form_name
    VARCHAR
(
    100
) NOT NULL COMMENT '表单名称',
    description VARCHAR
(
    500
) DEFAULT NULL COMMENT '表单描述',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常 1删除',
    PRIMARY KEY
(
    id
),
    KEY idx_form_name
(
    form_name
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表单定义表';

-- ---------------- 表单字段定义表 ----------------
CREATE TABLE IF NOT EXISTS form_field_definition
(
    id
    BIGINT
    NOT
    NULL
    COMMENT
    '主键ID(雪花算法)',
    form_id
    BIGINT
    NOT
    NULL
    COMMENT
    '所属表单ID',
    field_name
    VARCHAR
(
    100
) NOT NULL COMMENT '字段名称',
    field_code VARCHAR
(
    64
) NOT NULL COMMENT '字段编码(表单内唯一)',
    field_type VARCHAR
(
    20
) NOT NULL COMMENT '字段类型:STRING/INTEGER/DECIMAL/DATE/BOOLEAN',
    required TINYINT
(
    1
) NOT NULL DEFAULT 0 COMMENT '是否必填:0否 1是',
    description VARCHAR
(
    500
) DEFAULT NULL COMMENT '字段描述',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序号(升序)',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常 1删除',
    PRIMARY KEY
(
    id
),
    UNIQUE KEY uk_form_field_code
(
    form_id,
    field_code
),
    KEY idx_form_id
(
    form_id
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表单字段定义表';

-- ---------------- 文件记录表 ----------------
CREATE TABLE IF NOT EXISTS file_record
(
    id
    BIGINT
    NOT
    NULL
    COMMENT
    '主键ID(雪花算法)',
    file_name
    VARCHAR
(
    255
) NOT NULL COMMENT '原始文件名',
    file_type VARCHAR
(
    20
) NOT NULL COMMENT '文件类型:PDF/EXCEL/WORD/OTHER',
    file_path VARCHAR
(
    500
) NOT NULL COMMENT '存储相对路径',
    status VARCHAR
(
    20
) NOT NULL DEFAULT 'UPLOADED' COMMENT '状态:UPLOADED/PARSING/VECTORING/EXTRACTING/SUCCESS/FAILED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常 1删除',
    PRIMARY KEY
(
    id
),
    KEY idx_file_status
(
    status
),
    KEY idx_file_type
(
    file_type
)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件记录表';
