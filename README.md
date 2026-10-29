# 后端学习项目集

这是我的**后端学习仓库**，把自己跟着课程/文档做完的实战项目集中放在这里，方便回顾、对比和持续迭代。
**后续还会继续往里加新项目**，每个项目一个顶层目录，互不干扰。

> 说明：项目里的业务代码是我自己跟着课程敲/改的（部分课程章节的功能留有 TODO 未实现）；前端页面基本是课程配套素材，放在各项目的 `frontend/` 下方便本地预览。

## 项目一览

| 项目 | 内容 | 技术栈 | 目录 |
| --- | --- | --- | --- |
| 苍穹外卖 | 外卖点餐系统（后台管理 + 小程序端），含菜品/套餐、订单、员工、微信支付、报表统计、OSS 图片上传 | Spring Boot 2.7 + MyBatis + Druid + PageHelper + Redis + MySQL + JWT + Knife4j | [`cqwm/`](cqwm/) |
| 黑马点评 | Redis 实战项目：短信登录、商户缓存（穿透/互斥锁/逻辑过期）、优惠券秒杀与一人一单、达人探店、好友关注、附近商铺 | Spring Boot 2.7 + MyBatis-Plus + Redis(Lettuce) + Redisson + MySQL | [`hmdp/`](hmdp/) |

## 目录约定

```
后端学习仓库/
├── cqwm/                    # 项目一：苍穹外卖
│   ├── sky-take-out/        #   Spring Boot 后端（Maven 多模块：sky-common / sky-pojo / sky-server）
│   └── sky/                 #   小程序前端（构建产物，不入库）
├── hmdp/                    # 项目二：黑马点评
│   ├── backend/             #   Spring Boot 后端
│   ├── frontend/            #   课程配套前端静态页
│   └── nginx.hmdp.conf      #   macOS 下预览前端的 nginx 配置
└── README.md                # 本文件
```

新项目按同样方式组织：`项目名/backend`、`项目名/frontend`；编译产物（`target/`）、安装包、课程资料、IDE 配置一律不进仓库，规则见 [`.gitignore`](.gitignore)。

## 本地运行

### 黑马点评（hmdp）

1. 启动 MySQL（3306）和 Redis（6379），导入课程提供的 `hmdp.sql`。
2. 启动后端（默认 8081）：

   ```bash
   cd hmdp/backend
   mvn spring-boot:run     # 或在 IDEA 里直接运行 HmDianPingApplication
   ```

3. 打开前端预览页 http://localhost:8080

   macOS 上该端口由 Homebrew nginx 常驻（配置在 `/opt/homebrew/etc/nginx/servers/hmdp.conf`，随系统启动，无需手动拉起），把 `/api` 反向代理到后端 8081。

### 苍穹外卖（cqwm）

后端在 `cqwm/sky-take-out`，多模块 Maven 项目，启动 `sky-server` 模块（默认 8080）。
注意：**后端 8080 与黑马点评的前端预览 8080 会冲突**，两个项目不要同时跑。

## 环境

- JDK 17（后端用的 Lombok 1.18.24 在 JDK 21/26 下编译会报 `cannot find symbol`，请切到 17）
- MySQL 8 / Redis 6.2+（黑马点评的 GEO 功能需要 Redis ≥ 6.2）
- Maven 3.8+
- macOS 下前端预览用 Homebrew nginx

## 完成情况

| 项目 | 状态 |
| --- | --- |
| 苍穹外卖 | ✅ 课程功能全部完成（后台管理 + 小程序端） |
| 黑马点评 | ✅ 9 章中的 7 章完成：短信登录、商户缓存（穿透/雪崩/击穿）、优惠券秒杀与一人一单、达人探店、好友关注、附近商铺 GEO；⬜ 用户签到（BitMap）、UV 统计（HyperLogLog）已学未实现 |

## 后续计划

- [ ] 继续补充课程项目（微服务、消息队列、分布式事务等）
- [ ] 有空补上黑马点评的签到与 UV 统计
- [ ] 给每个项目补一份自己的 README（设计思路、踩坑记录）
