FROM docker.io/cimg/android:2024.01.1

ARG WEBSITE_URL=https://deckk.it
ARG APP_NAME=deckk.it
ARG PACKAGE_NAME=com.deckk.it
ARG FAVICON_URL=https://deckk.it/images/favicon.svg
ARG BUILD_TYPE=release
ARG VERSION=1.0.0
ARG VERSION_CODE=1
ARG CACHE_DATE=1

ENV WEBSITE_URL=$WEBSITE_URL
ENV APP_NAME=$APP_NAME
ENV PACKAGE_NAME=$PACKAGE_NAME
ENV FAVICON_URL=$FAVICON_URL
ENV BUILD_TYPE=$BUILD_TYPE
ENV VERSION=$VERSION
ENV VERSION_CODE=$VERSION_CODE

WORKDIR /project

RUN sudo apt-get update && sudo apt-get install -y wget imagemagick librsvg2-bin

# The project sources live in this repo; copy them in and build with the committed Gradle wrapper.
COPY . /project/

# COPY brings files in owned by root, but the cimg build user (circleci) is non-root,
# so take ownership of /project and set the executable bits via sudo (passwordless,
# same as the apt-get above). Without this, chmod and the icon/gradle steps fail
# with "Operation not permitted" / "Permission denied".
RUN sudo chown -R "$(id -u):$(id -g)" /project \
    && sudo chmod +x gradlew scripts/generate-icons.sh

# Generate the launcher icons from the favicon URL (env-provided, see build args above).
RUN ./scripts/generate-icons.sh

# Build the requested variant. For a release build the repo keystore signs the APK.
# Keystore credentials are passed as Docker secrets (not ARG/ENV) so they never land
# in an image layer and do not trigger buildx "SecretsUsedInArgOrEnv" checks.
# mode=0444: the cimg image builds as the non-root "circleci" user, and BuildKit's
# default mode=0400 root-owned secret files are unreadable by it.
# required=false: debug builds (which pass no secrets) must still build.
RUN --mount=type=secret,id=keystore_password,required=false,mode=0444 \
    --mount=type=secret,id=key_alias,required=false,mode=0444 \
    --mount=type=secret,id=key_password,required=false,mode=0444 \
    if [ "$BUILD_TYPE" = "release" ]; then \
        if [ ! -r /run/secrets/keystore_password ]; then \
            echo "ERROR: release build needs keystore secrets (unreadable/missing)"; exit 1; \
        fi && \
        export KEYSTORE_PASSWORD="$(cat /run/secrets/keystore_password)" && \
        export KEY_ALIAS="$(cat /run/secrets/key_alias)" && \
        export KEY_PASSWORD="$(cat /run/secrets/key_password)" && \
        if [ -z "$KEYSTORE_PASSWORD" ] || [ -z "$KEY_ALIAS" ] || [ -z "$KEY_PASSWORD" ]; then \
            echo "ERROR: keystore secrets were empty after reading"; exit 1; \
        fi && \
        echo "Keystore signing credentials loaded"; \
    else \
        echo "Debug build: keystore signing skipped"; \
    fi && \
    ./gradlew clean "assemble${BUILD_TYPE}" 2>&1 | tail -60

CMD ["tail", "-f", "/dev/null"]
