FROM docker.io/cimg/android:2024.01.1

ARG WEBSITE_URL=https://deckk.it
ARG APP_NAME=deckk.it
ARG PACKAGE_NAME=com.deckk.it
ARG FAVICON_URL=https://deckk.it/images/favicon.svg
ARG BUILD_TYPE=release
ARG VERSION=1.0.0
ARG VERSION_CODE=1
ARG KEYSTORE_PASSWORD
ARG KEY_ALIAS
ARG KEY_PASSWORD
ARG CACHE_DATE=1

ENV WEBSITE_URL=$WEBSITE_URL
ENV APP_NAME=$APP_NAME
ENV PACKAGE_NAME=$PACKAGE_NAME
ENV FAVICON_URL=$FAVICON_URL
ENV BUILD_TYPE=$BUILD_TYPE
ENV VERSION=$VERSION
ENV VERSION_CODE=$VERSION_CODE
ENV KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD
ENV KEY_ALIAS=$KEY_ALIAS
ENV KEY_PASSWORD=$KEY_PASSWORD

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

# Build the requested variant. For a release build the repo keystore signs the APK
# (KEYSTORE_PASSWORD is injected via the build args above).
RUN ./gradlew clean "assemble${BUILD_TYPE}" 2>&1 | tail -60

CMD ["tail", "-f", "/dev/null"]
