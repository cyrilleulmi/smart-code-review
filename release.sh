#!/usr/bin/env bash
# Releases a new version of SmartCodeReview (see docs/08-versionierung.md):
#   1. sets the version in backend/pom.xml and frontend/package.json
#   2. builds the native Docker image (runs the unit tests) as culmi/smart-code-review:X.Y.Z and :latest
#   3. commits "Release X.Y.Z" and tags vX.Y.Z
#   4. pushes main + tag to GitHub and both image tags to Docker Hub
#
#   ./release.sh 0.4.0              full release
#   ./release.sh 0.4.0 --no-push    everything except the pushes
#
# Windows: run in Git Bash. Needs: git, docker (logged in to Docker Hub for the push), npm.
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=culmi/smart-code-review
VERSION="${1:-}"
PUSH=1
[ "${2:-}" = "--no-push" ] && PUSH=0

fail() { echo "release: $*" >&2; exit 1; }

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "usage: ./release.sh X.Y.Z [--no-push]"
[ "$(git branch --show-current)" = main ] || fail "releases are made from main"
[ -z "$(git status --porcelain)" ] || fail "working tree is not clean"
git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null && fail "tag v$VERSION already exists"
grep -q "^## \[$VERSION\]" CHANGELOG.md || fail "CHANGELOG.md has no section '## [$VERSION] – <date>'"

echo "== version $VERSION"
# the project's own <version> is the first <version> element in the pom
sed -i "0,/<version>[^<]*<\/version>/s//<version>$VERSION<\/version>/" backend/pom.xml
(cd frontend && npm version "$VERSION" --no-git-tag-version --allow-same-version >/dev/null)

echo "== docker build $IMAGE:$VERSION"
if ! docker build --build-arg VERSION="$VERSION" -t "$IMAGE:$VERSION" -t "$IMAGE:latest" .; then
  git checkout -- backend/pom.xml frontend/package.json frontend/package-lock.json
  fail "image build failed - version change reverted, nothing committed"
fi

echo "== commit and tag v$VERSION"
git add backend/pom.xml frontend/package.json frontend/package-lock.json
git diff --cached --quiet || git commit -q -m "Release $VERSION"
git tag -a "v$VERSION" -m "SmartCodeReview $VERSION"

if [ "$PUSH" = 1 ]; then
  echo "== push"
  git push origin main "v$VERSION"
  docker push "$IMAGE:$VERSION"
  docker push "$IMAGE:latest"
  echo "Released $VERSION: https://hub.docker.com/r/$IMAGE/tags"
else
  echo "Built and tagged $VERSION locally. Push with:"
  echo "  git push origin main v$VERSION && docker push $IMAGE:$VERSION && docker push $IMAGE:latest"
fi
