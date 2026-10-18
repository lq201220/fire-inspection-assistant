# API 契约

所有路径以 `/api` 开始。登录和健康接口之外需要 Bearer JWT；请求/响应与错误格式详见[架构与业务规则](实施规则与接口.md)。时间采用包含时区的 ISO 8601。任务写入均发送 `version`，当前版本来自任务详情/动作成功后的重新加载。

| 路径 / 方法 | 请求输入 | 权限和返回 |
|---|---|---|
| GET /health | 无 | 无鉴权；真实数据库连接 |
| POST /auth/login | username,password | token、expiresAt、user；错账号/密码 401 |
| GET /auth/me；POST /auth/logout | 无 | 当前身份；退出作废当前会话 |
| GET /users；POST /users | username,name,password,role,enabled（可选，默认启用） | 本机构管理员；新增账号 |
| PATCH /users/{id} | name,role,enabled,password 可选 | 本机构管理员；密码留空不修改；不能停用/降级自己，未完成任务限制改变角色 |
| POST /users/{id}/reset-password | password | 本机构管理员；旧会话失效 |
| GET /units?q= | 搜索字符串 | 同机构档案列表 |
| GET /units/{id} | 无 | 档案、可访问关联任务/问题/未闭环风险 |
| POST /units；PATCH /units/{id} | name,type,address,people,buildings,risk,active | 管理员维护档案；people 是档案人数文字，不是本次现场核验数值 |
| DELETE /units/{id} | 无 | 管理员停用，不删除历史 |
| GET /state；GET /dashboard | 无 | 当前授权业务状态；工作台统计和风险优先/期限排序任务 |
| GET /tasks?q=&status= | 名称/编号/来源；全部或业务状态 | 当前被分配任务；管理员本机构全部 |
| GET /tasks/{id}；GET /tasks/{id}/history | 无 | 任务快照、清单、问题、风险及不可变审核/材料/复查/日志 |
| GET /checklist-options | unitId,source | 管理员；所选在用单位适用的检查模板，id 用于勾选下发 |
| POST /checklist-items | unitId,title,tip | 管理员；独立保存同机构、同单位类型可复用的自定义项目，返回可选 id；同名同要求幂等，同名不同要求 409 |
| DELETE /checklist-items/{id} | 默认项目需 unitId,source；自定义项目可省略 | 管理员；自定义项目从本机构库删除，默认项目按本机构及单位类型持久化移除；已有任务清单与记录保留，越权或不存在返回 404 |
| POST /tasks | unitId,source,risk,due,focus[],inspectorId,reviewerId,recheckerId,checklistIds[],customItems[{title,tip}] | 管理员；仅保存所选检查项目及自定义要求快照，至少一项，拒绝不适用项目；旧请求省略选择参数时沿用完整模板 |
| PATCH /tasks/{id}；POST /tasks/{id}/assign | version,due,focus[],三个 assignee ID 可选 | 管理员且待检查阶段；不支持直接修改业务状态 |
| POST /tasks/{id}/start | version | 分配检查员，待检查 -> 进行中 |
| PUT /tasks/{id}/site-check | version,type,height,floors,people | 兼容旧客户端的可选快照接口；当前页面不再录入，提交审核不依赖此数据 |
| PUT /tasks/{id}/checklist/{itemId} | version,status,note | 分配检查员，进行中；status 为 normal/abnormal/pending/na，未检查表示未记录，na 必填原因 |
| POST /tasks/{id}/issues；PATCH /issues/{id} | version,itemId（创建时）,type,location,finding,photoIds[],riskEventIds[]；可选 recordAbnormal=true | 分配检查员，进行中；recordAbnormal将异常结论与问题同事务保存，失败全部回退；修改仅待审核/已退回问题 |
| GET /issues/{id} | 无 | 授权问题事实及对应任务历史 |
| POST /tasks/{id}/submit-review | version | 分配检查员，清单/问题缺项汇总校验后待审核；不要求site；退回问题重新送审 |
| POST /issues/{id}/review | version,decision,opinion；确认时 requirements,responsibleContact,rectificationDue | 分配审核员；decision confirm/return/exclude，逐项审核保存当时事实快照 |
| POST /tasks/{id}/review | version,opinion,excludedRiskIds[]；可选 issueReviews[{issueId,decision,opinion,requirements,responsibleContact,rectificationDue}] | 分配审核员；全部问题及总意见同事务提交，每项保留历史；任一退回则回到检查补充，否则进入整改/无问题归档；旧逐项接口保持兼容 |
| POST /issues/{id}/rectifications | version,provider,description,fileIds[] | 分配检查员或复查员；在整改中代录材料 |
| POST /issues/{id}/recheck | version,conclusion,finding,fileIds[]；可选 material{provider,description,fileIds[]} | 分配复查员；conclusion pass/fail，失败之后须提供新的整改材料；material与本次复查原子保存，附件用途分别绑定 |
| POST /tasks/{id}/close | version；审核归档另需 opinion,excludedRiskIds[] | 审核员或复查员按阶段执行；不能跳过闭环条件 |
| GET /risk-events | 无 | 可访问任务全部风险，含已关闭/排除；前端统一过滤 active 状态统计 |
| POST /files/photos；POST /files/attachments | multipart: taskId,purpose,file | issue 用照片；rectification/recheck 用 PNG/JPEG/PDF；单个 10MB，最多 10 个绑定附件 |
| GET /files/photos/{id}；GET /files/attachments/{id} | 无 | Bearer 鉴权读取二进制；未绑定附件仅上传者可读，绑定后按任务范围读取 |
| DELETE /files/{id} | 无 | 只能删除自己尚未提交的附件 |

角色码 ADMIN/INSPECTOR/REVIEWER/RECHECKER。单位类型为学校、商业综合体、住宅小区、养老机构；基本模板按类型固定。任务来源为设施异常触发、日常抽查、历史隐患复查、专项检查；来源决定默认重点并进入清单快照元数据。设施异常触发创建待核验线索。重点可由管理员在未开始阶段调整，历史模板/依据不会随档案改动。

列表目前返回完整授权列表，前端搜索筛选；服务端 /tasks 与 /units 也提供检索，不提供分页协议。GET /tasks 的 status 指业务阶段，页面“进行中/已完成/已逾期”汇总筛选由前端完成；历史画像按检查事实区分有无异常，不以关注等级替代。

前端附件先鉴权获取 Blob 再预览/下载，不暴露公开上传目录；照片采用按账号和权限隔离的本机持久缓存，PDF 不持久缓存。
