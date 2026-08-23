# Spring AI Skills Demo - Dockerfile
# 使用多阶段构建优化镜像大小

# 阶段1: 构建阶段
FROM amazoncorretto:17-alpine-jdk AS builder

# 安装 Maven
RUN apk add --no-cache maven curl

# 设置工作目录
WORKDIR /build

# 首先复制 pom.xml 和下载依赖（利用 Docker 缓存层）
COPY pom.xml .

# 下载依赖（不编译，只下载依赖）
RUN mvn dependency:go-offline -B

# 复制源代码
COPY src ./src

# 构建应用（跳过测试以加快构建速度）
RUN mvn clean package -DskipTests -B

# 阶段2: 运行阶段
FROM amazoncorretto:17-alpine-jdk

# 安装必要的工具
RUN apk add --no-cache curl

# 创建非 root 用户运行应用（安全最佳实践）
RUN addgroup -S spring && adduser -S spring -G spring

# 设置工作目录
WORKDIR /app

# 从构建阶段复制生成的 JAR 文件
COPY --from=builder /build/target/*.jar app.jar

# 创建数据目录用于 H2 数据库和向量存储
RUN mkdir -p /app/data && chown -R spring:spring /app

# 切换到非 root 用户
USER spring:spring

# 暴露应用端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/api/products || exit 1

# 启动应用
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
