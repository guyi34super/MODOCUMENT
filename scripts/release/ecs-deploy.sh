#!/usr/bin/env bash
# Rolls the ECS service to a new task-definition revision with both images
# pinned by digest, and waits for the outcome (ws24, docs/release/runbook.md).
#
#   scripts/release/ecs-deploy.sh <cluster> <service> <family> <app-image@sha256:…> <frontend-image@sha256:…>
#
# 1. copies the latest ACTIVE revision of <family> (Terraform's template plus
#    any env/secret change it registered since) and pins both images
#    (render-task-definition.sh);
# 2. registers it and points the service at it;
# 3. polls the service until the new revision's deployment COMPLETES. It
#    fails when the rollout FAILS or when the PRIMARY deployment is no longer
#    the new revision — the deployment circuit breaker rolled back to the last
#    steady one — or after DEPLOY_TIMEOUT_SECONDS (default 1800).
#
# Prints the new task-definition ARN as the last line of stdout; progress
# goes to stderr. Needs the AWS CLI v2 and jq, and the deploy role's
# ecs:DescribeTaskDefinition/RegisterTaskDefinition/UpdateService/
# DescribeServices + iam:PassRole (infra/terraform/modules/ci-oidc).
set -euo pipefail

if [ "$#" -ne 5 ]; then
  echo "usage: $0 <cluster> <service> <family> <app-image@sha256:...> <frontend-image@sha256:...>" >&2
  exit 2
fi
cluster="$1"
service="$2"
family="$3"
app_image="$4"
frontend_image="$5"
timeout_seconds="${DEPLOY_TIMEOUT_SECONDS:-1800}"
poll_seconds="${DEPLOY_POLL_SECONDS:-15}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "ecs-deploy: rendering the next revision of $family" >&2
aws ecs describe-task-definition --task-definition "$family" --output json > "$work/current.json"
"$here/render-task-definition.sh" "$work/current.json" "$app_image" "$frontend_image" > "$work/next.json"

new_arn="$(aws ecs register-task-definition --cli-input-json "file://$work/next.json" \
  --query 'taskDefinition.taskDefinitionArn' --output text)"
echo "ecs-deploy: registered $new_arn" >&2

aws ecs update-service --cluster "$cluster" --service "$service" \
  --task-definition "$new_arn" --output json > /dev/null
echo "ecs-deploy: $cluster/$service now rolling to $new_arn" >&2

deadline=$((SECONDS + timeout_seconds))
while :; do
  aws ecs describe-services --cluster "$cluster" --services "$service" --output json \
    > "$work/service.json"
  primary="$(jq -c '[.services[0].deployments[]? | select(.status == "PRIMARY")][0] // {}' \
    "$work/service.json")"
  primary_td="$(jq -r '.taskDefinition // ""' <<< "$primary")"
  state="$(jq -r '.rolloutState // "UNKNOWN"' <<< "$primary")"
  reason="$(jq -r '.rolloutStateReason // ""' <<< "$primary")"
  running="$(jq -r '.runningCount // 0' <<< "$primary")"
  desired="$(jq -r '.desiredCount // 0' <<< "$primary")"

  if [ "$primary_td" != "$new_arn" ]; then
    echo "ecs-deploy: the service's primary deployment is $primary_td, not $new_arn —" \
      "the deployment circuit breaker rolled back. $reason" >&2
    exit 1
  fi
  case "$state" in
    COMPLETED)
      echo "ecs-deploy: rollout completed ($running/$desired tasks)." >&2
      break
      ;;
    FAILED)
      echo "ecs-deploy: rollout FAILED: $reason" >&2
      exit 1
      ;;
  esac
  if [ "$SECONDS" -ge "$deadline" ]; then
    echo "ecs-deploy: no steady state after ${timeout_seconds}s (state $state," \
      "$running/$desired tasks). $reason" >&2
    exit 1
  fi
  echo "ecs-deploy: $state ($running/$desired tasks)…" >&2
  sleep "$poll_seconds"
done

echo "$new_arn"
