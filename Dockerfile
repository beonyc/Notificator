# ============================================================================
# Dockerfile — рецепт сборки образа с ботом.
# Концепция «multi-stage build» (многоэтапная сборка): в первом этапе есть
# JDK и Gradle, чтобы собрать jar; во втором — только лёгкая JRE и готовый jar.
# Так итоговый образ маленький и в нём нет лишних инструментов сборки.
# ============================================================================

# ---------- Этап 1: сборка jar ----------
# JDK 21 — именно такая версия указана в build.gradle (toolchain).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Сначала копируем только файлы Gradle и скачиваем зависимости.
# Docker кеширует слои: пока build.gradle не менялся, зависимости
# не будут скачиваться заново при каждой пересборке — это ускоряет деплой.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Теперь исходный код и сборка исполняемого jar.
# -x test: тесты запускаем отдельно (в разработке), на сервере они не нужны.
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ---------- Этап 2: запуск ----------
# JRE (без компилятора) — меньше размер и меньше поверхность атаки.
FROM eclipse-temurin:21-jre
WORKDIR /app

# Обычный пользователь вместо root: если в приложении найдут уязвимость,
# злоумышленник не получит права администратора в контейнере.
RUN useradd --system --create-home bot \
    && mkdir -p /app/data /app/logs \
    && chown -R bot:bot /app
USER bot

# Забираем собранный jar из первого этапа.
COPY --from=build --chown=bot:bot /app/build/libs/*.jar /app/app.jar

# Папки с данными (H2) и логами. Их подключаем как volume в docker-compose.yml,
# чтобы данные не пропадали при пересоздании контейнера.
VOLUME ["/app/data", "/app/logs"]

# Ограничиваем память JVM долей от памяти контейнера — на маленьком VPS
# иначе система может убить процесс из-за нехватки памяти.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
