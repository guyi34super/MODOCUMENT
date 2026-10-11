#!/usr/bin/env bash
# Renders the next ECS task-definition revision (ws24, docs/release/runbook.md):
# the CURRENT revision of the family with both container images replaced by
# digest-pinned references, minus the read-only fields that
# `aws ecs register-task-definition --cli-input-json` refuses.
#
#   scripts/release/render-task-definition.sh <describe.json> <app-image> <frontend-image>
#
#   describe.json   output of `aws ecs describe-task-definition` (the
#                   {"taskDefinition": {...}} wrapper or the bare object)
#   app-image       <registry>/<repository>@sha256:<64 hex>   (container "app")
#   frontend-image  same, for container "frontend"
#
# Writes the JSON to stdout. Everything else (environment, secrets, roles,
# health checks, users) is copied unchanged: Terraform owns the template,
# CI owns the revisions (infra/terraform/modules/ecs-app, "RELEASES").
# Exit codes: 0 rendered, 1 invalid input, 2 usage error.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <describe-task-definition.json> <app-image@sha256:...> <frontend-image@sha256:...>" >&2
  exit 2
fi
current="$1"
app_image="$2"
frontend_image="$3"

# Digest-pinned only: a tag can be re-pointed, a digest cannot.
image_re='^[a-z0-9][a-z0-9.-]*(:[0-9]+)?/[a-z0-9][a-z0-9._/-]*@sha256:[0-9a-f]{64}$'
for ref in "$app_image" "$frontend_image"; do
  if ! [[ "$ref" =~ $image_re ]]; then
    echo "render-task-definition: '$ref' is not <registry>/<repository>@sha256:<digest>" >&2
    exit 1
  fi
done
if [ ! -f "$current" ]; then
  echo "render-task-definition: $current not found" >&2
  exit 1
fi

jq --arg app "$app_image" --arg frontend "$frontend_image" '
  (.taskDefinition // .) as $td
  | ([$td.containerDefinitions[]?.name]) as $names
  | if ($names | index("app")) == null or ($names | index("frontend")) == null then
      error("the task definition must have the containers \"app\" and \"frontend\" (found: \($names | join(", ")))")
    else . end
  | $td
  | .containerDefinitions |= map(
      if .name == "app" then .image = $app
      elif .name == "frontend" then .image = $frontend
      else . end)
  | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities,
        .registeredAt, .registeredBy, .deregisteredAt)
' "$current"
