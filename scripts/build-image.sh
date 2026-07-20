#!/usr/bin/env sh
set -eu

repo_source="https://github.com/dlsrnjs125/rippleguard-loan-service"
revision="$(git rev-parse HEAD)"
tag_revision="$(printf '%s' "${revision}" | cut -c1-12)"
image="rippleguard-loan-service:${tag_revision}"

./mvnw package

docker build \
  --build-arg "OCI_REVISION=${revision}" \
  --build-arg "OCI_SOURCE=${repo_source}" \
  -t "${image}" \
  .

echo "${image}"
