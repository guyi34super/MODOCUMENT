#!/usr/bin/env bash
# Pushes an OCI image archive to an ECR repository under one or more
# immutable tags WITHOUT changing a byte (ws24, docs/release/runbook.md), so
# staging and production run the exact image release.yml built and scanned.
#
#   scripts/release/push-oci.sh <image.oci.tar> <registry>/<repository> <sha256:digest> <tag>...
#
# - The archive's own manifest digest (index.json) must equal the expected
#   digest from the build job — an artifact swapped in between is refused.
# - skopeo copies with --preserve-digests (no recompression); a tag that
#   already exists is left alone when it names the same digest (a re-run)
#   and is an error when it names another (ECR tags are IMMUTABLE anyway).
# - Afterwards ECR must report the expected digest for every tag.
#
# Needs skopeo (logged in to the registry), jq, tar and the AWS CLI v2 with
# ecr:DescribeImages on the repository.
set -euo pipefail

if [ "$#" -lt 4 ]; then
  echo "usage: $0 <image.oci.tar> <registry>/<repository> <sha256:digest> <tag>..." >&2
  exit 2
fi
archive="$1"
target="$2"
expected="$3"
shift 3

if ! [[ "$expected" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "push-oci: '$expected' is not a sha256 digest" >&2
  exit 2
fi
if ! [[ "$target" =~ ^([a-z0-9][a-z0-9.-]*)/([a-z0-9][a-z0-9._/-]*)$ ]]; then
  echo "push-oci: '$target' is not <registry>/<repository>" >&2
  exit 2
fi
repository="${BASH_REMATCH[2]}"
for tag in "$@"; do
  if ! [[ "$tag" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
    echo "push-oci: '$tag' is not a valid image tag" >&2
    exit 2
  fi
done

actual="$(tar -xOf "$archive" index.json \
  | jq -r 'if (.manifests | length) == 1 then .manifests[0].digest else error("expected exactly one manifest") end')"
if [ "$actual" != "$expected" ]; then
  echo "push-oci: $archive holds $actual, expected $expected — refusing to push" >&2
  exit 1
fi

registry_digest() {
  # The digest ECR has under a tag, or nothing when the tag does not exist.
  aws ecr describe-images --repository-name "$repository" --image-ids "imageTag=$1" \
    --query 'imageDetails[0].imageDigest' --output text 2>/dev/null || true
}

for tag in "$@"; do
  existing="$(registry_digest "$tag")"
  if [ -n "$existing" ] && [ "$existing" != "None" ]; then
    if [ "$existing" = "$expected" ]; then
      echo "push-oci: $target:$tag already holds $expected (re-run); skipped"
      continue
    fi
    echo "push-oci: $target:$tag already holds $existing, not $expected — tags are immutable" >&2
    exit 1
  fi
  skopeo copy --quiet --retry-times 3 --preserve-digests \
    "oci-archive:$archive" "docker://$target:$tag"
  pushed="$(registry_digest "$tag")"
  if [ "$pushed" != "$expected" ]; then
    echo "push-oci: $target:$tag reports ${pushed:-nothing}, expected $expected" >&2
    exit 1
  fi
  echo "push-oci: $target:$tag = $expected"
done
